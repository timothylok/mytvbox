package com.setupbox.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Vector-drawn weather icon, so no image assets or emoji fonts are needed. */
final class WeatherIcon extends View {
    static final int CLEAR = 0, PARTLY = 1, CLOUD = 2, FOG = 3, RAIN = 4, SNOW = 5, STORM = 6;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int kind = CLOUD;
    private boolean night = false;

    WeatherIcon(Context c) {
        super(c);
    }

    void setCode(int code) {
        setCode(code, false);
    }

    void setCode(int code, boolean isNight) {
        night = isNight;
        int k;
        if (code == 0 || code == 1) k = CLEAR;
        else if (code == 2) k = PARTLY;
        else if (code == 3) k = CLOUD;
        else if (code == 45 || code == 48) k = FOG;
        else if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) k = RAIN;
        else if ((code >= 71 && code <= 77) || code == 85 || code == 86) k = SNOW;
        else if (code >= 95) k = STORM;
        else k = CLOUD;
        kind = k;
        invalidate();
    }

    private void cloud(Canvas cv, float s, float cx, float cy, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        cv.drawCircle(cx - 8 * s, cy + 2 * s, 8 * s, p);
        cv.drawCircle(cx + 1 * s, cy - 3 * s, 10 * s, p);
        cv.drawCircle(cx + 11 * s, cy + 3 * s, 7 * s, p);
        cv.drawRect(cx - 8 * s, cy + 2 * s, cx + 11 * s, cy + 10 * s, p);
    }

    /** Crescent moon (a circle with an offset circle cut out) plus optional stars. */
    private void moon(Canvas cv, float s, float cx, float cy, float r, boolean stars) {
        Path full = new Path();
        full.addCircle(cx, cy, r, Path.Direction.CW);
        Path cut = new Path();
        cut.addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW);
        full.op(cut, Path.Op.DIFFERENCE);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFF3E7B0);
        cv.drawPath(full, p);
        if (stars) {
            p.setColor(Color.WHITE);
            cv.drawCircle(cx + 11 * s, cy - 12 * s, 1.6f * s, p);
            cv.drawCircle(cx + 15 * s, cy - 3 * s, 1.3f * s, p);
            cv.drawCircle(cx + 7 * s, cy + 6 * s, 1.1f * s, p);
        }
    }

    private void sun(Canvas cv, float s, float cx, float cy, float r) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFC83D);
        cv.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * s);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float c = (float) Math.cos(a), sn = (float) Math.sin(a);
            cv.drawLine(cx + c * (r + 3 * s), cy + sn * (r + 3 * s),
                    cx + c * (r + 6.5f * s), cy + sn * (r + 6.5f * s), p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float s = Math.min(getWidth(), getHeight()) / 48f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        switch (kind) {
            case CLEAR:
                if (night) moon(cv, s, cx - 2 * s, cy, 13 * s, true);
                else sun(cv, s, cx, cy, 9 * s);
                break;
            case PARTLY:
                if (night) moon(cv, s, cx - 9 * s, cy - 8 * s, 8 * s, false);
                else sun(cv, s, cx - 9 * s, cy - 9 * s, 6 * s);
                cloud(cv, s, cx + 1 * s, cy + 5 * s, 0xFFD5DDF2);
                break;
            case CLOUD:
                cloud(cv, s, cx, cy + 1 * s, 0xFFC3CDE8);
                break;
            case FOG:
                cloud(cv, s, cx, cy - 6 * s, 0xFFB6C0DA);
                p.setColor(0xFF9AA6C8);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.5f * s);
                p.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 0; i < 3; i++) {
                    float y = cy + (8 + i * 5.5f) * s;
                    cv.drawLine(cx - 13 * s, y, cx + 13 * s, y, p);
                }
                break;
            case RAIN:
                cloud(cv, s, cx, cy - 6 * s, 0xFF9AA6C8);
                p.setColor(0xFF5AA9FF);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.5f * s);
                p.setStrokeCap(Paint.Cap.ROUND);
                for (int i = -1; i <= 1; i++) {
                    float x = cx + i * 8 * s;
                    cv.drawLine(x + 1.5f * s, cy + 9 * s, x - 1.5f * s, cy + 17 * s, p);
                }
                break;
            case SNOW:
                cloud(cv, s, cx, cy - 6 * s, 0xFF9AA6C8);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.WHITE);
                for (int i = -1; i <= 1; i++) {
                    cv.drawCircle(cx + i * 8 * s, cy + (i == 0 ? 17 : 11) * s, 2 * s, p);
                }
                break;
            case STORM:
                cloud(cv, s, cx, cy - 6 * s, 0xFF7C89B0);
                path.reset();
                path.moveTo(cx + 3 * s, cy + 3 * s);
                path.lineTo(cx - 5 * s, cy + 13 * s);
                path.lineTo(cx - 1 * s, cy + 13 * s);
                path.lineTo(cx - 4 * s, cy + 21 * s);
                path.lineTo(cx + 6 * s, cy + 10 * s);
                path.lineTo(cx + 2 * s, cy + 10 * s);
                path.close();
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFC83D);
                cv.drawPath(path, p);
                break;
            default:
                break;
        }
    }
}
