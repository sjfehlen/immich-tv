package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Random;

/**
 * Christmas overlay for the home screen: slow falling snow and a string of twinkling lights
 * along the top edge. Draws only while visible and attached, never takes focus or key events.
 */
final class ChristmasView extends View {
    private static final int FLAKES = 70;
    private static final int[] BULBS = {0xFFE53935, 0xFFFFC107, 0xFF43A047, 0xFF42A5F5};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wire = new Path();
    private final Random rnd = new Random();
    private final float[] x = new float[FLAKES];   // 0..1 of width
    private final float[] y = new float[FLAKES];   // 0..1 of height
    private final float[] r = new float[FLAKES];   // radius in dp
    private final float[] speed = new float[FLAKES]; // screens per second
    private final float[] drift = new float[FLAKES];
    private final float density;
    private long last;
    private boolean running;

    ChristmasView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setFocusable(false);
        setClickable(false);
        for (int i = 0; i < FLAKES; i++) {
            x[i] = rnd.nextFloat();
            y[i] = rnd.nextFloat();
            r[i] = 1.5f + rnd.nextFloat() * 3f;
            speed[i] = 0.03f + r[i] * 0.012f;
            drift[i] = (rnd.nextFloat() - 0.5f) * 0.02f;
        }
    }

    /** Whole days from the date of {@code now} to the next 25 December (0 on the day itself). */
    static int daysUntilChristmas(Calendar now) {
        int year = now.get(Calendar.YEAR);
        Calendar today = new GregorianCalendar(year, now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
        Calendar xmas = new GregorianCalendar(year, Calendar.DECEMBER, 25);
        if (today.after(xmas)) xmas = new GregorianCalendar(year + 1, Calendar.DECEMBER, 25);
        // Rounding absorbs the 23/25-hour days around daylight saving changes.
        return (int) Math.round((xmas.getTimeInMillis() - today.getTimeInMillis()) / 86400000.0);
    }

    static String countdownLabel(int days) {
        if (days == 0) return "It's Christmas Day!";
        return days == 1 ? "1 day until Christmas" : days + " days until Christmas";
    }

    void setActive(boolean on) {
        setVisibility(on ? VISIBLE : GONE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        last = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == VISIBLE && running) {
            last = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.1f, (now - last) / 1000f);
        last = now;
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            postInvalidateOnAnimation();
            return;
        }

        drawLights(canvas, w, now);

        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < FLAKES; i++) {
            y[i] += speed[i] * dt;
            x[i] += drift[i] * dt;
            if (y[i] > 1.02f) {
                y[i] = -0.02f;
                x[i] = rnd.nextFloat();
            }
            if (x[i] < -0.02f) x[i] += 1.04f;
            if (x[i] > 1.02f) x[i] -= 1.04f;
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(120 + (int) (r[i] * 22));
            canvas.drawCircle(x[i] * w, y[i] * h, r[i] * density, paint);
        }

        if (running && getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }

    /** A sagging wire from edge to edge with a bulb at each low point; bulbs twinkle. */
    private void drawLights(Canvas canvas, int w, long now) {
        float sag = 14f * density;
        float top = 6f * density;
        int spans = Math.max(4, Math.round(w / (96f * density)));
        float span = (float) w / spans;

        wire.reset();
        wire.moveTo(0, top);
        for (int i = 0; i < spans; i++) {
            float x0 = i * span;
            wire.quadTo(x0 + span / 2f, top + sag * 2f, x0 + span, top);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        paint.setColor(0xFF1B3A1F);
        canvas.drawPath(wire, paint);

        paint.setStyle(Paint.Style.FILL);
        int bulbsPerSpan = 2;
        for (int i = 0; i < spans * bulbsPerSpan; i++) {
            float t = (i + 0.5f) / bulbsPerSpan - (int) ((i + 0.5f) / bulbsPerSpan);
            int s = i / bulbsPerSpan;
            float bx = s * span + t * span;
            float by = top + 2f * sag * t * (1f - t) * 2f; // quad bezier midpoint height
            float tw = 0.65f + 0.35f * (float) Math.sin(now / 450.0 + i * 1.7);
            int color = BULBS[i % BULBS.length];
            paint.setColor(color);
            paint.setAlpha(60);
            canvas.drawCircle(bx, by + 6f * density, 11f * density * tw, paint); // glow
            paint.setColor(color);
            paint.setAlpha((int) (255 * tw));
            canvas.drawCircle(bx, by + 6f * density, 4.5f * density, paint);
        }
    }
}
