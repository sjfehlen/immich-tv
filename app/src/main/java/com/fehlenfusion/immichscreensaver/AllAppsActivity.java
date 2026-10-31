package com.fehlenfusion.immichscreensaver;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Grid of every launchable app over the blurred album backdrop, in the home screen's style.
 * OK opens; hold OK (or Menu) for a glass menu that adds it to or removes it from Home.
 */
public final class AllAppsActivity extends Activity {
    private static final int GAP_DP = 28;
    private static final int SIDE_DP = 56;

    private FrameLayout root;
    private Backdrop backdrop;
    private LinearLayout grid;
    private GlassMenu menu;
    private List<AppsRepo.App> apps = new ArrayList<>();
    private List<String> favorites = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        backdrop = new Backdrop(this, root);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Tiles.dp(this, SIDE_DP), Tiles.dp(this, 40), Tiles.dp(this, SIDE_DP), 0);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        TextView title = Tiles.label(this, "All apps", 32, Color.WHITE);
        title.setTypeface(Tiles.MEDIUM);
        page.addView(title);
        TextView hint = Tiles.label(this, "Apps marked \u2713 are on Home. Hold OK to add or remove one.",
                14, 0xB3FFFFFF);
        hint.setPadding(0, Tiles.dp(this, 4), 0, 0);
        page.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        // Room for the focused tile's zoom and shadow on every side.
        scroll.setPadding(Tiles.dp(this, 12), Tiles.dp(this, 28), Tiles.dp(this, 12), Tiles.dp(this, 40));
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        scroll.addView(grid);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        // The focused app's name brightens, like the tile under it.
        grid.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            lightLabel(was, false);
            lightLabel(now, true);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        backdrop.load();
        new Thread(() -> {
            final List<AppsRepo.App> loaded = AppsRepo.loadApps(getApplicationContext());
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                apps = loaded;
                favorites = AppsRepo.favorites(this, apps);
                build();
            });
        }, "all-apps").start();
    }

    @Override
    protected void onStop() {
        if (menu != null) menu.close(null);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        backdrop.release();
        super.onDestroy();
    }

    private void build() {
        String focusedPkg = getCurrentFocus() != null && getCurrentFocus().getTag() instanceof String
                ? (String) getCurrentFocus().getTag() : null;
        grid.removeAllViews();
        int w = Tiles.dp(this, Tiles.BANNER_W_DP), h = Tiles.dp(this, Tiles.BANNER_H_DP);
        int gap = Tiles.dp(this, GAP_DP);
        int avail = getResources().getDisplayMetrics().widthPixels - 2 * Tiles.dp(this, SIDE_DP + 12);
        int columns = Math.max(1, (avail + gap) / (w + gap));
        LinearLayout row = null;
        View restore = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
                rl.bottomMargin = Tiles.dp(this, 26);
                grid.addView(row, rl);
            }
            final AppsRepo.App a = apps.get(i);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);
            FrameLayout tile = (FrameLayout) Tiles.appTile(this, a);
            tile.setTag(a.pkg);
            tile.setOnClickListener(v -> open(a));
            tile.setOnLongClickListener(v -> {
                toggleFavorite(a, v);
                return true;
            });
            if (favorites.contains(a.pkg)) tile.addView(homeBadge(), badgeParams());
            cell.addView(tile, new LinearLayout.LayoutParams(w, h));
            TextView label = Tiles.label(this, a.label, 14, 0xB3FFFFFF);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(Tiles.dp(this, 2), Tiles.dp(this, 10), 0, 0);
            cell.addView(label, new LinearLayout.LayoutParams(w, -2));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(w, -2);
            cl.rightMargin = gap;
            row.addView(cell, cl);
            if (a.pkg.equals(focusedPkg)) restore = tile;
        }
        if (restore != null) restore.requestFocus();
        else if (grid.getChildCount() > 0 && getCurrentFocus() == null) {
            ((LinearLayout) ((LinearLayout) grid.getChildAt(0)).getChildAt(0)).getChildAt(0).requestFocus();
        }
    }

    /** Small frosted check in the tile's corner: this app is on the Home row. */
    private View homeBadge() {
        TextView b = new TextView(this);
        b.setText("\u2713");
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setTypeface(Tiles.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x99101318);
        b.setBackground(bg);
        return b;
    }

    private FrameLayout.LayoutParams badgeParams() {
        int d = Tiles.dp(this, 18), m = Tiles.dp(this, 5);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(d, d, Gravity.TOP | Gravity.END);
        lp.topMargin = m;
        lp.rightMargin = m;
        return lp;
    }

    private static void lightLabel(View tile, boolean lit) {
        if (tile == null || !(tile.getParent() instanceof LinearLayout)) return;
        LinearLayout cell = (LinearLayout) tile.getParent();
        if (cell.getChildCount() < 2 || !(cell.getChildAt(1) instanceof TextView)) return;
        ((TextView) cell.getChildAt(1)).setTextColor(lit ? Color.WHITE : 0xB3FFFFFF);
    }

    private void toggleFavorite(final AppsRepo.App a, View tile) {
        if (menu != null && menu.isOpen()) return;
        final boolean isFav = favorites.contains(a.pkg);
        menu = GlassMenu.show(root, backdrop.image, tile, a.label,
                new String[]{isFav ? "Remove from Home" : "Add to Home"}, which -> {
                    if (isFav) favorites.remove(a.pkg);
                    else favorites.add(a.pkg);
                    AppsRepo.saveFavorites(this, favorites);
                    build();
                });
    }

    private void open(AppsRepo.App a) {
        try {
            startActivity(a.launchIntent());
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) Tiles.lastNavKey = event.getKeyCode();
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof String) {
                for (AppsRepo.App a : apps) {
                    if (a.pkg.equals(f.getTag())) {
                        toggleFavorite(a, f);
                        return true;
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
