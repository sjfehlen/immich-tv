package com.fehlenfusion.immichscreensaver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure JSON parsing for Jellyfin responses (unit-tested on the JVM). */
final class JellyfinParser {
    private static final Pattern ID = Pattern.compile("^[0-9a-fA-F]{32}$|^[0-9a-fA-F-]{36}$");
    private static final Pattern TAG = Pattern.compile("^[0-9a-zA-Z]{1,64}$");

    private JellyfinParser() {}

    static boolean isId(String s) {
        return s != null && ID.matcher(s).matches();
    }

    /** One "continue watching" / "next up" card. */
    static final class Card {
        final String id;
        final String seriesId;
        final String title;
        final String subtitle;
        /** 0..1, or 0 for next-up items. */
        final float progress;
        /** Image to show: item id, image type path ("Thumb", "Backdrop/0", "Primary") and cache tag. */
        final String imageItemId;
        final String imagePath;
        final String imageTag;

        Card(String id, String seriesId, String title, String subtitle, float progress,
             String imageItemId, String imagePath, String imageTag) {
            this.id = id;
            this.seriesId = seriesId;
            this.title = title;
            this.subtitle = subtitle;
            this.progress = progress;
            this.imageItemId = imageItemId;
            this.imagePath = imagePath;
            this.imageTag = imageTag;
        }
    }

    static List<Card> cards(String json) throws JSONException {
        List<Card> out = new ArrayList<>();
        JSONArray items = new JSONObject(json).optJSONArray("Items");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            Card c = card(it);
            if (c != null) out.add(c);
        }
        return out;
    }

    static Card card(JSONObject it) {
        String id = AssetParser.str(it, "Id");
        if (!isId(id)) return null;
        String type = AssetParser.str(it, "Type");
        String name = AssetParser.str(it, "Name");
        if (name == null) name = "";
        boolean episode = "Episode".equals(type);

        String title = episode && AssetParser.str(it, "SeriesName") != null ? AssetParser.str(it, "SeriesName") : name;
        StringBuilder sub = new StringBuilder();
        if (episode) {
            int s = it.optInt("ParentIndexNumber", -1), e = it.optInt("IndexNumber", -1);
            if (s >= 0 && e >= 0) sub.append('S').append(s).append(":E").append(e);
            if (!name.isEmpty()) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(name);
            }
        }

        float progress = 0f;
        JSONObject ud = it.optJSONObject("UserData");
        long runtime = it.optLong("RunTimeTicks", 0);
        if (ud != null) {
            double pct = ud.optDouble("PlayedPercentage", 0);
            if (pct > 0 && pct < 100) progress = (float) (pct / 100.0);
            long pos = ud.optLong("PlaybackPositionTicks", 0);
            if (pos > 0 && runtime > pos) {
                long minLeft = Math.max(1, (runtime - pos) / 600_000_000L);
                if (sub.length() > 0) sub.append(" · ");
                sub.append(minLeft).append(" min left");
                if (progress == 0f) progress = (float) pos / runtime;
            }
        }

        // Landscape art: series/item Thumb, then a Backdrop, then the item's Primary image.
        String imgId = null, imgPath = null, imgTag = null;
        JSONObject tags = it.optJSONObject("ImageTags");
        if (episode && isId(AssetParser.str(it, "ParentThumbItemId")) && validTag(AssetParser.str(it, "ParentThumbImageTag"))) {
            imgId = AssetParser.str(it, "ParentThumbItemId");
            imgPath = "Thumb";
            imgTag = AssetParser.str(it, "ParentThumbImageTag");
        } else if (!episode && tags != null && validTag(AssetParser.str(tags, "Thumb"))) {
            imgId = id;
            imgPath = "Thumb";
            imgTag = AssetParser.str(tags, "Thumb");
        } else if (!episode && it.optJSONArray("BackdropImageTags") != null
                && validTag(it.optJSONArray("BackdropImageTags").optString(0, null))) {
            imgId = id;
            imgPath = "Backdrop/0";
            imgTag = it.optJSONArray("BackdropImageTags").optString(0);
        } else if (isId(AssetParser.str(it, "ParentBackdropItemId")) && it.optJSONArray("ParentBackdropImageTags") != null
                && validTag(it.optJSONArray("ParentBackdropImageTags").optString(0, null))) {
            imgId = AssetParser.str(it, "ParentBackdropItemId");
            imgPath = "Backdrop/0";
            imgTag = it.optJSONArray("ParentBackdropImageTags").optString(0);
        } else if (tags != null && validTag(AssetParser.str(tags, "Primary"))) {
            imgId = id;
            imgPath = "Primary";
            imgTag = AssetParser.str(tags, "Primary");
        }
        String seriesId = AssetParser.str(it, "SeriesId");
        return new Card(id, isId(seriesId) ? seriesId : null, title, sub.toString(), progress, imgId, imgPath, imgTag);
    }

    private static boolean validTag(String t) {
        return t != null && TAG.matcher(t).matches();
    }

    /** Resume first; then next-up episodes for series not already being resumed. Capped at limit. */
    static List<Card> merge(List<Card> resume, List<Card> nextUp, int limit) {
        List<Card> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> series = new HashSet<>();
        for (Card c : resume) {
            if (out.size() >= limit) break;
            if (ids.add(c.id)) {
                out.add(c);
                if (c.seriesId != null) series.add(c.seriesId);
            }
        }
        for (Card c : nextUp) {
            if (out.size() >= limit) break;
            if (c.seriesId != null && series.contains(c.seriesId)) continue;
            if (ids.add(c.id)) out.add(c);
        }
        return out;
    }

    /** Quick Connect: {"Secret":..,"Code":..,"Authenticated":..}. */
    static final class QuickConnect {
        final String secret;
        final String code;
        final boolean authenticated;

        QuickConnect(String secret, String code, boolean authenticated) {
            this.secret = secret;
            this.code = code;
            this.authenticated = authenticated;
        }
    }

    static QuickConnect quickConnect(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        return new QuickConnect(AssetParser.str(o, "Secret"), AssetParser.str(o, "Code"),
                o.optBoolean("Authenticated", false));
    }

    /** AuthenticationResult: returns {token, userId, userName} or null. */
    static String[] signIn(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        String token = AssetParser.str(o, "AccessToken");
        JSONObject user = o.optJSONObject("User");
        String userId = AssetParser.str(user, "Id");
        if (token == null || !isId(userId)) return null;
        String name = AssetParser.str(user, "Name");
        return new String[]{token, userId, name == null ? "" : name};
    }
}
