package com.fehlenfusion.immichscreensaver;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * Minimal read-only Immich client. Only GET endpoints plus POST /api/search/metadata
 * (a read-only search). Never logs URLs, headers or the key.
 */
final class ImmichClient {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    private static final int SEARCH_PAGE_SIZE = 1000;
    private static final int MAX_PAGES = 200;

    /** Non-2xx HTTP status. Message deliberately contains only the code. */
    static final class HttpStatusException extends IOException {
        final int code;

        HttpStatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private final String baseUrl;
    private final String apiKey;

    ImmichClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /** GET /api/albums: every album visible to the key (owned and shared). */
    List<AssetParser.Album> listAlbums() throws IOException, JSONException {
        return AssetParser.albums(new String(request("GET", "/api/albums", null, MAX_JSON_BYTES), UTF8));
    }

    /** Union of displayable photos across all given albums (deduplicated, in order). */
    List<AssetParser.Photo> fetchPhotos(List<String> albumIds) throws IOException, JSONException {
        Map<String, AssetParser.Photo> all = new LinkedHashMap<>();
        for (String id : albumIds) put(all, fetchPhotos(id));
        return new ArrayList<>(all.values());
    }

    private static void put(Map<String, AssetParser.Photo> into, List<AssetParser.Photo> photos) {
        for (AssetParser.Photo p : photos) if (!into.containsKey(p.id)) into.put(p.id, p);
    }

    /** The album's cover asset id, or null. One small request; used for the settings backdrop. */
    String fetchAlbumCoverId(String albumId) throws IOException, JSONException {
        if (!AssetParser.isUuid(albumId)) return null;
        JSONObject album = new JSONObject(
                new String(request("GET", "/api/albums/" + albumId, null, MAX_JSON_BYTES), UTF8));
        String id = AssetParser.str(album, "albumThumbnailAssetId");
        return AssetParser.isUuid(id) ? id : null;
    }

    /** "October 12, 2023 · Austin, Texas" for the photo, or null. */
    String fetchPhotoInfo(String assetId) throws IOException, JSONException {
        if (!AssetParser.isUuid(assetId)) return null;
        return AssetParser.photoInfo(
                new String(request("GET", "/api/assets/" + assetId, null, MAX_JSON_BYTES), UTF8),
                java.util.Locale.getDefault());
    }

    /**
     * Ids of all displayable photos in the album.
     * 1. GET /api/albums/{id}: older servers include an "assets" array.
     * 2. Immich v3.2+: POST /api/search/metadata with filter + cursor paging.
     * 3. Older search shape (albumIds/type/page) if the server rejects (2).
     */
    List<AssetParser.Photo> fetchPhotos(String albumId) throws IOException, JSONException {
        if (!AssetParser.isUuid(albumId)) throw new IOException("bad album id");
        JSONObject album = new JSONObject(
                new String(request("GET", "/api/albums/" + albumId, null, MAX_JSON_BYTES), UTF8));
        JSONArray assets = album.optJSONArray("assets");
        int assetCount = album.optInt("assetCount", -1);
        if (assets != null && (assets.length() > 0 || assetCount == 0)) {
            return AssetParser.photos(assets);
        }
        if (assetCount == 0) return new ArrayList<>();
        try {
            return searchWithFilter(albumId);
        } catch (HttpStatusException e) {
            if (e.code == 400 || e.code == 422) return searchLegacy(albumId);
            throw e;
        }
    }

    private List<AssetParser.Photo> searchWithFilter(String albumId) throws IOException, JSONException {
        Map<String, AssetParser.Photo> ids = new LinkedHashMap<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        for (int i = 0; i < MAX_PAGES; i++) {
            JSONObject filter = new JSONObject()
                    .put("albumIds", new JSONObject().put("any", new JSONArray().put(albumId)))
                    .put("type", new JSONObject().put("eq", "IMAGE"))
                    .put("visibility", new JSONObject().put("eq", "timeline"));
            JSONObject body = new JSONObject()
                    .put("filter", filter)
                    .put("size", SEARCH_PAGE_SIZE)
                    .put("withExif", false)
                    .put("withPeople", false);
            if (cursor != null) body.put("cursor", cursor);
            AssetParser.SearchPage page = AssetParser.searchPage(new String(
                    request("POST", "/api/search/metadata", body.toString(), MAX_JSON_BYTES), UTF8));
            put(ids, page.photos);
            cursor = page.nextCursor;
            if (cursor == null || !seenCursors.add(cursor)) break;
        }
        return new ArrayList<>(ids.values());
    }

    private List<AssetParser.Photo> searchLegacy(String albumId) throws IOException, JSONException {
        Map<String, AssetParser.Photo> ids = new LinkedHashMap<>();
        int page = 1;
        for (int i = 0; i < MAX_PAGES; i++) {
            JSONObject body = new JSONObject()
                    .put("albumIds", new JSONArray().put(albumId))
                    .put("type", "IMAGE")
                    .put("size", SEARCH_PAGE_SIZE)
                    .put("page", page);
            AssetParser.SearchPage p = AssetParser.searchPage(new String(
                    request("POST", "/api/search/metadata", body.toString(), MAX_JSON_BYTES), UTF8));
            put(ids, p.photos);
            if (p.nextPage == null) break;
            int next;
            try {
                next = Integer.parseInt(p.nextPage);
            } catch (NumberFormatException e) {
                break;
            }
            if (next <= page) break;
            page = next;
        }
        return new ArrayList<>(ids.values());
    }

    /**
     * Downloads the "preview" rendition (never the original) and decodes it with
     * inSampleSize so the result is at most maxW x maxH. Returns null if undecodable.
     */
    Bitmap fetchPreview(String assetId, int maxW, int maxH, Bitmap.Config config) throws IOException {
        return fetchImage(assetId, "preview", maxW, maxH, config);
    }

    /** size is "preview" (~1440 px) or "thumbnail" (~250 px). Never the original. */
    Bitmap fetchImage(String assetId, String size, int maxW, int maxH, Bitmap.Config config) throws IOException {
        if (!AssetParser.isUuid(assetId)) throw new IOException("bad asset id");
        if (!"preview".equals(size) && !"thumbnail".equals(size)) throw new IOException("bad size");
        byte[] data = request("GET", "/api/assets/" + assetId + "/thumbnail?size=" + size, null, MAX_IMAGE_BYTES);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxW, maxH);
        opts.inPreferredConfig = config;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (bmp != null && (bmp.getWidth() > maxW || bmp.getHeight() > maxH)) {
            // Decoder ignored the sample size; refuse rather than exceed the memory budget.
            bmp.recycle();
            return null;
        }
        return bmp;
    }

    /** Smallest power of two that brings w x h within maxW x maxH. */
    static int sampleSize(int w, int h, int maxW, int maxH) {
        int s = 1;
        while (w / s > maxW || h / s > maxH) s *= 2;
        return s;
    }

    private byte[] request(String method, String path, String jsonBody, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(false); // never forward the key to another location
            c.setUseCaches(false);
            c.setRequestMethod(method);
            c.setRequestProperty("x-api-key", apiKey);
            c.setRequestProperty("Accept", jsonBody != null || !path.contains("/thumbnail")
                    ? "application/json" : "image/*");
            if (jsonBody != null) {
                byte[] body = jsonBody.getBytes(UTF8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(body.length);
                OutputStream os = c.getOutputStream();
                try {
                    os.write(body);
                } finally {
                    os.close();
                }
            }
            int code = c.getResponseCode();
            if (code < 200 || code > 299) throw new HttpStatusException(code);
            InputStream in = c.getInputStream();
            try {
                return readAll(in, c.getContentLength(), maxBytes);
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int contentLength, int maxBytes) throws IOException {
        if (contentLength > maxBytes) throw new IOException("response too large");
        if (contentLength > 0) {
            byte[] buf = new byte[contentLength];
            int off = 0;
            while (off < contentLength) {
                int n = in.read(buf, off, contentLength - off);
                if (n < 0) throw new IOException("truncated response");
                off += n;
            }
            return buf;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
            if (out.size() > maxBytes) throw new IOException("response too large");
        }
        return out.toByteArray();
    }
}
