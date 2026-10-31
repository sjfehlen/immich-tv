package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.content.SharedPreferences;

/** Runtime configuration, stored in app-private SharedPreferences (allowBackup=false). */
final class Config {
    private static final String PREFS = "immich";

    final String serverUrl;
    final String apiKey;
    final String albumId;
    final String albumName;

    Config(String serverUrl, String apiKey, String albumId, String albumName) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.albumId = albumId;
        this.albumName = albumName;
    }

    boolean isComplete() {
        return serverUrl != null && apiKey != null && AssetParser.isUuid(albumId);
    }

    static Config load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Config(p.getString("url", null), p.getString("key", null),
                p.getString("album", null), p.getString("albumName", null));
    }

    void save(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("url", serverUrl)
                .putString("key", apiKey)
                .putString("album", albumId)
                .putString("albumName", albumName)
                .commit();
    }

    /**
     * Normalises a user-entered server URL: trims, strips trailing "/" and "/api".
     * Returns null unless it is an http(s) URL with a host.
     */
    static String normalizeUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith("/api")) s = s.substring(0, s.length() - 4);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.matches("(?i)^https?://[A-Za-z0-9.\\-\\[\\]:]+(:[0-9]{1,5})?(/[A-Za-z0-9._~\\-/]*)?$")) {
            return null;
        }
        return s;
    }

    /** API keys are opaque tokens; accept only URL/header-safe characters. */
    static boolean isPlausibleKey(String key) {
        return key != null && key.matches("^[A-Za-z0-9_\\-+/=.]{16,256}$");
    }
}
