package com.spatiallauncher.app.ui;

import android.content.Context;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Unpack a tar.bz2 shipped in assets into app files. No network. */
final class BundledArchive {
    private static final String TAG = "BundledArchive";

    static void extractTarBz2(Context context, String assetPath, File destRoot) throws Exception {
        destRoot.mkdirs();
        Log.i(TAG, "Unpacking " + assetPath + " -> " + destRoot);
        try (InputStream raw = new BufferedInputStream(context.getAssets().open(assetPath));
             BZip2CompressorInputStream bz = new BZip2CompressorInputStream(raw);
             TarArchiveInputStream tar = new TarArchiveInputStream(bz)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                File outFile = new File(destRoot, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(destRoot.getCanonicalPath())) {
                    throw new SecurityException("bad tar path " + entry.getName());
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                    continue;
                }
                File parent = outFile.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = tar.read(buf)) >= 0) {
                        fos.write(buf, 0, n);
                    }
                }
            }
        }
    }
}
