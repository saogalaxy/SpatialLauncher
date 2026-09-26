package com.spatiallauncher.app.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.spatiallauncher.app.R;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * Thin Quest viewer for Spatial Launcher Desktop: LAN auto-find + JPEG/MPEG SBS +
 * Horizon SIDE_BY_SIDE. No Quest depth / OCR / Listen / Piper on this path —
 * PC owns those pipelines.
 */
public class DesktopLinkActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = "DesktopLink";
    private static final String PREFS = "desktop_link";
    private static final String KEY_URL = "stream_url";
    private static final String KEY_STEREO = "stereo_3d";
    private static final int DISCOVERY_PORT = 8766;

    private enum LinkCodec { JPEG, H264, AV1 }

    /** Set while this activity is alive so the main panel can stay thin. */
    public static volatile boolean activeThinClient;

    private static final int CHIP_ACTIVE = Color.parseColor("#16A34A");
    private static final int CHIP_INACTIVE = Color.parseColor("#333841");
    private static final long CHROME_FADE_MS = 120L;
    /** Hide settings quickly once the stream goes live. */
    private static final long CHROME_LIVE_HIDE_MS = 500L;
    /** After the user opens settings, give them time to use controls. */
    private static final long CHROME_EDIT_HIDE_MS = 8000L;
    private static final long CHROME_HOVER_HIDE_MS = 1500L;
    /** Min keeps settings dismissed this long so hover/tap does not pop them back instantly. */
    private static final long CHROME_MIN_LOCK_MS = 10000L;

    private EditText urlInput;
    private TextView status;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private ToggleButton stereoToggle;
    private ToggleButton live3dToggle;
    private ToggleButton fullSbsToggle;
    private View chrome;
    private View tapCatcher;
    private SeekBar depthSeek;
    private SeekBar convSeek;
    private SeekBar jpegSeek;
    private SeekBar widthSeek;
    private SeekBar sharpenSeek;
    private SeekBar hzSeek;
    private SeekBar smoothSeek;
    private SeekBar edgeSeek;
    private TextView widthValue;
    private TextView jpegValue;
    private TextView sharpenValue;
    private TextView depthValue;
    private TextView convValue;
    private TextView hzValue;
    private TextView smoothValue;
    private TextView edgeValue;
    private Button codecJpegBtn;
    private Button codecMpegBtn;
    private Button codecAv1Btn;
    private Button presetGamingBtn;
    private Button presetMoviesBtn;
    private Button speakScreenBtn;
    private ToggleButton continuousToggle;
    private String selectedCodec = "mjpeg";
    private String selectedPreset = "gaming";
    private volatile boolean applyingRemote;
    /** User/UI wants a live link (survives brief stop during reconnect). */
    private volatile boolean streamDesired;
    /** Pump threads may run only while this is true. */
    private volatile boolean wantStream;
    private volatile boolean running;
    /** Bumped on every stop so orphaned pumps exit even if wantStream flips early. */
    private volatile int streamGen;
    private volatile boolean chromeHidden;
    /** Wall clock until Min allows chrome to show again (blocks hover/tap reopen). */
    private volatile long chromeLockedUntil;
    private volatile boolean reconnectScheduled;
    /** Suppress stall watchdog during codec/mode switches (ms wall clock). */
    private volatile long reconnectGraceUntil;
    private static final long SLOT_FREE_MS = 700L;
    /** Must stay longer than a slow codec/surface handoff so grace does not expire into an immediate stall reconnect. */
    private static final long RECONNECT_GRACE_MS = 45000L;
    private final Object surfaceRecreateLock = new Object();
    private final Runnable hideChromeRunnable = () -> {
        if (streamDesired || running) {
            setChromeVisible(false);
        }
    };
    private final Object jpegLock = new Object();
    private byte[] pendingJpeg;
    private int pendingJpegGen;
    private Thread decodeThread;
    private volatile boolean surfaceReady;
    private volatile boolean stereoApplied;
    private Thread worker;
    private Thread discoverThread;
    private final DesktopLinkAudioReceiver audioReceiver = new DesktopLinkAudioReceiver();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object drawLock = new Object();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap latestFrame;
    private volatile LinkCodec linkCodec = LinkCodec.JPEG;
    private MediaCodec videoDecoder;
    /** Mime currently configured on {@link #videoDecoder}, or null when idle. */
    private volatile String videoDecoderMime;
    private final Object decoderLock = new Object();
    private final Object auLock = new Object();
    private byte[] pendingAu;
    private int pendingAuGen;
    private volatile boolean waitForIdr = true;
    private Thread h264DecodeThread;
    /** JPEG uses lockCanvas; MPEG/AV1 use MediaCodec — switching without a surface recycle blacks the view. */
    private enum SurfaceProducer { NONE, CANVAS, MEDIA_CODEC }
    private volatile SurfaceProducer surfaceProducer = SurfaceProducer.NONE;
    /**
     * When false, never lockCanvas. surfaceCreated/Changed used to redraw the last JPEG onto a
     * freshly recreated MediaCodec surface, leaving BLAST on CPU (cur=2) so configure fails with
     * "already connected (cur=2 req=3)".
     */
    private volatile boolean allowLockCanvas = true;
    private final Object surfaceGate = new Object();
    private int surfaceGeneration;
    private View.OnHoverListener surfaceHoverReveal;
    private volatile String pendingRedirectUrl;
    private volatile byte[] pendingAv1C;
    /** Wall clock of last JPEG/AU received — watchdog reconnects if this stalls. */
    private volatile long lastMediaTick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activeThinClient = true;
        setContentView(R.layout.activity_desktop_link);
        urlInput = findViewById(R.id.desktop_link_url);
        status = findViewById(R.id.desktop_link_menu_status);
        surfaceView = findViewById(R.id.desktop_link_surface);
        chrome = findViewById(R.id.desktop_link_chrome);
        tapCatcher = findViewById(R.id.desktop_link_tap);
        stereoToggle = findViewById(R.id.desktop_link_stereo_3d);
        live3dToggle = findViewById(R.id.desktop_link_live3d);
        fullSbsToggle = findViewById(R.id.desktop_link_full_sbs);
        depthSeek = findViewById(R.id.desktop_link_depth);
        convSeek = findViewById(R.id.desktop_link_convergence);
        jpegSeek = findViewById(R.id.desktop_link_jpeg);
        widthSeek = findViewById(R.id.desktop_link_width);
        sharpenSeek = findViewById(R.id.desktop_link_sharpen);
        hzSeek = findViewById(R.id.desktop_link_hz);
        smoothSeek = findViewById(R.id.desktop_link_smooth);
        edgeSeek = findViewById(R.id.desktop_link_edge);
        widthValue = findViewById(R.id.desktop_link_width_value);
        jpegValue = findViewById(R.id.desktop_link_jpeg_value);
        sharpenValue = findViewById(R.id.desktop_link_sharpen_value);
        depthValue = findViewById(R.id.desktop_link_depth_value);
        convValue = findViewById(R.id.desktop_link_conv_value);
        hzValue = findViewById(R.id.desktop_link_hz_value);
        smoothValue = findViewById(R.id.desktop_link_smooth_value);
        edgeValue = findViewById(R.id.desktop_link_edge_value);
        codecJpegBtn = findViewById(R.id.desktop_link_codec_jpeg);
        codecMpegBtn = findViewById(R.id.desktop_link_codec_mpeg);
        codecAv1Btn = findViewById(R.id.desktop_link_codec_av1);
        presetGamingBtn = findViewById(R.id.desktop_link_preset_gaming);
        presetMoviesBtn = findViewById(R.id.desktop_link_preset_movies);
        findViewById(R.id.desktop_link_min).setOnClickListener(v -> minimizeChrome());
        findViewById(R.id.desktop_link_exit).setOnClickListener(v -> finish());
        Button usbBtn = findViewById(R.id.desktop_link_usb);
        if (usbBtn != null) {
            usbBtn.setOnClickListener(v -> connectUsb());
        }
        // Reader controls (on-screen: controller key events are eaten by the
        // system here — B arrives as Back, A never arrives).
        speakScreenBtn = findViewById(R.id.desktop_link_speak_screen);
        continuousToggle = findViewById(R.id.desktop_link_continuous);
        if (speakScreenBtn != null) {
            speakScreenBtn.setOnClickListener(v -> speakPcFrameOnce());
        }
        if (continuousToggle != null) {
            continuousToggle.setOnClickListener(v -> togglePcContinuous());
        }
        tapCatcher.setOnClickListener(v -> {
            if (chromeHidden) {
                if (System.currentTimeMillis() < chromeLockedUntil) {
                    setStatus(getString(R.string.desktop_link_min_locked));
                    return;
                }
                showChromeTemporary();
            } else if (streamDesired || running) {
                minimizeChrome();
            } else {
                setChromeVisible(true);
            }
        });
        View.OnHoverListener hoverReveal = (v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_MOVE:
                    if (System.currentTimeMillis() < chromeLockedUntil) {
                        break;
                    }
                    showChromeTemporary();
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    scheduleHideChrome(CHROME_HOVER_HIDE_MS);
                    break;
                default:
                    break;
            }
            return false;
        };
        surfaceHoverReveal = hoverReveal;
        surfaceView.setOnHoverListener(hoverReveal);
        tapCatcher.setOnHoverListener(hoverReveal);

        wireRemoteSliders();
        styleCodecChips();
        stylePresetChips();
        updateRemoteLabels();

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_URL, "");
        urlInput.setText(saved);
        boolean stereoOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_STEREO, true);
        stereoToggle.setChecked(stereoOn);

        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        stereoToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_STEREO, isChecked).apply();
            applyStereo(isChecked);
            redrawLatest();
        });

        // Auto-find + connect as soon as this thin viewer opens (monitor icon path).
        startDiscovery(true);
        // Keep retrying discovery until we have a live stream.
        main.postDelayed(discoveryRetryRunnable, 4000);
        main.postDelayed(streamWatchdogRunnable, 3000);
    }

    private final Runnable discoveryRetryRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            boolean workerAlive = worker != null && worker.isAlive();
            if (streamDesired && !workerAlive && !reconnectScheduled) {
                startDiscovery(true);
            } else if (!streamDesired) {
                startDiscovery(true);
            }
            main.postDelayed(this, 5000);
        }
    };

    /** If the TCP pump thread dies, reclaim a PC viewer slot and reconnect.
     * Never force-reconnect while the worker is alive — stall gaps during codec /
     * surface handoff look like silence and were killing mode switches. */
    private final Runnable streamWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (streamDesired && !reconnectScheduled && now >= reconnectGraceUntil) {
                boolean workerAlive = worker != null && worker.isAlive();
                if (!workerAlive) {
                    Log.w(TAG, "watchdog reconnect — pump thread dead");
                    setStatus(getString(R.string.desktop_link_reconnecting));
                    forceReconnect();
                }
            }
            main.postDelayed(this, 2500);
        }
    };

    /**
     * USB link: the PC forwards its :8765 over adb, so the stream lives at
     * Quest localhost. Same path as LAN discovery from here on; audio has no
     * UDP forward and stays on Wi-Fi.
     */
    private void connectUsb() {
        String next = "http://127.0.0.1:8765/" + codecPathSuffix(selectedCodec);
        urlInput.setText(next);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, next).apply();
        setStatus(getString(R.string.desktop_link_usb_hint));
        Log.i(TAG, "USB connect -> " + next);
        probeUsbLink();
        boolean workerAlive = worker != null && worker.isAlive();
        if (!streamDesired || !workerAlive) {
            forceReconnect();
        }
    }

    /** Live indicator: can we actually reach the PC through the USB forward? */
    private void probeUsbLink() {
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("http://127.0.0.1:8765/status").openConnection();
                c.setConnectTimeout(2500);
                c.setReadTimeout(2500);
                c.setRequestMethod("GET");
                if (c.getResponseCode() != 200) {
                    setStatus(getString(R.string.desktop_link_usb_hint));
                    return;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                InputStream in = c.getInputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
                String body = bos.toString("UTF-8");
                boolean live = body.contains("\"sessionActive\":true");
                if (body.contains("\"ok\":true")) {
                    setStatus(live ? getString(R.string.desktop_link_usb_live)
                            : getString(R.string.desktop_link_usb_forward_only));
                } else {
                    setStatus(getString(R.string.desktop_link_usb_hint));
                }
            } catch (Exception e) {
                Log.w(TAG, "USB probe failed", e);
                setStatus(getString(R.string.desktop_link_usb_hint));
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }, "DesktopLinkUsbProbe").start();
    }

    private void startDiscovery(boolean autoConnect) {
        if (discoverThread != null && discoverThread.isAlive()) {
            try {
                discoverThread.interrupt();
            } catch (Exception ignored) {
            }
        }
        setStatus(getString(R.string.desktop_link_searching));
        discoverThread = new Thread(() -> {
            String found = discoverPc(2500);
            main.post(() -> {
                if (found != null && !found.isEmpty()) {
                    urlInput.setText(found);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, found).apply();
                    setStatus(getString(R.string.desktop_link_found, found));
                    if (autoConnect) {
                        boolean workerAlive = worker != null && worker.isAlive();
                        if (!streamDesired || !workerAlive) {
                            forceReconnect();
                        }
                    }
                } else {
                    setStatus(getString(R.string.desktop_link_not_found));
                }
            });
        }, "DesktopLinkDiscover");
        discoverThread.start();
    }

    private static String discoverPc(int timeoutMs) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(350);
            byte[] ping = "SLD?".getBytes(StandardCharsets.UTF_8);
            DatagramPacket out = new DatagramPacket(
                    ping, ping.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < deadline) {
                try {
                    socket.send(out);
                } catch (Exception ignored) {
                }
                try {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    socket.receive(in);
                    String json = new String(in.getData(), 0, in.getLength(), StandardCharsets.UTF_8).trim();
                    if (json.startsWith("SLD?") || json.isEmpty()) {
                        continue;
                    }
                    String stream = parseStreamUrl(json);
                    if (stream != null) {
                        return stream;
                    }
                } catch (SocketTimeoutException ignored) {
                    // keep pinging
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "discover failed", e);
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
        return null;
    }

    private static String parseStreamUrl(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            String app = obj.optString("App", obj.optString("app", ""));
            if (!app.isEmpty() && !"SpatialLauncherDesktop".equals(app)) {
                return null;
            }
            String stream = obj.optString("Stream", obj.optString("stream", ""));
            if (stream.startsWith("http://") || stream.startsWith("https://")) {
                return stream;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    protected void onPause() {
        if (stereoApplied) {
            HorizonStereoComposition.set(surfaceView, false);
            stereoApplied = false;
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        // Leaving for another VR app: stop video pumps + headset audio so the
        // link doesn't burn CPU/GPU/network behind the foreground title.
        // streamDesired stays true, so onResume restarts via startStream().
        new Thread(() -> stopStreamJoin(true), "DesktopLinkBgStop").start();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activeThinClient = true;
        if (running && stereoToggle.isChecked()) {
            surfaceView.post(() -> applyStereo(true));
        }
        // Panel can start stopped (adb / not focused); resume when it becomes visible.
        if (streamDesired && !running && surfaceReady) {
            main.post(this::startStream);
        }
    }

    @Override
    protected void onDestroy() {
        activeThinClient = false;
        main.removeCallbacks(discoveryRetryRunnable);
        main.removeCallbacks(streamWatchdogRunnable);
        main.removeCallbacks(hideChromeRunnable);
        stopStream();
        if (stereoApplied) {
            HorizonStereoComposition.set(surfaceView, false);
            stereoApplied = false;
        }
        synchronized (drawLock) {
            if (latestFrame != null) {
                latestFrame.recycle();
                latestFrame = null;
            }
        }
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        surfaceReady = true;
        synchronized (surfaceGate) {
            surfaceGeneration++;
            surfaceGate.notifyAll();
        }
        if (running && stereoToggle.isChecked()) {
            applyStereo(true);
        }
        // Auto-connect may have raced ahead of the first surface; kick stream now.
        if (streamDesired && !running) {
            main.post(this::startStream);
        }
        redrawLatest();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        synchronized (surfaceGate) {
            surfaceGeneration++;
            surfaceGate.notifyAll();
        }
        if (running && stereoToggle.isChecked()) {
            applyStereo(true);
        }
        redrawLatest();
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        surfaceReady = false;
        if (stereoApplied) {
            HorizonStereoComposition.set(surfaceView, false);
            stereoApplied = false;
        }
    }

    /**
     * lockCanvas and MediaCodec cannot share one SurfaceView producer. On codec
     * switches, replace the SurfaceView so BLAST starts clean (visibility toggles
     * are unreliable on Horizon when going canvas→MediaCodec).
     */
    private void ensureSurfaceProducer(SurfaceProducer next) {
        synchronized (surfaceRecreateLock) {
            ensureSurfaceProducerLocked(next);
        }
    }

    private void ensureSurfaceProducerLocked(SurfaceProducer next) {
        if (next == SurfaceProducer.NONE) {
            surfaceProducer = next;
            return;
        }
        if (next == SurfaceProducer.MEDIA_CODEC) {
            // Must arm before any surfaceCreated/Changed callback from recreate.
            setCanvasDrawAllowed(false);
        } else if (next == SurfaceProducer.CANVAS) {
            setCanvasDrawAllowed(true);
        }
        boolean surfaceOk = isSurfaceValid();
        if (surfaceProducer == next && surfaceOk) {
            surfaceReady = true;
            return;
        }
        // Cold start: first MediaCodec use on the layout SurfaceView — keep it.
        if (surfaceProducer == SurfaceProducer.NONE && next == SurfaceProducer.MEDIA_CODEC && surfaceOk) {
            surfaceProducer = next;
            surfaceReady = true;
            Log.i(TAG, "surface producer cold→MEDIA_CODEC");
            return;
        }
        // Same producer already active: never tear down the SurfaceView on mode switch.
        // Waiting out a brief invalid surface is enough; recreate was stacking pumps
        // (MEDIA_CODEC→MEDIA_CODEC gen unchanged → black / interrupted av1c).
        if (surfaceProducer == next) {
            long waitUntil = System.currentTimeMillis() + 5000;
            while (wantStream && !Thread.currentThread().isInterrupted()
                    && !isSurfaceValid() && System.currentTimeMillis() < waitUntil) {
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (isSurfaceValid()) {
                surfaceReady = true;
                return;
            }
            surfaceReady = false;
            Log.w(TAG, "surface still invalid for " + next + " — pump will retry (no recreate)");
            return;
        }
        SurfaceProducer from = surfaceProducer;
        Log.i(TAG, "surface producer " + from + "→" + next + " (recreate)");
        releaseDecoder();
        final int genBefore = surfaceGeneration;
        final Object done = new Object();
        final boolean[] finished = {false};
        main.post(() -> {
            try {
                recreateSurfaceViewUnlocked();
            } finally {
                synchronized (done) {
                    finished[0] = true;
                    done.notifyAll();
                }
            }
        });
        long waitUntil = System.currentTimeMillis() + 1200;
        synchronized (done) {
            while (!finished[0] && wantStream && !Thread.currentThread().isInterrupted()
                    && System.currentTimeMillis() < waitUntil) {
                try {
                    done.wait(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        waitUntil = System.currentTimeMillis() + 2500;
        synchronized (surfaceGate) {
            while (wantStream && !Thread.currentThread().isInterrupted()
                    && surfaceGeneration == genBefore && System.currentTimeMillis() < waitUntil) {
                try {
                    surfaceGate.wait(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        waitUntil = System.currentTimeMillis() + 1500;
        while (wantStream && !Thread.currentThread().isInterrupted()
                && !isSurfaceValid() && System.currentTimeMillis() < waitUntil) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (isSurfaceValid()) {
            surfaceProducer = next;
            surfaceReady = true;
        } else {
            surfaceReady = false;
            Log.w(TAG, "surface recreate incomplete " + from + "→" + next
                    + " gen=" + surfaceGeneration + "/" + genBefore);
        }
    }

    /** Must run on the main thread. */
    private void recreateSurfaceViewUnlocked() {
        if (stereoApplied) {
            HorizonStereoComposition.set(surfaceView, false);
            stereoApplied = false;
        }
        ViewGroup parent = (ViewGroup) surfaceView.getParent();
        if (parent == null) {
            return;
        }
        int index = parent.indexOfChild(surfaceView);
        ViewGroup.LayoutParams lp = surfaceView.getLayoutParams();
        if (surfaceHolder != null) {
            try {
                surfaceHolder.removeCallback(this);
            } catch (Exception ignored) {
            }
        }
        parent.removeView(surfaceView);
        SurfaceView fresh = new SurfaceView(this);
        fresh.setId(R.id.desktop_link_surface);
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
        surfaceView = fresh;
        surfaceHolder = fresh.getHolder();
        surfaceHolder.addCallback(this);
        parent.addView(fresh, Math.max(0, index), lp);
        if (surfaceHoverReveal != null) {
            fresh.setOnHoverListener(surfaceHoverReveal);
        }
        surfaceReady = false;
        if (stereoToggle != null && stereoToggle.isChecked()) {
            applyStereo(true);
        }
    }

    private boolean isSurfaceValid() {
        return surfaceHolder != null
                && surfaceHolder.getSurface() != null
                && surfaceHolder.getSurface().isValid();
    }

    private void applyStereo(boolean sideBySide) {
        boolean ok = HorizonStereoComposition.set(surfaceView, sideBySide);
        stereoApplied = sideBySide && ok;
        if (sideBySide && !ok) {
            setStatus(getString(R.string.desktop_link_stereo_unavailable));
            return;
        }
        // Fixed-size buffers are for lockCanvas (JPEG). MediaCodec needs the native
        // stream resolution — pinning to panel size softens MPEG/AV1 in 3D.
        if (surfaceProducer == SurfaceProducer.MEDIA_CODEC || !allowLockCanvas) {
            return;
        }
        int w = surfaceView.getWidth();
        int h = surfaceView.getHeight();
        if (w > 0 && h > 0 && surfaceHolder != null) {
            surfaceHolder.setFixedSize(w, h == 1 ? 2 : h - 1);
            surfaceHolder.setFixedSize(w, h);
        }
    }

    private void minimizeChrome() {
        main.removeCallbacks(hideChromeRunnable);
        chromeLockedUntil = System.currentTimeMillis() + CHROME_MIN_LOCK_MS;
        setChromeVisible(false);
        setStatus(getString(R.string.desktop_link_min_hint));
    }

    private void setChromeVisible(boolean visible) {
        if (chrome == null) {
            return;
        }
        if (visible && System.currentTimeMillis() < chromeLockedUntil) {
            return;
        }
        chrome.animate().cancel();
        if (visible) {
            chromeHidden = false;
            chromeLockedUntil = 0;
            chrome.setVisibility(View.VISIBLE);
            chrome.animate()
                    .alpha(1f)
                    .setDuration(CHROME_FADE_MS)
                    .withEndAction(null)
                    .start();
        } else {
            chromeHidden = true;
            chrome.animate()
                    .alpha(0f)
                    .setDuration(CHROME_FADE_MS)
                    .withEndAction(() -> {
                        if (chromeHidden) {
                            chrome.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    private void showChromeTemporary() {
        if (System.currentTimeMillis() < chromeLockedUntil) {
            return;
        }
        setChromeVisible(true);
        main.removeCallbacks(hideChromeRunnable);
        // Stay open long enough to tweak knobs; Min locks hide for CHROME_MIN_LOCK_MS.
        if (streamDesired || running) {
            main.postDelayed(hideChromeRunnable, CHROME_EDIT_HIDE_MS);
        }
    }

    /** Call when the stream first goes live — tuck settings away fast. */
    private void hideChromeSoonAfterLive() {
        main.removeCallbacks(hideChromeRunnable);
        main.postDelayed(hideChromeRunnable, CHROME_LIVE_HIDE_MS);
    }

    private void scheduleHideChrome(long delayMs) {
        main.removeCallbacks(hideChromeRunnable);
        if (wantStream || running) {
            main.postDelayed(hideChromeRunnable, delayMs);
        } else {
            setChromeVisible(true);
        }
    }

    private void forceReconnect() {
        if (reconnectScheduled) {
            return;
        }
        reconnectScheduled = true;
        streamDesired = true;
        reconnectGraceUntil = System.currentTimeMillis() + RECONNECT_GRACE_MS;
        lastMediaTick = System.currentTimeMillis();
        // Do NOT set wantStream=true here — orphaned pumps must stay stopped until startStream.
        stopStreamJoin();
        updateConnectButtonLabel();
        setStatus(getString(R.string.desktop_link_reconnecting));
        new Thread(() -> {
            try {
                Thread.sleep(SLOT_FREE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reconnectScheduled = false;
                return;
            }
            if (!streamDesired || isFinishing() || isDestroyed()) {
                reconnectScheduled = false;
                return;
            }
            main.post(() -> {
                reconnectScheduled = false;
                if (streamDesired && !isFinishing() && !isDestroyed()) {
                    startStream();
                }
            });
        }, "DesktopLinkReconnect").start();
    }

    private void noteMedia() {
        lastMediaTick = System.currentTimeMillis();
    }

    /** Stop pumps and wait briefly so the PC viewer slot frees before reconnect. */
    private void stopStreamJoin() {
        stopStreamJoin(false);
    }

    /**
     * @param stopAudio when false (reconnect / codec switch), keep Opus UDP alive so
     *                  mode switches do not tear down headset audio or race the surface.
     */
    private void stopStreamJoin(boolean stopAudio) {
        streamGen++;
        wantStream = false;
        running = false;
        synchronized (jpegLock) {
            pendingJpeg = null;
            pendingJpegGen++;
            jpegLock.notifyAll();
        }
        synchronized (auLock) {
            pendingAu = null;
            pendingAuGen++;
            auLock.notifyAll();
        }
        Thread w = worker;
        Thread d = decodeThread;
        Thread h = h264DecodeThread;
        worker = null;
        decodeThread = null;
        h264DecodeThread = null;
        interruptQuiet(w);
        interruptQuiet(d);
        interruptQuiet(h);
        joinQuiet(w, 1200);
        joinQuiet(d, 600);
        joinQuiet(h, 600);
        if (stopAudio) {
            try {
                audioReceiver.stop();
            } catch (Exception ignored) {
            }
        } else if (audioReceiver.isRunning()) {
            // Video restarted; keep the socket but drop backlog so headset audio
            // does not stay a beat behind the new stream.
            try {
                audioReceiver.flush();
            } catch (Exception ignored) {
            }
        }
        releaseDecoder();
        // Keep surfaceProducer as-is. Resetting to NONE made JPEG→AV1 look like a
        // cold start and skip the canvas→MediaCodec recycle, which drops the stream.
        if (stereoApplied) {
            HorizonStereoComposition.set(surfaceView, false);
            stereoApplied = false;
        }
    }

    private static void interruptQuiet(Thread t) {
        if (t == null) return;
        try {
            t.interrupt();
        } catch (Exception ignored) {
        }
    }

    private static void joinQuiet(Thread t, long ms) {
        if (t == null) return;
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void updateConnectButtonLabel() {
        // Find/Connect UI removed — monitor icon auto-connects; label unused.
    }

    private void probeSessionStatus(String streamUrl) {
        new Thread(() -> {
            try {
                URL stream = new URL(streamUrl);
                String statusUrl = stream.getProtocol() + "://" + stream.getAuthority() + "/status";
                HttpURLConnection conn = (HttpURLConnection) new URL(statusUrl).openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("GET");
                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    return;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                InputStream in = conn.getInputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    bos.write(buf, 0, n);
                }
                conn.disconnect();
                JSONObject o = new JSONObject(bos.toString(StandardCharsets.UTF_8.name()));
                if (!o.optBoolean("sessionActive", true)) {
                    setStatus(getString(R.string.desktop_link_waiting_session));
                }
            } catch (Exception e) {
                Log.w(TAG, "status probe failed", e);
            }
        }, "DesktopLinkStatus").start();
    }

    private void startStream() {
        String url = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
        if (url.isEmpty()) {
            status.setText(R.string.desktop_link_need_url);
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, url).apply();
        stopStreamJoin();
        streamDesired = true;
        wantStream = true;
        running = true;
        final int gen = streamGen;
        lastMediaTick = System.currentTimeMillis();
        reconnectGraceUntil = System.currentTimeMillis() + RECONNECT_GRACE_MS;
        linkCodec = detectCodec(url);
        // Arm before stereo setFixedSize → surfaceChanged, which used to lockCanvas
        // the MediaCodec surface with a leftover JPEG and poison BLAST.
        setCanvasDrawAllowed(linkCodec == LinkCodec.JPEG);
        updateConnectButtonLabel();
        status.setText(R.string.desktop_link_connecting);
        probeSessionStatus(url);
        if (surfaceReady && stereoToggle.isChecked()) {
            applyStereo(true);
        }
        if (linkCodec != LinkCodec.JPEG) {
            waitForIdr = true;
            h264DecodeThread = new Thread(() -> compressedDecodeLoop(gen), "DesktopLinkAuDec");
            h264DecodeThread.start();
            worker = new Thread(() -> pumpCompressed(url, gen), "DesktopLinkCompressed");
        } else {
            decodeThread = new Thread(() -> decodeLoop(gen), "DesktopLinkDecode");
            decodeThread.start();
            worker = new Thread(() -> pumpMjpeg(url, gen), "DesktopLinkMjpeg");
        }
        worker.start();
        // Opus UDP: start once; keep across video reconnect / codec / Gaming↔Movies.
        if (!audioReceiver.isRunning()) {
            try {
                audioReceiver.start();
            } catch (Exception e) {
                Log.w(TAG, "audio start failed", e);
            }
            postAudioHeadsetPreference();
        }
    }

    private void postAudioHeadsetPreference() {
        new Thread(() -> {
            try {
                String url = settingsUrl();
                if (url == null) {
                    return;
                }
                JSONObject body = new JSONObject();
                body.put("audio", "headset");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(2000);
                c.setReadTimeout(2000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                c.getOutputStream().write(bytes);
                c.getResponseCode();
                c.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "audio preference POST failed", e);
            }
        }, "DesktopLinkAudioPref").start();
    }

    private static LinkCodec detectCodec(String url) {
        String u = url.toLowerCase();
        if (u.contains(".av1") || u.contains("av1") || u.contains("av01")) {
            return LinkCodec.AV1;
        }
        if (u.contains(".h264") || u.contains("h264") || u.contains("mpeg")) {
            return LinkCodec.H264;
        }
        return LinkCodec.JPEG;
    }

    private boolean discoveryMatchesSelectedCodec(String url) {
        LinkCodec found = detectCodec(url);
        if ("av1".equals(selectedCodec)) {
            return found == LinkCodec.AV1;
        }
        if ("h264".equals(selectedCodec)) {
            return found == LinkCodec.H264;
        }
        return found == LinkCodec.JPEG;
    }

    private static String codecLabel(LinkCodec codec) {
        switch (codec) {
            case AV1:
                return "AV1";
            case H264:
                return "MPEG";
            default:
                return "JPEG";
        }
    }

    private void stopStream() {
        streamDesired = false;
        reconnectScheduled = false;
        stopStreamJoin(true);
        main.removeCallbacks(hideChromeRunnable);
        main.post(() -> {
            if (!streamDesired) {
                updateConnectButtonLabel();
                status.setText(R.string.desktop_link_idle);
                setChromeVisible(true);
            }
        });
    }


    private void pumpMjpeg(String urlString, int gen) {
        int failStreak = 0;
        while (wantStream && streamGen == gen && !Thread.currentThread().isInterrupted()) {
            running = true;
            // Prefer the latest advertised URL in case PC switched codecs —
            // but never let a stale beacon undo the codec we just selected.
            String fresh = discoverPc(900);
            if (fresh != null && !fresh.isEmpty() && discoveryMatchesSelectedCodec(fresh)) {
                urlString = fresh;
                String finalUrl = fresh;
                main.post(() -> {
                    urlInput.setText(finalUrl);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, finalUrl).apply();
                });
            }
            if (!wantStream || streamGen != gen) {
                break;
            }
            LinkCodec detected = detectCodec(urlString);
            if (detected != LinkCodec.JPEG) {
                linkCodec = detected;
                setCanvasDrawAllowed(false);
                waitForIdr = true;
                if (h264DecodeThread == null || !h264DecodeThread.isAlive()) {
                    h264DecodeThread = new Thread(() -> compressedDecodeLoop(gen), "DesktopLinkAuDec");
                    h264DecodeThread.start();
                }
                pumpCompressed(urlString, gen);
                return;
            }
            linkCodec = LinkCodec.JPEG;
            setCanvasDrawAllowed(true);
            long before = lastMediaTick;
            readMjpegOnce(urlString);
            if (streamGen != gen) {
                break;
            }
            if (pendingRedirectUrl != null) {
                urlString = pendingRedirectUrl;
                pendingRedirectUrl = null;
                failStreak = 0;
                continue;
            }
            if (!wantStream) {
                break;
            }
            if (lastMediaTick > before) {
                failStreak = 0;
            } else {
                failStreak++;
            }
            setStatus(getString(R.string.desktop_link_reconnecting));
            long sleepMs = Math.min(2500L, 450L * Math.max(1, failStreak));
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (streamGen == gen) {
            running = false;
        }
        main.post(() -> {
            if (!streamDesired) {
                updateConnectButtonLabel();
            }
        });
    }

    private void pumpCompressed(String urlString, int gen) {
        int failStreak = 0;
        while (wantStream && streamGen == gen && !Thread.currentThread().isInterrupted()) {
            running = true;
            // Prefer the latest advertised URL in case PC switched codecs —
            // but never let a stale beacon undo the codec we just selected.
            String fresh = discoverPc(900);
            if (fresh != null && !fresh.isEmpty() && discoveryMatchesSelectedCodec(fresh)) {
                urlString = fresh;
                String finalUrl = fresh;
                main.post(() -> {
                    urlInput.setText(finalUrl);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, finalUrl).apply();
                });
            }
            if (!wantStream || streamGen != gen) {
                break;
            }
            LinkCodec detected = detectCodec(urlString);
            if (detected == LinkCodec.JPEG) {
                linkCodec = LinkCodec.JPEG;
                setCanvasDrawAllowed(true);
                if (decodeThread == null || !decodeThread.isAlive()) {
                    decodeThread = new Thread(() -> decodeLoop(gen), "DesktopLinkDecode");
                    decodeThread.start();
                }
                pumpMjpeg(urlString, gen);
                return;
            }
            linkCodec = detected;
            setCanvasDrawAllowed(false);
            long before = lastMediaTick;
            readCompressedOnce(urlString, detected);
            if (streamGen != gen) {
                break;
            }
            if (pendingRedirectUrl != null) {
                // Path/codec change — drop decoder so the next mime can configure cleanly.
                releaseDecoder();
                urlString = pendingRedirectUrl;
                pendingRedirectUrl = null;
                failStreak = 0;
                continue;
            }
            if (!wantStream) {
                break;
            }
            if (lastMediaTick > before) {
                failStreak = 0;
            } else {
                failStreak++;
            }
            setStatus(getString(R.string.desktop_link_reconnecting));
            long sleepMs = Math.min(3000L, 600L * Math.max(1, failStreak));
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (streamGen == gen) {
            running = false;
            releaseDecoder();
        }
        main.post(() -> {
            if (!streamDesired) {
                updateConnectButtonLabel();
            }
        });
    }

    private void readCompressedOnce(String urlString, LinkCodec codec) {
        // Wait for a live surface before claiming a PC viewer slot / recycling buffers.
        long surfaceDeadline = System.currentTimeMillis() + 8000;
        while (wantStream && (!surfaceReady || surfaceHolder == null
                || surfaceHolder.getSurface() == null || !surfaceHolder.getSurface().isValid())
                && System.currentTimeMillis() < surfaceDeadline) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                return;
            }
        }
        ensureSurfaceProducer(SurfaceProducer.MEDIA_CODEC);
        if (codec == LinkCodec.AV1) {
            fetchAv1CodecConfig(urlString);
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "*/*");
            conn.connect();
            int code = conn.getResponseCode();
            if (code == 409 || code == 503) {
                handleStreamRedirect(conn, code);
                return;
            }
            if (code != 200) {
                setStatus("HTTP " + code);
                return;
            }
            String label = codecLabel(codec);
            noteMedia();
            setStatus(stereoToggle.isChecked()
                    ? getString(R.string.desktop_link_live_3d) + " · " + label
                    : getString(R.string.desktop_link_live) + " · " + label);
            main.post(() -> {
                hideChromeSoonAfterLive();
                refreshRemoteSettings();
            });
            if (stereoToggle.isChecked()) {
                final Object gate = new Object();
                final boolean[] done = {false};
                main.post(() -> {
                    applyStereo(true);
                    synchronized (gate) {
                        done[0] = true;
                        gate.notifyAll();
                    }
                });
                long waitUntil = System.currentTimeMillis() + 1500;
                synchronized (gate) {
                    while (!done[0] && System.currentTimeMillis() < waitUntil) {
                        try {
                            gate.wait(50);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
            long deadline = System.currentTimeMillis() + 4000;
            while (wantStream && (!surfaceReady || surfaceHolder == null
                    || surfaceHolder.getSurface() == null || !surfaceHolder.getSurface().isValid())
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!surfaceReady || surfaceHolder == null
                    || surfaceHolder.getSurface() == null || !surfaceHolder.getSurface().isValid()) {
                Log.w(TAG, label + " surface not ready after wait producer=" + surfaceProducer);
                setStatus(label + " surface not ready");
                return;
            }
            // Reuse a live decoder — releasing every HTTP reconnect left the Quest
            // BLAST surface "already connected" and mode/codec switches went black.
            if (!ensureDecoder(codec)) {
                setStatus(label + " decoder unavailable");
                return;
            }
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(conn.getInputStream(), 512 * 1024));
            int auCount = 0;
            int dropped = 0;
            while (wantStream && !Thread.currentThread().isInterrupted()) {
                int len = in.readInt();
                if (len <= 0 || len > 4 * 1024 * 1024) {
                    Log.w(TAG, "bad AU length " + len);
                    break;
                }
                byte[] au = new byte[len];
                in.readFully(au);
                noteMedia();
                synchronized (auLock) {
                    if (pendingAu != null) {
                        dropped++;
                    }
                    pendingAu = au;
                    pendingAuGen++;
                    auLock.notifyAll();
                }
                auCount++;
                if (auCount == 1 || auCount % 120 == 0) {
                    Log.i(TAG, label + " AU#" + auCount + " bytes=" + len + " dropped=" + dropped);
                    dropped = 0;
                }
            }
        } catch (Exception e) {
            if (wantStream) {
                Log.w(TAG, "compressed stream failed", e);
                setStatus(e.getMessage() != null ? e.getMessage() : codecLabel(codec) + " stream failed");
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void compressedDecodeLoop(int gen) {
        int lastGen = -1;
        while (wantStream && streamGen == gen && !Thread.currentThread().isInterrupted()) {
            byte[] au;
            int auGen;
            synchronized (auLock) {
                while (wantStream && streamGen == gen
                        && (pendingAu == null || pendingAuGen == lastGen)) {
                    try {
                        auLock.wait(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!wantStream || streamGen != gen) {
                    return;
                }
                au = pendingAu;
                auGen = pendingAuGen;
                pendingAu = null;
            }
            if (au == null) {
                continue;
            }
            synchronized (auLock) {
                if (pendingAuGen != auGen && pendingAu != null) {
                    waitForIdr = true;
                    continue;
                }
                if (auGen > lastGen + 1) {
                    waitForIdr = true;
                }
            }
            lastGen = auGen;
            LinkCodec codec = linkCodec;
            boolean keyish = codec == LinkCodec.AV1
                    ? isAv1KeyframeOrConfig(au)
                    : isIdrOrConfigAu(au);
            if (waitForIdr && !keyish) {
                continue;
            }
            waitForIdr = false;
            feedDecoder(au);
        }
    }

    /** Annex-B AU contains IDR (5), SPS (7), or PPS (8). */
    private static boolean isIdrOrConfigAu(byte[] au) {
        int i = 0;
        while (i + 4 < au.length) {
            int sc = 0;
            if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1) {
                sc = 3;
            } else if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 0 && au[i + 3] == 1) {
                sc = 4;
            } else {
                i++;
                continue;
            }
            int nalType = au[i + sc] & 0x1F;
            if (nalType == 5 || nalType == 7 || nalType == 8) {
                return true;
            }
            i += sc + 1;
        }
        return false;
    }

    /** AV1 LOBF: sequence header or key frame. */
    private static boolean isAv1KeyframeOrConfig(byte[] au) {
        int i = 0;
        while (i < au.length) {
            int b = au[i] & 0xFF;
            if ((b & 0x80) != 0) return false;
            int type = (b >> 3) & 0x0F;
            boolean hasSize = (b & 0x02) != 0;
            boolean extension = (b & 0x04) != 0;
            i++;
            if (extension) {
                if (i >= au.length) break;
                i++;
            }
            long obuSize = au.length - i;
            if (hasSize) {
                obuSize = 0;
                for (int shift = 0; i < au.length; shift += 7) {
                    int nb = au[i++] & 0xFF;
                    obuSize |= (long) (nb & 0x7F) << shift;
                    if ((nb & 0x80) == 0) break;
                }
            }
            if (type == 1) { // SEQUENCE_HEADER
                return true;
            }
            // FRAME_HEADER (3) or FRAME (6): show_existing_frame + frame_type
            if ((type == 3 || type == 6) && i < au.length) {
                int first = au[i] & 0xFF;
                boolean showExisting = (first & 0x80) != 0;
                if (!showExisting) {
                    int frameType = (first >> 5) & 0x03;
                    if (frameType == 0) { // KEY_FRAME
                        return true;
                    }
                }
            }
            if (obuSize < 0 || i + obuSize > au.length) break;
            i += (int) obuSize;
            if (!hasSize) break;
        }
        return false;
    }

    private void fetchAv1CodecConfig(String streamUrl) {
        pendingAv1C = null;
        try {
            URL u = new URL(streamUrl);
            String statusUrl = u.getProtocol() + "://" + u.getAuthority() + "/status";
            HttpURLConnection conn = (HttpURLConnection) new URL(statusUrl).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
            conn.disconnect();
            JSONObject o = new JSONObject(bos.toString(StandardCharsets.UTF_8.name()));
            String b64 = o.optString("av1c", "");
            if (!b64.isEmpty()) {
                byte[] csd = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                // Reject bloated/corrupt av1c (real config is typically <2KB).
                if (csd != null && csd.length > 8 && csd.length < 2048) {
                    pendingAv1C = csd;
                    Log.i(TAG, "AV1 csd-0 av1c bytes=" + pendingAv1C.length);
                } else {
                    pendingAv1C = null;
                    Log.w(TAG, "AV1 av1c rejected size=" + (csd == null ? -1 : csd.length));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "av1c fetch failed", e);
        }
    }

    private void handleStreamRedirect(HttpURLConnection conn, int code) {
        try {
            InputStream err = conn.getErrorStream();
            if (err == null) err = conn.getInputStream();
            if (err != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = err.read(buf)) >= 0) {
                    bos.write(buf, 0, n);
                }
                JSONObject o = new JSONObject(bos.toString(StandardCharsets.UTF_8.name()));
                String stream = o.optString("stream", "");
                String codec = o.optString("codec", "");
                if (!stream.isEmpty()) {
                    String base = settingsAuthority();
                    if (base != null) {
                        String next = base + (stream.startsWith("/") ? stream : "/" + stream);
                        pendingRedirectUrl = next;
                        main.post(() -> {
                            urlInput.setText(next);
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, next).apply();
                        });
                        Log.i(TAG, "stream redirect HTTP " + code + " → " + next);
                    }
                }
                if (!codec.isEmpty()) {
                    selectedCodec = codec;
                    main.post(this::styleCodecChips);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "redirect parse failed", e);
        }
        if (code == 503) {
            try {
                // Give the PC time to drop a zombie viewer before we claim a slot.
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String settingsAuthority() {
        String stream = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
        if (stream.isEmpty()) return null;
        try {
            URL u = new URL(stream);
            return u.getProtocol() + "://" + u.getAuthority();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean ensureDecoder(LinkCodec codec) {
        String wantMime = codec == LinkCodec.AV1
                ? MediaFormat.MIMETYPE_VIDEO_AV1
                : MediaFormat.MIMETYPE_VIDEO_AVC;
        synchronized (decoderLock) {
            if (videoDecoder != null && wantMime.equals(videoDecoderMime)) {
                return true;
            }
            if (videoDecoder != null) {
                releaseDecoderLocked();
            }
            Surface surface = surfaceHolder != null ? surfaceHolder.getSurface() : null;
            if (surface == null || !surface.isValid()) {
                Log.w(TAG, "decoder init skipped: surface invalid");
                return false;
            }
            if (configureDecoderLocked(codec, wantMime, surface)) {
                return true;
            }
            Log.w(TAG, "decoder configure failed — recovering surface");
        }
        if (!recoverMediaCodecSurface()) {
            return false;
        }
        synchronized (decoderLock) {
            Surface surface = surfaceHolder != null ? surfaceHolder.getSurface() : null;
            if (surface == null || !surface.isValid()) {
                return false;
            }
            return configureDecoderLocked(codec, wantMime, surface);
        }
    }

    private boolean configureDecoderLocked(LinkCodec codec, String mime, Surface surface) {
        MediaCodec decoder = null;
        try {
            // SBS stream is typically ~1920x540–1080; allow adaptive size.
            MediaFormat format = MediaFormat.createVideoFormat(mime, 1920, 1080);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
            try {
                format.setInteger(MediaFormat.KEY_MAX_WIDTH, 3840);
                format.setInteger(MediaFormat.KEY_MAX_HEIGHT, 2160);
            } catch (Exception ignored) {
            }
            try {
                format.setInteger("low-latency", 1);
            } catch (Exception ignored) {
            }
            try {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            } catch (Exception ignored) {
            }
            try {
                format.setFloat(MediaFormat.KEY_OPERATING_RATE, 120f);
            } catch (Exception ignored) {
            }
            if (codec == LinkCodec.AV1 && pendingAv1C != null && pendingAv1C.length > 0) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(pendingAv1C));
            }
            decoder = createVideoDecoder(mime, codec == LinkCodec.AV1);
            decoder.configure(format, surface, null, 0);
            decoder.start();
            videoDecoder = decoder;
            videoDecoderMime = mime;
            Log.i(TAG, "decoder started mime=" + mime
                    + " csd0=" + (pendingAv1C != null ? pendingAv1C.length : 0));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "decoder configure failed", e);
            if (decoder != null) {
                try {
                    decoder.release();
                } catch (Exception ignored) {
                }
            }
            videoDecoder = null;
            videoDecoderMime = null;
            return false;
        }
    }

    private void setCanvasDrawAllowed(boolean allowed) {
        allowLockCanvas = allowed;
        if (!allowed) {
            clearLatestFrame();
        }
    }

    private void clearLatestFrame() {
        synchronized (drawLock) {
            if (latestFrame != null) {
                latestFrame.recycle();
                latestFrame = null;
            }
        }
    }

    /**
     * Force a real SurfaceView replace so BLAST drops a sticky MediaCodec consumer.
     * Used only when configure fails with "already connected".
     */
    private boolean recoverMediaCodecSurface() {
        synchronized (surfaceRecreateLock) {
            releaseDecoder();
            // Pretend we were on CANVAS so MEDIA_CODEC triggers recreate (same-producer
            // path intentionally never tears down the view).
            surfaceProducer = SurfaceProducer.CANVAS;
            ensureSurfaceProducerLocked(SurfaceProducer.MEDIA_CODEC);
            return isSurfaceValid();
        }
    }

    private static MediaCodec createVideoDecoder(String mime, boolean av1) throws Exception {
        if (av1) {
            String[] preferred = {
                    "c2.qti.av1.decoder.low_latency",
                    "c2.qti.av1.decoder",
                    "c2.android.av1-dav1d.decoder",
                    "c2.android.av1.decoder"
            };
            for (String name : preferred) {
                try {
                    MediaCodec c = MediaCodec.createByCodecName(name);
                    Log.i(TAG, "using decoder " + name);
                    return c;
                } catch (Exception ignored) {
                }
            }
        }
        return MediaCodec.createDecoderByType(mime);
    }

    private void feedDecoder(byte[] au) {
        MediaCodec codec;
        synchronized (decoderLock) {
            codec = videoDecoder;
        }
        if (codec == null) {
            return;
        }
        try {
            // Never block waiting for an input slot — drop and stay on the latest AU.
            int inIndex = codec.dequeueInputBuffer(0);
            if (inIndex < 0) {
                waitForIdr = true;
                return;
            }
            ByteBuffer buf = codec.getInputBuffer(inIndex);
            if (buf == null) {
                return;
            }
            buf.clear();
            if (au.length > buf.remaining()) {
                Log.w(TAG, "AU too large for input buffer");
                codec.queueInputBuffer(inIndex, 0, 0, 0, 0);
            } else {
                // With csd-0 set, do not mark in-band AUs as CODEC_CONFIG — that
                // confuses QTI AV1. Sequence headers stay in the elementary stream.
                buf.put(au);
                codec.queueInputBuffer(inIndex, 0, au.length, System.nanoTime() / 1000L, 0);
            }
            // Drain outputs; only render the last buffer so the surface stays current.
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int lastOut = -1;
            int outIndex;
            while ((outIndex = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                if (lastOut >= 0) {
                    codec.releaseOutputBuffer(lastOut, false);
                }
                lastOut = outIndex;
            }
            if (lastOut >= 0) {
                codec.releaseOutputBuffer(lastOut, true);
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "decoder state", e);
            releaseDecoder();
            waitForIdr = true;
        }
    }

    private void releaseDecoder() {
        synchronized (decoderLock) {
            releaseDecoderLocked();
        }
    }

    private void releaseDecoderLocked() {
        if (videoDecoder != null) {
            MediaCodec codec = videoDecoder;
            videoDecoder = null;
            videoDecoderMime = null;
            try {
                // Detach BLAST consumer before stop/release — prevents
                // "connect: already connected" on the next configure.
                codec.setOutputSurface(null);
            } catch (Exception ignored) {
            }
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void readMjpegOnce(String urlString) {
        ensureSurfaceProducer(SurfaceProducer.CANVAS);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "*/*");
            conn.connect();
            int code = conn.getResponseCode();
            if (code == 409 || code == 503) {
                handleStreamRedirect(conn, code);
                return;
            }
            if (code != 200) {
                setStatus("HTTP " + code);
                return;
            }
            noteMedia();
            setStatus(stereoToggle.isChecked()
                    ? getString(R.string.desktop_link_live_3d)
                    : getString(R.string.desktop_link_live));
            main.post(() -> {
                hideChromeSoonAfterLive();
                if (stereoToggle.isChecked()) {
                    surfaceView.post(() -> {
                        applyStereo(true);
                        surfaceView.post(this::redrawLatest);
                    });
                }
                refreshRemoteSettings();
            });
            InputStream raw = new BufferedInputStream(conn.getInputStream(), 512 * 1024);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(256 * 1024);
            byte[] buf = new byte[64 * 1024];
            boolean inImage = false;
            int prev = 0;
            while (wantStream && !Thread.currentThread().isInterrupted()) {
                int n = raw.read(buf);
                if (n < 0) {
                    break;
                }
                for (int i = 0; i < n; i++) {
                    int b = buf[i] & 0xFF;
                    if (!inImage) {
                        if (prev == 0xFF && b == 0xD8) {
                            jpeg.reset();
                            jpeg.write(0xFF);
                            jpeg.write(0xD8);
                            inImage = true;
                        }
                    } else {
                        jpeg.write(b);
                        if (prev == 0xFF && b == 0xD9) {
                            // Keep only the newest JPEG — never queue. That is what
                            // causes multi-second lag on Full SBS.
                            byte[] data = jpeg.toByteArray();
                            noteMedia();
                            synchronized (jpegLock) {
                                pendingJpeg = data;
                                pendingJpegGen++;
                                jpegLock.notifyAll();
                            }
                            inImage = false;
                            jpeg.reset();
                        }
                    }
                    prev = b;
                }
            }
        } catch (Exception e) {
            if (wantStream) {
                Log.w(TAG, "stream failed", e);
                setStatus(e.getMessage() != null ? e.getMessage() : "Stream failed");
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void decodeLoop(int gen) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        int lastGen = -1;
        while (wantStream && streamGen == gen && !Thread.currentThread().isInterrupted()) {
            byte[] data;
            int jpegGen;
            synchronized (jpegLock) {
                while (wantStream && streamGen == gen
                        && (pendingJpeg == null || pendingJpegGen == lastGen)) {
                    try {
                        jpegLock.wait(200);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!wantStream || streamGen != gen) {
                    return;
                }
                data = pendingJpeg;
                jpegGen = pendingJpegGen;
                pendingJpeg = null;
            }
            if (data == null) {
                continue;
            }
            lastGen = jpegGen;
            // If a newer frame arrived while we were waiting, skip this one.
            synchronized (jpegLock) {
                if (pendingJpegGen != gen && pendingJpeg != null) {
                    continue;
                }
            }
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (bmp != null) {
                presentFrame(bmp);
            }
        }
    }

    private void presentFrame(Bitmap bmp) {
        synchronized (drawLock) {
            if (latestFrame != null && latestFrame != bmp) {
                latestFrame.recycle();
            }
            latestFrame = bmp;
        }
        // Draw on the stream thread. Posting every JPEG to the UI queue
        // was backing up and making MJPEG look like 10–20 fps.
        redrawLatest();
    }

    private void redrawLatest() {
        if (!allowLockCanvas || surfaceProducer == SurfaceProducer.MEDIA_CODEC) {
            return;
        }
        if (!surfaceReady || surfaceHolder == null) {
            return;
        }
        Bitmap frame;
        synchronized (drawLock) {
            frame = latestFrame;
        }
        if (frame == null || frame.isRecycled()) {
            return;
        }
        Canvas canvas = null;
        try {
            canvas = surfaceHolder.lockCanvas();
            if (canvas == null) {
                return;
            }
            canvas.drawColor(Color.BLACK);
            int cw = canvas.getWidth();
            int ch = canvas.getHeight();
            int fw = frame.getWidth();
            int fh = frame.getHeight();
            if (cw <= 0 || ch <= 0 || fw <= 1 || fh <= 0) {
                return;
            }

            // With Horizon SIDE_BY_SIDE, left half of the surface → left eye and
            // right half → right eye. Draw the full SBS frame edge-to-edge (same as
            // PanelMainActivity cast). Manually splitting + letterboxing caused the
            // right eye to go black when the canvas size raced stereo composition.
            boolean stereo = stereoApplied
                    || (stereoToggle != null && stereoToggle.isChecked());
            if (stereo && fw >= 4) {
                canvas.drawBitmap(frame, null, new Rect(0, 0, cw, ch), paint);
            } else {
                float scale = Math.min(cw / (float) fw, ch / (float) fh);
                int dw = Math.max(1, Math.round(fw * scale));
                int dh = Math.max(1, Math.round(fh * scale));
                int x0 = (cw - dw) / 2;
                int y0 = (ch - dh) / 2;
                canvas.drawBitmap(frame, null, new Rect(x0, y0, x0 + dw, y0 + dh), paint);
            }
        } catch (Exception e) {
            Log.w(TAG, "draw failed", e);
        } finally {
            if (canvas != null) {
                try {
                    surfaceHolder.unlockCanvasAndPost(canvas);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void wireRemoteSliders() {
        SeekBar.OnSeekBarChangeListener push = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || applyingRemote) return;
                updateRemoteLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (!applyingRemote) pushRemoteSettings(false);
            }
        };
        depthSeek.setOnSeekBarChangeListener(push);
        convSeek.setOnSeekBarChangeListener(push);
        jpegSeek.setOnSeekBarChangeListener(push);
        widthSeek.setOnSeekBarChangeListener(push);
        sharpenSeek.setOnSeekBarChangeListener(push);
        hzSeek.setOnSeekBarChangeListener(push);
        smoothSeek.setOnSeekBarChangeListener(push);
        edgeSeek.setOnSeekBarChangeListener(push);
        live3dToggle.setOnCheckedChangeListener((b, checked) -> {
            if (!applyingRemote) pushRemoteSettings(false);
        });
        fullSbsToggle.setOnCheckedChangeListener((b, checked) -> {
            if (!applyingRemote) pushRemoteSettings(false);
        });
        codecJpegBtn.setOnClickListener(v -> selectCodec("mjpeg"));
        codecMpegBtn.setOnClickListener(v -> selectCodec("h264"));
        codecAv1Btn.setOnClickListener(v -> selectCodec("av1"));
        presetGamingBtn.setOnClickListener(v -> {
            selectedPreset = "gaming";
            stylePresetChips();
            reconnectGraceUntil = System.currentTimeMillis() + RECONNECT_GRACE_MS;
            lastMediaTick = System.currentTimeMillis();
            pushRemoteSettings(false);
        });
        presetMoviesBtn.setOnClickListener(v -> {
            selectedPreset = "movies";
            stylePresetChips();
            reconnectGraceUntil = System.currentTimeMillis() + RECONNECT_GRACE_MS;
            lastMediaTick = System.currentTimeMillis();
            pushRemoteSettings(false);
        });
    }

    private void selectCodec(String codec) {
        selectedCodec = codec;
        styleCodecChips();
        reconnectGraceUntil = System.currentTimeMillis() + RECONNECT_GRACE_MS;
        lastMediaTick = System.currentTimeMillis();
        pushRemoteSettings(true);
    }

    private void styleChip(Button btn, boolean active) {
        if (btn == null) return;
        btn.setBackgroundColor(active ? CHIP_ACTIVE : CHIP_INACTIVE);
        btn.setTextColor(Color.WHITE);
    }

    private void styleCodecChips() {
        styleChip(codecJpegBtn, "mjpeg".equals(selectedCodec));
        styleChip(codecMpegBtn, "h264".equals(selectedCodec));
        styleChip(codecAv1Btn, "av1".equals(selectedCodec));
    }

    private void stylePresetChips() {
        styleChip(presetGamingBtn, "gaming".equals(selectedPreset));
        styleChip(presetMoviesBtn, "movies".equals(selectedPreset));
    }

    private int streamWidthFromProgress() {
        int w = 1280 + widthSeek.getProgress() * 160;
        return Math.max(1280, Math.min(2560, w));
    }

    private int widthProgressFromPx(int px) {
        int clamped = Math.max(1280, Math.min(2560, px));
        return Math.max(0, Math.min(8, Math.round((clamped - 1280) / 160f)));
    }

    private void updateRemoteLabels() {
        int depth = 10 + depthSeek.getProgress();
        int conv = convSeek.getProgress();
        int jpeg = 50 + jpegSeek.getProgress();
        int width = streamWidthFromProgress();
        int sharpen = sharpenSeek.getProgress();
        int hz = 5 + hzSeek.getProgress();
        int smooth = smoothSeek.getProgress();
        int edge = edgeSeek != null ? edgeSeek.getProgress() : 60;
        if (widthValue != null) widthValue.setText(String.valueOf(width));
        if (jpegValue != null) jpegValue.setText(String.valueOf(jpeg));
        if (sharpenValue != null) sharpenValue.setText(String.valueOf(sharpen));
        if (depthValue != null) depthValue.setText(depth + "%");
        if (convValue != null) convValue.setText(conv + "%");
        if (hzValue != null) hzValue.setText(hz + " Hz");
        if (smoothValue != null) smoothValue.setText(smooth + "%");
        if (edgeValue != null) edgeValue.setText(edge + "%");
    }

    private String settingsUrl() {
        String stream = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
        if (stream.isEmpty()) return null;
        int slash = stream.lastIndexOf('/');
        if (slash <= "http://x".length()) return null;
        return stream.substring(0, slash) + "/settings";
    }

    private void refreshRemoteSettings() {
        final String url = settingsUrl();
        if (url == null) return;
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                conn.setRequestMethod("GET");
                if (conn.getResponseCode() != 200) return;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                InputStream in = conn.getInputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
                conn.disconnect();
                JSONObject o = new JSONObject(bos.toString(StandardCharsets.UTF_8.name()));
                main.post(() -> applyRemoteJsonToUi(o));
            } catch (Exception e) {
                Log.w(TAG, "settings GET failed", e);
            }
        }, "DesktopLinkSettingsGet").start();
    }

    private void applyRemoteJsonToUi(JSONObject o) {
        applyingRemote = true;
        try {
            if (o.has("depthStrength")) {
                depthSeek.setProgress(Math.max(0, o.optInt("depthStrength", 21) - 10));
            }
            if (o.has("convergence")) {
                convSeek.setProgress(o.optInt("convergence", 50));
            }
            if (o.has("jpegQuality")) {
                jpegSeek.setProgress(Math.max(0, o.optInt("jpegQuality", 95) - 50));
            }
            if (o.has("live3d")) {
                live3dToggle.setChecked(o.optBoolean("live3d", true));
            }
            if (o.has("fullSbs")) {
                fullSbsToggle.setChecked(o.optBoolean("fullSbs", false));
            }
            if (o.has("streamWidth")) {
                widthSeek.setProgress(widthProgressFromPx(o.optInt("streamWidth", 1920)));
            }
            if (o.has("sharpen")) {
                sharpenSeek.setProgress(Math.max(0, Math.min(80, o.optInt("sharpen", 25))));
            }
            if (o.has("depthHz")) {
                hzSeek.setProgress(Math.max(0, Math.min(55, o.optInt("depthHz", 20) - 5)));
            }
            if (o.has("depthSmooth")) {
                smoothSeek.setProgress(Math.max(0, Math.min(90, o.optInt("depthSmooth", 25))));
            }
            if (o.has("edgeClean") && edgeSeek != null) {
                edgeSeek.setProgress(Math.max(0, Math.min(100, o.optInt("edgeClean", 60))));
            }
            if (o.has("codec")) {
                String c = o.optString("codec", selectedCodec).toLowerCase();
                if ("jpeg".equals(c) || "mjpeg".equals(c)) selectedCodec = "mjpeg";
                else if ("mpeg".equals(c) || "h264".equals(c)) selectedCodec = "h264";
                else if ("av1".equals(c)) selectedCodec = "av1";
            }
            if (o.has("depthPreset")) {
                String p = o.optString("depthPreset", selectedPreset).toLowerCase();
                if ("gaming".equals(p) || "movies".equals(p)) selectedPreset = p;
            }
            styleCodecChips();
                stylePresetChips();
            updateRemoteLabels();
        } catch (Exception ignored) {
        } finally {
            applyingRemote = false;
        }
    }

    private String codecPathSuffix(String codec) {
        if ("h264".equals(codec)) return "sbs.h264";
        if ("av1".equals(codec)) return "sbs.av1";
        return "sbs.mjpg";
    }

    private void rewriteUrlPathForCodec(String codec) {
        String stream = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
        if (stream.isEmpty()) return;
        int slash = stream.lastIndexOf('/');
        if (slash <= "http://x".length()) return;
        String next = stream.substring(0, slash + 1) + codecPathSuffix(codec);
        urlInput.setText(next);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, next).apply();
    }

    private void pushRemoteSettings(boolean reconnectForCodec) {
        final String url = settingsUrl();
        if (url == null) return;
        final int depth = 10 + depthSeek.getProgress();
        final int conv = convSeek.getProgress();
        final int jpeg = 50 + jpegSeek.getProgress();
        final boolean live3d = live3dToggle.isChecked();
        final boolean fullSbs = fullSbsToggle.isChecked();
        final int streamWidth = streamWidthFromProgress();
        final int sharpen = sharpenSeek.getProgress();
        final int depthHz = 5 + hzSeek.getProgress();
        final int depthSmooth = smoothSeek.getProgress();
        final int edgeClean = edgeSeek != null ? edgeSeek.getProgress() : 60;
        final String codec = selectedCodec;
        final String depthPreset = selectedPreset;
        updateRemoteLabels();
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("depthStrength", depth);
                body.put("convergence", conv);
                body.put("jpegQuality", jpeg);
                body.put("live3d", live3d);
                body.put("fullSbs", fullSbs);
                body.put("streamWidth", streamWidth);
                body.put("sharpen", sharpen);
                body.put("depthHz", depthHz);
                body.put("depthSmooth", depthSmooth);
                body.put("edgeClean", edgeClean);
                body.put("codec", codec);
                body.put("depthPreset", depthPreset);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                conn.getOutputStream().write(bytes);
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code == 200) {
                    setStatus(getString(R.string.desktop_link_live_3d) + " · PC depth " + depth + "%");
                    if (reconnectForCodec) {
                        reconnectGraceUntil = System.currentTimeMillis() + RECONNECT_GRACE_MS;
                        lastMediaTick = System.currentTimeMillis();
                        boolean matched = waitForServerCodec(codec, 4000);
                        main.post(() -> {
                            rewriteUrlPathForCodec(codec);
                            if (!matched) {
                                Log.w(TAG, "PC codec not confirmed yet; reconnecting anyway to " + codec);
                            }
                            Log.i(TAG, "codec switch → " + codec + " matched=" + matched);
                            forceReconnect();
                        });
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "settings POST failed", e);
            }
        }, "DesktopLinkSettingsPost").start();
    }

    /** Poll GET /status until the PC reports the requested codec (hot-swap confirm). */
    private boolean waitForServerCodec(String wantCodec, int timeoutMs) {
        String stream = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
        if (stream.isEmpty()) return false;
        String statusUrl;
        try {
            URL u = new URL(stream);
            statusUrl = u.getProtocol() + "://" + u.getAuthority() + "/status";
        } catch (Exception e) {
            return false;
        }
        String want = wantCodec == null ? "" : wantCodec.toLowerCase();
        if ("jpeg".equals(want)) want = "mjpeg";
        if ("mpeg".equals(want)) want = "h264";
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(statusUrl).openConnection();
                conn.setConnectTimeout(1200);
                conn.setReadTimeout(1200);
                conn.setRequestMethod("GET");
                if (conn.getResponseCode() == 200) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    InputStream in = conn.getInputStream();
                    byte[] buf = new byte[512];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        bos.write(buf, 0, n);
                    }
                    conn.disconnect();
                    JSONObject o = new JSONObject(bos.toString(StandardCharsets.UTF_8.name()));
                    String got = o.optString("codec", "").toLowerCase();
                    if (want.equals(got) || ("mjpeg".equals(want) && "jpeg".equals(got))) {
                        return true;
                    }
                } else {
                    conn.disconnect();
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void setStatus(String msg) {
        main.post(() -> status.setText(msg));
    }

    /**
     * Reader controls while linked (on-screen buttons: controller key events
     * never arrive here — B is eaten by the system as Back, A never arrives).
     * Speak reads one OCR pass of the current frame (manual one-shot; the
     * pipeline covers continuous). Gated on a live stream.
     */

    private void togglePcContinuous() {
        if (!(wantStream || running)) {
            setStatus(getString(R.string.desktop_link_waiting_session));
            return;
        }
        new Thread(() -> {
            try {
                String base = settingsBaseUrl();
                if (base == null) {
                    return;
                }
                boolean current = fetchPcTtsContinuous(base + "/settings");
                JSONObject body = new JSONObject();
                body.put("ttsContinuous", !current);
                postJson(base + "/settings", body.toString());
                main.post(() -> {
                    if (continuousToggle != null) {
                        continuousToggle.setChecked(!current);
                    }
                });
                setStatus("PC continuous: " + (!current ? "on" : "off"));
                Log.i(TAG, "reader button -> PC ttsContinuous " + (!current));
            } catch (Exception e) {
                Log.w(TAG, "PC continuous toggle failed", e);
            }
        }, "DesktopLinkPcTts").start();
    }

    private void speakPcFrameOnce() {
        if (!(wantStream || running)) {
            setStatus(getString(R.string.desktop_link_waiting_session));
            return;
        }
        new Thread(() -> {
            try {
                String base = settingsBaseUrl();
                if (base == null) {
                    return;
                }
                String resp = postJson(base + "/reader/once", "{}");
                String text = "";
                try {
                    text = new JSONObject(resp).optString("text", "");
                } catch (Exception ignored) {
                }
                if (!text.isEmpty()) {
                    setStatus(text);
                } else {
                    setStatus(getString(R.string.desktop_link_speak_empty));
                }
                Log.i(TAG, "reader button -> PC speak-once (" + text.length() + " chars)");
            } catch (Exception e) {
                Log.w(TAG, "PC speak-once failed", e);
            }
        }, "DesktopLinkPcSpeak").start();
    }

    private String settingsBaseUrl() {
        String stream = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
        if (stream.isEmpty()) return null;
        int slash = stream.lastIndexOf('/');
        if (slash <= "http://x".length()) return null;
        return stream.substring(0, slash);
    }

    private boolean fetchPcTtsContinuous(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setRequestMethod("GET");
            if (c.getResponseCode() != 200) {
                return false;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            return new JSONObject(bos.toString("UTF-8")).optBoolean("ttsContinuous", false);
        } catch (Exception e) {
            Log.w(TAG, "PC ttsContinuous fetch failed", e);
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private String postJson(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(2500);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            c.getOutputStream().write(bytes);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) {
                return "";
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
