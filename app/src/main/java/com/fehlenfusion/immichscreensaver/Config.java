package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Server connection, stored in app-private SharedPreferences (allowBackup=false). */
final class Config {
    private static final String PREFS = "immich";

    final String serverUrl;
    final String apiKey;
    final List<String> albumIds;
    /** Human-readable summary of the selected albums, e.g. "Family" or "Family + 2 more". */
    final String albumName;

    Config(String serverUrl, String apiKey, List<String> albumIds, String albumName) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.albumIds = Collections.unmodifiableList(new ArrayList<>(albumIds));
        this.albumName = albumName;
    }

    boolean isComplete() {
        return serverUrl != null && apiKey != null && !albumIds.isEmpty();
    }

    static Config load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Config(p.getString("url", null), p.getString("key", null),
                parseIds(p.getString("album", null)), p.getString("albumName", null));
    }

    void save(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("url", serverUrl)
                .putString("key", apiKey)
                .putString("album", joinIds(albumIds))
                .putString("albumName", albumName)
                .commit();
    }

    static List<String> parseIds(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String part : s.split(",")) {
            String id = part.trim();
            if (AssetParser.isUuid(id) && !out.contains(id)) out.add(id);
        }
        return out;
    }

    static String joinIds(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    static String summarize(List<String> names) {
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0);
        if (names.size() == 2) return names.get(0) + " & " + names.get(1);
        return names.get(0) + " + " + (names.size() - 1) + " more";
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
