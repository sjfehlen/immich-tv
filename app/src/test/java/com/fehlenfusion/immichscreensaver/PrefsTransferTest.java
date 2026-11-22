package com.fehlenfusion.immichscreensaver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;
import java.util.Set;

public class PrefsTransferTest {
    @Test
    public void parsesEveryValueType() throws Exception {
        String xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                + "    <string name=\"url\">http://10.5.1.57:30041/a?x=1&amp;y=2</string>\n"
                + "    <int name=\"duration\" value=\"30\" />\n"
                + "    <boolean name=\"christmas\" value=\"true\" />\n"
                + "    <long name=\"t\" value=\"1234567890123\" />\n"
                + "    <float name=\"f\" value=\"1.5\" />\n"
                + "    <set name=\"s\"><string>a</string><string>b</string></set>\n"
                + "</map>";
        Map<String, Object> m = PrefsTransfer.parse(xml);
        assertEquals("http://10.5.1.57:30041/a?x=1&y=2", m.get("url"));
        assertEquals(30, m.get("duration"));
        assertEquals(true, m.get("christmas"));
        assertEquals(1234567890123L, m.get("t"));
        assertEquals(1.5f, m.get("f"));
        assertTrue(((Set<?>) m.get("s")).contains("b"));
    }

    @Test(expected = Exception.class)
    public void rejectsOtherDocuments() throws Exception {
        PrefsTransfer.parse("<html></html>");
    }


    @Test
    public void onlyOwnFilesAreKnown() {
        assertTrue(PrefsTransfer.isKnown("immich"));
        assertFalse(PrefsTransfer.isKnown("../x"));
        assertFalse(PrefsTransfer.isKnown("random"));
    }
}
