package com.fehlenfusion.immichscreensaver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Structural checks. Set -DqrDumpDir=/some/dir to also write PGM images that an
 * external decoder (e.g. OpenCV) can verify; see README.
 */
public class QrCodeTest {
    static final String[] SAMPLES = {
            "http://192.168.1.23:8765/s/0123456789abcdef",
            "http://172.16.0.200:41234/s/fedcba9876543210",
            "hi",
            // Long enough to need version 7+ (version information blocks).
            "http://192.168.100.200:65535/s/0123456789abcdef/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            // Version 10 (16-bit length field).
            "http://example.com/" + new String(new char[180]).replace('\0', 'x'),
    };

    @Test
    public void finderPatternsAndSizes() throws IOException {
        String dump = System.getProperty("qrDumpDir");
        for (int i = 0; i < SAMPLES.length; i++) {
            QrCode qr = QrCode.encode(SAMPLES[i].getBytes(StandardCharsets.UTF_8));
            assertEquals(qr.version * 4 + 17, qr.size);
            // Finder corners are dark, separators light.
            assertTrue(qr.get(0, 0) && qr.get(qr.size - 1, 0) && qr.get(0, qr.size - 1));
            assertTrue(!qr.get(7, 7) && qr.get(3, 3));
            if (dump != null) writePgm(qr, new File(dump, "qr" + i + ".pgm"));
        }
        assertTrue(QrCode.encode(SAMPLES[3].getBytes(StandardCharsets.UTF_8)).version >= 7);
        assertEquals(10, QrCode.encode(SAMPLES[4].getBytes(StandardCharsets.UTF_8)).version);
    }

    private static void writePgm(QrCode qr, File f) throws IOException {
        int scale = 8, quiet = 4, n = (qr.size + quiet * 2) * scale;
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(("P5\n" + n + " " + n + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            byte[] row = new byte[n];
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int qx = x / scale - quiet, qy = y / scale - quiet;
                    boolean dark = qx >= 0 && qy >= 0 && qx < qr.size && qy < qr.size && qr.get(qx, qy);
                    row[x] = (byte) (dark ? 0 : 255);
                }
                out.write(row);
            }
        }
    }
}
