package com.spatiallauncher.app.ui;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tiny LAN HTTP receiver for desktop → headset EPUB push.
 * <p>
 * {@code GET /health} — liveness JSON<br>
 * {@code POST /import} — raw EPUB bytes (header {@code X-Filename}, optional {@code X-Import-Token})
 */
final class BookImportHttp {
    private static final String TAG = "BookImportHttp";
    static final int DEFAULT_PORT = 8765;
    static final int DISCOVERY_PORT = 8766;

    interface Listener {
        void onBookImported(File epubFile, String displayName);

        void onServerReady(String lanUrl, String token);

        void onServerError(String message);
    }

    private final Context appContext;
    private final String token;
    private final int port;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService acceptPool = Executors.newSingleThreadExecutor();
    private final ExecutorService workerPool = Executors.newCachedThreadPool();
    private final ScheduledExecutorService beaconPool = Executors.newSingleThreadScheduledExecutor();
    private ServerSocket serverSocket;
    private DatagramSocket beaconSocket;

    BookImportHttp(Context context, String token, int port, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.token = token != null ? token : "";
        this.port = port > 0 ? port : DEFAULT_PORT;
        this.listener = listener;
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        acceptPool.execute(() -> {
            try {
                serverSocket = new ServerSocket(port);
                serverSocket.setReuseAddress(true);
                String url = "http://" + lanIpv4() + ":" + port;
                Log.i(TAG, "Listening on " + url);
                if (listener != null) {
                    listener.onServerReady(url, token);
                }
                startDiscoveryBeacon();
                while (running.get()) {
                    Socket client = serverSocket.accept();
                    workerPool.execute(() -> handleClient(client));
                }
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "Server stopped: " + e.getMessage());
                    if (listener != null) {
                        listener.onServerError(e.getMessage() != null ? e.getMessage() : "import server failed");
                    }
                }
            } finally {
                running.set(false);
                closeQuietly(serverSocket);
            }
        });
    }

    void stop() {
        running.set(false);
        closeQuietly(serverSocket);
        if (beaconSocket != null) {
            beaconSocket.close();
            beaconSocket = null;
        }
        acceptPool.shutdownNow();
        workerPool.shutdownNow();
        beaconPool.shutdownNow();
    }

    private void startDiscoveryBeacon() {
        beaconPool.scheduleWithFixedDelay(() -> {
            if (!running.get()) {
                return;
            }
            try {
                if (beaconSocket == null || beaconSocket.isClosed()) {
                    beaconSocket = new DatagramSocket();
                    beaconSocket.setBroadcast(true);
                }
                String ip = lanIpv4();
                // SLIMPORT1|<ip>|<port>|<token>
                String payload = "SLIMPORT1|" + ip + "|" + port + "|" + token;
                byte[] data = payload.getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(
                        data,
                        data.length,
                        InetAddress.getByName("255.255.255.255"),
                        DISCOVERY_PORT);
                beaconSocket.send(packet);
            } catch (Exception e) {
                Log.w(TAG, "beacon failed: " + e.getMessage());
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    boolean isRunning() {
        return running.get();
    }

    int getPort() {
        return port;
    }

    String lanIpv4() {
        try {
            WifiManager wifi = (WifiManager) appContext.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifi.getConnectionInfo() != null) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return Formatter.formatIpAddress(ip);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return "0.0.0.0";
    }

    private void handleClient(Socket client) {
        try (Socket socket = client;
             InputStream rawIn = new BufferedInputStream(socket.getInputStream());
             OutputStream rawOut = new BufferedOutputStream(socket.getOutputStream())) {
            Request req = readRequest(rawIn);
            if (req == null) {
                writeResponse(rawOut, 400, "text/plain", "bad request");
                return;
            }
            if ("GET".equals(req.method) && ("/health".equals(req.path) || "/".equals(req.path))) {
                String body = "{\"ok\":true,\"app\":\"SpatialLauncher\",\"port\":" + port
                        + ",\"token\":\"" + jsonEscape(token) + "\",\"ip\":\"" + jsonEscape(lanIpv4()) + "\"}";
                writeResponse(rawOut, 200, "application/json", body);
                return;
            }
            if ("POST".equals(req.method) && "/import".equals(req.path)) {
                if (!token.isEmpty()) {
                    String got = req.header("x-import-token");
                    if (got == null || !token.equals(got)) {
                        writeResponse(rawOut, 401, "text/plain", "unauthorized");
                        return;
                    }
                }
                if (req.body == null || req.body.length < 64) {
                    writeResponse(rawOut, 400, "text/plain", "empty body");
                    return;
                }
                String name = req.header("x-filename");
                if (name == null || name.trim().isEmpty()) {
                    name = "book.epub";
                }
                name = sanitizeFilename(name);
                if (!name.toLowerCase(Locale.US).endsWith(".epub")) {
                    writeResponse(rawOut, 415, "text/plain", "only .epub supported");
                    return;
                }
                // ZIP local header signature PK\x03\x04
                if (req.body[0] != 'P' || req.body[1] != 'K') {
                    writeResponse(rawOut, 400, "text/plain", "not an EPUB zip");
                    return;
                }
                File inbox = new File(appContext.getFilesDir(), "epub/inbox");
                if (!inbox.exists() && !inbox.mkdirs()) {
                    writeResponse(rawOut, 500, "text/plain", "inbox mkdir failed");
                    return;
                }
                File out = uniqueFile(inbox, name);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(req.body);
                }
                writeResponse(rawOut, 200, "application/json",
                        "{\"ok\":true,\"file\":\"" + out.getName() + "\"}");
                if (listener != null) {
                    listener.onBookImported(out, out.getName());
                }
                return;
            }
            writeResponse(rawOut, 404, "text/plain", "not found");
        } catch (Exception e) {
            Log.w(TAG, "client error", e);
        }
    }

    private static File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) {
            return candidate;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            candidate = new File(dir, base + "-" + i + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private static String sanitizeFilename(String raw) {
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^a-zA-Z0-9._\\- \\u3040-\\u30ff\\u4e00-\\u9fff]", "_");
        if (name.trim().isEmpty()) {
            return "book.epub";
        }
        return name;
    }

    private static Request readRequest(InputStream in) throws IOException {
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
            if (headerBuf.size() > 64 * 1024) {
                return null;
            }
        }
        String headerText = new String(headerBuf.toByteArray(), StandardCharsets.ISO_8859_1);
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0) {
            return null;
        }
        String[] parts = lines[0].split(" ");
        if (parts.length < 2) {
            return null;
        }
        Request req = new Request();
        req.method = parts[0].toUpperCase(Locale.US);
        String path = parts[1];
        int q = path.indexOf('?');
        req.path = q >= 0 ? path.substring(0, q) : path;
        int contentLength = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            req.headers.put(key, value);
            if ("content-length".equals(key)) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (contentLength < 0 || contentLength > 200 * 1024 * 1024) {
            return null;
        }
        if (contentLength > 0) {
            byte[] body = new byte[contentLength];
            int off = 0;
            while (off < contentLength) {
                int n = in.read(body, off, contentLength - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            if (off != contentLength) {
                return null;
            }
            req.body = body;
        } else {
            req.body = new byte[0];
        }
        return req;
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeResponse(OutputStream out, int code, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK"
                : code == 401 ? "Unauthorized"
                : code == 404 ? "Not Found"
                : code == 415 ? "Unsupported Media Type"
                : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class Request {
        String method;
        String path;
        final java.util.HashMap<String, String> headers = new java.util.HashMap<>();
        byte[] body;

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }
    }
}
