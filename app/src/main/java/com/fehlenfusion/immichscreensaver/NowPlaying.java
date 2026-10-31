package com.fehlenfusion.immichscreensaver;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.List;

/**
 * Tracks the media session that is playing (Spotify, YouTube, Jellyfin, ...) and exposes
 * play/pause/next/previous. Needs notification-listener access for {@link MediaListenerService};
 * without it, {@link #start()} does nothing. Main thread only.
 */
final class NowPlaying {
    interface Listener {
        /** Called with the current item, or null when nothing is playing or paused. */
        void onNowPlaying(Info info);
    }

    static final class Info {
        final String title;
        final String artist;
        /** Owned by the media session: display it, never recycle it. */
        final Bitmap art;
        final boolean playing;
        final String pkg;

        Info(String title, String artist, Bitmap art, boolean playing, String pkg) {
            this.title = title;
            this.artist = artist;
            this.art = art;
            this.playing = playing;
            this.pkg = pkg;
        }
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ComponentName component;
    private MediaSessionManager msm;
    private MediaController current;
    private Info info;
    private boolean started;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::update;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            refresh();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publish();
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    };

    NowPlaying(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.component = new ComponentName(ctx, MediaListenerService.class);
    }

    static boolean accessGranted(Context ctx) {
        ComponentName cn = new ComponentName(ctx, MediaListenerService.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(cn);
        }
        String v = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        return v != null && v.contains(cn.flattenToString());
    }

    void start() {
        if (started) return;
        msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) return;
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, component, handler);
            started = true;
            update(msm.getActiveSessions(component));
        } catch (SecurityException notGranted) {
            started = false;
        }
    }

    void stop() {
        if (msm != null && started) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsChanged);
            } catch (RuntimeException ignored) {
            }
        }
        started = false;
        setCurrent(null);
        info = null;
    }

    Info info() {
        return info;
    }

    void playPause() {
        if (current == null) return;
        if (isPlaying(current.getPlaybackState())) current.getTransportControls().pause();
        else current.getTransportControls().play();
    }

    void next() {
        if (current != null) current.getTransportControls().skipToNext();
    }

    void previous() {
        if (current != null) current.getTransportControls().skipToPrevious();
    }

    // ------------------------------------------------------------ internals

    private void refresh() {
        if (!started || msm == null) return;
        try {
            update(msm.getActiveSessions(component));
        } catch (SecurityException e) {
            update(null);
        }
    }

    /** Prefer a session that is playing; else the first paused one. */
    private void update(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) {
            for (MediaController m : list) {
                if (isPlaying(m.getPlaybackState())) {
                    pick = m;
                    break;
                }
            }
            if (pick == null) {
                for (MediaController m : list) {
                    PlaybackState s = m.getPlaybackState();
                    if (s != null && s.getState() == PlaybackState.STATE_PAUSED && m.getMetadata() != null) {
                        pick = m;
                        break;
                    }
                }
            }
        }
        setCurrent(pick);
        publish();
    }

    private void setCurrent(MediaController m) {
        if (current != null && (m == null || !current.getSessionToken().equals(m.getSessionToken()))) {
            current.unregisterCallback(callback);
            current = null;
        }
        if (m != null && current == null) {
            current = m;
            current.registerCallback(callback, handler);
        }
    }

    private void publish() {
        Info next = null;
        if (current != null) {
            PlaybackState s = current.getPlaybackState();
            MediaMetadata md = current.getMetadata();
            boolean active = s != null && (isPlaying(s) || s.getState() == PlaybackState.STATE_PAUSED);
            if (active && md != null) {
                String title = text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
                String artist = text(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                        MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (title != null) next = new Info(title, artist, art, isPlaying(s), current.getPackageName());
            }
        }
        info = next;
        listener.onNowPlaying(next);
    }

    private static String text(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    private static boolean isPlaying(PlaybackState s) {
        if (s == null) return false;
        int st = s.getState();
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
                || st == PlaybackState.STATE_SKIPPING_TO_NEXT || st == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                || st == PlaybackState.STATE_CONNECTING;
    }
}
