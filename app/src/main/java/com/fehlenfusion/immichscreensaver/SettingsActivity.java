package com.fehlenfusion.immichscreensaver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Settings for the whole app (screensaver, home screen, accounts), launched from the home
 * screen's menu or the launcher: grouped frosted cards over a blurred photo from the album.
 * Selecting a row cycles its value or opens its setup screen.
 */
public final class SettingsActivity extends Activity {
    private static final String DREAM_COMPONENT =
            "com.fehlenfusion.immichscreensaver/.PhotoDreamService";
    private static final String DREAM_COMPONENT_LONG =
            "com.fehlenfusion.immichscreensaver/com.fehlenfusion.immichscreensaver.PhotoDreamService";

    private Prefs prefs;
    private Backdrop backdrop;
    private TextView subtitle;
    private Glass.Row preview, order, duration, style, zoom, clock, info, dim;
    private Glass.Row homeBg, idle;
    private Glass.Row immich, albums, jellyfin, homeAssistant;
    private Glass.Row screensaverStatus, nowPlayingStatus, homeButtonStatus;
    private ScrollView content;
    private final java.util.List<Section> sections = new java.util.ArrayList<>();

    /** One block of settings and its entry in the table of contents. */
    private static final class Section {
        final View header;
        final LinearLayout card;
        Glass.Row toc;

        Section(View header, LinearLayout card) {
            this.header = header;
            this.card = card;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs = Prefs.load(this);
        refresh();
        backdrop.load();
    }

    @Override
    protected void onDestroy() {
        backdrop.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------ layout

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        backdrop = new Backdrop(this, root);

        // Left: title and a table of contents. Right: the settings, scrolling on their own.
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(columns, match());

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(56), dp(40), dp(28), dp(40));
        columns.addView(side, new LinearLayout.LayoutParams(dp(340), -1));

        TextView title = Tiles.label(this, "Settings", 32, Color.WHITE);
        title.setTypeface(Tiles.MEDIUM);
        title.setPadding(dp(14), 0, 0, 0);
        side.addView(title);
        subtitle = Tiles.label(this, "", 14, 0xB3FFFFFF);
        subtitle.setLineSpacing(0f, 1.15f);
        subtitle.setPadding(dp(14), dp(4), 0, 0);
        side.addView(subtitle);
        LinearLayout toc = Glass.cardLayout(this);
        toc.setBackground(Glass.card(this));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(28);
        side.addView(toc, tlp);

        content = new ScrollView(this);
        content.setVerticalScrollBarEnabled(false);
        content.setClipToPadding(false);
        content.setPadding(dp(4), dp(18), dp(56), dp(40));
        columns.addView(content, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);

        LinearLayout saver = section(list, "Screensaver");
        immich = Glass.add(saver, Glass.row(this, "Immich",
                v -> startActivity(new Intent(this, SetupActivity.class))));
        albums = Glass.add(saver, Glass.row(this, "Albums", v -> openAlbums()));
        preview = Glass.add(saver, Glass.row(this, "Preview", v -> openPreview())).value("\u25B6");
        order = Glass.add(saver, Glass.row(this, "Order", v -> { prefs.order = (prefs.order + 1) % 3; save(); }));
        duration = Glass.add(saver, Glass.row(this, "Photo duration", v -> { prefs.nextDuration(); save(); }));
        style = Glass.add(saver, Glass.row(this, "Photo style", v -> { prefs.style = (prefs.style + 1) % 3; save(); }));
        zoom = Glass.add(saver, Glass.row(this, "Slow zoom", v -> { prefs.zoom = !prefs.zoom; save(); }));
        clock = Glass.add(saver, Glass.row(this, "Clock", v -> { prefs.clock = (prefs.clock + 1) % 3; save(); }));
        info = Glass.add(saver, Glass.row(this, "Photo date & place", v -> { prefs.info = !prefs.info; save(); }));
        dim = Glass.add(saver, Glass.row(this, "Dim screen", v -> { prefs.dim = !prefs.dim; save(); }));

        LinearLayout home = section(list, "Home screen");
        homeBg = Glass.add(home, Glass.row(this, "Background", v -> { prefs.homePhotos = !prefs.homePhotos; save(); }));
        idle = Glass.add(home, Glass.row(this, "Hide apps when idle", v -> { prefs.nextIdle(); save(); }));

        LinearLayout accounts = section(list, "Accounts");
        jellyfin = Glass.add(accounts, Glass.row(this, "Jellyfin",
                v -> startActivity(new Intent(this, JellyfinSetupActivity.class))));
        homeAssistant = Glass.add(accounts, Glass.row(this, "Home Assistant",
                v -> startActivity(new Intent(this, SetupActivity.class)
                        .putExtra(SetupActivity.EXTRA_HOME_ASSISTANT, true))));

        LinearLayout system = section(list, "System");
        screensaverStatus = Glass.add(system, Glass.row(this, "TV screensaver", null));
        nowPlayingStatus = Glass.add(system, Glass.row(this, "Now playing controls", null));
        homeButtonStatus = Glass.add(system, Glass.row(this, "Home button", null));

        buildToc(toc, new String[]{"Screensaver", "Home screen", "Accounts", "System"});
        return root;
    }

    /**
     * One entry per section. Moving onto an entry scrolls the settings to that section, Right
     * enters it, Left from any of its rows comes back; the current section's entry stays lit.
     */
    private void buildToc(LinearLayout toc, String[] names) {
        for (int i = 0; i < sections.size(); i++) {
            final Section sec = sections.get(i);
            View first = sec.card.getChildAt(0);
            first.setId(View.generateViewId());
            sec.toc = Glass.add(toc, Glass.row(this, names[i], v -> sec.card.getChildAt(0).requestFocus()));
            sec.toc.title.setTextColor(Glass.states(0xFFFFFFFF, 0xBFFFFFFF));
            sec.toc.view.setId(View.generateViewId());
            sec.toc.view.setNextFocusRightId(first.getId());
            sec.toc.view.setOnFocusChangeListener((v, has) -> {
                if (!has) return;
                markSection(sec);
                content.smoothScrollTo(0, sec.header.getTop());
            });
            for (int r = 0; r < sec.card.getChildCount(); r++) {
                View row = sec.card.getChildAt(r);
                row.setNextFocusLeftId(sec.toc.view.getId());
                row.setOnFocusChangeListener((v, has) -> {
                    if (has) markSection(sec);
                });
            }
        }
    }

    private void markSection(Section current) {
        for (Section s : sections) s.toc.view.setSelected(s == current);
    }

    /** A heading and a frosted card; returns the card to add rows to. */
    private LinearLayout section(LinearLayout list, String name) {
        TextView header = Glass.header(this, name);
        list.addView(header);
        LinearLayout card = Glass.cardLayout(this);
        card.setBackground(Glass.card(this));
        list.addView(card, new LinearLayout.LayoutParams(-1, -2));
        sections.add(new Section(header, card));
        return card;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private int dp(int v) {
        return Tiles.dp(this, v);
    }

    // ------------------------------------------------------------ state

    private void save() {
        prefs.save(this);
        refresh();
    }

    private void refresh() {
        Config c = Config.load(this);
        if (c.isComplete()) {
            subtitle.setText("Showing " + (c.albumName == null ? "your albums" : "\u201C" + c.albumName + "\u201D")
                    + " from " + host(c.serverUrl));
            albums.value(c.albumName != null ? c.albumName
                    : (c.albumIds.size() == 1 ? "1 album" : c.albumIds.size() + " albums"));
            immich.value(host(c.serverUrl));
        } else {
            subtitle.setText("Not connected yet. Select Immich to pair with your phone.");
            albums.value(c.serverUrl != null ? "Choose" : "");
            immich.value("Set up");
        }
        order.value(Prefs.orderLabel(prefs.order));
        duration.value(Prefs.durationLabel(prefs.durationSec));
        style.value(Prefs.styleLabel(prefs.style));
        zoom.value(onOff(prefs.zoom));
        clock.value(Prefs.clockLabel(prefs.clock));
        info.value(onOff(prefs.info));
        dim.value(onOff(prefs.dim));
        homeBg.value(prefs.homePhotos ? "Photos" : "Gradient");
        idle.value(Prefs.idleLabel(prefs.homeIdleSec));
        JellyfinConfig jf = JellyfinConfig.load(this);
        jellyfin.value(jf.isConnected()
                ? (jf.userName == null || jf.userName.isEmpty() ? "Connected" : jf.userName) : "Set up");
        HaConfig ha = HaConfig.load(this);
        homeAssistant.value(ha.isConnected() ? host(ha.url) : "Set up");

        Boolean active = isActiveScreensaver();
        screensaverStatus.value(Boolean.TRUE.equals(active) ? "On" : active == null ? "Unknown" : "Off")
                .detail(Boolean.TRUE.equals(active) ? "Changes apply the next time it starts."
                        : "Run once from a computer: adb shell settings put secure screensaver_components "
                        + DREAM_COMPONENT);
        boolean np = NowPlaying.accessGranted(this);
        nowPlayingStatus.value(onOff(np)).detail(np ? null
                : "Run once from a computer: adb shell cmd notification allow_listener "
                + getPackageName() + "/" + MediaListenerService.class.getName());
        boolean hb = homeButtonEnabled();
        homeButtonStatus.value(hb ? "Opens this home screen" : "Off").detail(hb ? null
                : "Turn on \u201CImmich Home button\u201D in TV settings \u203A Accessibility.");

        if (getCurrentFocus() == null) {
            (c.isComplete() ? preview : c.serverUrl != null ? albums : immich).view.requestFocus();
        }
    }

    private static String onOff(boolean on) {
        return on ? "On" : "Off";
    }

    private static String host(String url) {
        return url == null ? "" : url.replaceFirst("^https?://", "").replaceFirst("/+$", "");
    }

    private boolean homeButtonEnabled() {
        try {
            String v = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return v != null && v.contains(getPackageName() + "/" + HomeRedirectService.class.getName());
        } catch (Exception e) {
            return false;
        }
    }

    /** TRUE/FALSE if readable, null if the system does not let us read the setting. */
    private Boolean isActiveScreensaver() {
        try {
            String v = Settings.Secure.getString(getContentResolver(), "screensaver_components");
            if (v == null) return null;
            return v.contains(DREAM_COMPONENT) || v.contains(DREAM_COMPONENT_LONG);
        } catch (Exception e) {
            return null;
        }
    }

    private void openAlbums() {
        Config c = Config.load(this);
        if (c.serverUrl == null || c.apiKey == null) {
            startActivity(new Intent(this, SetupActivity.class));
        } else {
            startActivity(new Intent(this, AlbumsActivity.class));
        }
    }

    private void openPreview() {
        if (!Config.load(this).isComplete()) {
            startActivity(new Intent(this, SetupActivity.class));
        } else {
            startActivity(new Intent(this, PreviewActivity.class));
        }
    }
}
