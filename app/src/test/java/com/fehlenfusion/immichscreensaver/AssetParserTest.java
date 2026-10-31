package com.fehlenfusion.immichscreensaver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class AssetParserTest {
    private static final String IMG1 = "11111111-1111-4111-8111-111111111111";
    private static final String IMG2 = "22222222-2222-4222-8222-222222222222";
    private static final String VID = "33333333-3333-4333-8333-333333333333";
    private static final String TRASH = "44444444-4444-4444-8444-444444444444";
    private static final String ARCH = "55555555-5555-4555-8555-555555555555";
    private static final String ARCH_VIS = "66666666-6666-4666-8666-666666666666";
    private static final String HIDDEN = "77777777-7777-4777-8777-777777777777";
    private static final String LOCKED = "88888888-8888-4888-8888-888888888888";

    @Test
    public void keepsOnlyImagesDropsVideoTrashedArchived() throws Exception {
        JSONArray assets = new JSONArray("["
                + "{\"id\":\"" + IMG1 + "\",\"type\":\"IMAGE\",\"isTrashed\":false,\"isArchived\":false,\"visibility\":\"timeline\"},"
                + "{\"id\":\"" + VID + "\",\"type\":\"VIDEO\"},"
                + "{\"id\":\"" + TRASH + "\",\"type\":\"IMAGE\",\"isTrashed\":true},"
                + "{\"id\":\"" + ARCH + "\",\"type\":\"IMAGE\",\"isArchived\":true},"
                + "{\"id\":\"" + ARCH_VIS + "\",\"type\":\"IMAGE\",\"visibility\":\"archive\"},"
                + "{\"id\":\"" + HIDDEN + "\",\"type\":\"IMAGE\",\"visibility\":\"hidden\"},"
                + "{\"id\":\"" + LOCKED + "\",\"type\":\"IMAGE\",\"visibility\":\"locked\"},"
                + "{\"id\":\"" + IMG2 + "\",\"type\":\"IMAGE\",\"visibility\":null},"
                + "{\"id\":\"" + IMG1 + "\",\"type\":\"IMAGE\"},"           // duplicate
                + "{\"id\":\"not-a-uuid\",\"type\":\"IMAGE\"},"             // malformed id
                + "{\"type\":\"IMAGE\"},"                                   // missing id
                + "{\"id\":\"" + IMG2 + "\",\"type\":\"OTHER\"},"
                + "42"
                + "]");
        assertEquals(Arrays.asList(IMG1, IMG2), AssetParser.imageIds(assets));
    }

    @Test
    public void nullArrayGivesEmptyList() {
        assertEquals(0, AssetParser.imageIds(null).size());
    }

    @Test
    public void parsesV3SearchPageWithCursor() throws Exception {
        AssetParser.SearchPage p = AssetParser.searchPage("{\"albums\":{},\"assets\":{\"count\":2,\"items\":["
                + "{\"id\":\"" + IMG1 + "\",\"type\":\"IMAGE\",\"visibility\":\"timeline\"},"
                + "{\"id\":\"" + VID + "\",\"type\":\"VIDEO\",\"visibility\":\"timeline\"}],"
                + "\"nextCursor\":\"abc\",\"facets\":[]}}");
        assertEquals(Arrays.asList(IMG1), p.ids);
        assertEquals("abc", p.nextCursor);
        assertNull(p.nextPage);
    }

    @Test
    public void parsesLegacySearchPageWithNullNext() throws Exception {
        AssetParser.SearchPage p = AssetParser.searchPage(
                "{\"assets\":{\"items\":[{\"id\":\"" + IMG2 + "\",\"type\":\"IMAGE\"}],\"nextPage\":null}}");
        assertEquals(Arrays.asList(IMG2), p.ids);
        assertNull(p.nextCursor);
        assertNull(p.nextPage);
    }

    @Test
    public void parsesAlbumList() throws Exception {
        List<AssetParser.Album> albums = AssetParser.albums("["
                + "{\"id\":\"" + IMG1 + "\",\"albumName\":\"Family\",\"assetCount\":12},"
                + "{\"id\":\"" + IMG2 + "\",\"albumName\":\"\"},"
                + "{\"id\":\"bad\",\"albumName\":\"x\"}]");
        assertEquals(2, albums.size());
        assertEquals("Family", albums.get(0).name);
        assertEquals(12, albums.get(0).assetCount);
        assertEquals("(untitled)", albums.get(1).name);
    }
}
