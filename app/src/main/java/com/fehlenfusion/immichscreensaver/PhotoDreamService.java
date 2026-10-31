package com.fehlenfusion.immichscreensaver;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Screensaver: shuffled photos from one Immich album with a cross-fade, plus a clock.
 *
 * Memory budget: at most two decoded bitmaps exist at any time. The next photo is only
 * fetched after the previous cross-fade finished and the outgoing bitmap was recycled.
 * All state below is touched on the main thread only; network/decode runs on one
 * low-priority background thread.
 */
public final class PhotoDreamService extends DreamService {
    private static final String TAG = "ImmichDream";
    private static final long SLIDE_MS = 20_000;
    private static final long FADE_MS = 1_500;
    private static final long RETRY_MIN_MS = 30_000;
    private static final long RETRY_MAX_MS = 5 * 60_000;
    private static final int MAX_W = 1920;
    private static final int MAX_H = 1080;
    /** RGB_565 halves memory (≈4 MB vs 8 MB at 1080p). Switch to ARGB_8888 if banding shows. */
    private static final Bitmap.Config BITMAP_CONFIG = Bitmap.Config.RGB_565;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private ImageView[] views;
    private TextView hint;
    private int front; // index into views of the visible photo

    private ExecutorService executor;
    private ImmichClient client;
    private String albumId;
    private Playlist playlist;
    private boolean running;
    private int session; // bumped on start/stop so late background results are discarded

    private Bitmap current;   // shown in views[front]
    private Bitmap pending;   // decoded, waiting for its turn
    private Bitmap outgoing;  // fading out
    private long shownAt;
    private long retryDelay = RETRY_MIN_MS;

    private final Runnable showPendingRunnable = this::showPending;
    private final Runnable loadAlbumRunnable = this::loadAlbum;
    private final Runnable prefetchRunnable = this::prefetchNext;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setContentView(buildLayout());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        start();
    }

    @Override
    public void onDreamingStopped() {
        stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stop();
        views = null;
        hint = null;
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- layout

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        views = new ImageView[2];
        for (int i = 0; i < 2; i++) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setAlpha(0f);
            root.addView(iv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            views[i] = iv;
        }
        front = 0;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int marginX = Math.round(dm.widthPixels * 0.05f);
        int marginY = Math.round(dm.heightPixels * 0.05f);

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("H:mm"); // honours the device's 24-hour setting
        styleText(time, 56);
        clock.addView(time);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMM d");
        date.setFormat24Hour("EEEE, MMM d");
        styleText(date, 24);
        clock.addView(date);

        hint = new TextView(this);
        styleText(hint, 18);
        hint.setVisibility(View.GONE);
        clock.addView(hint);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        lp.setMargins(marginX, marginY, marginX, marginY);
        root.addView(clock, lp);
        return root;
    }

    private static void styleText(TextView tv, int sp) {
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setShadowLayer(8f, 0f, 2f, 0xCC000000);
    }

    // ---------------------------------------------------------------- lifecycle

    private void start() {
        if (running) return;
        Config cfg = Config.load(this);
        if (!cfg.isComplete()) {
            showHint("Open the Immich Screensaver app on this TV to set it up.");
            return;
        }
        running = true;
        session++;
        retryDelay = RETRY_MIN_MS;
        client = new ImmichClient(cfg.serverUrl, cfg.apiKey);
        albumId = cfg.albumId;
        executor = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "immich-loader"));
        loadAlbum();
    }

    private void stop() {
        running = false;
        session++;
        handler.removeCallbacksAndMessages(null);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        client = null;
        playlist = null;
        if (views != null) {
            for (ImageView v : views) {
                v.animate().cancel();
                v.setImageDrawable(null); // detach before recycling
            }
        }
        recycle(current);
        recycle(pending);
        recycle(outgoing);
        current = pending = outgoing = null;
    }

    private boolean isLive(int s) {
        return running && s == session && views != null;
    }

    // ---------------------------------------------------------------- loading

    private void loadAlbum() {
        if (!running) return;
        final int s = session;
        final ImmichClient c = client;
        final String album = albumId;
        submit(() -> {
            List<String> ids = null;
            try {
                ids = c.fetchImageIds(album);
            } catch (Throwable t) {
                logFailure("album", t);
            }
            final List<String> result = ids;
            handler.post(() -> onAlbumLoaded(s, result));
        });
    }

    private void onAlbumLoaded(int s, List<String> ids) {
        if (!isLive(s)) return;
        if (ids == null || ids.isEmpty()) {
            if (ids != null) showHint("No photos in the selected album.");
            scheduleRetry(loadAlbumRunnable);
            return;
        }
        showHint(null);
        retryDelay = RETRY_MIN_MS;
        playlist = new Playlist(ids, random);
        prefetchNext();
    }

    private void prefetchNext() {
        if (!running || playlist == null || pending != null) return;
        final String id = playlist.next();
        if (id == null) return;
        final int s = session;
        final ImmichClient c = client;
        submit(() -> {
            Bitmap bmp = null;
            try {
                bmp = c.fetchPreview(id, MAX_W, MAX_H, BITMAP_CONFIG);
            } catch (Throwable t) {
                logFailure("photo", t);
            }
            final Bitmap result = bmp;
            handler.post(() -> onPhotoLoaded(s, result));
        });
    }

    private void onPhotoLoaded(int s, Bitmap bmp) {
        if (!isLive(s)) {
            recycle(bmp);
            return;
        }
        if (bmp == null) {
            scheduleRetry(prefetchRunnable);
            return;
        }
        retryDelay = RETRY_MIN_MS;
        pending = bmp;
        if (current == null) {
            showPending();
        } else {
            long wait = Math.max(0, shownAt + SLIDE_MS - SystemClock.uptimeMillis());
            handler.postDelayed(showPendingRunnable, wait);
        }
    }

    private void showPending() {
        if (!running || pending == null || views == null) return;
        final ImageView out = views[front];
        final ImageView in = views[1 - front];

        outgoing = current;
        current = pending;
        pending = null;
        front = 1 - front;
        shownAt = SystemClock.uptimeMillis();

        in.setImageBitmap(current);
        in.setAlpha(0f);
        out.animate().alpha(0f).setDuration(FADE_MS);
        in.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            if (!running) return;
            out.setImageDrawable(null);
            recycle(outgoing);
            outgoing = null;
            prefetchNext();
        });
    }

    // ---------------------------------------------------------------- helpers

    private void scheduleRetry(Runnable r) {
        handler.removeCallbacks(r);
        handler.postDelayed(r, retryDelay);
        retryDelay = Math.min(retryDelay * 2, RETRY_MAX_MS);
    }

    private void submit(Runnable task) {
        ExecutorService ex = executor;
        if (ex == null) return;
        try {
            ex.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Stopped concurrently; nothing to do.
        }
    }

    private void showHint(String text) {
        if (hint == null) return;
        hint.setText(text);
        hint.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private static void recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
    }

    /** Logs only the failure kind and HTTP status/exception class, never URLs or headers. */
    private static void logFailure(String what, Throwable t) {
        String detail = t instanceof ImmichClient.HttpStatusException
                ? "HTTP " + ((ImmichClient.HttpStatusException) t).code
                : t.getClass().getSimpleName();
        Log.w(TAG, what + " load failed: " + detail);
    }
}
