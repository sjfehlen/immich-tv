package com.fehlenfusion.immichscreensaver;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Sends the remote's Home button to {@link HomeActivity} while Google TV's launcher stays installed
 * (it must stay enabled on the Chromecast: disabling it, or the setup app that re-enables it, breaks
 * phone casting after a reboot).
 *
 * - When a launcherx HOME activity comes to the front, open HomeActivity instead.
 * - A short Home press while launcherx is already in front (no window change) opens it too;
 *   a long press is left to Google TV (quick settings).
 * - Whenever the service binds (boot, app update), an invisible ProbeActivity triggers a window
 *   event in case launcherx was already on screen.
 * - HomeActivity is started like an app, not as a Home intent, so launcherx stays the home
 *   process and keeps handling the remote's YouTube/Netflix buttons.
 * - Safety: stops redirecting after 3 failed starts, and pauses if it fires in a tight loop.
 *
 * Reads only window class names and the Home key; every other key passes through untouched.
 * Never disables packages or changes the Home role.
 */
public final class HomeRedirectService extends AccessibilityService {
    private static final String TAG = "ImmichHome";
    private static final String STOCK_HOME_PKG = "com.google.android.apps.tv.launcherx";
    private static final long HOME_KEY_DELAY_MS = 250;
    private static final int MAX_FAILURES = 3;
    private static final int LOOP_LIMIT = 6;          // redirects ...
    private static final long LOOP_WINDOW_MS = 10_000; // ... within this window = loop
    private static final long LOOP_PAUSE_MS = 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> stockHome = new HashSet<>();
    private final long[] recent = new long[LOOP_LIMIT];
    private int recentIdx;
    private int failures;
    private long pausedUntil;
    private long lastOpen;
    private static final long DEDUPE_MS = 1_000; // window event + Home key for the same press

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        stockHome.clear();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(STOCK_HOME_PKG);
        int flags = Build.VERSION.SDK_INT >= 24 ? PackageManager.MATCH_DISABLED_COMPONENTS : 0;
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, flags)) {
            stockHome.add(ri.activityInfo.name);
        }
        failures = 0;
        probeAtBoot();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null || !STOCK_HOME_PKG.contentEquals(pkg)) return;
        if (stockHome.contains(cls.toString())) openHome();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && event.getAction() == KeyEvent.ACTION_UP
                && !event.isCanceled()
                && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
            // Let the system handle the key first; then make sure our home ends up in front.
            handler.postDelayed(this::openHome, HOME_KEY_DELAY_MS);
        }
        return false; // never swallow keys
    }

    private void openHome() {
        if (failures >= MAX_FAILURES) return;
        long now = SystemClock.elapsedRealtime();
        if (now < pausedUntil) return;
        if (now - lastOpen < DEDUPE_MS) return;
        lastOpen = now;
        long oldest = recent[recentIdx];
        recent[recentIdx] = now;
        recentIdx = (recentIdx + 1) % LOOP_LIMIT;
        if (oldest != 0 && now - oldest < LOOP_WINDOW_MS) {
            Log.w(TAG, "redirect loop detected; pausing");
            pausedUntil = now + LOOP_PAUSE_MS;
            return;
        }
        try {
            // CATEGORY_HOME without ACTION_MAIN: not a Home intent, so launcherx stays the home process.
            startActivity(new Intent(this, HomeActivity.class)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            failures = 0;
        } catch (RuntimeException e) {
            failures++;
            Log.w(TAG, "redirect failed (" + failures + "/" + MAX_FAILURES + "): " + e.getClass().getSimpleName());
        }
    }

    /**
     * Launcherx is often already on screen when this service binds: at boot, and after this app is
     * updated (the update closes HomeActivity, so the TV falls back to launcherx). Briefly open an
     * invisible activity so whatever is in front sends a window event; if it is launcherx's home,
     * onAccessibilityEvent redirects. Anything else in front is simply resumed again.
     */
    private void probeAtBoot() {
        try {
            startActivity(new Intent(this, ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
