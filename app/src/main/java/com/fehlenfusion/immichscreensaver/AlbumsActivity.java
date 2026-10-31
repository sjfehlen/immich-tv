package com.fehlenfusion.immichscreensaver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pick albums with the remote. Uses the saved server and key; each OK press toggles an
 * album and saves immediately. At least one album must stay selected.
 */
public final class AlbumsActivity extends Activity {
    private LinearLayout list;
    private TextView status;
    private final List<AssetParser.Album> albums = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final List<TextView> checks = new ArrayList<>();
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B1020, 0xFF1A1630}));
        root.setPadding(dp(56), dp(26), dp(56), dp(16));

        root.addView(text("Albums", 28, Color.WHITE));
        status = text("Loading albums…", 15, 0xFFB8C1D9);
        status.setPadding(0, 0, 0, dp(10));
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                Math.round(getResources().getDisplayMetrics().widthPixels * 0.62f), 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (albums.isEmpty()) load();
    }

    private void load() {
        if (loading) return;
        final Config c = Config.load(this);
        if (c.serverUrl == null || c.apiKey == null) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        loading = true;
        status.setText("Loading albums…");
        new Thread(() -> {
            List<AssetParser.Album> result = null;
            String error = null;
            try {
                result = new ImmichClient(c.serverUrl, c.apiKey).listAlbums();
            } catch (ImmichClient.HttpStatusException e) {
                error = "The server answered with HTTP " + e.code + ".";
            } catch (Throwable t) {
                error = "Couldn't reach the server (" + t.getClass().getSimpleName() + ").";
            }
            final List<AssetParser.Album> r = result;
            final String err = error;
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || isDestroyed()) return;
                if (r == null) showError(err);
                else show(r, c);
            });
        }, "immich-albums").start();
    }

    private void show(List<AssetParser.Album> result, Config c) {
        albums.clear();
        albums.addAll(result);
        Collections.sort(albums, (a, b) -> a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT)));
        selected.clear();
        for (AssetParser.Album a : albums) if (c.albumIds.contains(a.id)) selected.add(a.id);

        list.removeAllViews();
        checks.clear();
        if (albums.isEmpty()) {
            showError("This API key can't see any albums.");
            return;
        }
        updateStatus();
        View firstSelected = null;
        for (int i = 0; i < albums.size(); i++) {
            final int index = i;
            AssetParser.Album a = albums.get(i);
            View row = row(a.name, a.assetCount + (a.assetCount == 1 ? " item" : " items"),
                    v -> toggle(index));
            if (firstSelected == null && selected.contains(a.id)) firstSelected = row;
            if (i == 0 && firstSelected == null) firstSelected = row;
        }
        refreshChecks();
        if (firstSelected != null) firstSelected.requestFocus();
    }

    private void showError(String message) {
        list.removeAllViews();
        checks.clear();
        status.setText(message);
        row("Try again", "", v -> load()).requestFocus();
        row("Change server or API key", "", v -> startActivity(new Intent(this, SetupActivity.class)));
    }

    private void toggle(int index) {
        AssetParser.Album a = albums.get(index);
        if (selected.contains(a.id)) {
            if (selected.size() == 1) {
                status.setText("Keep at least one album selected.");
                return;
            }
            selected.remove(a.id);
        } else {
            selected.add(a.id);
        }
        save();
        refreshChecks();
        updateStatus();
    }

    private void save() {
        Config c = Config.load(this);
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (AssetParser.Album a : albums) {
            if (selected.contains(a.id)) {
                ids.add(a.id);
                names.add(a.name);
            }
        }
        new Config(c.serverUrl, c.apiKey, ids, Config.summarize(names)).save(this);
    }

    private void updateStatus() {
        int n = selected.size();
        status.setText((n == 1 ? "1 album selected" : n + " albums selected")
                + ". Press OK to tick or untick. Changes save automatically.");
    }

    private void refreshChecks() {
        for (int i = 0; i < checks.size() && i < albums.size(); i++) {
            checks.get(i).setText(selected.contains(albums.get(i).id) ? "✓" : "");
        }
    }

    // ------------------------------------------------------------ UI helpers

    private View row(String title, String detail, View.OnClickListener onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(dp(18), dp(8), dp(18), dp(8));
        r.setBackground(rowBackground());
        r.setOnClickListener(onClick);

        TextView check = text("", 20, 0xFF86EFAC);
        r.addView(check, new LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT));
        if (!detail.isEmpty()) checks.add(check);

        TextView t = text(title, 18, Color.WHITE);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView d = text(detail, 15, 0xFF9AA3B8);
        r.addView(d);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        list.addView(r, lp);
        return r;
    }

    private StateListDrawable rowBackground() {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0x33FFFFFF);
        focused.setStroke(dp(1), 0x55FFFFFF);
        focused.setCornerRadius(dp(12));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    private TextView text(String s, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
