package com.canvashome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Flat one-color versions of the apps' own icons for the home screen, in the text color, so they
 * dim and brighten with focus like the text did. The play triangle inside is cut
 * out, so the photo or painting behind shows through it. Named in res/values/apps.xml (app_icons).
 */
final class AppGlyph extends Drawable {
    /** The glyph for a name in app_icons, or null to show the label as text. */
    static AppGlyph forName(String name, int color) {
        if ("stremio".equals(name) || "youtube".equals(name)) return new AppGlyph(name, color);
        return null;
    }

    private final String name;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);

    private AppGlyph(String name, int color) {
        this.name = name;
        fill.setColor(color);
        cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        int save = canvas.saveLayer(b.left, b.top, b.right, b.bottom, null);
        canvas.translate(b.left + (b.width() - s) / 2f, b.top + (b.height() - s) / 2f);
        canvas.scale(s, s);
        if ("stremio".equals(name)) {
            // Stremio: a rounded diamond with a play triangle.
            Path diamond = new Path();
            float side = 0.68f;
            diamond.addRoundRect(new RectF(0.5f - side / 2, 0.5f - side / 2, 0.5f + side / 2, 0.5f + side / 2),
                    0.11f, 0.11f, Path.Direction.CW);
            Matrix m = new Matrix();
            m.setRotate(45, 0.5f, 0.5f);
            diamond.transform(m);
            canvas.drawPath(diamond, fill);
            Path play = new Path();
            play.moveTo(0.42f, 0.36f);
            play.lineTo(0.42f, 0.64f);
            play.lineTo(0.65f, 0.5f);
            play.close();
            canvas.drawPath(play, cut);
        } else {
            // YouTube (for SmartTube): a wide rounded rectangle with a play triangle.
            canvas.drawRoundRect(new RectF(0.04f, 0.18f, 0.96f, 0.82f), 0.17f, 0.17f, fill);
            Path play = new Path();
            play.moveTo(0.41f, 0.36f);
            play.lineTo(0.41f, 0.64f);
            play.lineTo(0.64f, 0.5f);
            play.close();
            canvas.drawPath(play, cut);
        }
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
