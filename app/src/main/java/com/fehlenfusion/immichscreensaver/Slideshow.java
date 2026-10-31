package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
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
 * The photo slideshow shared by the screensaver and the settings preview.
 *
 * Memory budget: at most two full-size photo bitmaps (plus two ~10 KB blur thumbnails)
 * exist at once. The next photo is only fetched after the previous cross-fade finished
 * and the outgoing photo was recycled. All fields are touched on the main thread only;
 * network and decoding run on one low-priority background thread.
 */
final class Slideshow {
    private static final String TAG = "ImmichSlideshow";
    private static final long FADE_MS = 1_500;
    private static final long RETRY_MIN_MS = 30_000;
    private static final long RETRY_MAX_MS = 5 * 60_000;
    private static final int MAX_W = 1920;
    private static final int MAX_H = 1080;
    private static final int BLUR_WIDTH = 64;
    private static final int BLUR_RADIUS = 3;
    private static final float ZOOM_TO = 1.08f;
    /** RGB_565 halves memory (~4 MB vs 8 MB at 1080p). */
    private static final Bitmap.Config BITMAP_CONFIG = Bitmap.Config.RGB_565;

    /** One decoded photo plus its optional blur backdrop and caption. */
    private static final class Slide {
        Bitmap photo;
        Bitmap blur;
        String info;

        void recycle() {
            if (photo != null && !photo.isRecycled()) photo.recycle();
            if (blur != null && !blur.isRecycled()) blur.recycle();
            photo = blur = null;
        }
    }

    /** A blur backdrop plus the photo. Children are faded together without an offscreen layer. */
    private static final class Layer extends FrameLayout {
        final ImageView blur;
        final ImageView photo;

        Layer(Context ctx) {
            super(ctx);
            blur = new ImageView(ctx);
            blur.setScaleType(ImageView.ScaleType.CENTER_CROP);
            blur.setColorFilter(Color.argb(90, 0, 0, 0), PorterDuff.Mode.SRC_ATOP); // darken
            photo = new ImageView(ctx);
            addView(blur, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            addView(photo, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false; // avoid allocating a full-screen layer for every alpha frame
        }

        void clear() {
            photo.animate().cancel();
            photo.setScaleX(1f);
            photo.setScaleY(1f);
            photo.setImageDrawable(null);
            blur.setImageDrawable(null);
        }
    }

    private final Context ctx;
    private final Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final FrameLayout root;
    private final Layer[] layers = new Layer[2];
    private final LinearLayout clockBox;
    private final TextView hint;
    private final TextView infoView;
    private final int burnInShiftPx;
    private int front;

    private ExecutorService executor;
    private ImmichClient client;
    private List<String> albumIds;
    private Playlist playlist;
    private boolean running;
    private int session; // bumped on start/stop so late background results are discarded

    private Slide current;
    private Slide pending;
    private Slide outgoing;
    private long shownAt;
    private long retryDelay = RETRY_MIN_MS;

    private final Runnable showPendingRunnable = this::showPending;
    private final Runnable loadAlbumRunnable = this::loadAlbum;
    private final Runnable prefetchRunnable = this::prefetchNext;

    private final boolean embedded;

    Slideshow(Context ctx, Prefs prefs) {
        this(ctx, prefs, false);
    }

    /**
     * @param embedded true when used as the home-screen background: transparent until a photo
     *                 arrives (so the home gradient shows when offline) and no setup hints.
     */
    Slideshow(Context ctx, Prefs prefs, boolean embedded) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.embedded = embedded;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        burnInShiftPx = Math.round(12 * dm.density);

        root = new FrameLayout(ctx);
        if (!embedded) root.setBackgroundColor(Color.BLACK);
        for (int i = 0; i < 2; i++) {
            Layer l = new Layer(ctx);
            l.setAlpha(0f);
            l.blur.setVisibility(prefs.style == Prefs.STYLE_BLUR ? View.VISIBLE : View.GONE);
            l.photo.setScaleType(prefs.style == Prefs.STYLE_FILL
                    ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            root.addView(l, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            layers[i] = l;
        }

        // Soft gradient at the bottom so the clock and caption stay readable on bright photos.
        View scrim = new View(ctx);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x99000000, 0x40000000, 0x00000000}));
        boolean wantScrim = prefs.clock != Prefs.CLOCK_OFF || prefs.info;
        scrim.setVisibility(wantScrim ? View.VISIBLE : View.GONE);
        root.addView(scrim, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                Math.round(dm.heightPixels * 0.4f), Gravity.BOTTOM));

        int marginX = Math.round(dm.widthPixels * 0.05f);
        int marginY = Math.round(dm.heightPixels * 0.05f);

        clockBox = new LinearLayout(ctx);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(ctx);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("H:mm"); // honours the device's 24-hour setting
        style(time, 56);
        TextClock date = new TextClock(ctx);
        date.setFormat12Hour("EEEE, MMM d");
        date.setFormat24Hour("EEEE, MMM d");
        style(date, 24);
        hint = new TextView(ctx);
        style(hint, 18);
        hint.setVisibility(View.GONE);
        time.setVisibility(prefs.clock == Prefs.CLOCK_OFF ? View.GONE : View.VISIBLE);
        date.setVisibility(prefs.clock == Prefs.CLOCK_FULL ? View.VISIBLE : View.GONE);
        clockBox.addView(time);
        clockBox.addView(date);
        clockBox.addView(hint);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        clp.setMargins(marginX, marginY, marginX, marginY);
        root.addView(clockBox, clp);

        infoView = new TextView(ctx);
        style(infoView, 18);
        infoView.setAlpha(0.85f);
        infoView.setGravity(Gravity.END);
        infoView.setMaxWidth(Math.round(dm.widthPixels * 0.4f));
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        ilp.setMargins(marginX, marginY, marginX, marginY + Math.round(6 * dm.density));
        root.addView(infoView, ilp);
    }

    View getView() {
        return root;
    }

    /** Hides the photo date/place caption (e.g. while a Now playing card uses that corner). */
    void setCaptionHidden(boolean hidden) {
        infoView.setVisibility(hidden ? View.GONE : View.VISIBLE);
    }

    private static void style(TextView tv, int sp) {
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setShadowLayer(8f, 0f, 2f, 0xCC000000);
    }

    // ---------------------------------------------------------------- lifecycle

    void start() {
        if (running) return;
        Config cfg = Config.load(ctx);
        if (!cfg.isComplete()) {
            showHint("Open Immich Screensaver on this TV to set it up.");
            return;
        }
        running = true;
        session++;
        retryDelay = RETRY_MIN_MS;
        client = new ImmichClient(cfg.serverUrl, cfg.apiKey);
        albumIds = cfg.albumIds;
        executor = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "immich-loader"));
        loadAlbum();
    }

    /** Stops all work and frees every bitmap. Safe to call repeatedly. */
    void stop() {
        running = false;
        session++;
        handler.removeCallbacksAndMessages(null);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        client = null;
        playlist = null;
        for (Layer l : layers) {
            l.animate().cancel();
            l.setAlpha(0f);
            l.clear(); // detach before recycling
        }
        infoView.setText(null);
        if (current != null) current.recycle();
        if (pending != null) pending.recycle();
        if (outgoing != null) outgoing.recycle();
        current = pending = outgoing = null;
    }

    private boolean isLive(int s) {
        return running && s == session;
    }

    // ---------------------------------------------------------------- loading

    private void loadAlbum() {
        if (!running) return;
        final int s = session;
        final ImmichClient c = client;
        final List<String> albums = albumIds;
        submit(() -> {
            List<String> ids = null;
            try {
                ids = Playlist.ordered(c.fetchPhotos(albums), prefs.order);
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
            if (ids != null) showHint("No photos in the selected albums.");
            scheduleRetry(loadAlbumRunnable);
            return;
        }
        showHint(null);
        retryDelay = RETRY_MIN_MS;
        playlist = new Playlist(ids, random, prefs.order == Prefs.ORDER_SHUFFLE);
        prefetchNext();
    }

    private void prefetchNext() {
        if (!running || playlist == null || pending != null) return;
        final String id = playlist.next();
        if (id == null) return;
        final int s = session;
        final ImmichClient c = client;
        final boolean wantBlur = prefs.style == Prefs.STYLE_BLUR;
        final boolean wantInfo = prefs.info;
        submit(() -> {
            Slide slide = new Slide();
            try {
                slide.photo = c.fetchPreview(id, MAX_W, MAX_H, BITMAP_CONFIG);
                if (slide.photo != null && wantBlur) slide.blur = Blur.tiny(slide.photo, BLUR_WIDTH, BLUR_RADIUS);
            } catch (Throwable t) {
                logFailure("photo", t);
                slide.recycle();
            }
            if (slide.photo != null && wantInfo) {
                try {
                    slide.info = c.fetchPhotoInfo(id);
                } catch (Throwable t) {
                    // Caption is optional; show the photo without it.
                }
            }
            final Slide result = slide.photo != null ? slide : null;
            handler.post(() -> onPhotoLoaded(s, result));
        });
    }

    private void onPhotoLoaded(int s, Slide slide) {
        if (!isLive(s)) {
            if (slide != null) slide.recycle();
            return;
        }
        if (slide == null) {
            scheduleRetry(prefetchRunnable);
            return;
        }
        retryDelay = RETRY_MIN_MS;
        pending = slide;
        if (current == null) {
            showPending();
        } else {
            long wait = Math.max(0, shownAt + prefs.durationSec * 1000L - SystemClock.uptimeMillis());
            handler.postDelayed(showPendingRunnable, wait);
        }
    }

    private void showPending() {
        if (!running || pending == null) return;
        final Layer out = layers[front];
        final Layer in = layers[1 - front];

        outgoing = current;
        current = pending;
        pending = null;
        front = 1 - front;
        shownAt = SystemClock.uptimeMillis();

        in.photo.setImageBitmap(current.photo);
        in.blur.setImageBitmap(current.blur);
        in.setAlpha(0f);

        if (prefs.zoom) {
            // Slow "Ken Burns" push-in around a random point.
            in.photo.setPivotX(root.getWidth() * (0.3f + 0.4f * random.nextFloat()));
            in.photo.setPivotY(root.getHeight() * (0.3f + 0.4f * random.nextFloat()));
            in.photo.setScaleX(1f);
            in.photo.setScaleY(1f);
            in.photo.animate().scaleX(ZOOM_TO).scaleY(ZOOM_TO)
                    .setDuration(prefs.durationSec * 1000L + 2 * FADE_MS)
                    .setInterpolator(new LinearInterpolator());
        }

        // Burn-in protection: nudge the clock a few pixels with every photo.
        clockBox.setTranslationX(random.nextInt(2 * burnInShiftPx + 1) - burnInShiftPx);
        clockBox.setTranslationY(random.nextInt(2 * burnInShiftPx + 1) - burnInShiftPx);

        crossFadeInfo(current.info);
        out.animate().alpha(0f).setDuration(FADE_MS);
        in.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            if (!running) return;
            out.clear();
            if (outgoing != null) outgoing.recycle();
            outgoing = null;
            prefetchNext();
        });
    }

    private void crossFadeInfo(final String text) {
        if (!prefs.info) return;
        infoView.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction(() -> {
            infoView.setText(text);
            infoView.animate().alpha(0.85f).setDuration(FADE_MS / 2);
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
        if (embedded) return;
        hint.setText(text);
        hint.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    /** Logs only the failure kind and HTTP status/exception class, never URLs or headers. */
    private static void logFailure(String what, Throwable t) {
        String detail = t instanceof ImmichClient.HttpStatusException
                ? "HTTP " + ((ImmichClient.HttpStatusException) t).code
                : t.getClass().getSimpleName();
        Log.w(TAG, what + " load failed: " + detail);
    }
}
