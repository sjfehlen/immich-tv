package com.fehlenfusion.immichscreensaver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Grid of every launchable app. OK opens; long-press (or Menu) adds/removes it from the home row. */
public final class AllAppsActivity extends Activity {
    private static final int COLUMNS = 5;

    private LinearLayout grid;
    private TextView hint;
    private List<AppsRepo.App> apps = new ArrayList<>();
    private List<String> favorites = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B1020, 0xFF1A1630}));
        int pad = Tiles.dp(this, 48);
        root.setPadding(pad, Tiles.dp(this, 28), pad, 0);

        root.addView(Tiles.label(this, "All apps", 28, Color.WHITE));
        hint = Tiles.label(this, "OK opens an app. Hold OK (or press Menu) to add it to or remove it from Home.",
                15, 0xFFB8C1D9);
        hint.setPadding(0, 0, 0, Tiles.dp(this, 12));
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(Tiles.dp(this, 8), Tiles.dp(this, 8), Tiles.dp(this, 8), Tiles.dp(this, 32));
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
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

    private void build() {
        grid.removeAllViews();
        int w = Tiles.dp(this, Tiles.BANNER_W_DP), h = Tiles.dp(this, Tiles.BANNER_H_DP);
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
                rl.bottomMargin = Tiles.dp(this, 34);
                grid.addView(row, rl);
            }
            final AppsRepo.App a = apps.get(i);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);
            View tile = Tiles.appTile(this, a);
            tile.setTag(a.pkg);
            tile.setOnClickListener(v -> open(a));
            tile.setOnLongClickListener(v -> {
                toggleFavorite(a);
                return true;
            });
            cell.addView(tile, new LinearLayout.LayoutParams(w, h));
            TextView label = Tiles.label(this, (favorites.contains(a.pkg) ? "★ " : "") + a.label, 14, 0xFFE5E7EB);
            label.setSingleLine(true);
            label.setMaxWidth(w);
            label.setPadding(0, Tiles.dp(this, 6), 0, 0);
            cell.addView(label);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(w, -2);
            cl.rightMargin = Tiles.dp(this, 22);
            row.addView(cell, cl);
        }
        if (grid.getChildCount() > 0 && getCurrentFocus() == null) {
            ((LinearLayout) ((LinearLayout) grid.getChildAt(0)).getChildAt(0)).getChildAt(0).requestFocus();
        }
    }

    private void toggleFavorite(final AppsRepo.App a) {
        final boolean isFav = favorites.contains(a.pkg);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(a.label)
                .setItems(new String[]{isFav ? "Remove from Home" : "Add to Home"}, (d, which) -> {
                    if (isFav) favorites.remove(a.pkg);
                    else favorites.add(a.pkg);
                    AppsRepo.saveFavorites(this, favorites);
                    Toast.makeText(this, isFav ? "Removed from Home" : "Added to Home", Toast.LENGTH_SHORT).show();
                    View focused = grid.findViewWithTag(a.pkg);
                    build();
                    View again = grid.findViewWithTag(a.pkg);
                    if (again != null) again.requestFocus();
                    else if (focused != null) focused.requestFocus();
                })
                .show();
    }

    private void open(AppsRepo.App a) {
        try {
            startActivity(a.launchIntent());
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof String) {
                for (AppsRepo.App a : apps) {
                    if (a.pkg.equals(f.getTag())) {
                        toggleFavorite(a);
                        return true;
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
