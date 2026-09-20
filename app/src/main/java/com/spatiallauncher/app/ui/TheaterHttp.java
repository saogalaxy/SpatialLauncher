package com.spatiallauncher.app.ui;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spike-only localhost server for the WebXR theater prototype (branch
 * webxr-theater-spike, never main). Serves the three.js page and an MJPEG
 * mirror of the stereo output to the Quest Browser on this headset.
 *
 * <p>{@code GET /theater} — viewer page<br>
 * {@code GET /theater.mjpg} — multipart JPEG of {@link TheaterFrames}
 */
final class TheaterHttp {
    private static final String TAG = "TheaterHttp";
    static final int PORT = 8768;
    private static final String BOUNDARY = "THEATERFRAME";

    private final Context appContext;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService acceptPool = Executors.newSingleThreadExecutor();
    private final ExecutorService workerPool = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private byte[] pageBytes;

    TheaterHttp(Context context) {
        this.appContext = context.getApplicationContext();
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        acceptPool.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                serverSocket.setReuseAddress(true);
                Log.i(TAG, "Theater on http://127.0.0.1:" + PORT + "/theater");
                while (running.get()) {
                    Socket client = serverSocket.accept();
                    // Dead browser tabs (killed renderers, wedged sockets) must not
                    // pile handler threads forever: reads and writes time out.
                    try {
                        client.setSoTimeout(15000);
                    } catch (Throwable ignored) {
                    }
                    workerPool.execute(() -> handleClient(client));
                }
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "server stopped: " + e.getMessage());
                }
            } finally {
                running.set(false);
                if (serverSocket != null) {
                    try {
                        serverSocket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        });
    }

    void stop() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        acceptPool.shutdownNow();
        workerPool.shutdownNow();
    }

    boolean isRunning() {
        return running.get();
    }

    private void handleClient(Socket client) {
        try (Socket socket = client;
             InputStream rawIn = new BufferedInputStream(socket.getInputStream());
             OutputStream rawOut = new BufferedOutputStream(socket.getOutputStream())) {
            String path = readPath(rawIn);
            if (path == null) {
                return;
            }
            if ("/theater".equals(path) || "/theater/".equals(path)) {
                byte[] page = theaterPage();
                if (page == null) {
                    writeHead(rawOut, 500, "text/plain", -1);
                    rawOut.write("page missing".getBytes(StandardCharsets.UTF_8));
                    rawOut.flush();
                    return;
                }
                writeHead(rawOut, 200, "text/html", page.length);
                rawOut.write(page);
                rawOut.flush();
                return;
            }
            if ("/frame.jpg".equals(path)) {
                // Stateless snapshot for fragile viewers: each GET is independent,
                // so a killed renderer/decoder recovers on the next poll instead
                // of hanging on a dead multipart socket. 503 = no frame yet.
                byte[] jpeg = TheaterFrames.latestJpeg();
                if (jpeg == null) {
                    writeHead(rawOut, 503, "text/plain", 13);
                    rawOut.write("no frame yet".getBytes(StandardCharsets.UTF_8));
                    rawOut.flush();
                    return;
                }
                writeHead(rawOut, 200, "image/jpeg", jpeg.length);
                rawOut.write(jpeg);
                rawOut.flush();
                return;
            }
            writeHead(rawOut, 404, "text/plain", 9);
            rawOut.write("not found".getBytes(StandardCharsets.UTF_8));
            rawOut.flush();
        } catch (Exception e) {
            Log.w(TAG, "client error", e);
        }
    }

    private void streamMjpeg(OutputStream out) throws IOException {
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        while (running.get()) {
            byte[] jpeg = TheaterFrames.latestJpeg();
            if (jpeg == null) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            String part = "--" + BOUNDARY + "\r\n"
                    + "Content-Type: image/jpeg\r\n"
                    + "Content-Length: " + jpeg.length + "\r\n\r\n";
            try {
                out.write(part.getBytes(StandardCharsets.ISO_8859_1));
                out.write(jpeg);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            } catch (IOException e) {
                return; // viewer gone
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private byte[] theaterPage() {
        if (pageBytes != null) {
            return pageBytes;
        }
        try (InputStream in = appContext.getAssets().open("theater/index.html");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            pageBytes = out.toByteArray();
            return pageBytes;
        } catch (IOException e) {
            Log.w(TAG, "theater page missing from assets", e);
            return null;
        }
    }

    private static String readPath(InputStream in) throws IOException {
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            headerBuf.write(b);
            if (b == '\r' && (state == 0 || state == 2)) {
                state++;
            } else if (b == '\n' && (state == 1 || state == 3)) {
                state++;
            } else {
                state = 0;
            }
            if (headerBuf.size() > 16 * 1024) {
                return null;
            }
        }
        String[] lines = new String(headerBuf.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
        if (lines.length == 0) {
            return null;
        }
        String[] parts = lines[0].split(" ");
        if (parts.length < 2 || !"GET".equals(parts[0].toUpperCase(Locale.US))) {
            return null;
        }
        String path = parts[1];
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    private static void writeHead(OutputStream out, int code, String contentType, long length)
            throws IOException {
        String status = code == 200 ? "OK" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + (length >= 0 ? "Content-Length: " + length + "\r\n" : "")
                + "Cache-Control: no-cache, no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }
}
