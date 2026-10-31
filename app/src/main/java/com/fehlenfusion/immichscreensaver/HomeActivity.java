package com.fehlenfusion.immichscreensaver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Home screen: clock, favourite apps, shortcuts, and Immich photos cross-fading behind.
 * The photo slideshow runs only while this screen is visible (onStart/onStop).
 */
public final class HomeActivity extends Activity {
    private static final int HOME_PHOTO_SECONDS = 30;

    private FrameLayout photoHost;
    private Slideshow slideshow;
    private LinearLayout favRow;
    private HorizontalScrollView favScroll;
    private TextView focusLabel;
    private View firstShortcut;
    private View[] chrome = new View[0]; // what fades out when idle
    // Jellyfin "Continue watching": hidden row under the apps, revealed with Down.
    private HorizontalScrollView cwScroll;
    private LinearLayout cwRow;
    private final List<android.graphics.Bitmap> cwBitmaps = new ArrayList<>();
    private int cwGen;
    private View lastAppFocus;
    private static final int CARD_W_DP = 176;
    private static final int CARD_H_DP = 99;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
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
        setContentView(buildLayout());
    }

    @Override
    protected void onStart() {
        super.onStart();
        startPhotos();
        loadApps();
    }

    @Override
    protected void onStop() {
        hideContinue(false);
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

    // ------------------------------------------------------------ idle: photos only

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
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && f != null && isInside(f, favRow)
                    && cwScroll.getVisibility() != View.VISIBLE) {
                lastAppFocus = f;
                showContinue();
                return true;
            }
            if ((code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_BACK)
                    && f != null && isInside(f, cwRow)) {
                hideContinue(true);
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_UP && code == KeyEvent.KEYCODE_BACK
                && cwScroll.getVisibility() == View.VISIBLE) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isInside(View v, View parent) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == parent) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ Jellyfin continue watching

    private void showContinue() {
        cwScroll.setVisibility(View.VISIBLE);
        clearContinue();
        final JellyfinConfig c = JellyfinConfig.load(this);
        if (!c.isConnected()) {
            infoCard("Connect Jellyfin", "Show what you're watching here",
                    v -> start(new Intent(this, JellyfinSetupActivity.class))).requestFocus();
            return;
        }
        infoCard("Continue watching", "Loading\u2026", null).requestFocus();
        final int gen = ++cwGen;
        final int px = Tiles.dp(this, CARD_W_DP);
        new Thread(() -> {
            JellyfinClient client = JellyfinClient.of(c);
            List<JellyfinParser.Card> cards = null;
            String error = null;
            try {
                cards = client.continueWatching(c.userId);
            } catch (ImmichClient.HttpStatusException e) {
                error = e.code == 401 ? "Signed out \u2013 reconnect in Settings \u203A Jellyfin" : "HTTP " + e.code;
            } catch (Exception e) {
                error = "Can't reach Jellyfin";
            }
            final List<JellyfinParser.Card> list = cards;
            final String err = error;
            runOnUiThread(() -> {
                if (gen != cwGen || isDestroyed()) return;
                showCards(list, err);
            });
            if (list == null) return;
            for (int i = 0; i < list.size() && gen == cwGen; i++) {
                android.graphics.Bitmap bmp = null;
                try {
                    bmp = client.image(list.get(i), px);
                } catch (Throwable ignored) {
                    // a card without art still works
                }
                final android.graphics.Bitmap b = bmp;
                final int index = i;
                runOnUiThread(() -> {
                    if (b == null) return;
                    if (gen != cwGen || isDestroyed() || index >= cwRow.getChildCount()) {
                        b.recycle();
                        return;
                    }
                    View img = cwRow.getChildAt(index).findViewWithTag("img");
                    if (img instanceof android.widget.ImageView) {
                        ((android.widget.ImageView) img).setImageBitmap(b);
                        cwBitmaps.add(b);
                    } else {
                        b.recycle();
                    }
                });
            }
        }, "jellyfin-cw").start();
    }

    private void showCards(List<JellyfinParser.Card> cards, String error) {
        clearContinue();
        if (cards == null) {
            infoCard("Jellyfin unavailable", error, v -> showContinue()).requestFocus();
            return;
        }
        if (cards.isEmpty()) {
            infoCard("Nothing in progress", "Start something in Jellyfin",
                    v -> launchPackage("org.jellyfin.androidtv")).requestFocus();
            return;
        }
        int w = Tiles.dp(this, CARD_W_DP), h = Tiles.dp(this, CARD_H_DP);
        for (final JellyfinParser.Card card : cards) {
            View v = Tiles.mediaCard(this, card.title, card.subtitle, card.progress);
            v.setOnClickListener(x -> openInJellyfin(card.id));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.rightMargin = Tiles.dp(this, 30);
            cwRow.addView(v, lp);
        }
        cwRow.getChildAt(0).requestFocus();
    }

    private View infoCard(String title, String subtitle, View.OnClickListener l) {
        View v = Tiles.mediaCard(this, title, subtitle, 0f);
        if (l != null) v.setOnClickListener(l);
        cwRow.addView(v, new LinearLayout.LayoutParams(Tiles.dp(this, CARD_W_DP), Tiles.dp(this, CARD_H_DP)));
        return v;
    }

    private void openInJellyfin(String itemId) {
        // Jellyfin's StartupActivity opens the item's details page for VIEW + item id.
        start(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(itemId))
                .setClassName("org.jellyfin.androidtv", "org.jellyfin.androidtv.ui.startup.StartupActivity"));
    }

    private void hideContinue(boolean refocus) {
        cwGen++;
        if (cwScroll == null) return;
        boolean wasVisible = cwScroll.getVisibility() == View.VISIBLE;
        cwScroll.setVisibility(View.GONE);
        clearContinue();
        if (refocus && wasVisible) {
            if (lastAppFocus != null && lastAppFocus.isAttachedToWindow()) lastAppFocus.requestFocus();
            else focusFirst();
        }
    }

    private void clearContinue() {
        for (int i = 0; i < cwRow.getChildCount(); i++) {
            View img = cwRow.getChildAt(i).findViewWithTag("img");
            if (img instanceof android.widget.ImageView) ((android.widget.ImageView) img).setImageDrawable(null);
        }
        cwRow.removeAllViews();
        for (android.graphics.Bitmap b : cwBitmaps) b.recycle();
        cwBitmaps.clear();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideChrome);
        if (idleSec > 0) handler.postDelayed(hideChrome, idleSec * 1000L);
    }

    private void hideChrome() {
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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to the first tile.
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

    // ------------------------------------------------------------ layout

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
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
        final View bottomScrim = bottom;

        int padX = Tiles.dp(this, 48);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padX, Tiles.dp(this, 36), padX, Tiles.dp(this, 26));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        styleClock(date, 17);
        content.addView(date);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        styleClock(time, 72);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        content.addView(time);

        View spacer = new View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        // The focused tile's zoom and outline are enough; no name label.
        focusLabel = Tiles.label(this, "", 15, Color.WHITE);
        focusLabel.setVisibility(View.GONE);

        favScroll = new HorizontalScrollView(this);
        favScroll.setHorizontalScrollBarEnabled(false);
        // Let the focused tile's zoom, lift and shadow draw outside the row's height.
        favScroll.setClipChildren(false);
        favScroll.setClipToPadding(false);
        int edge = Tiles.dp(this, 16);
        favScroll.setPadding(edge, Tiles.dp(this, 20), edge, Tiles.dp(this, 14)); // room for zoom, lift and shadow
        favRow = new LinearLayout(this);
        favRow.setOrientation(LinearLayout.HORIZONTAL);
        favRow.setGravity(Gravity.CENTER_VERTICAL);
        favRow.setClipChildren(false);
        favScroll.addView(favRow);
        LinearLayout.LayoutParams fsl = new LinearLayout.LayoutParams(-1, -2);
        fsl.leftMargin = -edge;
        fsl.rightMargin = -edge;
        content.addView(favScroll, fsl);

        cwScroll = new HorizontalScrollView(this);
        cwScroll.setHorizontalScrollBarEnabled(false);
        cwScroll.setClipChildren(false);
        cwScroll.setClipToPadding(false);
        cwScroll.setPadding(edge, Tiles.dp(this, 16), edge, Tiles.dp(this, 10));
        cwRow = new LinearLayout(this);
        cwRow.setOrientation(LinearLayout.HORIZONTAL);
        cwRow.setClipChildren(false);
        cwScroll.addView(cwRow);
        cwScroll.setVisibility(View.GONE);
        LinearLayout.LayoutParams cwl = new LinearLayout.LayoutParams(-1, -2);
        cwl.leftMargin = -edge;
        cwl.rightMargin = -edge;
        content.addView(cwScroll, cwl);
        // Apps glide up when the row below them appears.
        android.animation.LayoutTransition lt = new android.animation.LayoutTransition();
        lt.setDuration(220);
        content.setLayoutTransition(lt);

        // Small settings button: the last item in the app row (added in buildFavorites).
        firstShortcut = Tiles.settingsTile(this);
        firstShortcut.setContentDescription("Settings and more");
        firstShortcut.setOnClickListener(v -> showMenu());
        chrome = new View[]{bottomScrim, favScroll, cwScroll};
        return root;
    }

    /** The small ⚙ button's menu: what used to be the shortcut row. */
    private void showMenu() {
        String[] items = {"Play Store", "TV settings", "Photos & screensaver", "All apps"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setItems(items, (d, which) -> {
                    if (which == 0) launchPackage("com.android.vending");
                    else if (which == 1) start(new Intent(Settings.ACTION_SETTINGS));
                    else if (which == 2) start(new Intent(this, SettingsActivity.class));
                    else start(new Intent(this, AllAppsActivity.class));
                })
                .show();
    }

    private static void styleClock(TextView t, int sp) {
        t.setTextColor(Color.WHITE);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        t.setShadowLayer(10f, 0f, 2f, 0x99000000);
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
            final AppsRepo.App a = appsByPkg.get(pkg);
            if (a == null) continue; // uninstalled
            View tile = Tiles.appTile(this, a);
            tile.setTag(pkg);
            tile.setOnClickListener(v -> launch(a));
            tile.setOnLongClickListener(v -> {
                editFavorite(pkg, a.label);
                return true;
            });
            View.OnFocusChangeListener ring = tile.getOnFocusChangeListener();
            tile.setOnFocusChangeListener((v, has) -> {
                ring.onFocusChange(v, has);
                if (has) focusLabel.setText(a.label);
                else if (focusLabel.getText().toString().equals(a.label)) focusLabel.setText("");
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.rightMargin = Tiles.dp(this, 26); // 18% zoom needs ~10 dp each side
            favRow.addView(tile, lp);
            if (pkg.equals(focusedPkg)) restore = tile;
        }

        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(w, h);
        if (firstShortcut.getParent() != null) ((LinearLayout) firstShortcut.getParent()).removeView(firstShortcut);
        favRow.addView(firstShortcut, gl);

        if (restore != null) restore.requestFocus();
        else if (!hadFocus) focusFirst();
    }

    private void focusFirst() {
        if (favRow == null) return;
        favScroll.scrollTo(0, 0);
        View first = favRow.getChildCount() > 0 ? favRow.getChildAt(0) : firstShortcut;
        if (first != null) first.requestFocus();
    }

    private void editFavorite(final String pkg, String label) {
        String[] items = {"Move left", "Move right", "Remove from home"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(label)
                .setItems(items, (d, which) -> {
                    if (which == 0) favorites = AppsRepo.move(favorites, pkg, -1);
                    else if (which == 1) favorites = AppsRepo.move(favorites, pkg, 1);
                    else favorites.remove(pkg);
                    AppsRepo.saveFavorites(this, favorites);
                    buildFavorites();
                    View t = favRow.findViewWithTag(pkg);
                    if (t != null) t.requestFocus();
                })
                .show();
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
                AppsRepo.App a = appsByPkg.get(f.getTag());
                if (a != null) editFavorite(a.pkg, a.label);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
