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
        stopPhotos();
        super.onStop();
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
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x80000000, 0x00000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, Math.round(h * 0.62f), Gravity.BOTTOM));

        int padX = Tiles.dp(this, 48);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padX, Tiles.dp(this, 40), padX, Tiles.dp(this, 32));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        styleClock(date, 18);
        content.addView(date);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        styleClock(time, 84);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        content.addView(time);

        View spacer = new View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        favScroll = new HorizontalScrollView(this);
        favScroll.setHorizontalScrollBarEnabled(false);
        favScroll.setClipChildren(false);
        favScroll.setClipToPadding(false);
        favScroll.setPadding(Tiles.dp(this, 8), Tiles.dp(this, 10), Tiles.dp(this, 8), Tiles.dp(this, 6));
        favRow = new LinearLayout(this);
        favRow.setOrientation(LinearLayout.HORIZONTAL);
        favRow.setClipChildren(false);
        favScroll.addView(favRow);
        LinearLayout.LayoutParams fsl = new LinearLayout.LayoutParams(-1, -2);
        fsl.leftMargin = -Tiles.dp(this, 8);
        content.addView(favScroll, fsl);

        focusLabel = Tiles.label(this, "", 16, Color.WHITE);
        focusLabel.setPadding(0, Tiles.dp(this, 2), 0, Tiles.dp(this, 14));
        content.addView(focusLabel);

        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setOrientation(LinearLayout.HORIZONTAL);
        shortcuts.setClipChildren(false);
        firstShortcut = addShortcut(shortcuts, "▶", "Play Store", v -> launchPackage("com.android.vending"));
        addShortcut(shortcuts, "⚙", "TV settings", v -> start(new Intent(Settings.ACTION_SETTINGS)));
        addShortcut(shortcuts, "▣", "Photos", v -> start(new Intent(this, SettingsActivity.class)));
        addShortcut(shortcuts, "☰", "All apps", v -> start(new Intent(this, AllAppsActivity.class)));
        content.addView(shortcuts);
        return root;
    }

    private View addShortcut(LinearLayout row, String glyph, String text, View.OnClickListener l) {
        View p = Tiles.pill(this, glyph, text);
        p.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Tiles.dp(this, 136), Tiles.dp(this, 64));
        lp.rightMargin = Tiles.dp(this, 16);
        row.addView(p, lp);
        return p;
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
            lp.rightMargin = Tiles.dp(this, 20);
            favRow.addView(tile, lp);
            if (pkg.equals(focusedPkg)) restore = tile;
        }
        View add = Tiles.pill(this, "+", "Add app");
        add.setOnClickListener(v -> start(new Intent(this, AllAppsActivity.class)));
        favRow.addView(add, new LinearLayout.LayoutParams(w, h));

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
