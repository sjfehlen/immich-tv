package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.content.SharedPreferences;

/** Display settings chosen on the TV settings screen. */
final class Prefs {
    private static final String FILE = "display";

    static final int[] DURATIONS_SEC = {10, 20, 30, 60, 120, 300};

    static final int STYLE_BLUR = 0;  // fit, blurred copy of the photo behind
    static final int STYLE_FIT = 1;   // fit, black bars
    static final int STYLE_FILL = 2;  // crop to fill
    static final int ORDER_SHUFFLE = 0;
    static final int ORDER_NEWEST = 1;
    static final int ORDER_OLDEST = 2;
    static final int CLOCK_FULL = 0;
    static final int CLOCK_TIME = 1;
    static final int CLOCK_OFF = 2;

    int durationSec = 20;
    int style = STYLE_BLUR;
    int order = ORDER_SHUFFLE;
    boolean zoom = false;
    int clock = CLOCK_FULL;
    boolean info = true;
    boolean dim = false;
    boolean homePhotos = true;
    /** Seconds of no remote input before the home screen fades to photos only; 0 = never. */
    int homeIdleSec = 30;
    static final int[] IDLE_SEC = {15, 30, 60, 120, 0};

    static Prefs load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Prefs r = new Prefs();
        r.durationSec = p.getInt("duration", r.durationSec);
        r.style = clamp(p.getInt("style", r.style), 0, 2);
        r.order = clamp(p.getInt("order", r.order), 0, 2);
        r.zoom = p.getBoolean("zoom", r.zoom);
        r.clock = clamp(p.getInt("clock", r.clock), 0, 2);
        r.info = p.getBoolean("info", r.info);
        r.dim = p.getBoolean("dim", r.dim);
        r.homePhotos = p.getBoolean("homePhotos", r.homePhotos);
        r.homeIdleSec = Math.max(0, p.getInt("homeIdle", r.homeIdleSec));
        if (r.durationSec < 5) r.durationSec = 20;
        return r;
    }

    void save(Context ctx) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putInt("duration", durationSec)
                .putInt("style", style)
                .putInt("order", order)
                .putBoolean("zoom", zoom)
                .putInt("clock", clock)
                .putBoolean("info", info)
                .putBoolean("dim", dim)
                .putBoolean("homePhotos", homePhotos)
                .putInt("homeIdle", homeIdleSec)
                .apply();
    }

    void nextDuration() {
        for (int i = 0; i < DURATIONS_SEC.length; i++) {
            if (DURATIONS_SEC[i] == durationSec) {
                durationSec = DURATIONS_SEC[(i + 1) % DURATIONS_SEC.length];
                return;
            }
        }
        durationSec = 20;
    }

    void nextIdle() {
        for (int i = 0; i < IDLE_SEC.length; i++) {
            if (IDLE_SEC[i] == homeIdleSec) {
                homeIdleSec = IDLE_SEC[(i + 1) % IDLE_SEC.length];
                return;
            }
        }
        homeIdleSec = 30;
    }

    static String idleLabel(int sec) {
        return sec == 0 ? "Never" : "After " + durationLabel(sec);
    }

    static String durationLabel(int sec) {
        if (sec < 60) return sec + " seconds";
        int min = sec / 60;
        return min == 1 ? "1 minute" : min + " minutes";
    }

    static String styleLabel(int style) {
        switch (style) {
            case STYLE_FIT: return "Fit, black bars";
            case STYLE_FILL: return "Fill screen (crop)";
            default: return "Fit, blurred background";
        }
    }

    static String orderLabel(int order) {
        switch (order) {
            case ORDER_NEWEST: return "Newest first";
            case ORDER_OLDEST: return "Oldest first";
            default: return "Shuffle";
        }
    }

    static String clockLabel(int clock) {
        switch (clock) {
            case CLOCK_TIME: return "Time only";
            case CLOCK_OFF: return "Off";
            default: return "Time and date";
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
