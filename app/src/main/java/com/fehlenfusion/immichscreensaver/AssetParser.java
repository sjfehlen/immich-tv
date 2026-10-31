package com.fehlenfusion.immichscreensaver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure JSON parsing of Immich responses. No Android dependencies besides org.json. */
final class AssetParser {
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private AssetParser() {}

    static boolean isUuid(String s) {
        return s != null && UUID.matcher(s).matches();
    }

    /** A displayable photo and when it was taken (ISO-8601 local time, "" if unknown). */
    static final class Photo {
        final String id;
        final String takenAt;

        Photo(String id, String takenAt) {
            this.id = id;
            this.takenAt = takenAt == null ? "" : takenAt;
        }
    }

    /**
     * Displayable photos: type IMAGE, not trashed, not archived, and (when the server reports
     * it) visibility "timeline". Malformed entries and duplicates are skipped.
     */
    static List<Photo> photos(JSONArray assets) {
        List<Photo> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (assets == null) return out;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String id = str(a, "id");
            if (!isUuid(id)) continue;
            if (!"IMAGE".equals(str(a, "type"))) continue;
            if (a.optBoolean("isTrashed", false)) continue;
            if (a.optBoolean("isArchived", false)) continue;
            String visibility = str(a, "visibility");
            if (visibility != null && !"timeline".equals(visibility)) continue;
            if (!seen.add(id)) continue;
            String taken = str(a, "localDateTime");
            if (taken == null) taken = str(a, "fileCreatedAt");
            out.add(new Photo(id, taken));
        }
        return out;
    }

    /** Ids of {@link #photos(JSONArray)}. */
    static List<String> imageIds(JSONArray assets) {
        return ids(photos(assets));
    }

    static List<String> ids(List<Photo> photos) {
        List<String> out = new ArrayList<>(photos.size());
        for (Photo p : photos) out.add(p.id);
        return out;
    }

    /** One page of a POST /api/search/metadata response. */
    static final class SearchPage {
        final List<Photo> photos;
        final List<String> ids;
        /** v3.2+ cursor, or null. */
        final String nextCursor;
        /** Legacy page token (numeric string), or null. */
        final String nextPage;

        SearchPage(List<Photo> photos, String nextCursor, String nextPage) {
            this.photos = photos;
            this.ids = ids(photos);
            this.nextCursor = nextCursor;
            this.nextPage = nextPage;
        }
    }

    static SearchPage searchPage(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject assets = root.optJSONObject("assets");
        if (assets == null) return new SearchPage(new ArrayList<>(), null, null);
        return new SearchPage(photos(assets.optJSONArray("items")),
                str(assets, "nextCursor"), str(assets, "nextPage"));
    }

    /** Album list entry for the setup page. */
    static final class Album {
        final String id;
        final String name;
        final int assetCount;

        Album(String id, String name, int assetCount) {
            this.id = id;
            this.name = name;
            this.assetCount = assetCount;
        }
    }

    /** Parses GET /api/albums (a JSON array). */
    static List<Album> albums(String json) throws JSONException {
        JSONArray arr = new JSONArray(json);
        List<Album> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            String id = str(a, "id");
            if (!isUuid(id)) continue;
            String name = str(a, "albumName");
            out.add(new Album(id, name == null || name.isEmpty() ? "(untitled)" : name,
                    a.optInt("assetCount", 0)));
        }
        return out;
    }

    /**
     * Caption for a photo from GET /api/assets/{id}: "October 12, 2023 · Austin, Texas".
     * Uses localDateTime (wall-clock time where the photo was taken). Returns null if empty.
     */
    static String photoInfo(String json, Locale locale) throws JSONException {
        JSONObject a = new JSONObject(json);
        JSONObject exif = a.optJSONObject("exifInfo");
        List<String> parts = new ArrayList<>();

        String dt = str(a, "localDateTime");
        if (dt == null) dt = str(exif, "dateTimeOriginal");
        if (dt != null && dt.length() >= 10) {
            try {
                SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                in.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = in.parse(dt.substring(0, 10));
                SimpleDateFormat out = new SimpleDateFormat("MMMM d, yyyy", locale);
                out.setTimeZone(TimeZone.getTimeZone("UTC"));
                if (d != null) parts.add(out.format(d));
            } catch (ParseException ignored) {
            }
        }

        String city = str(exif, "city");
        String state = str(exif, "state");
        String country = str(exif, "country");
        List<String> place = new ArrayList<>();
        if (city != null && !city.isEmpty()) place.add(city);
        boolean us = country != null && (country.equals("United States of America")
                || country.equals("United States") || country.equals("USA"));
        if ((us || country == null || country.isEmpty()) && state != null && !state.isEmpty()) {
            place.add(state);
        } else if (country != null && !country.isEmpty()) {
            place.add(country);
        }
        if (!place.isEmpty()) parts.add(join(place, ", "));
        return parts.isEmpty() ? null : join(parts, " \u00B7 ");
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    /** String value, or null when missing / JSON null / not a string. */
    static String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        Object v = o.opt(key);
        return v instanceof String ? (String) v : null;
    }
}
