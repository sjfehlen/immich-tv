package com.fehlenfusion.immichscreensaver;

import android.animation.LayoutTransition;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Home screen: clock, favourite apps along the bottom, and Immich photos cross-fading behind.
 * Down from the apps opens Jellyfin rows (continue watching, recently added movies and shows):
 * the apps slide up, the clock fades, and the screen scrolls like Apple TV. Up into the apps
 * or Back folds them away. The photo slideshow runs only while this screen is visible.
 */
public final class HomeActivity extends Activity {
    private static final int HOME_PHOTO_SECONDS = 30;
    private static final int CARD_W_DP = 176;
    private static final int CARD_H_DP = 99;

    private FrameLayout photoHost;
    private Slideshow slideshow;
    private View clockBox;
    private View dim;
    private ScrollView pager;
    private LinearLayout column;
    private LinearLayout favRow;
    private HorizontalScrollView favScroll;
    private View settingsTile;
    private View appsTile;
    private Frost clockFrost;
    private FrameLayout rootView;
    private GlassMenu menu;
    private LinearLayout media;
    private MediaRow[] rows = new MediaRow[0];
    private View lastAppFocus;
    private View[] chrome = new View[0]; // what fades out when idle
    private NowPlaying nowPlaying;
    private NowPlayingView npView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean chromeHidden;
    private int swallowKeyUp = -1;
    private int idleSec;
    private final Runnable hideChrome = this::hideChrome;

    private List<AppsRepo.App> apps = new ArrayList<>();
    private final Map<String, AppsRepo.App> appsByPkg = new HashMap<>();
    private List<String> favorites = new ArrayList<>();
    private int loadGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nowPlaying = new NowPlaying(this, this::onNowPlaying);
        setContentView(buildLayout());
    }

    @Override
    protected void onStart() {
        super.onStart();
        nowPlaying.start();
        startPhotos();
        loadApps();
    }

    @Override
    protected void onStop() {
        if (menu != null) menu.close(null);
        nowPlaying.stop();
        onNowPlaying(null);
        collapse(false);
        stopPhotos();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        idleSec = Prefs.load(this).homeIdleSec;
        showChrome(false);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(hideChrome);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: fold the rows and go back to the first tile.
        collapse(false);
        focusFirst();
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to.
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) stopPhotos();
    }

    // ------------------------------------------------------------ keys: idle wake, expand/collapse

    /** Any remote key wakes the apps; the waking key itself is swallowed so it can't open anything. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean volume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE;
        if (event.getAction() == KeyEvent.ACTION_UP && code == swallowKeyUp) {
            swallowKeyUp = -1;
            return true;
        }
        if (chromeHidden && !volume && event.getAction() == KeyEvent.ACTION_DOWN) {
            swallowKeyUp = code;
            showChrome(true);
            return true;
        }
        if (!volume) scheduleHide();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            Tiles.lastNavKey = code;
            View f = getCurrentFocus();
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && f != null && isInside(f, favRow) && !expanded()) {
                lastAppFocus = f;
                expand();
                return true;
            }
            if (expanded() && f != null && rows.length > 0
                    && ((code == KeyEvent.KEYCODE_DPAD_UP && isInside(f, rows[0].row))
                    || (code == KeyEvent.KEYCODE_BACK && isInside(f, media)))) {
                swallowKeyUp = code;
                collapse(true);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isInside(View v, View parent) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == parent) return true;
        }
        return false;
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideChrome);
        if (idleSec > 0) handler.postDelayed(hideChrome, idleSec * 1000L);
    }

    private void hideChrome() {
        if (expanded()) collapse(true); // idle with the rows open: fold them, then fade
        chromeHidden = true;
        for (View v : chrome) v.animate().alpha(0f).setDuration(800);
    }

    private void showChrome(boolean animate) {
        chromeHidden = false;
        for (View v : chrome) {
            v.animate().cancel();
            if (animate) v.animate().alpha(1f).setDuration(250);
            else v.setAlpha(1f);
        }
        scheduleHide();
    }

    // ------------------------------------------------------------ Jellyfin rows

    private boolean expanded() {
        return media != null && media.getVisibility() == View.VISIBLE;
    }

    // ------------------------------------------------------------ now playing

    private void onNowPlaying(NowPlaying.Info info) {
        if (npView == null) return;
        if (info == null) {
            boolean hadFocus = getCurrentFocus() != null && isInside(getCurrentFocus(), npView);
            npView.setVisibility(View.GONE);
            npView.clear();
            if (hadFocus) focusFirst();
            return;
        }
        npView.bind(info);
        npView.setVisibility(expanded() ? View.GONE : View.VISIBLE);
    }

    private void expand() {
        final JellyfinConfig c = JellyfinConfig.load(this);
        npView.setVisibility(View.GONE);
        media.setVisibility(View.VISIBLE);
        column.setGravity(Gravity.TOP);
        clockBox.animate().alpha(0f).setDuration(220);
        dim.setVisibility(View.VISIBLE);
        dim.animate().alpha(1f).setDuration(220);
        if (!c.isConnected()) {
            for (int i = 1; i < rows.length; i++) rows[i].setVisible(false);
            rows[0].setVisible(true);
            rows[0].clear();
            rows[0].info("Connect Jellyfin", "Show what you're watching here",
                    v -> start(new Intent(this, JellyfinSetupActivity.class))).requestFocus();
            return;
        }
        for (MediaRow r : rows) {
            r.setVisible(true);
            r.load(c);
        }
        View first = rows[0].row.getChildAt(0);
        if (first != null) first.requestFocus();
    }

    private void collapse(boolean refocus) {
        if (media == null) return;
        boolean was = expanded();
        for (MediaRow r : rows) r.clear();
        media.setVisibility(View.GONE);
        column.setGravity(Gravity.BOTTOM);
        pager.scrollTo(0, 0);
        clockBox.animate().cancel();
        clockBox.animate().alpha(1f).setDuration(220);
        dim.animate().cancel();
        dim.setAlpha(0f);
        dim.setVisibility(View.GONE);
        if (nowPlaying.info() != null) npView.setVisibility(View.VISIBLE);
        if (refocus && was) {
            if (lastAppFocus != null && lastAppFocus.isAttachedToWindow()) lastAppFocus.requestFocus();
            else focusFirst();
        }
    }

    private interface Loader {
        List<JellyfinParser.Card> load(JellyfinClient client, String userId) throws Exception;
    }

    /** One labelled, horizontally scrolling row of Jellyfin cards. Main-thread state only. */
    private final class MediaRow {
        final LinearLayout view;
        final LinearLayout row;
        final HorizontalScrollView scroll;
        final Loader loader;
        final String emptyTitle;
        final String emptySubtitle;
        /** Jellyfin collection type for the "View all" cards ("movies", "tvshows"), or null. */
        final String libraryType;
        final List<Bitmap> bitmaps = new ArrayList<>();
        int gen;

        MediaRow(String title, String emptyTitle, String emptySubtitle, String libraryType, Loader loader) {
            this.loader = loader;
            this.libraryType = libraryType;
            this.emptyTitle = emptyTitle;
            this.emptySubtitle = emptySubtitle;
            view = new LinearLayout(HomeActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setClipChildren(false);
            TextView header = Tiles.label(HomeActivity.this, title, 16, 0xE6FFFFFF);
            header.setTypeface(Tiles.MEDIUM);
            header.setPadding(0, Tiles.dp(HomeActivity.this, 6), 0, 0);
            view.addView(header);
            scroll = new HorizontalScrollView(HomeActivity.this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipChildren(false);
            scroll.setClipToPadding(false);
            int edge = Tiles.dp(HomeActivity.this, 16);
            scroll.setPadding(edge, Tiles.dp(HomeActivity.this, 14), edge, Tiles.dp(HomeActivity.this, 14));
            row = new LinearLayout(HomeActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            scroll.addView(row);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
            sl.leftMargin = -edge;
            sl.rightMargin = -edge;
            view.addView(scroll, sl);
        }

        void setVisible(boolean v) {
            view.setVisibility(v ? View.VISIBLE : View.GONE);
        }

        void load(final JellyfinConfig c) {
            clear();
            info(null, "Loading…", null);
            final int my = ++gen;
            final int px = Tiles.dp(HomeActivity.this, CARD_W_DP);
            new Thread(() -> {
                JellyfinClient client = JellyfinClient.of(c);
                List<JellyfinParser.Card> cards = null;
                String error = null;
                try {
                    cards = loader.load(client, c.userId);
                } catch (ImmichClient.HttpStatusException e) {
                    error = e.code == 401 ? "Signed out – reconnect in Settings › Jellyfin" : "HTTP " + e.code;
                } catch (Exception e) {
                    error = "Can't reach Jellyfin";
                }
                final List<JellyfinParser.Card> list = cards;
                final String err = error;
                runOnUiThread(() -> {
                    if (my == gen && !isDestroyed()) show(list, err, c);
                });
                if (list == null) return;
                if (libraryType != null) {
                    try {
                        final List<JellyfinParser.Library> libs = client.libraries(c.userId, libraryType);
                        runOnUiThread(() -> {
                            if (my == gen && !isDestroyed()) addViewAll(libs);
                        });
                    } catch (Exception ignored) {
                        // no "View all" card; the row still works
                    }
                }
                for (int i = 0; i < list.size() && my == gen; i++) {
                    Bitmap bmp = null;
                    try {
                        bmp = client.image(list.get(i), px);
                    } catch (Throwable ignored) {
                        // a card without art still works
                    }
                    final Bitmap b = bmp;
                    final int index = i;
                    runOnUiThread(() -> {
                        if (b == null) return;
                        if (my != gen || isDestroyed() || index >= row.getChildCount()) {
                            b.recycle();
                            return;
                        }
                        View img = row.getChildAt(index).findViewWithTag("img");
                        if (img instanceof ImageView) {
                            ((ImageView) img).setImageBitmap(b);
                            bitmaps.add(b);
                        } else {
                            b.recycle();
                        }
                    });
                }
            }, "jellyfin-row").start();
        }

        /**
         * Replaces the "Loading…" card. New cards are added before the old ones are removed and
         * focus moves first, so removing a focused card never pushes focus into another row.
         * Does not bump gen: the image loader for this list is still current.
         */
        private void show(List<JellyfinParser.Card> cards, String error, JellyfinConfig c) {
            boolean hadFocus = getCurrentFocus() != null && isInside(getCurrentFocus(), row);
            int old = row.getChildCount();
            if (cards == null) {
                info("Jellyfin unavailable", error, v -> load(c));
            } else if (cards.isEmpty()) {
                info(emptyTitle, emptySubtitle, v -> launchPackage("org.jellyfin.androidtv"));
            } else {
                int w = Tiles.dp(HomeActivity.this, CARD_W_DP), h = Tiles.dp(HomeActivity.this, CARD_H_DP);
                for (final JellyfinParser.Card card : cards) {
                    View v = Tiles.mediaCard(HomeActivity.this, card.title, card.subtitle, card.progress);
                    v.setOnClickListener(x -> openInJellyfin(card.id));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
                    lp.rightMargin = Tiles.dp(HomeActivity.this, 30);
                    row.addView(v, lp);
                }
            }
            if (hadFocus && row.getChildCount() > old) row.getChildAt(old).requestFocus();
            row.removeViews(0, old); // the Loading card(s); they hold no images
        }

        /** Appended after the cards (image indexes above stay valid). */
        void addViewAll(List<JellyfinParser.Library> libs) {
            int w = Tiles.dp(HomeActivity.this, CARD_W_DP), h = Tiles.dp(HomeActivity.this, CARD_H_DP);
            for (final JellyfinParser.Library lib : libs) {
                View v = Tiles.viewAllTile(HomeActivity.this, lib.name);
                v.setOnClickListener(x -> openLibraryInJellyfin(lib.id));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
                lp.rightMargin = Tiles.dp(HomeActivity.this, 30);
                row.addView(v, lp);
            }
        }

        View info(String title, String subtitle, View.OnClickListener l) {
            View v = Tiles.mediaCard(HomeActivity.this, title == null ? "" : title, subtitle, 0f);
            if (l != null) v.setOnClickListener(l);
            row.addView(v, new LinearLayout.LayoutParams(
                    Tiles.dp(HomeActivity.this, CARD_W_DP), Tiles.dp(HomeActivity.this, CARD_H_DP)));
            return v;
        }

        void clear() {
            gen++;
            for (int i = 0; i < row.getChildCount(); i++) {
                View img = row.getChildAt(i).findViewWithTag("img");
                if (img instanceof ImageView) ((ImageView) img).setImageDrawable(null);
            }
            row.removeAllViews();
            scroll.scrollTo(0, 0);
            for (Bitmap b : bitmaps) b.recycle();
            bitmaps.clear();
        }
    }

    private void openLibraryInJellyfin(String libraryId) {
        // ItemIsUserView makes Jellyfin open the library's browse screen instead of item details.
        start(new Intent(Intent.ACTION_VIEW, Uri.parse(libraryId))
                .putExtra("ItemIsUserView", true)
                .setClassName("org.jellyfin.androidtv", "org.jellyfin.androidtv.ui.startup.StartupActivity"));
    }

    private void openInJellyfin(String itemId) {
        // Jellyfin's StartupActivity opens the item's details page for VIEW + item id.
        start(new Intent(Intent.ACTION_VIEW, Uri.parse(itemId))
                .setClassName("org.jellyfin.androidtv", "org.jellyfin.androidtv.ui.startup.StartupActivity"));
    }

    // ------------------------------------------------------------ layout

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2B3A67, 0xFF5B3A6E, 0xFF1C1B2E}));

        photoHost = new FrameLayout(this);
        root.addView(photoHost, new FrameLayout.LayoutParams(-1, -1));

        int h = getResources().getDisplayMetrics().heightPixels;
        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x00000000}));
        root.addView(top, new FrameLayout.LayoutParams(-1, Math.round(h * 0.42f), Gravity.TOP));
        // Light gradient only behind the app row, so most of the photo stays clear.
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x40000000, 0x00000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, Math.round(h * 0.33f), Gravity.BOTTOM));
        // Darkens the photo while the Jellyfin rows are open, so the cards stay readable.
        dim = new View(this);
        dim.setBackgroundColor(0x99000000);
        dim.setAlpha(0f);
        dim.setVisibility(View.GONE);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        int padX = Tiles.dp(this, 48);
        LinearLayout clock = new LinearLayout(this) {
            /** Children wrap their content, then all stretch to the widest one. */
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, heightSpec);
                int max = 0;
                for (int i = 0; i < getChildCount(); i++) {
                    View ch = getChildAt(i);
                    if (ch.getVisibility() != GONE) max = Math.max(max, ch.getMeasuredWidth());
                }
                for (int i = 0; i < getChildCount(); i++) {
                    View ch = getChildAt(i);
                    if (ch.getVisibility() == GONE || ch.getMeasuredWidth() == max) continue;
                    ch.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(ch.getMeasuredHeight(), MeasureSpec.EXACTLY));
                }
            }
        };
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setPadding(padX, Tiles.dp(this, 36), padX, 0);
        // The date and time sit on the same frosted panel as the now playing card, and both
        // stretch to the wider of the two so they stack as one block.
        LinearLayout face = new LinearLayout(this);
        face.setOrientation(LinearLayout.VERTICAL);
        face.setPadding(Tiles.dp(this, 16), Tiles.dp(this, 10), Tiles.dp(this, 20), Tiles.dp(this, 12));
        Tiles.rounded(face, Tiles.dp(this, Tiles.RADIUS_DP));
        clockFrost = new Frost(face, Tiles.dp(this, Tiles.RADIUS_DP));
        clockFrost.setSource(photoHost);
        // Same type system as the cards: medium for the date line, regular numerals (light looked
        // thin next to the card's medium titles).
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        styleClock(date, 16, 0xD9FFFFFF);
        date.setTypeface(Tiles.MEDIUM);
        face.addView(date);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        styleClock(time, 60, Color.WHITE);
        time.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        time.setLetterSpacing(-0.02f);
        time.setIncludeFontPadding(false);
        face.addView(time);
        clock.addView(face, new LinearLayout.LayoutParams(-2, -2));
        clockBox = clock;
        root.addView(clock, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        // Now playing (any media app): directly below the clock, part of the same block.
        // Up from the apps reaches it; it fades with the clock when the Jellyfin rows open.
        npView = new NowPlayingView(this, true, nowPlaying);
        npView.setVisibility(View.GONE);
        LinearLayout.LayoutParams npl = new LinearLayout.LayoutParams(-2, -2);
        npl.topMargin = Tiles.dp(this, 10);
        clock.addView(npView, npl);
        npView.setBackdropSource(photoHost);

        // Everything below the clock scrolls vertically: apps, then the Jellyfin rows.
        pager = new ScrollView(this);
        pager.setFillViewport(true);
        pager.setVerticalScrollBarEnabled(false);
        pager.setClipChildren(false);
        pager.setClipToPadding(false);
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.BOTTOM);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(padX, Tiles.dp(this, 24), padX, Tiles.dp(this, 26));
        LayoutTransition lt = new LayoutTransition();
        lt.enableTransitionType(LayoutTransition.CHANGING);
        lt.setDuration(240);
        column.setLayoutTransition(lt);
        pager.addView(column, new FrameLayout.LayoutParams(-1, -1));

        favScroll = new HorizontalScrollView(this);
        favScroll.setHorizontalScrollBarEnabled(false);
        // Let the focused tile's zoom, lift and shadow draw outside the row's height.
        favScroll.setClipChildren(false);
        favScroll.setClipToPadding(false);
        int edge = Tiles.dp(this, 16);
        favScroll.setPadding(edge, Tiles.dp(this, 20), edge, Tiles.dp(this, 14));
        favRow = new LinearLayout(this);
        favRow.setOrientation(LinearLayout.HORIZONTAL);
        favRow.setGravity(Gravity.CENTER_VERTICAL);
        favRow.setClipChildren(false);
        favScroll.addView(favRow);
        LinearLayout.LayoutParams fsl = new LinearLayout.LayoutParams(-1, -2);
        fsl.leftMargin = -edge;
        fsl.rightMargin = -edge;
        column.addView(favScroll, fsl);

        media = new LinearLayout(this);
        media.setOrientation(LinearLayout.VERTICAL);
        media.setClipChildren(false);
        media.setVisibility(View.GONE);
        rows = new MediaRow[]{
                new MediaRow("Continue watching", "Nothing in progress", "Start something in Jellyfin", null,
                        (cl, user) -> cl.continueWatching(user)),
                new MediaRow("Recently added movies", "No new movies", "Open Jellyfin", "movies",
                        (cl, user) -> cl.latest(user, "Movie")),
                new MediaRow("Recently added shows", "No new episodes", "Open Jellyfin", "tvshows",
                        (cl, user) -> cl.latest(user, "Episode")),
        };
        for (MediaRow r : rows) media.addView(r.view);
        column.addView(media, new LinearLayout.LayoutParams(-1, -2));

        // Settings: a muted tile at the end of the app row (added in buildFavorites).
        settingsTile = Tiles.settingsTile(this);
        settingsTile.setOnClickListener(v -> showMenu());
        appsTile = Tiles.appsTile(this);
        appsTile.setOnClickListener(v -> start(new Intent(this, AllAppsActivity.class)));
        chrome = new View[]{bottom, pager}; // the clock stays when idle
        return root;
    }

    /** The settings tile's menu: a frosted panel floating above the tile. */
    private void showMenu() {
        String[] items = {"Settings", "TV settings", "Play Store"};
        showGlassMenu(settingsTile, null, items, which -> {
            if (which == 0) start(new Intent(this, SettingsActivity.class));
            else if (which == 1) start(new Intent(Settings.ACTION_SETTINGS));
            else launchPackage("com.android.vending");
        });
    }

    private void showGlassMenu(View anchor, String title, String[] items, GlassMenu.OnPick onPick) {
        if (menu != null && menu.isOpen()) return;
        menu = GlassMenu.show(rootView, photoHost, anchor, title, items, onPick);
    }

    private static void styleClock(TextView t, int sp, int color) {
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        Tiles.shadow(t);
    }

    // ------------------------------------------------------------ photos

    private void startPhotos() {
        Prefs base = Prefs.load(this);
        if (!base.homePhotos) {
            stopPhotos();
            return;
        }
        if (slideshow == null) {
            Prefs p = Prefs.load(this);
            p.durationSec = HOME_PHOTO_SECONDS;
            p.zoom = false;
            p.info = false;
            p.clock = Prefs.CLOCK_OFF;
            slideshow = new Slideshow(this, p, true);
            slideshow.setOnPhotoShown(() -> {
                npView.refreshBackdrop();
                clockFrost.refresh();
            });
            photoHost.addView(slideshow.getView(), new FrameLayout.LayoutParams(-1, -1));
        }
        slideshow.start();
    }

    private void stopPhotos() {
        if (slideshow == null) return;
        slideshow.stop();
        photoHost.removeAllViews();
        slideshow = null; // rebuilt on next start so setting changes apply
    }

    // ------------------------------------------------------------ apps

    private void loadApps() {
        final int gen = ++loadGeneration;
        final Context app = getApplicationContext();
        new Thread(() -> {
            final List<AppsRepo.App> loaded = AppsRepo.loadApps(app);
            runOnUiThread(() -> {
                if (gen != loadGeneration || isDestroyed()) return;
                apps = loaded;
                appsByPkg.clear();
                for (AppsRepo.App a : apps) appsByPkg.put(a.pkg, a);
                favorites = AppsRepo.favorites(this, apps);
                if (HaScripts.addOnce(this, favorites)) AppsRepo.saveFavorites(this, favorites);
                buildFavorites();
            });
        }, "home-apps").start();
    }

    private void buildFavorites() {
        View focused = getCurrentFocus();
        String focusedPkg = focused != null && focused.getTag() instanceof String ? (String) focused.getTag() : null;
        boolean hadFocus = focused != null;

        favRow.removeAllViews();
        int w = Tiles.dp(this, Tiles.BANNER_W_DP), h = Tiles.dp(this, Tiles.BANNER_H_DP);
        View restore = null;
        for (final String pkg : favorites) {
            final View tile;
            final String label;
            final HaScripts.Script script = HaScripts.find(pkg);
            if (script != null) {
                tile = Tiles.scriptTile(this, script);
                tile.setOnClickListener(v -> runScript(script));
                label = script.label;
            } else {
                final AppsRepo.App a = appsByPkg.get(pkg);
                if (a == null) continue; // uninstalled
                tile = Tiles.appTile(this, a);
                tile.setOnClickListener(v -> launch(a));
                label = a.label;
            }
            tile.setTag(pkg);
            tile.setOnLongClickListener(v -> {
                editFavorite(pkg, label);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.rightMargin = Tiles.dp(this, 26); // 18% zoom needs ~10 dp each side
            favRow.addView(tile, lp);
            if (pkg.equals(focusedPkg)) restore = tile;
        }
        // All apps, then Settings: muted tiles that close the row.
        for (View t : new View[]{appsTile, settingsTile}) {
            if (t.getParent() != null) ((LinearLayout) t.getParent()).removeView(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            if (t == appsTile) lp.rightMargin = Tiles.dp(this, 26);
            favRow.addView(t, lp);
        }

        if (restore != null) restore.requestFocus();
        else if (!hadFocus) focusFirst();
    }

    private void focusFirst() {
        if (favRow == null) return;
        favScroll.scrollTo(0, 0);
        View first = favRow.getChildCount() > 0 ? favRow.getChildAt(0) : settingsTile;
        if (first != null) first.requestFocus();
    }

    private void editFavorite(final String pkg, String label) {
        String[] items = {"Move left", "Move right", "Remove from home"};
        View anchor = favRow.findViewWithTag(pkg);
        showGlassMenu(anchor != null ? anchor : settingsTile, label, items, which -> {
            if (which == 0) favorites = AppsRepo.move(favorites, pkg, -1);
            else if (which == 1) favorites = AppsRepo.move(favorites, pkg, 1);
            else favorites.remove(pkg);
            AppsRepo.saveFavorites(this, favorites);
            buildFavorites();
            View t = favRow.findViewWithTag(pkg);
            if (t != null) t.requestFocus();
            else focusFirst();
        });
    }

    /** Runs a Home Assistant script; the first time, opens the phone setup for the address and token. */
    private void runScript(HaScripts.Script s) {
        final HaConfig cfg = HaConfig.load(this);
        if (!cfg.isConnected()) {
            Toast.makeText(this, "Connect Home Assistant first", Toast.LENGTH_SHORT).show();
            openHaSetup();
            return;
        }
        Toast.makeText(this, s.label + "\u2026", Toast.LENGTH_SHORT).show();
        final Context app = getApplicationContext();
        new Thread(() -> {
            String error = null;
            try {
                HaClient.of(cfg).runScript(s.entityId);
            } catch (ImmichClient.HttpStatusException e) {
                error = e.code == 401 ? "Home Assistant rejected the token" : "Home Assistant error " + e.code;
            } catch (Exception e) {
                error = "Couldn't reach Home Assistant";
            }
            final String msg = error;
            if (msg != null) runOnUiThread(() -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
        }, "ha-script").start();
    }

    private void openHaSetup() {
        start(new Intent(this, SetupActivity.class).putExtra(SetupActivity.EXTRA_HOME_ASSISTANT, true));
    }

    private void launch(AppsRepo.App a) {
        start(a.launchIntent());
    }

    private void launchPackage(String pkg) {
        AppsRepo.App a = appsByPkg.get(pkg);
        if (a != null) launch(a);
        else Toast.makeText(this, "Not installed", Toast.LENGTH_SHORT).show();
    }

    private void start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Menu on a tile = edit, for remotes where long-press is awkward.
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof String) {
                String key = (String) f.getTag();
                HaScripts.Script s = HaScripts.find(key);
                AppsRepo.App a = appsByPkg.get(key);
                if (s != null) editFavorite(key, s.label);
                else if (a != null) editFavorite(a.pkg, a.label);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
