package com.canvashome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;

/**
 * A painting on a plain mat the size of the 4K panel, with a small museum label under its lower
 * right corner. Art Mode fills the screen with each photo, so a painting saved whole on a 16:9
 * mat is never cropped. With the mat off, the painting is only shrunk to fit 3840 x 2160.
 */
final class Mat {
    static final int WIDTH = 3840;
    static final int HEIGHT = 2160;

    // The painting's largest share of the mat, and the label's size, in mat pixels.
    private static final float MAX_W = 0.80f;
    private static final float MAX_H = 0.74f;
    private static final float TITLE_PX = 40f;
    private static final float ARTIST_PX = 32f;

    private Mat() {
    }

    /** {@code color} is the mat's color, or null for no mat. May return {@code painting} itself. */
    static Bitmap draw(Bitmap painting, String title, String artist, Integer color) {
        if (color == null) {
            float scale = Math.min((float) WIDTH / painting.getWidth(), (float) HEIGHT / painting.getHeight());
            if (scale >= 1f) return painting;
            return Bitmap.createScaledBitmap(painting, Math.round(painting.getWidth() * scale),
                    Math.round(painting.getHeight() * scale), true);
        }
        Bitmap out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(color);

        float scale = Math.min(WIDTH * MAX_W / painting.getWidth(), HEIGHT * MAX_H / painting.getHeight());
        float w = painting.getWidth() * scale, h = painting.getHeight() * scale;
        // A little above center, leaving room for the label.
        float left = (WIDTH - w) / 2f, top = (HEIGHT - h) / 2f - HEIGHT * 0.03f;
        RectF where = new RectF(left, top, left + w, top + h);

        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(color);
        shadow.setShadowLayer(28f, 0f, 10f, Color.argb(90, 0, 0, 0));
        c.drawRect(where, shadow);
        Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(painting, new Rect(0, 0, painting.getWidth(), painting.getHeight()), where, image);

        boolean darkMat = luminance(color) < 0.45f;
        int ink = darkMat ? Color.argb(200, 240, 238, 232) : Color.argb(210, 40, 38, 34);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(ink);
        t.setTextAlign(Paint.Align.RIGHT);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        t.setTextSize(TITLE_PX);
        float maxText = Math.max(w, WIDTH * 0.4f);
        float y = where.bottom + 70f;
        c.drawText(TextUtils.ellipsize(title, new android.text.TextPaint(t), maxText, TextUtils.TruncateAt.END).toString(),
                where.right, y, t);
        if (artist.length() > 0) {
            t.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            t.setTextSize(ARTIST_PX);
            c.drawText(TextUtils.ellipsize(artist, new android.text.TextPaint(t), maxText, TextUtils.TruncateAt.END).toString(),
                    where.right, y + 50f, t);
        }
        return out;
    }

    private static float luminance(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }
}
