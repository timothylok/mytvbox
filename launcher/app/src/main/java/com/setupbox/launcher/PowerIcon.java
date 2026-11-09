package com.setupbox.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** The classic power symbol: an open ring with a short bar at the top. */
final class PowerIcon extends Drawable {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();

    PowerIcon() {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFFFF6B6B);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float r = Math.min(b.width(), b.height()) * 0.32f;
        p.setStrokeWidth(r * 0.30f);
        arc.set(cx - r, cy - r + r * 0.08f, cx + r, cy + r + r * 0.08f);
        c.drawArc(arc, -50f, 280f, false, p);
        c.drawLine(cx, cy - r * 1.05f, cx, cy - r * 0.12f, p);
    }

    @Override
    public void setAlpha(int alpha) {
        p.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        p.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
