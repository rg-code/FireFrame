package com.framehome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Immich client and on-device photo cache. One instance per process (see {@link #get}).
 *
 * The "pool" is the list of photo IDs to show, fetched from Immich at most every POOL_TTL_MS:
 * every photo in one album (POST /api/search/metadata, paged), or a random 1000 from the whole
 * library (POST /api/search/random). Photos are downloaded at Immich's "preview" size
 * (GET /api/assets/{id}/thumbnail?size=preview, about 1440 px) as they are shown, and the newest
 * CACHE_MAX stay on the TV, so the screensaver keeps working while the server is offline.
 *
 * Immich v3.2 added a new search request shape (filter / cursor) and deprecated the flat one
 * (albumIds / page). We use the new shape on >= 3.2 and fall back to the flat one on older
 * servers, or if a v3 server rejects the new shape with HTTP 400.
 *
 * Everything except the small getters does network or disk I/O: call it off the main thread.
 */
final class PhotoStore {
    static final String TAG = "Frame";

    private static final int PAGE_SIZE = 250;
    private static final int POOL_MAX = 2000;
    private static final int RANDOM_SIZE = 1000;
    private static final int CACHE_MAX = 80;
    private static final long POOL_TTL_MS = 12L * 60 * 60 * 1000;
    private static final long OFFLINE_RETRY_MS = 10L * 60 * 1000;

    static final class Photo {
        final String id;
        final String caption;

        Photo(String id, String caption) {
            this.id = id;
            this.caption = caption;
        }
    }

    /** A photo (caption: place and month) or an artwork from {@link ArtStore} (title, artist, one sentence). */
    static final class Shown {
        final Bitmap bitmap;
        final String caption;
        final boolean art;
        final String title;
        final String artist;

        Shown(Bitmap bitmap, String caption) {
            this.bitmap = bitmap;
            this.caption = caption;
            this.art = false;
            this.title = "";
            this.artist = "";
        }

        Shown(Bitmap bitmap, String title, String artist, String caption) {
            this.bitmap = bitmap;
            this.caption = caption;
            this.art = true;
            this.title = title;
            this.artist = artist;
        }
    }

    static final class HttpError extends IOException {
        final int code;

        HttpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static PhotoStore instance;

    static synchronized PhotoStore get(Context context) {
        if (instance == null) instance = new PhotoStore(context.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;
    private final File photoDir;
    private final File localDir;
    private final File poolFile;
    private final Random random = new Random();

    private List<Photo> pool;
    private Map<String, String> captions;
    private final List<Photo> queue = new ArrayList<Photo>();
    private int queuePos;
    private long offlineSince = -1;
    private String lastShownId;

    private PhotoStore(Context context) {
        prefs = context.getSharedPreferences("frame", Context.MODE_PRIVATE);
        photoDir = new File(context.getCacheDir(), "photos");
        photoDir.mkdirs();
        localDir = new File(context.getFilesDir(), "local");
        poolFile = new File(context.getFilesDir(), "pool.json");
    }

    // ---------------------------------------------------------------- configuration

    String url() {
        String u = prefs.getString("url", "").trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/api")) u = u.substring(0, u.length() - 4);
        return u;
    }

    String apiKey() {
        return prefs.getString("key", "").trim();
    }

    String album() {
        return prefs.getString("album", "").trim();
    }

    int intervalSeconds() {
        return Math.max(5, Math.min(600, prefs.getInt("interval", 20)));
    }

    boolean configured() {
        return url().length() > 0 && apiKey().length() > 0;
    }

    /** "saved": keep showing saved photos while Immich is offline. "fallback": switch to the fallback. */
    String offlinePolicy() {
        return prefs.getString("offline", "saved");
    }

    /** Fallback and offline settings for {@link ScreensaverRouter}; null values are left alone. */
    synchronized void configureFallback(String fallback, String offline, String previous) {
        SharedPreferences.Editor e = prefs.edit();
        if (fallback != null && fallback.trim().length() > 0) e.putString("fallback", fallback.trim());
        if (offline != null && (offline.equals("saved") || offline.equals("fallback"))) e.putString("offline", offline);
        if (previous != null) e.putString("previous", previous.trim());
        if (fallback != null || offline != null) e.remove("dream_failed");
        e.commit();
    }

    synchronized int savedCount() {
        return cachedFiles().length;
    }

    synchronized int poolSize() {
        return loadPool().size();
    }

    /** Whether Immich answers right now (a few seconds at most). The answer is kept for {@link #lastPing}. */
    boolean ping() {
        boolean ok = pingOnce();
        prefs.edit().putBoolean("ping_ok", ok).putLong("ping_at", System.currentTimeMillis()).apply();
        return ok;
    }

    /** The last ping's answer if it is under 20 minutes old, else null. */
    Boolean lastPing() {
        long at = prefs.getLong("ping_at", 0);
        if (System.currentTimeMillis() - at > 20L * 60 * 1000) return null;
        return prefs.getBoolean("ping_ok", false);
    }

    private boolean pingOnce() {
        if (!configured()) return false;
        try {
            return get("/api/server/ping").contains("pong");
        } catch (HttpError e) {
            if (e.code != 404) return false;
            try {
                return get("/api/server-info/ping").contains("pong"); // servers before v1.106
            } catch (IOException e2) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- screensaver health

    private static final long FAILURE_HOLD_MS = 60L * 60 * 1000;

    void dreamStarted() {
        prefs.edit().putLong("dream_started", System.currentTimeMillis()).commit();
    }

    void dreamStopped() {
        prefs.edit().putLong("dream_stopped", System.currentTimeMillis()).commit();
    }

    void dreamFailed(String why) {
        prefs.edit().putLong("dream_failed", System.currentTimeMillis()).putString("dream_failed_why", why).commit();
    }

    /**
     * True if Frame's screensaver gave up, or died without stopping (a crash: it started but never
     * stopped and isn't running now), within the last hour.
     */
    boolean recentFailure(boolean runningNow) {
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("dream_failed", 0) < FAILURE_HOLD_MS) return true;
        long started = prefs.getLong("dream_started", 0);
        long stopped = prefs.getLong("dream_stopped", 0);
        return started > stopped && !runningNow && now - started < FAILURE_HOLD_MS;
    }

    /** Applies the non-null values. Changing the server, key or album drops the photo list. */
    synchronized void configure(String url, String key, String album, int interval) {
        SharedPreferences.Editor e = prefs.edit();
        boolean source = false;
        if (url != null) { e.putString("url", url.trim()); source = true; }
        if (key != null) { e.putString("key", key.trim()); source = true; }
        if (album != null) { e.putString("album", album.trim()); source = true; }
        if (interval > 0) e.putInt("interval", interval);
        if (source) e.remove("pool_at").remove("error");
        e.commit();
        if (source) {
            poolFile.delete();
            pool = null;
            captions = null;
            queue.clear();
            offlineSince = -1;
        }
    }

    /** Forgets the server settings, every saved photo and the imported backgrounds. */
    synchronized void clear() {
        prefs.edit().clear().commit();
        poolFile.delete();
        File[] files = photoDir.listFiles();
        if (files != null) for (File f : files) f.delete();
        for (File f : localFiles()) f.delete();
        pool = null;
        captions = null;
        queue.clear();
        offlineSince = -1;
    }

    synchronized String describe() {
        if (!configured()) return "";
        return "Immich " + url() + ", " + (album().length() > 0 ? "album " + album() : "whole library")
                + ", a new photo every " + intervalSeconds() + " s.";
    }

    /** One or two sentences for the home screen's menu. */
    synchronized String status() {
        if (!configured()) {
            return "Screensaver photos aren't set up. On your computer, run ./install.sh config.";
        }
        int inPool = loadPool().size();
        int saved = cachedFiles().length;
        StringBuilder s = new StringBuilder("Screensaver: ")
                .append(inPool).append(inPool == 1 ? " photo" : " photos").append(" from Immich, ")
                .append(saved).append(" saved on this TV.");
        long at = prefs.getLong("pool_at", 0);
        if (at > 0) s.append(" Synced ").append(ago(System.currentTimeMillis() - at)).append('.');
        String error = prefs.getString("error", "");
        if (error.length() > 0) s.append("\nLast sync failed: ").append(error);
        return s.toString();
    }

    /** What the screensaver says when it has nothing to show. */
    synchronized String emptyMessage() {
        if (!configured()) {
            return "Screensaver photos aren't set up.\nOn your computer, run ./install.sh config <immich-url> <api-key>.";
        }
        String error = prefs.getString("error", "");
        return "Can't reach Immich at " + url() + (error.length() > 0 ? ".\n" + error : ".")
                + "\nPhotos appear here once one has loaded. Trying again every minute.";
    }

    // ---------------------------------------------------------------- sync

    /** Refreshes the photo list if it is missing or older than POOL_TTL_MS. Never throws. */
    synchronized void syncIfStale() {
        if (!configured() || inOfflineCooldown()) return;
        long at = prefs.getLong("pool_at", 0);
        if (!loadPool().isEmpty() && System.currentTimeMillis() - at < POOL_TTL_MS) return;
        try {
            sync();
        } catch (Exception e) {
            goOffline(e);
        }
    }

    /** Fetches the photo list from Immich now. Returns how many photos it holds. */
    synchronized int sync() throws IOException, JSONException {
        int[] version = serverVersion();
        boolean newShape = version[0] > 3 || (version[0] == 3 && version[1] >= 2);
        List<Photo> fetched;
        try {
            fetched = fetchPool(newShape);
        } catch (HttpError e) {
            if (!newShape || e.code != 400) throw e;
            Log.w(TAG, "v3 search shape rejected, retrying with the flat one: " + e.getMessage());
            fetched = fetchPool(false);
        }
        if (fetched.isEmpty()) {
            throw new IOException(album().length() > 0
                    ? "Album " + album() + " has no photos this API key can see."
                    : "Immich returned no photos for this API key.");
        }
        savePool(fetched);
        prefs.edit().putLong("pool_at", System.currentTimeMillis()).remove("error").remove("dream_failed").commit();
        offlineSince = -1;
        queue.clear();
        Log.i(TAG, "synced " + fetched.size() + " photos from Immich " + version[0] + "." + version[1]);
        return fetched.size();
    }

    /** Downloads up to {@code count} photos that are not on the TV yet, for offline use. */
    synchronized int prefetch(int count, long budgetMs) {
        long deadline = SystemClock.uptimeMillis() + budgetMs;
        List<Photo> todo = new ArrayList<Photo>(loadPool());
        Collections.shuffle(todo, random);
        int saved = 0;
        for (Photo p : todo) {
            if (saved >= count || SystemClock.uptimeMillis() > deadline) break;
            File f = photoFile(p.id);
            if (f.exists()) continue;
            try {
                download(p.id, f);
                saved++;
            } catch (IOException e) {
                Log.w(TAG, "prefetch stopped: " + e.getMessage());
                break;
            }
        }
        trimCache();
        return saved;
    }

    synchronized void recordError(String message) {
        prefs.edit().putString("error", message).commit();
    }

    private List<Photo> fetchPool(boolean newShape) throws IOException, JSONException {
        Map<String, Photo> found = new LinkedHashMap<String, Photo>();
        String album = album();
        if (album.length() > 0) {
            String cursor = null;
            int page = 1;
            while (found.size() < POOL_MAX) {
                JSONObject body = new JSONObject().put("size", PAGE_SIZE).put("withExif", true);
                if (newShape) {
                    body.put("filter", filter(album));
                    if (cursor != null) body.put("cursor", cursor);
                } else {
                    body.put("albumIds", new JSONArray().put(album)).put("type", "IMAGE").put("page", page);
                }
                JSONObject assets = new JSONObject(post("/api/search/metadata", body)).getJSONObject("assets");
                collect(assets.getJSONArray("items"), found);
                String next = string(assets, newShape ? "nextCursor" : "nextPage");
                if (next == null || next.length() == 0) break;
                cursor = next;
                page++;
            }
        } else {
            JSONObject body = new JSONObject().put("size", RANDOM_SIZE).put("withExif", true);
            if (newShape) body.put("filter", filter(null));
            else body.put("type", "IMAGE");
            collect(new JSONArray(post("/api/search/random", body)), found);
        }
        return new ArrayList<Photo>(found.values());
    }

    /**
     * The v3 filter. Unlike the old request shape it has no default exclusions, so trashed photos
     * and live-photo halves have to be filtered out here. Archived photos are shown only when they
     * are in the chosen album, since archiving usually means "keep it off the timeline".
     */
    private static JSONObject filter(String album) throws JSONException {
        JSONArray visibility = new JSONArray().put("timeline");
        if (album != null) visibility.put("archive");
        JSONObject f = new JSONObject()
                .put("type", new JSONObject().put("eq", "IMAGE"))
                .put("visibility", new JSONObject().put("in", visibility))
                .put("trashedAt", new JSONObject().put("eq", JSONObject.NULL));
        if (album != null) f.put("albumIds", new JSONObject().put("any", new JSONArray().put(album)));
        return f;
    }

    private static void collect(JSONArray items, Map<String, Photo> into) throws JSONException {
        for (int i = 0; i < items.length(); i++) {
            JSONObject a = items.getJSONObject(i);
            String type = string(a, "type");
            if (type != null && !"IMAGE".equals(type)) continue;
            String id = a.getString("id");
            into.put(id, new Photo(id, caption(a)));
        }
    }

    /** "Lisbon, May 2019", "May 2019" or "". */
    private static String caption(JSONObject asset) {
        String when = string(asset, "localDateTime");
        if (when == null) when = string(asset, "fileCreatedAt");
        String date = "";
        if (when != null && when.length() >= 10) {
            try {
                Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(when.substring(0, 10));
                date = new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(d);
            } catch (Exception ignored) {
                // keep the caption empty rather than show a raw timestamp
            }
        }
        JSONObject exif = asset.optJSONObject("exifInfo");
        String city = exif == null ? null : string(exif, "city");
        if (city == null || city.length() == 0) return date;
        return date.length() == 0 ? city : city + ", " + date;
    }

    // ---------------------------------------------------------------- showing photos

    /**
     * The next photo to show, decoded to roughly {@code width} x {@code height}, or null if there is
     * none (not set up, or offline with nothing saved). Prefers Immich; falls back to saved photos
     * when the server can't be reached, and tries the server again every OFFLINE_RETRY_MS.
     */
    synchronized Shown next(int width, int height) {
        if (configured() && !inOfflineCooldown()) {
            if (queue.isEmpty()) {
                queue.addAll(loadPool());
                Collections.shuffle(queue, random);
                queuePos = 0;
            }
            for (int tries = 0; tries < 8 && !queue.isEmpty(); tries++) {
                if (queuePos >= queue.size()) {
                    Collections.shuffle(queue, random);
                    queuePos = 0;
                }
                Photo p = queue.get(queuePos++);
                File f = photoFile(p.id);
                try {
                    if (!f.exists()) {
                        download(p.id, f);
                        trimCache();
                    }
                    Bitmap b = decode(f, width, height);
                    if (b == null) {
                        f.delete();
                        continue;
                    }
                    f.setLastModified(System.currentTimeMillis());
                    lastShownId = p.id;
                    offlineSince = -1;
                    return new Shown(b, p.caption);
                } catch (HttpError e) {
                    if (e.code == 404) continue; // deleted on the server since the last sync
                    goOffline(e);
                    break;
                } catch (IOException e) {
                    goOffline(e);
                    break;
                }
            }
        }
        return nextSaved(width, height);
    }

    /**
     * A random saved photo, small, for the home screen's background: an Immich one, or else one of
     * the imported backgrounds (see {@link #importBackgrounds}). Null if there are neither.
     */
    synchronized Bitmap randomSaved(int width, int height) {
        File[] files = cachedFiles();
        if (files.length == 0) files = localFiles();
        if (files.length == 0) return null;
        return decode(files[random.nextInt(files.length)], width, height);
    }

    /**
     * Replaces the home screen's own backgrounds with the JPEG and PNG files in {@code dir}, for
     * while no Immich photos are saved. They never count as saved photos, so the screensaver's
     * choice between Frame and the fallback is unchanged. Returns how many were imported.
     */
    synchronized int importBackgrounds(File dir) throws IOException {
        File[] in = dir.listFiles();
        if (in == null) throw new IOException("Can't read " + dir + ".");
        localDir.mkdirs();
        for (File f : localFiles()) f.delete();
        int n = 0;
        for (File f : in) {
            if (!f.getName().toLowerCase(Locale.US).matches(".*\\.(jpe?g|png)")) continue;
            File into = new File(localDir, "local-" + n + ".img");
            OutputStream out = new FileOutputStream(into);
            try {
                out.write(readAll(new FileInputStream(f)));
            } finally {
                out.close();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(into.getPath(), o);
            if (o.outWidth > 0) n++;
            else into.delete();
        }
        return n;
    }

    private Shown nextSaved(int width, int height) {
        File[] files = cachedFiles();
        if (files.length == 0) return null;
        File f = files[random.nextInt(files.length)];
        if (files.length > 1 && f.getName().equals(lastShownId + ".img")) {
            f = files[(Arrays.asList(files).indexOf(f) + 1) % files.length];
        }
        Bitmap b = decode(f, width, height);
        if (b == null) {
            f.delete();
            return null;
        }
        String id = f.getName().substring(0, f.getName().length() - 4);
        lastShownId = id;
        String c = captions().get(id);
        return new Shown(b, c == null ? "" : c);
    }

    static Bitmap decode(File f, int width, int height) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width && o.outHeight / (sample * 2) >= height) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }

    // ---------------------------------------------------------------- offline handling

    private boolean inOfflineCooldown() {
        return offlineSince >= 0 && SystemClock.elapsedRealtime() - offlineSince < OFFLINE_RETRY_MS;
    }

    private void goOffline(Exception e) {
        offlineSince = SystemClock.elapsedRealtime();
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        recordError(message);
        Log.w(TAG, "offline: " + message);
    }

    // ---------------------------------------------------------------- disk

    private File photoFile(String id) {
        return new File(photoDir, id + ".img");
    }

    private File[] cachedFiles() {
        File[] files = photoDir.listFiles();
        if (files == null) return new File[0];
        List<File> out = new ArrayList<File>();
        for (File f : files) if (f.getName().endsWith(".img")) out.add(f);
        return out.toArray(new File[0]);
    }

    private File[] localFiles() {
        File[] files = localDir.listFiles();
        return files == null ? new File[0] : files;
    }

    private void trimCache() {
        File[] files = cachedFiles();
        if (files.length <= CACHE_MAX) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (int i = 0; i < files.length - CACHE_MAX; i++) files[i].delete();
    }

    private List<Photo> loadPool() {
        if (pool != null) return pool;
        pool = new ArrayList<Photo>();
        if (poolFile.exists()) {
            try {
                JSONArray a = new JSONArray(new String(readAll(new FileInputStream(poolFile)), "UTF-8"));
                for (int i = 0; i < a.length(); i++) {
                    JSONArray p = a.getJSONArray(i);
                    pool.add(new Photo(p.getString(0), p.optString(1, "")));
                }
            } catch (Exception e) {
                Log.w(TAG, "photo list unreadable, will sync again", e);
                pool.clear();
            }
        }
        return pool;
    }

    private void savePool(List<Photo> photos) throws IOException {
        JSONArray a = new JSONArray();
        for (Photo p : photos) a.put(new JSONArray().put(p.id).put(p.caption));
        File tmp = new File(poolFile.getPath() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(a.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
        if (!tmp.renameTo(poolFile)) throw new IOException("could not save the photo list");
        pool = photos;
        captions = null;
    }

    private Map<String, String> captions() {
        if (captions == null) {
            captions = new HashMap<String, String>();
            for (Photo p : loadPool()) captions.put(p.id, p.caption);
        }
        return captions;
    }

    // ---------------------------------------------------------------- HTTP

    private int[] serverVersion() throws IOException, JSONException {
        try {
            JSONObject v = new JSONObject(get("/api/server/version"));
            return new int[] {v.optInt("major"), v.optInt("minor")};
        } catch (HttpError e) {
            if (e.code == 404) return new int[] {1, 0}; // before v1.106 the route was /api/server-info/version
            throw e;
        }
    }

    private void download(String id, File into) throws IOException {
        HttpURLConnection c = open("/api/assets/" + id + "/thumbnail?size=preview", "GET");
        try {
            check(c);
            File tmp = new File(into.getPath() + ".part");
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
                in.close();
            }
            if (!tmp.renameTo(into)) throw new IOException("could not save photo " + id);
        } finally {
            c.disconnect();
        }
    }

    private String get(String path) throws IOException {
        HttpURLConnection c = open(path, "GET");
        try {
            check(c);
            return new String(readAll(c.getInputStream()), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private String post(String path, JSONObject body) throws IOException {
        HttpURLConnection c = open(path, "POST");
        try {
            byte[] bytes = body.toString().getBytes("UTF-8");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = c.getOutputStream();
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            check(c);
            return new String(readAll(c.getInputStream()), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url() + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(20000);
        c.setRequestProperty("x-api-key", apiKey());
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private static void check(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code / 100 == 2) return;
        String path = c.getURL().getPath();
        String detail = "";
        InputStream err = c.getErrorStream();
        if (err != null) {
            try {
                detail = new String(readAll(err), "UTF-8").trim();
                if (detail.length() > 160) detail = detail.substring(0, 160);
            } catch (IOException ignored) {
                // the status code is enough
            }
        }
        String message;
        if (code == 401) message = "Immich rejected the API key (HTTP 401).";
        else if (code == 403) message = "The API key needs the asset.read and asset.view permissions (HTTP 403).";
        else message = path + " returned HTTP " + code + (detail.length() > 0 ? ": " + detail : ".");
        throw new HttpError(code, message);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Android's optString turns JSON null into the text "null"; this returns real null instead. */
    private static String string(JSONObject o, String key) {
        Object v = o.opt(key);
        return v == null || v == JSONObject.NULL ? null : String.valueOf(v);
    }

    private static String ago(long ms) {
        long min = ms / 60000;
        if (min < 1) return "just now";
        if (min < 60) return min + (min == 1 ? " minute ago" : " minutes ago");
        long h = min / 60;
        if (h < 48) return h + (h == 1 ? " hour ago" : " hours ago");
        return (h / 24) + " days ago";
    }
}
