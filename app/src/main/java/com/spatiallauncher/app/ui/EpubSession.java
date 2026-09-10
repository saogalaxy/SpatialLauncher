package com.spatiallauncher.app.ui;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Unpack an EPUB and expose spine chapters as file:// URLs for the in-app WebView. */
final class EpubSession {
    private static final String TAG = "EpubSession";

    final String title;
    final List<String> chapterUrls;
    int index;

    private EpubSession(String title, List<String> chapterUrls) {
        this.title = title == null || title.isEmpty() ? "EPUB" : title;
        this.chapterUrls = chapterUrls;
        this.index = 0;
    }

    boolean hasPrev() {
        return index > 0;
    }

    boolean hasNext() {
        return index + 1 < chapterUrls.size();
    }

    String currentUrl() {
        if (index < 0 || index >= chapterUrls.size()) {
            return null;
        }
        return chapterUrls.get(index);
    }

    static EpubSession open(Context context, Uri uri) throws Exception {
        File root = new File(context.getFilesDir(), "epub/current");
        deleteRecursive(root);
        root.mkdirs();
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IllegalStateException("Could not open EPUB");
            }
            unzip(in, root);
        }
        File container = new File(root, "META-INF/container.xml");
        if (!container.isFile()) {
            throw new IllegalStateException("Not a valid EPUB (missing container.xml)");
        }
        String opfRel = readRootfile(container);
        File opf = new File(root, opfRel);
        if (!opf.isFile()) {
            throw new IllegalStateException("OPF missing: " + opfRel);
        }
        File opfDir = opf.getParentFile();
        ParsedOpf parsed = parseOpf(opf);
        ArrayList<String> urls = new ArrayList<>();
        for (String href : parsed.spineHrefs) {
            File chapter = new File(opfDir, href.split("#")[0]);
            if (chapter.isFile()) {
                urls.add(Uri.fromFile(chapter).toString());
            }
        }
        if (urls.isEmpty()) {
            throw new IllegalStateException("EPUB has no chapters");
        }
        Log.i(TAG, "Opened EPUB " + parsed.title + " chapters=" + urls.size());
        return new EpubSession(parsed.title, urls);
    }

    private static void unzip(InputStream in, File destRoot) throws Exception {
        byte[] buf = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(destRoot, entry.getName());
                if (!out.getCanonicalPath().startsWith(destRoot.getCanonicalPath())) {
                    throw new SecurityException("zip slip " + entry.getName());
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buf)) >= 0) {
                        fos.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static String readRootfile(File container) throws Exception {
        try (InputStream in = new FileInputStream(container)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, "UTF-8");
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "rootfile".equals(parser.getName())) {
                    String full = parser.getAttributeValue(null, "full-path");
                    if (full != null && !full.isEmpty()) {
                        return full;
                    }
                }
                event = parser.next();
            }
        }
        throw new IllegalStateException("container.xml has no rootfile");
    }

    private static ParsedOpf parseOpf(File opf) throws Exception {
        HashMap<String, String> idToHref = new HashMap<>();
        ArrayList<String> spine = new ArrayList<>();
        String title = "";
        try (InputStream in = new FileInputStream(opf)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, "UTF-8");
            int event = parser.getEventType();
            boolean inTitle = false;
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String name = parser.getName();
                    if ("item".equals(name)) {
                        String id = parser.getAttributeValue(null, "id");
                        String href = parser.getAttributeValue(null, "href");
                        if (id != null && href != null) {
                            idToHref.put(id, href);
                        }
                    } else if ("itemref".equals(name)) {
                        String idref = parser.getAttributeValue(null, "idref");
                        String href = idToHref.get(idref);
                        if (href != null) {
                            spine.add(href);
                        }
                    } else if ("title".equals(name)) {
                        inTitle = true;
                    }
                } else if (event == XmlPullParser.TEXT && inTitle) {
                    title = parser.getText();
                    inTitle = false;
                } else if (event == XmlPullParser.END_TAG && "title".equals(parser.getName())) {
                    inTitle = false;
                }
                event = parser.next();
            }
        }
        ParsedOpf parsed = new ParsedOpf();
        parsed.title = title;
        parsed.spineHrefs = spine;
        return parsed;
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] kids = file.listFiles();
        if (kids != null) {
            for (File kid : kids) {
                deleteRecursive(kid);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static final class ParsedOpf {
        String title;
        List<String> spineHrefs;
    }
}
