package com.fehlenfusion.immichscreensaver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HaTest {
    @Test
    public void normalizeUrlKeepsOnlyOrigin() {
        assertEquals("http://10.5.1.107:8123", HaConfig.normalizeUrl("http://10.5.1.107:8123/lovelace/0"));
        assertEquals("http://homeassistant.local:8123", HaConfig.normalizeUrl(" http://homeassistant.local:8123/ "));
        assertEquals("https://ha.example.com", HaConfig.normalizeUrl("https://ha.example.com?x=1"));
        assertEquals("http://10.0.0.2:8123", HaConfig.normalizeUrl("http://10.0.0.2:8123#frag"));
        assertNull(HaConfig.normalizeUrl("10.5.1.107:8123"));
        assertNull(HaConfig.normalizeUrl("javascript:alert(1)"));
        assertNull(HaConfig.normalizeUrl(null));
    }

    @Test
    public void tokenMustLookLikeJwt() {
        String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJhYmMifQ.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertTrue(HaConfig.isPlausibleToken(jwt));
        assertFalse(HaConfig.isPlausibleToken("short"));
        assertFalse(HaConfig.isPlausibleToken("has spaces in it and is long enough to pass the length check ok"));
        assertFalse(HaConfig.isPlausibleToken(null));
    }
}
