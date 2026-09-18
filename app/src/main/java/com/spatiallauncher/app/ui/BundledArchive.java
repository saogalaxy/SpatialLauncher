package com.spatiallauncher.app.ui;

import android.content.Context;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Unpack a tar.bz2 shipped in assets into app files. No network. */
final class BundledArchive {
    private static final String TAG = "BundledArchive";

    static void extractTarBz2(Context context, String assetPath, File destRoot) throws Exception {
        destRoot.mkdirs();
        Log.i(TAG, "Unpacking " + assetPath + " -> " + destRoot);
        try (InputStream raw = new BufferedInputStream(context.getAssets().open(assetPath))) {
            extractTarBz2Stream(raw, destRoot);
        }
    }

    static void extractTarBz2File(File archive, File destRoot) throws Exception {
        destRoot.mkdirs();
        Log.i(TAG, "Unpacking " + archive.getName() + " -> " + destRoot);
        try (InputStream raw = new BufferedInputStream(new java.io.FileInputStream(archive))) {
            extractTarBz2Stream(raw, destRoot);
        }
    }

    /** Keep-filter for selective extraction (matched against the full entry name). */
    interface EntryKeep {
        boolean keep(String entryName);
    }

    static void extractTarBz2Selective(Context context, String assetPath, File destRoot,
            EntryKeep keep) throws Exception {
        destRoot.mkdirs();
        Log.i(TAG, "Unpacking (selective) " + assetPath + " -> " + destRoot);
        try (InputStream raw = new BufferedInputStream(context.getAssets().open(assetPath))) {
            extractTarBz2StreamSelective(raw, destRoot, keep);
        }
    }

    static void extractTarBz2FileSelective(File archive, File destRoot,
            EntryKeep keep) throws Exception {
        destRoot.mkdirs();
        Log.i(TAG, "Unpacking (selective) " + archive.getName() + " -> " + destRoot);
        try (InputStream raw = new BufferedInputStream(new java.io.FileInputStream(archive))) {
            extractTarBz2StreamSelective(raw, destRoot, keep);
        }
    }

    private static void extractTarBz2StreamSelective(InputStream raw, File destRoot,
            EntryKeep keep) throws Exception {
        try (BZip2CompressorInputStream bz = new BZip2CompressorInputStream(raw);
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
                if (!keep.keep(entry.getName())) {
                    continue;
                }
                File parent = outFile.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                long written = 0;
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = tar.read(buf)) >= 0) {
                        fos.write(buf, 0, n);
                        written += n;
                    }
                }
                long expected = entry.getSize();
                if (expected >= 0 && written != expected) {
                    outFile.delete();
                    throw new IOException("short entry " + entry.getName()
                            + " (" + written + "/" + expected + " bytes)");
                }
                Log.i(TAG, "kept " + entry.getName() + " (" + written + " bytes)");
            }
        }
    }

    private static void extractTarBz2Stream(InputStream raw, File destRoot) throws Exception {
        try (BZip2CompressorInputStream bz = new BZip2CompressorInputStream(raw);
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
