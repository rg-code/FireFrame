package com.framehome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Famous paintings for the screensaver and the home screen, from several open collections (see
 * {@link ArtSources}): the Art Institute of Chicago, the Met and the Cleveland Museum of Art
 * (their highlighted public-domain paintings), and Wikidata (the paintings with the most Wikipedia
 * articles, made before 1930) with a caption from Wikipedia. None needs a key.
 *
 * The combined list is refreshed weekly, source by source (a source that fails keeps its last
 * list). Works are saved in turn from each source, shrunk on the TV to fit 1920 x 1080 (the TV's
 * UI resolution) with their title, artist and caption beside them. The ART_MAX most recently shown
 * stay, and a few new ones rotate in each day. Violent scenes are left out (see {@link #violent}).
 *
 * Network and disk except for the small getters: call it off the main thread.
 */
final class ArtStore {
    static final int MIN_SAVED = 5;

    // Wikimedia and the museums ask clients to say who they are.
    static final String USER_AGENT = "FrameScreensaver/1.0 (personal Fire TV art screensaver)";
    private static final int ART_MAX = 60;
    private static final long LIST_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long ROTATE_MS = 24L * 60 * 60 * 1000;
    private static final int ROTATE_COUNT = 4;
    // Bump when the selection rules change, so the lists are fetched again and dropped works deleted.
    private static final int LIST_VERSION = 4;

    /** One painting. {@code image} may be null until {@link Source#resolve} fills it in. */
    static final class Work {
        final String id;      // "aic:28560", "met:436535", "cma:1922.1133", "wd:Q12418"
        final String image;
        final String title;
        final String artist;  // "Claude Monet, 1906"
        final String caption; // one sentence, or ""
        final String extra;   // what resolve needs, e.g. the Wikipedia article

        Work(String id, String image, String title, String artist, String caption, String extra) {
            this.id = id;
            this.image = image;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.caption = caption == null ? "" : caption;
            this.extra = extra == null ? "" : extra;
        }
    }

    interface Source {
        /** The prefix of this source's ids, e.g. "aic". */
        String key();

        /** "Art Institute of Chicago". */
        String name();

        /** All candidate works, already filtered where the list allows it. */
        List<Work> list() throws Exception;

        /** The complete work, or null if it can't be shown (not public domain, violent, no image). */
        Work resolve(Work w) throws Exception;
    }

    private static ArtStore instance;

    static synchronized ArtStore get(Context context) {
        if (instance == null) instance = new ArtStore(context.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;
    private final File dir;
    private final File listFile;
    private final Random random = new Random();
    private final List<Source> sources = ArtSources.all();
    private List<Work> works;
    private String lastShownId;

    private ArtStore(Context context) {
        prefs = context.getSharedPreferences("frame", Context.MODE_PRIVATE);
        dir = new File(context.getCacheDir(), "art");
        dir.mkdirs();
        listFile = new File(context.getFilesDir(), "art.json");
    }

    /** On unless turned off with ./install.sh art off. */
    boolean enabled() {
        return prefs.getBoolean("art", true);
    }

    void setEnabled(boolean on) {
        prefs.edit().putBoolean("art", on).commit();
    }

    /** Saved works, or 0 when art is off. */
    synchronized int savedCount() {
        return enabled() ? savedFiles().length : 0;
    }

    synchronized String status() {
        if (!enabled()) return "Art is off.";
        Map<String, Integer> listed = new LinkedHashMap<String, Integer>();
        for (Source s : sources) listed.put(s.key(), 0);
        for (Work w : load()) {
            String k = w.id.substring(0, w.id.indexOf(':'));
            if (listed.containsKey(k)) listed.put(k, listed.get(k) + 1);
        }
        StringBuilder b = new StringBuilder("Art: ").append(savedFiles().length).append(" of ")
                .append(load().size()).append(" works saved (");
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(sources.get(i).name()).append(' ').append(listed.get(sources.get(i).key()));
        }
        return b.append(").").toString();
    }

    /** Refreshes the lists if they are a week old, then saves up to {@code count} more works. Never throws. */
    synchronized int refresh(int count, long budgetMs) {
        if (!enabled()) return 0;
        long deadline = SystemClock.uptimeMillis() + budgetMs;
        if (load().isEmpty() || prefs.getInt("art_version", 0) != LIST_VERSION
                || System.currentTimeMillis() - prefs.getLong("art_at", 0) > LIST_TTL_MS) {
            fetchLists(deadline);
        }
        if (load().isEmpty()) return 0;
        // Once the cache is full, a few new works rotate in each day.
        if (savedFiles().length >= ART_MAX) {
            if (System.currentTimeMillis() - prefs.getLong("art_rotated", 0) < ROTATE_MS) return 0;
            count = Math.min(count, ROTATE_COUNT);
            prefs.edit().putLong("art_rotated", System.currentTimeMillis()).apply();
        }
        Set<String> skip = new HashSet<String>(prefs.getStringSet("art_skip", Collections.<String>emptySet()));
        Set<String> seenTitles = new HashSet<String>();
        for (Work w : load()) if (w.title.length() > 0 && !w.id.startsWith("wd:")) seenTitles.add(sameWork(w));
        int n = 0;
        for (Work w : roundRobin()) {
            if (n >= count || SystemClock.uptimeMillis() > deadline) break;
            if (skip.contains(w.id) || image(w.id).exists()) continue;
            Source source = source(w.id);
            try {
                Work full = source == null ? null : source.resolve(w);
                // A Met work's title is only known now; skip it if another museum already has it.
                if (full != null && w.title.length() == 0 && seenTitles.contains(sameWork(full))) full = null;
                if (full == null || full.image == null || full.caption.length() == 0
                        || violent(full.title + " " + full.caption, null)) {
                    skip.add(w.id);
                    continue;
                }
                save(full);
                n++;
            } catch (Exception e) {
                Log.w(PhotoStore.TAG, "art " + w.id + ": " + e.getMessage());
            }
        }
        prefs.edit().putStringSet("art_skip", skip).apply();
        trim();
        if (n > 0) Log.i(PhotoStore.TAG, "saved " + n + " artworks");
        return n;
    }

    /** A saved work, decoded to fit {@code width} x {@code height}, or null if none is saved. */
    synchronized PhotoStore.Shown next(int width, int height) {
        if (!enabled()) return null;
        File[] files = savedFiles();
        if (files.length == 0) return null;
        File f = files[random.nextInt(files.length)];
        if (files.length > 1 && f.getName().equals(lastShownId + ".jpg")) {
            f = files[(Arrays.asList(files).indexOf(f) + 1) % files.length];
        }
        Bitmap b = decodeToFit(f, width, height);
        if (b == null) {
            f.delete();
            return null;
        }
        f.setLastModified(System.currentTimeMillis());
        String name = f.getName().substring(0, f.getName().length() - 4);
        lastShownId = name;
        String[] meta = {"", "", ""};
        try {
            String[] lines = new String(readAll(new FileInputStream(new File(dir, name + ".txt"))), "UTF-8").split("\n", -1);
            for (int i = 0; i < Math.min(3, lines.length); i++) meta[i] = lines[i];
        } catch (IOException ignored) {
            // shown without a card
        }
        return new PhotoStore.Shown(b, meta[0], meta[1], meta[2]);
    }

    // ---------------------------------------------------------------- the lists

    private void fetchLists(long deadline) {
        Map<String, List<Work>> bySource = new LinkedHashMap<String, List<Work>>();
        for (Work w : load()) {
            String k = w.id.substring(0, w.id.indexOf(':'));
            if (!bySource.containsKey(k)) bySource.put(k, new ArrayList<Work>());
            bySource.get(k).add(w);
        }
        boolean all = true;
        for (Source s : sources) {
            // A source's list can take half a minute (Wikidata's query especially); one that might
            // not finish in time is left for the next check, which keeps its previous list until then.
            if (deadline - SystemClock.uptimeMillis() < 30_000) {
                all = false;
                continue;
            }
            try {
                List<Work> fresh = s.list();
                bySource.put(s.key(), fresh);
                Log.i(PhotoStore.TAG, "art list: " + fresh.size() + " from " + s.name());
            } catch (Exception e) {
                all = false;
                Log.w(PhotoStore.TAG, "art list " + s.name() + ": " + e.getMessage());
            }
        }
        // Wikidata's famous works that one of the museums also lists are taken from the museum.
        Set<String> museum = new HashSet<String>();
        for (Map.Entry<String, List<Work>> e : bySource.entrySet()) {
            if (!e.getKey().equals("wd")) for (Work w : e.getValue()) if (w.title.length() > 0) museum.add(sameWork(w));
        }
        List<Work> merged = new ArrayList<Work>();
        for (Map.Entry<String, List<Work>> e : bySource.entrySet()) {
            for (Work w : e.getValue()) if (!e.getKey().equals("wd") || !museum.contains(sameWork(w))) merged.add(w);
        }
        try {
            JSONArray out = new JSONArray();
            for (Work w : merged) {
                out.put(new JSONArray().put(w.id).put(w.image == null ? "" : w.image).put(w.title).put(w.artist)
                        .put(w.caption).put(w.extra));
            }
            writeAtomically(listFile, out.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            Log.w(PhotoStore.TAG, "could not save the art list: " + e.getMessage());
            return;
        }
        works = merged;
        Set<String> ids = new HashSet<String>();
        for (Work w : merged) ids.add(fileName(w.id));
        for (File f : savedFiles()) {
            String name = f.getName().substring(0, f.getName().length() - 4);
            // Also works saved before every work needed a caption, so captioned ones replace them.
            if (!ids.contains(name) || caption(name).length() == 0) {
                f.delete();
                new File(dir, name + ".txt").delete();
            }
        }
        SharedPreferences.Editor e = prefs.edit().putLong("art_at", System.currentTimeMillis());
        // Only a complete refresh settles the version; otherwise the next check tries again.
        if (all) e.putInt("art_version", LIST_VERSION).remove("art_skip");
        e.apply();
    }

    /** The list with the sources interleaved, each shuffled, so every source gets its turn. */
    private List<Work> roundRobin() {
        Map<String, List<Work>> bySource = new LinkedHashMap<String, List<Work>>();
        for (Work w : load()) {
            String k = w.id.substring(0, w.id.indexOf(':'));
            if (!bySource.containsKey(k)) bySource.put(k, new ArrayList<Work>());
            bySource.get(k).add(w);
        }
        List<List<Work>> lists = new ArrayList<List<Work>>(bySource.values());
        for (List<Work> l : lists) Collections.shuffle(l, random);
        Collections.shuffle(lists, random);
        List<Work> out = new ArrayList<Work>();
        for (int i = 0; ; i++) {
            boolean any = false;
            for (List<Work> l : lists) {
                if (i < l.size()) {
                    out.add(l.get(i));
                    any = true;
                }
            }
            if (!any) return out;
        }
    }

    private Source source(String id) {
        String k = id.substring(0, id.indexOf(':'));
        for (Source s : sources) if (s.key().equals(k)) return s;
        return null;
    }

    private List<Work> load() {
        if (works != null) return works;
        works = new ArrayList<Work>();
        if (!listFile.exists()) return works;
        try {
            JSONArray a = new JSONArray(new String(readAll(new FileInputStream(listFile)), "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONArray w = a.getJSONArray(i);
                if (w.length() < 6 || w.getString(0).indexOf(':') < 0) continue; // an older list
                String image = w.getString(1);
                works.add(new Work(w.getString(0), image.length() == 0 ? null : image, w.getString(2),
                        w.getString(3), w.getString(4), w.getString(5)));
            }
        } catch (Exception e) {
            Log.w(PhotoStore.TAG, "art list unreadable, will fetch again", e);
            works.clear();
        }
        return works;
    }

    /** Title and the artist's last name, letters only: how the same painting is recognised across sources. */
    private static String sameWork(Work w) {
        String artist = w.artist.split(",")[0].trim();
        String last = artist.contains(" ") ? artist.substring(artist.lastIndexOf(' ') + 1) : artist;
        return (w.title + "|" + last).toLowerCase(Locale.US).replaceAll("[^a-z|]", "");
    }

    // ---------------------------------------------------------------- what to leave out

    /**
     * No violent scenes, and nothing sexually explicit (classical nudes stay). Tags are the museums'
     * own subject terms and Wikidata's "depicts"; words are checked in the title, the caption and,
     * for Wikidata, Wikipedia's summary. Checked against all four sources' lists: "dead" also drops
     * dead-game still lifes, "execution" only counts as a tag ("executed in oil" is common).
     */
    private static final Set<String> VIOLENT_TAGS = new HashSet<String>(Arrays.asList(
            "violence", "crucifixions", "crucifixion", "battles", "battle", "war", "murder", "martyrdom",
            "executions", "execution", "suicide", "death", "corpse", "dead body", "cadaver", "blood", "weapon",
            "skull", "boxing", "vulva", "penis", "genitalia", "sexual intercourse"));
    private static final Pattern VIOLENT_WORDS = Pattern.compile("\\b(crucifi\\w*|behead\\w*|decapitat\\w*"
            + "|martyr\\w*|massacre\\w*|slaughter\\w*|murder\\w*|assassin\\w*|flay\\w*|flagellat\\w*"
            + "|scourg\\w*|judith|holofernes|salome|battle\\w*|combat|killing|kills?|slay\\w*|slain|tortur\\w*"
            + "|corpse\\w*|bloodletting|bloody|rape|devour\\w*|chastis\\w*|suicide|wounded|dead|shoots?"
            + "|death of|boxing|boxers?|prizefight\\w*|pistols?|bayonet\\w*|muskets?|rifles?|anatomy lesson"
            + "|dissect\\w*|bodies of the fallen|incubus)\\b");

    static boolean violent(String text, Collection<String> tags) {
        if (tags != null) {
            for (String t : tags) if (VIOLENT_TAGS.contains(t.toLowerCase(Locale.US).trim())) return true;
        }
        return text != null && VIOLENT_WORDS.matcher(text.toLowerCase(Locale.US)).find();
    }

    // ---------------------------------------------------------------- captions

    /** The museums' descriptions are a short paragraph of HTML; the first sentence makes the caption. */
    static String firstSentence(String html) {
        List<String> s = sentences(html);
        return s.isEmpty() ? "" : shorten(s.get(0));
    }

    /**
     * Wikipedia's first sentence usually just restates the title and artist ("X is an oil painting
     * by Y"), so the second is used when there is one.
     */
    static String informativeSentence(String text) {
        List<String> s = sentences(text);
        if (s.isEmpty()) return "";
        String first = s.get(0);
        boolean restates = first.matches(".*\\b(is|was) an? .*") && first.toLowerCase(Locale.US).contains("paint");
        return shorten(restates && s.size() > 1 ? s.get(1) : first);
    }

    private static List<String> sentences(String html) {
        List<String> out = new ArrayList<String>();
        if (html == null) return out;
        String s = clean(html);
        int start = 0;
        for (int i = start + 40; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && (i + 1 == s.length() || s.charAt(i + 1) == ' ')
                    && i - start >= 40) {
                out.add(s.substring(start, i + 1).trim());
                start = i + 1;
            }
        }
        if (start < s.length() && s.substring(start).trim().length() > 0) out.add(s.substring(start).trim());
        return out;
    }

    private static String shorten(String s) {
        if (s.length() <= 160) return s;
        int cut = s.lastIndexOf(' ', 157);
        return s.substring(0, cut > 0 ? cut : 157) + "…";
    }

    static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]*>", "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("ﬁ", "fi").replace("ﬂ", "fl").replaceAll("\\s+", " ").trim();
    }

    // ---------------------------------------------------------------- disk

    private String caption(String name) {
        try {
            String[] lines = new String(readAll(new FileInputStream(new File(dir, name + ".txt"))), "UTF-8").split("\n", -1);
            return lines.length >= 3 ? lines[2].trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String fileName(String id) {
        return id.replaceAll("[^A-Za-z0-9.]", "_");
    }

    private File image(String id) {
        return new File(dir, fileName(id) + ".jpg");
    }

    /** Downloads the image, shrinks it to fit the screen, and writes it with its card beside it. */
    private void save(Work w) throws IOException {
        File raw = new File(dir, fileName(w.id) + ".part");
        try {
            download(w.image, raw);
            Bitmap b = decodeToFit(raw, 1920, 1080);
            if (b == null) throw new IOException("unreadable image");
            float scale = Math.min(1920f / b.getWidth(), 1080f / b.getHeight());
            if (scale < 1f) {
                Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true);
                if (s != b) b.recycle();
                b = s;
            }
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 90, jpeg);
            b.recycle();
            writeAtomically(new File(dir, fileName(w.id) + ".txt"), (w.title + "\n" + w.artist + "\n" + w.caption).getBytes("UTF-8"));
            writeAtomically(image(w.id), jpeg.toByteArray());
        } finally {
            raw.delete();
        }
    }

    private File[] savedFiles() {
        File[] files = dir.listFiles();
        if (files == null) return new File[0];
        List<File> out = new ArrayList<File>();
        for (File f : files) if (f.getName().endsWith(".jpg")) out.add(f);
        return out.toArray(new File[0]);
    }

    private void trim() {
        File[] files = savedFiles();
        if (files.length <= ART_MAX) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (int i = 0; i < files.length - ART_MAX; i++) {
            String name = files[i].getName();
            files[i].delete();
            new File(dir, name.substring(0, name.length() - 4) + ".txt").delete();
        }
    }

    /** Paintings are shown whole, so only the side that limits the fit needs the full size. */
    private static Bitmap decodeToFit(File f, int width, int height) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        float scale = Math.min((float) width / o.outWidth, (float) height / o.outHeight);
        int sample = 1;
        while (sample * 2 * scale <= 1f) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }

    private static void writeAtomically(File f, byte[] bytes) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        OutputStream os = new FileOutputStream(tmp);
        try {
            os.write(bytes);
        } finally {
            os.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("could not save " + f.getName());
    }

    // ---------------------------------------------------------------- HTTP

    private static void download(String url, File into) throws IOException {
        HttpURLConnection c = open(url, null, 30000);
        try {
            if (c.getResponseCode() / 100 != 2) throw new IOException(url + " returned HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(into);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    /** GET, or POST when {@code body} isn't null. Returns the response text. */
    static String fetch(String url, String body, Map<String, String> headers, int readTimeoutMs) throws IOException {
        HttpURLConnection c = open(url, headers, readTimeoutMs);
        try {
            if (body != null) {
                byte[] bytes = body.getBytes("UTF-8");
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(bytes.length);
                OutputStream out = c.getOutputStream();
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException(new URL(url).getHost() + " returned HTTP " + code);
            return new String(readAll(c.getInputStream()), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, Map<String, String> headers, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestProperty("User-Agent", USER_AGENT);
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        return c;
    }

    static byte[] readAll(InputStream in) throws IOException {
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

    static String string(JSONObject o, String key) {
        Object v = o.opt(key);
        return v == null || v == JSONObject.NULL ? null : String.valueOf(v);
    }

    static Map<String, String> headers(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
