package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.content.SharedPreferences;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Moves the app's settings files in and out as SharedPreferences XML, for backup and reinstall
 * over adb (see {@link TransferActivity}). Only the app's own preference files are handled.
 */
final class PrefsTransfer {
    /** The preference files this app uses; nothing else can be exported or imported. */
    static final String[] FILES = {"immich", "display", "home", "homeassistant", "jellyfin"};

    private PrefsTransfer() {}

    static boolean isKnown(String name) {
        for (String f : FILES) if (f.equals(name)) return true;
        return false;
    }

    /** Parses SharedPreferences XML into a map of String, Integer, Long, Float, Boolean or Set. */
    static Map<String, Object> parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); // input comes only from adb
        Element root = f.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
        if (!"map".equals(root.getTagName())) throw new IllegalArgumentException("not a preferences file");
        Map<String, Object> out = new HashMap<>();
        NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            String name = e.getAttribute("name");
            if (name.isEmpty()) continue;
            switch (e.getTagName()) {
                case "string": out.put(name, e.getTextContent()); break;
                case "int": out.put(name, Integer.valueOf(e.getAttribute("value"))); break;
                case "long": out.put(name, Long.valueOf(e.getAttribute("value"))); break;
                case "float": out.put(name, Float.valueOf(e.getAttribute("value"))); break;
                case "boolean": out.put(name, Boolean.valueOf(e.getAttribute("value"))); break;
                case "set": {
                    Set<String> s = new HashSet<>();
                    NodeList items = e.getElementsByTagName("string");
                    for (int j = 0; j < items.getLength(); j++) s.add(items.item(j).getTextContent());
                    out.put(name, s);
                    break;
                }
                default: break;
            }
        }
        return out;
    }

    /** Replaces the named preference file with the parsed values. */
    @SuppressWarnings("unchecked")
    static void apply(Context ctx, String name, Map<String, Object> values) {
        if (!isKnown(name)) throw new IllegalArgumentException("unknown file");
        SharedPreferences.Editor ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String) ed.putString(e.getKey(), (String) v);
            else if (v instanceof Integer) ed.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Long) ed.putLong(e.getKey(), (Long) v);
            else if (v instanceof Float) ed.putFloat(e.getKey(), (Float) v);
            else if (v instanceof Boolean) ed.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof Set) ed.putStringSet(e.getKey(), (Set<String>) v);
        }
        ed.commit();
    }
}
