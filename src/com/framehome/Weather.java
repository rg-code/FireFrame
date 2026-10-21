package com.framehome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * The current temperature and an icon for one place, shown under the clock. From Open-Meteo (no
 * key). The place is set over ADB as coordinates (./install.sh weather ZIP looks them up on the
 * computer), and the reading is kept in the preferences and refreshed every 20 minutes at most.
 */
final class Weather {
    private static final long STALE_MS = 20L * 60 * 1000;
    private static final long EXPIRED_MS = 3L * 60 * 60 * 1000;

    private Weather() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("frame", Context.MODE_PRIVATE);
    }

    static boolean configured(Context c) {
        return prefs(c).getString("weather_lat", "").length() > 0;
    }

    /** Sets the place; an empty latitude turns the weather off. */
    static void configure(Context c, String lat, String lon, String unit) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (lat != null) e.putString("weather_lat", lat.trim()).putString("weather_lon", lon == null ? "" : lon.trim());
        if ("celsius".equals(unit) || "fahrenheit".equals(unit)) e.putString("weather_unit", unit);
        e.remove("weather_at").commit();
    }

    /** Fetches a new reading if the last one is over 20 minutes old. Network: call off the main thread. */
    static void refreshIfStale(Context c) {
        SharedPreferences p = prefs(c);
        if (!configured(c) || System.currentTimeMillis() - p.getLong("weather_at", 0) < STALE_MS) return;
        String unit = p.getString("weather_unit", "fahrenheit");
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + p.getString("weather_lat", "")
                + "&longitude=" + p.getString("weather_lon", "")
                + "&current=temperature_2m,weather_code,is_day&temperature_unit=" + unit;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            try {
                if (conn.getResponseCode() / 100 != 2) throw new IOException("HTTP " + conn.getResponseCode());
                JSONObject now = new JSONObject(new String(ArtStore.readAll(conn.getInputStream()), "UTF-8"))
                        .getJSONObject("current");
                p.edit().putInt("weather_temp", (int) Math.round(now.getDouble("temperature_2m")))
                        .putInt("weather_code", now.getInt("weather_code"))
                        .putBoolean("weather_day", now.optInt("is_day", 1) == 1)
                        .putLong("weather_at", System.currentTimeMillis()).apply();
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(PhotoStore.TAG, "weather: " + e.getMessage());
        }
    }

    /** Shows the last reading in {@code view} with its icon at the text's size, or hides it if there is none. */
    static void show(Context c, TextView view) {
        SharedPreferences p = prefs(c);
        if (!configured(c) || System.currentTimeMillis() - p.getLong("weather_at", 0) > EXPIRED_MS) {
            view.setText("");
            view.setCompoundDrawables(null, null, null, null);
            return;
        }
        view.setText(String.format(Locale.US, "%d°", p.getInt("weather_temp", 0)));
        int size = Math.round(view.getTextSize() * 1.15f);
        Icon icon = new Icon(kind(p.getInt("weather_code", 0), p.getBoolean("weather_day", true)),
                view.getCurrentTextColor());
        icon.setBounds(0, 0, size, size);
        view.setCompoundDrawables(icon, null, null, null);
        view.setCompoundDrawablePadding(Math.round(size * 0.4f));
    }

    static final int CLEAR = 0, PARTLY = 1, CLOUDY = 2, FOG = 3, RAIN = 4, SNOW = 5, STORM = 6;

    /** WMO weather codes, as Open-Meteo reports them. */
    static int kind(int code, boolean day) {
        int k;
        if (code == 0) k = CLEAR;
        else if (code <= 2) k = PARTLY;
        else if (code == 3) k = CLOUDY;
        else if (code == 45 || code == 48) k = FOG;
        else if ((code >= 71 && code <= 77) || code == 85 || code == 86) k = SNOW;
        else if (code >= 95) k = STORM;
        else k = RAIN;
        return day ? k : k | 0x100;
    }

    /** Thin line icons in the text color, drawn in a unit square. */
    static final class Icon extends Drawable {
        private final int kind;
        private final boolean night;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(int kind, int color) {
            this.kind = kind & 0xFF;
            this.night = (kind & 0x100) != 0;
            line.setColor(color);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            line.setStrokeWidth(Math.max(1.5f, s * 0.075f));
            int save = canvas.saveLayer(b.left, b.top, b.right, b.bottom, null);
            canvas.translate(b.left, b.top);
            canvas.scale(s, s);
            line.setStrokeWidth(line.getStrokeWidth() / s);
            switch (kind) {
                case CLEAR:
                    if (night) moon(canvas, 0.5f, 0.5f, 0.3f);
                    else sun(canvas, 0.5f, 0.5f, 0.2f, true);
                    break;
                case PARTLY:
                    if (night) moon(canvas, 0.36f, 0.36f, 0.22f);
                    else sun(canvas, 0.36f, 0.36f, 0.15f, true);
                    cloud(canvas, 0.58f, 0.66f, 0.8f);
                    break;
                case FOG:
                    for (int i = 0; i < 3; i++) {
                        float y = 0.3f + i * 0.2f;
                        canvas.drawLine(0.12f + (i % 2) * 0.1f, y, 0.88f - ((i + 1) % 2) * 0.1f, y, line);
                    }
                    break;
                default:
                    boolean wet = kind != CLOUDY;
                    cloud(canvas, 0.5f, wet ? 0.42f : 0.55f, 1f);
                    if (kind == RAIN) {
                        for (int i = 0; i < 3; i++) canvas.drawLine(0.32f + i * 0.18f, 0.74f, 0.27f + i * 0.18f, 0.9f, line);
                    } else if (kind == SNOW) {
                        Paint dot = new Paint(line);
                        dot.setStyle(Paint.Style.FILL);
                        for (int i = 0; i < 3; i++) canvas.drawCircle(0.3f + i * 0.2f, 0.8f + (i % 2) * 0.07f, 0.045f, dot);
                    } else if (kind == STORM) {
                        Path bolt = new Path();
                        bolt.moveTo(0.54f, 0.66f);
                        bolt.lineTo(0.42f, 0.82f);
                        bolt.lineTo(0.56f, 0.82f);
                        bolt.lineTo(0.45f, 0.97f);
                        canvas.drawPath(bolt, line);
                    }
                    break;
            }
            canvas.restoreToCount(save);
        }

        private void sun(Canvas canvas, float cx, float cy, float r, boolean rays) {
            canvas.drawCircle(cx, cy, r, line);
            if (!rays) return;
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                canvas.drawLine(cx + cos * r * 1.55f, cy + sin * r * 1.55f, cx + cos * r * 2.05f, cy + sin * r * 2.05f, line);
            }
        }

        private void moon(Canvas canvas, float cx, float cy, float r) {
            Path disc = new Path();
            disc.addCircle(cx, cy, r, Path.Direction.CW);
            Path bite = new Path();
            bite.addCircle(cx + r * 0.55f, cy - r * 0.45f, r * 0.85f, Path.Direction.CW);
            disc.op(bite, Path.Op.DIFFERENCE);
            canvas.drawPath(disc, line);
        }

        /** A cloud centred on (cx, cy), about 0.8 x 0.45 at scale 1. Clears what is behind it first. */
        private void cloud(Canvas canvas, float cx, float cy, float scale) {
            canvas.save();
            canvas.translate(cx, cy);
            canvas.scale(scale, scale);
            Path p = new Path();
            p.addCircle(-0.16f, 0.02f, 0.17f, Path.Direction.CW);
            p.addCircle(0.08f, -0.06f, 0.22f, Path.Direction.CW);
            Path base = new Path();
            base.addRoundRect(new RectF(-0.38f, -0.02f, 0.38f, 0.2f), 0.11f, 0.11f, Path.Direction.CW);
            p.op(base, Path.Op.UNION);
            canvas.drawPath(p, clear);
            float w = line.getStrokeWidth();
            line.setStrokeWidth(w / scale);
            canvas.drawPath(p, line);
            line.setStrokeWidth(w);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            line.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            line.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
