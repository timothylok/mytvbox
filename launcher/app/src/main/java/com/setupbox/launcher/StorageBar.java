package com.setupbox.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Thin rounded progress bar for used storage. */
final class StorageBar extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    float usedFrac = 0f;
    int fillColor = Color.parseColor("#4C8DFF");

    StorageBar(Context c) {
        super(c);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float h = getHeight(), w = getWidth(), rad = h / 2f;
        p.setColor(Color.parseColor("#2A3558"));
        r.set(0, 0, w, h);
        cv.drawRoundRect(r, rad, rad, p);
        float fw = Math.max(h, w * Math.min(1f, Math.max(0f, usedFrac)));
        p.setColor(fillColor);
        r.set(0, 0, fw, h);
        cv.drawRoundRect(r, rad, rad, p);
    }
}
