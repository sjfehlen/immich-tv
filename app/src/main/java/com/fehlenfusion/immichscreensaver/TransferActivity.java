package com.fehlenfusion.immichscreensaver;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Backup and restore of the settings over adb, so a reinstall (or a change of signing key)
 * does not mean pairing everything again. Not exported and has no intent filter, so only adb
 * (the shell user can start any activity) can run it; apps on the TV cannot.
 *
 * <pre>
 * adb shell mkdir -p /sdcard/Android/data/com.fehlenfusion.immichscreensaver/files/transfer
 * adb shell am start -n com.fehlenfusion.immichscreensaver/.TransferActivity --es mode export
 * adb pull /sdcard/Android/data/com.fehlenfusion.immichscreensaver/files/transfer .
 *
 * adb push transfer /sdcard/Android/data/com.fehlenfusion.immichscreensaver/files/
 * adb shell am start -n com.fehlenfusion.immichscreensaver/.TransferActivity --es mode import
 * </pre>
 *
 * The files hold the API key and tokens in plain text: delete the copies after use. Import
 * deletes the files it read. A result.txt lists what happened per file (never any values).
 */
public final class TransferActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String mode = getIntent().getStringExtra("mode");
        File dir = new File(getExternalFilesDir(null), "transfer");
        StringBuilder log = new StringBuilder();
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("no transfer folder");
            if ("export".equals(mode)) export(dir, log);
            else if ("import".equals(mode)) importAll(dir, log);
            else log.append("mode must be export or import\n");
        } catch (Exception e) {
            log.append("failed: ").append(e.getClass().getSimpleName()).append('\n');
        }
        write(new File(dir, "result.txt"), log.toString().getBytes(StandardCharsets.UTF_8));
        Toast.makeText(this, "Settings " + (mode == null ? "transfer" : mode) + " done", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void export(File dir, StringBuilder log) {
        File prefs = new File(getApplicationInfo().dataDir, "shared_prefs");
        for (String name : PrefsTransfer.FILES) {
            File src = new File(prefs, name + ".xml");
            if (!src.isFile()) {
                log.append("missing ").append(name).append('\n');
                continue;
            }
            try {
                write(new File(dir, name + ".xml"), read(src));
                log.append("exported ").append(name).append('\n');
            } catch (Exception e) {
                log.append("failed ").append(name).append('\n');
            }
        }
    }

    private void importAll(File dir, StringBuilder log) {
        for (String name : PrefsTransfer.FILES) {
            File src = new File(dir, name + ".xml");
            if (!src.isFile()) {
                log.append("skipped ").append(name).append('\n');
                continue;
            }
            try {
                PrefsTransfer.apply(this, name,
                        PrefsTransfer.parse(new String(read(src), StandardCharsets.UTF_8)));
                log.append("imported ").append(name).append('\n');
            } catch (Exception e) {
                log.append("failed ").append(name).append('\n');
            } finally {
                src.delete();
            }
        }
    }

    private static byte[] read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void write(File f, byte[] data) {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        } catch (Exception ignored) {
            // best effort: the result file is only a convenience
        }
    }
}
