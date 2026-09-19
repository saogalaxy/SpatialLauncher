package com.spatiallauncher.app.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import java.util.concurrent.atomic.AtomicBoolean;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.media3.ui.PlayerView;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.spatiallauncher.app.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * True Horizon OS 2D panel Activity (see AndroidManifest's
 * com.oculus.intent.category.2D + &lt;layout&gt; sizing hints) — a plain Android
 * Activity, not a NativeActivity/OpenXR immersive app. The OS's panel framework owns
 * window placement, resizing, and pointer/hand-ray input; this class only ever deals in
 * ordinary Views, Intents, and PackageManager — no custom Canvas hit-testing, no OpenXR
 * composition layers, no coordinate inversions to get wrong.
 *
 * Responsibilities:
 *  - A settings control panel (stereo-render toggle + render-scale slider — currently
 *    UI-only placeholders; see prepareStereoRenderSurface()).
 *  - A bottom dock that shows no per-game icons until the user "adds a game" (picks one
 *    from their installed, launchable apps), plus an always-present "Add Game" control.
 *  - Launching an added game via its own normal launch Intent.
 */
public class PanelMainActivity extends AppCompatActivity {

    private static final String TAG = "PanelMainActivity";
    private static final int REQUEST_MEDIA_PROJECTION = 1001;

    // Real, documented, non-privileged Horizon OS API (SDK 204+) that tells the
    // compositor to split a SurfaceView's buffer per eye instead of showing the same
    // flat texture to both eyes — see
    // https://developers.meta.com/horizon/documentation/android-apps/stereo-surface-composition/.
    // Called via reflection (no compile-time stub/dependency available from this plain
    // Gradle CLI setup) so the app still builds and runs fine — just staying mono — on
    // devices/OS builds where these classes don't exist.
    private static final String HORIZONOS_SURFACE_VIEW_EXT_CLASS = "horizonos.view.SurfaceViewExt";
    private static final String HORIZONOS_SURFACE_CONTROL_EXT_CLASS = "horizonos.view.SurfaceControlExt";

    private GameLibraryStore libraryStore;
    private UserSettingsStore settingsStore;
    private BrowserLibraryStore browserLibraryStore;
    private EpubLibraryStore epubLibraryStore;
    private boolean epubSortByTitle = false;
    private String currentEpubPath = "";
    private boolean epubScrollToBottomOnLoad = false;
    private View epubPageBar;
    private AlertDialog myBooksDialog;
    private VideoLibraryStore videoLibraryStore;
    private View videoHost;
    private PlayerView hostedPlayerView;
    private View videoBar;
    private ImageButton videoBarRew;
    private ImageButton videoBarPlay;
    private ImageButton videoBarFfwd;
    private SeekBar videoBarSeek;
    private boolean videoBarSeekDragging;
    private final Handler videoBarHandler = new Handler(Looper.getMainLooper());
    private final Runnable videoBarTick = this::updateVideoBarUi;
    private ExoPlayer videoPlayer;
    private boolean videoPlaying;
    private boolean videoPausedForBackground;
    private boolean stereoBeforeVideo = true;
    /** True after first good video frame is drawn to the stereo surface (avoids black cover). */
    private volatile boolean videoStereoSurfaceShown;
    /** Intrinsic video pixels from ExoPlayer — stage is letterboxed to this aspect. */
    private int videoContentWidth;
    private int videoContentHeight;
    private final VideoPcmTap videoPcmTap = new VideoPcmTap();
    /** Frame pump for 3D and/or OCR/TTS/Share while video plays. */
    private volatile boolean videoFramePumpRunning;
    private Thread videoFramePumpThread;
    private PackageManager packageManager;

    private LinearLayout dockContainer;
    private LinearLayout controlRow;
    private LinearLayout panelRoot;
    private View cardRoot;
    private View settingsDrawer;
    private View helpDrawer;
    private Button settingsCloseButton;
    private Button helpCloseButton;
    private TextView emptyStateText;
    private SurfaceView gameRenderSurface;
    private final GlesZMeshView glesZMeshView = new GlesZMeshView();
    private View overlayView;
    private FrameLayout browserHost;
    private View browserTabScroll;
    private LinearLayout browserTabStrip;
    private final List<WebView> browserTabs = new ArrayList<>();
    private WebView drmWebView;
    private boolean ttsEnabled;
    private boolean ttsManualMode;
    private AssistMode assistMode = AssistMode.DEFAULT;
    private PlaybackListenEngine listenEngine;
    private TextView shareCaption;
    private boolean stereoBeforeListen = true;
    private boolean stereoBeforePageTranslate = true;
    private boolean pageTranslateHeldStereo;
    private boolean suppressStereoPersist;
    private static final int REQUEST_LISTEN_AUDIO = 7101;
    private static final int REQUEST_OPEN_EPUB = 7103;
    private static final int REQUEST_OPEN_VIDEO = 7104;
    private LinearLayout ttsPlayer;
    private ImageButton ttsSpeakButton;
    private ImageButton ttsPrevButton;
    private ImageButton ttsNextButton;
    private ImageButton ttsPauseButton;
    private ImageButton ttsPlayButton;
    private ImageButton ttsStopButton;
    private TextView ttsKaraoke;
    private final List<String> ttsPlaylist = new ArrayList<>();
    private int ttsPlaylistIndex;
    private boolean ttsHeldPaused;
    private boolean sessionRestored;
    private boolean ttsPreviewActive;
    private boolean ttsEngineSpeaking;
    private final Handler ttsPreviewHandler = new Handler(Looper.getMainLooper());
    private final Runnable ttsPreviewRunnable = this::playTtsVoicePreview;
    private ImageButton ocrRegionButton;
    private OcrRegionOverlayView ocrRegionOverlay;
    private LinearLayout ocrEditorBar;
    private LinearLayout ocrAppStrip;
    private TextView ocrTargetLabel;
    private String ocrEditTargetKey;
    private boolean horizonStereoCompositionApplied = false;
    private View browserChrome;
    private EditText browserAddress;
    private ImageButton browserButton;
    private ImageButton stopMirrorButton;
    private OcrRegionStore ocrRegionStore;
    private final ScreenFrameCapture screenFrameCapture = new ScreenFrameCapture();
    private DialogueTextExtractor dialogueTextExtractor;
    private ScreenDialogueReader screenDialogueReader;
    private PageTranslator pageTranslator;
    private TextView browserTranslateStatus;
    private Button browserTranslateButton;
    private ProgressBar browserTranslateProgress;
    private View browserTranslateMeter;
    private TextView browserTranslateEta;
    private android.graphics.drawable.Drawable browserTranslateButtonIdleBg;
    private boolean translateUiActive;
    private int translateDone;
    private int translateTotal;
    private long translateStartedAt;
    private final Handler translateUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable translateUiTick = this::refreshTranslateOverlay;
    private EpubSession epubSession;

    // --- In-panel "stereo mirror" pipeline -------------------------------------------
    // See prepareStereoRenderSurface() for the full explanation of what this can and
    // can't do on a normal (non-system, non-rooted) app.
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread mirrorThread;
    private Handler mirrorHandler;
    private InstalledAppInfo pendingLaunchApp;
    private boolean pendingLaunchAlreadyStarted;
    private InstalledAppInfo mirroringApp;
    private int captureWidth = 1;
    private int captureHeight = 1;
    // Locked bounds of the selected app window inside the VirtualDisplay. Horizon OS
    // still delivers window-only captures at the VD's full size with a uniform pad
    // around the window; we trim that pad once and never shrink it, so a video going
    // fullscreen *inside* Firefox can't eat into the window the way the old
    // near-black detector did.
    private volatile Rect capturedWindowRect;
    private final java.util.concurrent.atomic.AtomicBoolean windowBoundsBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private View contentArea;

    // Live setting, wired up to the switch in the settings panel.
    private boolean forceStereoEnabled = true;

    private DepthEstimator depthEstimator;
    private DepthEstimator depthStaticEstimator;
    private volatile boolean staticDepthLoadStarted;

    // Bound only while a mirror session is starting/running — see MirrorCaptureService's
    // doc comment for why this must be bound before any MediaProjection capture call.
    private MirrorCaptureService mirrorCaptureService;
    private boolean mirrorServiceBound = false;
    private boolean bookImportRunning = false;
    private String bookImportUrl = "";
    private String bookImportToken = "";
    private final BroadcastReceiver bookImportReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            if (BookImportService.ACTION_IMPORT_READY.equals(intent.getAction())) {
                bookImportUrl = intent.getStringExtra(BookImportService.EXTRA_URL);
                bookImportToken = intent.getStringExtra(BookImportService.EXTRA_TOKEN);
                bookImportRunning = true;
                return;
            }
            if (BookImportService.ACTION_BOOK_RECEIVED.equals(intent.getAction())) {
                String path = intent.getStringExtra(BookImportService.EXTRA_PATH);
                String name = intent.getStringExtra(BookImportService.EXTRA_NAME);
                if (path == null) {
                    return;
                }
                File file = new File(path);
                if (!file.exists()) {
                    return;
                }
                PanelAlerts.show(PanelMainActivity.this,
                        getString(R.string.book_import_received, name != null ? name : file.getName()));
                if (epubLibraryStore != null) {
                    epubLibraryStore.upsert(file, name);
                }
                openEpubFromUri(Uri.fromFile(file));
            }
        }
    };
    // Set if the user tapped a dock icon before the service finished its (near-instant,
    // but technically async) bind — retried automatically once onServiceConnected fires.
    private InstalledAppInfo pendingLaunchAwaitingService;

    private final ServiceConnection mirrorServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mirrorCaptureService = ((MirrorCaptureService.LocalBinder) service).getService();
            mirrorServiceBound = true;
            if (pendingLaunchAwaitingService != null) {
                InstalledAppInfo app = pendingLaunchAwaitingService;
                pendingLaunchAwaitingService = null;
                launchGame(app);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mirrorCaptureService = null;
            mirrorServiceBound = false;
        }
    };

    private int browserPageScrollX;
    private int browserPageScrollY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Must run before any WebView is inflated. Lets draw() include the full
        // document so we can crop to window.scrollY instead of a hole-punched
        // compositor tile (black band under the address bar in 3D).
        WebView.enableSlowWholeDocumentDraw();
        setContentView(R.layout.activity_panel_main);
        PanelAlerts.bindBanner(findViewById(R.id.panel_message_bar));

        packageManager = getPackageManager();
        libraryStore = new GameLibraryStore(this);
        settingsStore = new UserSettingsStore(this);
        browserLibraryStore = new BrowserLibraryStore(this);
        epubLibraryStore = new EpubLibraryStore(this);
        videoLibraryStore = new VideoLibraryStore(this);
        ocrRegionStore = new OcrRegionStore(this);
        dialogueTextExtractor = new DialogueTextExtractor(this);
        dialogueTextExtractor.setMode(DialogueTextExtractor.ReadingStepMode.OPTION_B_FAST_OCR);
        dialogueTextExtractor.setSmoothnessPercent(settingsStore.getOcrSmoothnessPercent());
        dialogueTextExtractor.setDeferHeavyVision(depthInferenceBusy::get);
        screenFrameCapture.setPeriodicIntervalMs(dialogueTextExtractor.recommendedCaptureIntervalMs());
        forceStereoEnabled = settingsStore.getForceStereo();
        useGlesZMesh = settingsStore.getGlesZMesh();
        stretchFill = settingsStore.getStretchFill();
        depthModeStatic = settingsStore.getDepthModeStatic();
        int savedDepthPercent = Math.max(10, settingsStore.getDepthStrengthPercent(depthModeStatic));
        depthStrengthMultiplier = savedDepthPercent / 100f;
        int savedConvergenceProgress = settingsStore.getConvergenceProgress(depthModeStatic);
        convergenceOffsetPx = Math.round((savedConvergenceProgress - 50) / 50f * CONVERGENCE_RANGE_PX);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        ttsEnabled = settingsStore.getTtsEnabled();
        assistMode = settingsStore.getAssistMode();
        listenEngine = new PlaybackListenEngine(this);
        listenEngine.setListener(new PlaybackListenEngine.Listener() {
            @Override
            public void onTranscript(String text) {
                handleAssistText(text, false);
            }

            @Override
            public void onStatus(String message) {
                PanelAlerts.show(PanelMainActivity.this, message);
            }
        });
        new Thread(() -> {
            OfflineModelPack.unpackAll(getApplicationContext());
            OfflineModelPack.unpackSpeechGated(getApplicationContext(), this::postModelProgress);
            if (OfflineModelPack.asrState(getApplicationContext())
                    == OfflineModelPack.ASR_NEED_CONSENT) {
                runOnUiThread(this::showModelConsentDialog);
            }
            if (assistMode == AssistMode.TRANSLATE || assistMode == AssistMode.SHARE) {
                OnDeviceTranslator.get(PanelMainActivity.this).ensureReady(ok -> { });
            }
        }, "ModelPack").start();
        ttsManualMode = settingsStore.getTtsManualMode();
        PiperTtsEngine piper = PiperTtsEngine.get(this);
        piper.setSpeechRate(settingsStore.getTtsSpeedPercent() / 100f);
        piper.setTonePercent(settingsStore.getTtsTonePercent());
        piper.setMaleVoice(settingsStore.getTtsMaleVoice());
        piper.setPlaybackListener(new PiperTtsEngine.PlaybackListener() {
            @Override
            public void onSpeakingChanged(boolean speaking) {
                ttsEngineSpeaking = speaking;
                if (!speaking
                        && !PiperTtsEngine.get(PanelMainActivity.this).isPaused()
                        && !ttsHeldPaused) {
                    ttsPreviewActive = false;
                    hideTtsKaraoke();
                }
                refreshTtsSpeakButton();
            }

            @Override
            public void onPaused(boolean paused) {
                refreshTtsSpeakButton();
            }

            @Override
            public void onUtteranceProgress(String text, int highlightStart, int highlightEnd) {
                if (ttsHeldPaused) {
                    return;
                }
                if (text != null) {
                    for (int i = 0; i < ttsPlaylist.size(); i++) {
                        if (text.equals(ttsPlaylist.get(i))) {
                            ttsPlaylistIndex = i;
                            break;
                        }
                    }
                }
                updateTtsKaraoke(text, highlightStart, highlightEnd);
            }
        });
        if (ocrRegionStore.hasCustomRegions()) {
            screenFrameCapture.setOcrRegions(ocrRegionStore.load());
        }
        screenFrameCapture.setSnapshotListener((frame, heavy) -> {
            if (!backgrounded && wantScreenOcr()) {
                dialogueTextExtractor.analyze(frame, heavy);
            }
        });
        dialogueTextExtractor.setListener(new DialogueTextExtractor.Listener() {
            @Override
            public void onDialogueText(String text) {
                handleAssistText(text, false);
            }

            @Override
            public void onDialogueTextForced(String text) {
                handleAssistText(text, true);
            }
        });
        if (ttsEnabled) {
            piper.ensureReadyAsync();
            ensureDialogueTts();
        }
        // and must not block onCreate()/the UI thread. computeParallaxGrid() already
        // null-checks depthEstimator and falls back to the luminance heuristic until this
        // finishes, so mirroring can start immediately even if depth isn't ready yet.
        new Thread(() -> {
            DepthEstimator estimator = DepthEstimator.createDefault(getApplicationContext());
            estimator.setDepthGamma(contrastPercentToGamma(settingsStore.getDepthContrastPercent(false)));
            // Live only: light temporal flicker dampen. Keep Static crisp for pop.
            estimator.setTemporalKeep(0.35f);
            depthEstimator = estimator;
            if (settingsStore.getDepthModeStatic()) {
                ensureStaticDepthModel();
            }
        }, "DepthModelLoader").start();

        dockContainer = findViewById(R.id.dock_container);
        controlRow = findViewById(R.id.control_row);
        panelRoot = findViewById(R.id.panel_root);
        cardRoot = findViewById(R.id.card_root);
        emptyStateText = findViewById(R.id.empty_state_text);
        gameRenderSurface = findViewById(R.id.game_render_surface);
        glesZMeshView.setOnBound(() -> {
            if (useGlesZMesh && (mirroringApp != null || browserStereoRunning)) {
                setStereoComposition(forceStereoEnabled);
            }
            stereoHandoff = false;
        });
        overlayView = findViewById(R.id.touch_overlay);
        drmWebView = findViewById(R.id.webView);
        browserHost = findViewById(R.id.browser_host);
        browserTabScroll = findViewById(R.id.browser_tab_scroll);
        browserTabStrip = findViewById(R.id.browser_tab_strip);
        browserChrome = findViewById(R.id.browser_chrome);
        browserAddress = findViewById(R.id.browser_address);
        browserTranslateStatus = findViewById(R.id.browser_translate_status);
        browserTranslateButton = findViewById(R.id.browser_translate_page);
        browserTranslateProgress = findViewById(R.id.browser_translate_progress);
        browserTranslateMeter = findViewById(R.id.browser_translate_meter);
        browserTranslateEta = findViewById(R.id.browser_translate_eta);
        if (browserTranslateButton != null) {
            browserTranslateButtonIdleBg = browserTranslateButton.getBackground();
        }
        browserButton = findViewById(R.id.browser_button);
        applyBrowserWebView(drmWebView);
        browserTabs.add(drmWebView);
        browserButton.setOnClickListener(v -> {
            if (isBrowserOpen()) {
                hideDrmBrowser();
            } else {
                showDrmBrowser(browserLibraryStore.getLastUrlOrHome());
            }
        });
        findViewById(R.id.epub_button).setOnClickListener(v -> onEpubButtonClicked());
        findViewById(R.id.epub_button).setOnLongClickListener(v -> {
            toggleBookImportServer();
            return true;
        });
        videoHost = findViewById(R.id.video_host);
        hostedPlayerView = findViewById(R.id.video_player_view);
        if (hostedPlayerView != null) {
            hostedPlayerView.setUseController(false);
            hostedPlayerView.setResizeMode(
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
        bindVideoBar();
        View videoButton = findViewById(R.id.video_button);
        if (videoButton != null) {
            // Reel: stop if playing, else system video picker (import → play).
            videoButton.setOnClickListener(v -> {
                if (videoPlaying) {
                    stopVideoPlayback();
                } else {
                    openVideoPicker();
                }
            });
        }
        findViewById(R.id.browser_close_button).setOnClickListener(v -> hideDrmBrowser());
        findViewById(R.id.browser_go_button).setOnClickListener(v -> goToAddressBarUrl());
        findViewById(R.id.browser_bookmark_button).setOnClickListener(v -> toggleCurrentBookmark());
        findViewById(R.id.browser_history_button).setOnClickListener(v -> showBrowserLists());
        findViewById(R.id.browser_back_button).setOnClickListener(v -> {
            if (epubSession != null) {
                epubTurnPage(false);
                return;
            }
            if (drmWebView != null && drmWebView.canGoBack()) {
                drmWebView.goBack();
            }
        });
        findViewById(R.id.browser_forward_button).setOnClickListener(v -> {
            if (epubSession != null) {
                epubTurnPage(true);
                return;
            }
            if (drmWebView != null && drmWebView.canGoForward()) {
                drmWebView.goForward();
            }
        });
        epubPageBar = findViewById(R.id.epub_page_bar);
        View epubPrev = findViewById(R.id.epub_prev_page);
        View epubNext = findViewById(R.id.epub_next_page);
        if (epubPrev != null) {
            epubPrev.setOnClickListener(v -> epubTurnPage(false));
        }
        if (epubNext != null) {
            epubNext.setOnClickListener(v -> epubTurnPage(true));
        }
        findViewById(R.id.browser_translate_page).setOnClickListener(v -> {
            if (pageTranslator != null && pageTranslator.isBusy()) {
                pageTranslator.cancel(this);
                stopTranslateUi();
                PiperTtsEngine.get(this).stopSpeaking();
                PanelAlerts.show(this, R.string.browser_translate_cancelled);
                return;
            }
            translateBrowserPage();
        });
        findViewById(R.id.browser_google_translate).setOnClickListener(v -> {
            if (pageTranslator != null && pageTranslator.isBusy()) {
                pageTranslator.cancel(this);
                stopTranslateUi();
                PiperTtsEngine.get(this).stopSpeaking();
                PanelAlerts.show(this, R.string.browser_translate_cancelled);
                return;
            }
            googleTranslateBrowserPage();
        });
        findViewById(R.id.browser_read_page).setOnClickListener(v -> readBrowserPage());
        browserAddress.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                goToAddressBarUrl();
                return true;
            }
            return false;
        });
        contentArea = findViewById(R.id.content_area);
        muteCastPointerChrome();
        contentArea.setOnApplyWindowInsetsListener((v, insets) -> {
            screenFrameCapture.setSystemInsets(
                    v.getWidth(),
                    v.getHeight(),
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        contentArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            if (r - l == or_ - ol && b - t == ob - ot) {
                return;
            }
            if (videoPlaying) {
                fitVideoStageToAspect();
            } else if (mirroringApp != null || isBrowserOpen()) {
                fitSurfaceToCaptureAspectRatio();
            }
        });
        panelRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            if (r - l == or_ - ol && b - t == ob - ot) {
                return;
            }
            setHomeRowCompact(mirroringApp != null || isBrowserOpen() || videoPlaying);
            if (videoPlaying) {
                fitVideoStageToAspect();
            }
        });
        stopMirrorButton = findViewById(R.id.stop_mirror_button);
        stopMirrorButton.setOnClickListener(v -> stopMirroring());

        // "3D" toggle: when ON, draw SBS + parallax and ask Horizon for SIDE_BY_SIDE
        // composition. When OFF, draw one full-bleed frame (no mesh/depth) — same idea
        // as browser mode, which swaps back to the live WebView.
        ToggleButton toggleStereo3d = findViewById(R.id.toggle_stereo_3d);
        toggleStereo3d.setChecked(forceStereoEnabled);
        // Same round-button color as power/settings for a consistent palette; the "on"
        // state is shown with full opacity + accent-tinted text vs. a slightly dimmed
        // look when off, instead of a differently-colored background.
        updateStereoToggleLook(toggleStereo3d, forceStereoEnabled);
        toggleStereo3d.setOnCheckedChangeListener((buttonView, isChecked) -> {
            forceStereoEnabled = isChecked;
            if (!suppressStereoPersist) {
                settingsStore.setForceStereo(isChecked);
            }
            updateStereoToggleLook(toggleStereo3d, isChecked);
            if (mirroringApp != null) {
                setStereoComposition(forceStereoEnabled);
                if (!forceStereoEnabled) {
                    cachedParallaxGrid = flatParallaxGrid();
                    cachedDepth01 = null;
                }
                activeStereoSurface().post(this::lockSurfaceBufferSize);
            } else if (isBrowserOpen()) {
                if (isChecked) {
                    startBrowserStereo();
                } else {
                    stopBrowserStereo();
                }
            } else if (videoPlaying) {
                setVideoDisplayMode();
            }
        });

        // Settings gear opens/closes a slide-out drawer (top-end corner) holding the
        // depth strength + convergence sliders, instead of those sliders permanently
        // taking up space on the main panel surface.
        View settingsDrawer = findViewById(R.id.settings_drawer);
        this.settingsDrawer = settingsDrawer;
        View helpDrawer = findViewById(R.id.help_drawer);
        this.helpDrawer = helpDrawer;
        populateHelpContent();
        View exitButton = findViewById(R.id.exit_button);
        if (exitButton != null) {
            exitButton.setOnClickListener(v -> confirmQuitApp());
        }
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            if (helpDrawer != null && helpDrawer.getVisibility() == View.VISIBLE) {
                closeSideDrawer(helpDrawer);
            }
            if (settingsDrawer.getVisibility() == View.VISIBLE) {
                closeSideDrawer(settingsDrawer);
            } else {
                openSideDrawer(settingsDrawer);
            }
        });
        ImageButton helpButton = findViewById(R.id.help_button);
        helpButton.setOnClickListener(v -> {
            if (settingsDrawer.getVisibility() == View.VISIBLE) {
                closeSideDrawer(settingsDrawer);
            }
            if (helpDrawer.getVisibility() == View.VISIBLE) {
                closeSideDrawer(helpDrawer);
            } else {
                openSideDrawer(helpDrawer);
            }
        });
        ocrRegionButton = findViewById(R.id.ocr_region_button);
        ocrRegionOverlay = findViewById(R.id.ocr_region_overlay);
        ocrEditorBar = findViewById(R.id.ocr_editor_bar);
        ocrAppStrip = findViewById(R.id.ocr_app_strip);
        ocrTargetLabel = findViewById(R.id.ocr_target_label);
        ttsPlayer = findViewById(R.id.tts_player);
        ttsSpeakButton = findViewById(R.id.tts_speak_button);
        ttsPrevButton = findViewById(R.id.tts_prev_button);
        ttsNextButton = findViewById(R.id.tts_next_button);
        ttsPauseButton = findViewById(R.id.tts_pause_button);
        ttsPlayButton = findViewById(R.id.tts_play_button);
        ttsStopButton = findViewById(R.id.tts_stop_button);
        ttsKaraoke = findViewById(R.id.tts_karaoke);
        shareCaption = findViewById(R.id.share_caption);
        ttsSpeakButton.setFocusable(false);
        ttsSpeakButton.setFocusableInTouchMode(false);
        ttsSpeakButton.setOnClickListener(v -> onTtsSpeakButtonClicked());
        ttsPrevButton.setOnClickListener(v -> onTtsPrevClicked());
        ttsNextButton.setOnClickListener(v -> onTtsNextClicked());
        ttsPauseButton.setOnClickListener(v -> onTtsPauseClicked());
        ttsPlayButton.setOnClickListener(v -> onTtsPlayClicked());
        ttsStopButton.setOnClickListener(v -> onTtsStopClicked());
        wireTtsPlayerHoverPopup();
        ttsSpeakButton.setOnLongClickListener(v -> {
            if (!ttsEnabled) {
                PanelAlerts.show(this, R.string.tts_turn_on_first);
                return true;
            }
            setTtsManualMode(!ttsManualMode);
            PanelAlerts.show(this, ttsManualMode ? getString(R.string.tts_mode_once_toast) : getString(R.string.tts_mode_loop_toast));
            return true;
        });
        refreshTtsSpeakButton();
        ocrRegionButton.setOnClickListener(v -> openOcrRegionEditor());
        ocrRegionOverlay.setListener(new OcrRegionOverlayView.Listener() {
            @Override
            public void onRegionsChanged(List<OcrRegion> regions) {
                screenFrameCapture.setOcrRegions(regions);
            }

            @Override
            public void onEditFinished(List<OcrRegion> regions) {
                ocrRegionStore.save(regions);
                screenFrameCapture.setOcrRegions(regions);
                if (ocrEditTargetKey != null) {
                    ocrRegionStore.saveForApp(
                            ocrEditTargetKey, labelForOcrKey(ocrEditTargetKey), regions);
                }
                closeOcrRegionEditor();
            }
        });
        Button settingsCloseButton = findViewById(R.id.settings_close_button);
        this.settingsCloseButton = settingsCloseButton;
        settingsCloseButton.setOnClickListener(v -> closeSideDrawer(settingsDrawer));
        Button helpCloseButton = findViewById(R.id.help_close_button);
        this.helpCloseButton = helpCloseButton;
        if (helpCloseButton != null) {
            helpCloseButton.setOnClickListener(v -> closeSideDrawer(helpDrawer));
        }
        Switch toggleTts = findViewById(R.id.toggle_tts);
        Switch toggle3dOffPageTranslate = findViewById(R.id.toggle_3d_off_page_translate);
        if (toggle3dOffPageTranslate != null) {
            toggle3dOffPageTranslate.setChecked(settingsStore.get3dOffForPageTranslate());
            toggle3dOffPageTranslate.setOnClickListener(v ->
                    settingsStore.set3dOffForPageTranslate(toggle3dOffPageTranslate.isChecked()));
        }
        Switch toggleTtsAuto = findViewById(R.id.toggle_tts_auto);
        Switch toggleTtsManual = findViewById(R.id.toggle_tts_manual);
        toggleTts.setChecked(ttsEnabled);
        toggleTts.setOnClickListener(v -> {
            boolean on = toggleTts.isChecked();
            Log.i("PanelMainActivity", "TTS master -> " + on);
            setTtsEnabled(on);
        });
        toggleTtsAuto.setOnClickListener(v -> {
            if (!ttsEnabled) {
                toggleTtsAuto.setChecked(false);
                PanelAlerts.show(this, R.string.tts_turn_on_first);
                return;
            }
            if (toggleTtsAuto.isChecked()) {
                setTtsManualMode(false);
            } else {
                // Must have a mode while TTS is on — fall back to manual.
                setTtsManualMode(true);
            }
        });
        toggleTtsManual.setOnClickListener(v -> {
            if (!ttsEnabled) {
                toggleTtsManual.setChecked(false);
                PanelAlerts.show(this, R.string.tts_turn_on_first);
                return;
            }
            if (toggleTtsManual.isChecked()) {
                setTtsManualMode(true);
            } else {
                setTtsManualMode(false);
            }
        });
        refreshTtsModeSwitches();
        findViewById(R.id.assist_mode_default).setOnClickListener(v -> setAssistMode(AssistMode.DEFAULT));
        findViewById(R.id.assist_mode_translate).setOnClickListener(v -> setAssistMode(AssistMode.TRANSLATE));
        findViewById(R.id.assist_mode_listen).setOnClickListener(v -> setAssistMode(AssistMode.LISTEN));
        findViewById(R.id.assist_mode_share).setOnClickListener(v -> setAssistMode(AssistMode.SHARE));
        findViewById(R.id.mt_engine_opus).setOnClickListener(v -> setUseOpusTranslate(true));
        findViewById(R.id.mt_engine_mlkit).setOnClickListener(v -> setUseOpusTranslate(false));
        findViewById(R.id.listen_button).setOnClickListener(v -> toggleListenFromToolbar());
        findViewById(R.id.desktop_link_button).setOnClickListener(v -> {
            // Thin client: pause Quest-side depth / OCR / Listen — PC owns those while Desktop Link runs.
            pausePipelinesForDesktopLink();
            startActivity(new android.content.Intent(this, DesktopLinkActivity.class));
        });
        applyAssistMode(assistMode, false);
        refreshListenButton();
        refreshMtEngineButtons();

        SeekBar seekbarOcrSmooth = findViewById(R.id.seekbar_ocr_smoothness);
        TextView ocrSmoothValue = findViewById(R.id.ocr_smoothness_value);
        int savedOcrSmooth = settingsStore.getOcrSmoothnessPercent();
        seekbarOcrSmooth.setProgress(savedOcrSmooth);
        ocrSmoothValue.setText(ocrSmoothnessLabel(savedOcrSmooth));
        seekbarOcrSmooth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                ocrSmoothValue.setText(ocrSmoothnessLabel(progress));
                dialogueTextExtractor.setSmoothnessPercent(progress);
                screenFrameCapture.setPeriodicIntervalMs(
                        dialogueTextExtractor.recommendedCaptureIntervalMs());
                if (fromUser) {
                    settingsStore.setOcrSmoothnessPercent(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        SeekBar seekbarListenSmooth = findViewById(R.id.seekbar_listen_smoothness);
        TextView listenSmoothValue = findViewById(R.id.listen_smoothness_value);
        int savedListenSmooth = settingsStore.getListenSmoothnessPercent();
        seekbarListenSmooth.setProgress(savedListenSmooth);
        listenSmoothValue.setText(listenSmoothnessLabel(savedListenSmooth));
        if (listenEngine != null) {
            listenEngine.setSmoothnessPercent(savedListenSmooth);
        }
        seekbarListenSmooth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                listenSmoothValue.setText(listenSmoothnessLabel(progress));
                if (listenEngine != null) {
                    listenEngine.setSmoothnessPercent(progress);
                }
                if (fromUser) {
                    settingsStore.setListenSmoothnessPercent(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        SeekBar seekbarTtsSpeed = findViewById(R.id.seekbar_tts_speed);
        TextView ttsSpeedValue = findViewById(R.id.tts_speed_value);
        int savedTtsSpeed = settingsStore.getTtsSpeedPercent();
        // SeekBar max=150 → percent = progress + 50 (range 50–200).
        seekbarTtsSpeed.setProgress(savedTtsSpeed - 50);
        ttsSpeedValue.setText(savedTtsSpeed + "%");
        seekbarTtsSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = progress + 50;
                ttsSpeedValue.setText(percent + "%");
                PiperTtsEngine.get(PanelMainActivity.this).setSpeechRate(percent / 100f);
                if (fromUser) {
                    settingsStore.setTtsSpeedPercent(percent);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                scheduleTtsVoicePreview(400);
            }
        });

        boolean male = settingsStore.getTtsMaleVoice();
        refreshVoiceButtons(male);
        findViewById(R.id.tts_voice_female).setOnClickListener(v -> {
            settingsStore.setTtsMaleVoice(false);
            PiperTtsEngine.get(this).setMaleVoice(false);
            refreshVoiceButtons(false);
            scheduleTtsVoicePreview(900);
        });
        findViewById(R.id.tts_voice_male).setOnClickListener(v -> {
            settingsStore.setTtsMaleVoice(true);
            PiperTtsEngine.get(this).setMaleVoice(true);
            refreshVoiceButtons(true);
            PanelAlerts.show(this, R.string.tts_voice_switched_male);
            scheduleTtsVoicePreview(1400);
        });

        SeekBar seekbarTtsTone = findViewById(R.id.seekbar_tts_tone);
        TextView ttsToneValue = findViewById(R.id.tts_tone_value);
        int savedTone = settingsStore.getTtsTonePercent();
        seekbarTtsTone.setProgress(savedTone);
        ttsToneValue.setText(String.valueOf(savedTone));
        seekbarTtsTone.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                ttsToneValue.setText(String.valueOf(progress));
                if (fromUser) {
                    settingsStore.setTtsTonePercent(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                PiperTtsEngine.get(PanelMainActivity.this).setTonePercent(seekBar.getProgress());
                scheduleTtsVoicePreview(900);
            }
        });

        findViewById(R.id.ocr_add_box).setOnClickListener(v -> ocrRegionOverlay.addRegion());
        findViewById(R.id.ocr_delete_box).setOnClickListener(v -> ocrRegionOverlay.deleteSelected());
        findViewById(R.id.ocr_reset_box).setOnClickListener(v -> ocrRegionOverlay.resetToDefault());
        findViewById(R.id.ocr_save_for_app).setOnClickListener(v -> saveOcrLayoutForSelectedApp());
        findViewById(R.id.ocr_done_edit).setOnClickListener(v -> ocrRegionOverlay.finishEdit());

        // "Depth strength": live multiplier on the depth model's pixel-shift range (see
        // minParallaxPx()/maxParallaxPx()). Takes effect on the very next depth-inference
        // pass (usually well under a second), no mirror restart needed.
        SeekBar seekbarDepthStrength = findViewById(R.id.seekbar_depth_strength);
        TextView depthStrengthValue = findViewById(R.id.depth_strength_value);
        seekbarDepthStrength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = Math.max(10, progress);
                depthStrengthMultiplier = percent / 100f;
                depthStrengthValue.setText(percent + "%");
                if (fromUser) {
                    settingsStore.setDepthStrengthPercent(depthModeStatic, percent);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // "Convergence": shifts the zero-parallax plane — a constant amount added to
        // every cell's shift (same sign per eye as the depth-based shift), independent of
        // the per-cell depth value. Slide it down if the stereo effect causes eye strain
        // (it lowers overall crossed disparity across the whole scene, which is usually
        // the actual source of strain, more so than depth *strength* itself); slide it up
        // to make things pop further toward you.
        SeekBar seekbarConvergence = findViewById(R.id.seekbar_convergence);
        TextView convergenceValue = findViewById(R.id.convergence_value);
        seekbarConvergence.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // progress 0..100 -> -CONVERGENCE_RANGE_PX..+CONVERGENCE_RANGE_PX, 50 = 0
                int px = Math.round((progress - 50) / 50f * CONVERGENCE_RANGE_PX);
                convergenceOffsetPx = px;
                convergenceValue.setText(String.valueOf(px));
                if (fromUser) {
                    settingsStore.setConvergenceProgress(depthModeStatic, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekbarDepthStrength.setProgress(savedDepthPercent);
        depthStrengthValue.setText(savedDepthPercent + "%");
        seekbarConvergence.setProgress(savedConvergenceProgress);
        convergenceValue.setText(String.valueOf(
                Math.round((savedConvergenceProgress - 50) / 50f * CONVERGENCE_RANGE_PX)));

        bindAdvanced3dSettings();

        prepareStereoRenderSurface();
        refreshDock();

        // Bind (and thus start) MirrorCaptureService immediately, for the whole time
        // this panel Activity is alive. The platform's MediaProjection restriction is
        // enforced as early as MediaProjectionManager.getMediaProjection() itself (not
        // just createVirtualDisplay()), so the foreground service of type
        // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION must already be running well before
        // the user ever taps a dock icon — binding lazily inside launchGame() was too
        // late and still threw "Media projections require a foreground service of type
        // ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION". unbindService() in
        // onDestroy() lets it tear down automatically once the panel closes.
        bindService(new Intent(this, MirrorCaptureService.class), mirrorServiceConnection, BIND_AUTO_CREATE);
        IntentFilter importFilter = new IntentFilter();
        importFilter.addAction(BookImportService.ACTION_BOOK_RECEIVED);
        importFilter.addAction(BookImportService.ACTION_IMPORT_READY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bookImportReceiver, importFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bookImportReceiver, importFilter);
        }
        // Do NOT start BookImport here — onCreate can run while still "background"
        // (Quest restoring the panel under a VR game) and startService() crashes the app.
        contentArea.post(this::restoreLastSession);
    }

    /**
     * Start LAN EPUB import only when the user asks (EPUB long-press / toggle).
     * Never auto-start on boot/focus — that fought VR games and crashed in background.
     */
    private void startBookImportServer() {
        if (bookImportRunning) {
            return;
        }
        try {
            startService(new Intent(this, BookImportService.class));
            bookImportRunning = true;
        } catch (IllegalStateException e) {
            android.util.Log.w("PanelMain", "BookImport start blocked (background)", e);
            bookImportRunning = false;
            PanelAlerts.show(this, R.string.book_import_off);
        }
    }

    private void stopBookImportServerQuiet() {
        if (!bookImportRunning) {
            return;
        }
        try {
            stopService(new Intent(this, BookImportService.class));
        } catch (Exception ignored) {
        }
        bookImportRunning = false;
    }

    private void showBookImportStatus() {
        startBookImportServer();
        String url = bookImportUrl != null && !bookImportUrl.isEmpty()
                ? bookImportUrl
                : ("http://(waiting…):" + BookImportHttp.DEFAULT_PORT);
        String token = bookImportToken != null ? bookImportToken : "";
        if (token.isEmpty()) {
            token = getSharedPreferences("book_import", MODE_PRIVATE).getString("token", "");
        }
        PanelAlerts.show(this, getString(R.string.book_import_on, url, token));
    }

    private void toggleBookImportServer() {
        if (bookImportRunning) {
            stopBookImportServerQuiet();
            bookImportUrl = "";
            PanelAlerts.show(this, R.string.book_import_off);
            return;
        }
        startBookImportServer();
        showBookImportStatus();
    }

    private void persistSession() {
        if (settingsStore == null) {
            return;
        }
        if (mirroringApp != null && mirroringApp.packageName != null) {
            settingsStore.setSessionApp(mirroringApp.packageName);
        } else if (isBrowserOpen()) {
            settingsStore.setSessionBrowser();
        } else if (videoPlaying) {
            // No dedicated session token yet — keep last non-idle if any; else idle.
            settingsStore.setSessionIdle();
        } else {
            settingsStore.setSessionIdle();
        }
    }

    private void restoreLastSession() {
        if (sessionRestored) {
            return;
        }
        sessionRestored = true;
        String mode = settingsStore.getSessionMode();
        if (UserSettingsStore.SESSION_BROWSER.equals(mode)) {
            showDrmBrowser(browserLibraryStore.getLastUrlOrHome());
        }
    }

    private boolean isBrowserOpen() {
        return browserHost != null && browserHost.getVisibility() == View.VISIBLE;
    }

    private void applyBrowserWebView(WebView webView) {
        WidevineWebViewConfig.apply(webView,
                (supported, detail) -> {
                    String msg = supported
                            ? "Widevine EME OK (" + WidevineWebViewConfig.WIDEVINE_KEY_SYSTEM + ")"
                            : "Widevine EME missing: " + detail;
                    Log.i("PanelMainActivity", msg);
                },
                (view, url) -> runOnUiThread(() -> {
                    if (epubSession != null) {
                        if (browserAddress != null && !browserAddress.hasFocus()) {
                            browserAddress.setText(epubSession.title + "  " + (epubSession.index + 1)
                                    + "/" + epubSession.chapterUrls.size());
                        }
                        refreshBrowserChrome();
                        return;
                    }
                    if (view == drmWebView && browserAddress != null && !browserAddress.hasFocus()) {
                        browserAddress.setText(url);
                    }
                    rememberBrowserVisit(view, url);
                    refreshBrowserChrome();
                }),
                new WidevineWebViewConfig.WindowHandler() {
                    @Override
                    public WebView createPopupWindow() {
                        return createBrowserPopupTab();
                    }

                    @Override
                    public void closePopupWindow(WebView webView) {
                        closeBrowserTab(webView);
                    }
                },
                (view, title) -> runOnUiThread(this::refreshBrowserTabs));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
    }

    private PageTranslator pageTranslator() {
        if (pageTranslator == null) {
            pageTranslator = new PageTranslator();
            pageTranslator.setListener(new PageTranslator.Listener() {
                @Override
                public void onStatus(String message) {
                    if (message == null) {
                        return;
                    }
                    if (browserTranslateProgress != null && translateUiActive) {
                        browserTranslateProgress.setIndeterminate(false);
                        browserTranslateProgress.setProgress(0);
                        browserTranslateProgress.setContentDescription(message);
                    }
                    PanelAlerts.show(PanelMainActivity.this, message);
                }

                @Override
                public void onTranslatedPage(String english) {
                    if (!ttsEnabled) {
                        PanelAlerts.show(PanelMainActivity.this, R.string.tts_turn_on_first);
                        return;
                    }
                    adoptTtsPlaylist(english);
                    speakPlaylistFrom(0);
                }

                @Override
                public void onTranslateStarted(int total) {
                    startTranslateUi(Math.max(1, total));
                }

                @Override
                public void onTranslateProgress(int done, int total) {
                    translateDone = done;
                    translateTotal = Math.max(1, total);
                    refreshTranslateOverlay();
                }

                @Override
                public void onTranslateLine(String englishLine) {
                    if (browserTranslateEta != null && englishLine != null && !englishLine.isEmpty()) {
                        String shortLine = englishLine.length() > 48
                                ? englishLine.substring(0, 45) + "…"
                                : englishLine;
                        browserTranslateEta.setText(shortLine);
                    }
                }

                @Override
                public void onTranslatePartial(String englishSoFar) {
                    // Page stays Japanese until the end; line text is in the ETA label.
                }

                @Override
                public void onTranslateFinished() {
                    endPageTranslateStereoHold();
                    if (browserTranslateProgress != null) {
                        browserTranslateProgress.setIndeterminate(false);
                        browserTranslateProgress.setMax(Math.max(1, translateTotal));
                        browserTranslateProgress.setProgress(Math.max(translateDone, translateTotal));
                    }
                    long holdMs = translateDone <= 0 ? 4000L : 700L;
                    translateUiHandler.removeCallbacks(translateUiTick);
                    translateUiHandler.postDelayed(PanelMainActivity.this::stopTranslateUi, holdMs);
                }
            });
        }
        return pageTranslator;
    }

    private void readBrowserPage() {
        if (!isBrowserOpen() || drmWebView == null) {
            showDrmBrowser(browserLibraryStore.getLastUrlOrHome());
        }
        if (!ttsEnabled) {
            PanelAlerts.show(this, R.string.tts_turn_on_first);
            return;
        }
        pageTranslator().readPage(drmWebView);
    }

    private void translateBrowserPage() {
        if (!isBrowserOpen() || drmWebView == null) {
            showDrmBrowser(browserLibraryStore.getLastUrlOrHome());
        }
        // Stop OCR→Piper so it can't fight line-by-line page Translate.
        PiperTtsEngine.get(this).stopSpeaking();
        beginPageTranslateStereoHold();
        startTranslateUi(1);
        if (!ttsEnabled) {
            PanelAlerts.show(this, R.string.tts_turn_on_first);
        }
        pageTranslator().translatePage(drmWebView, ttsEnabled);
    }

    private void googleTranslateBrowserPage() {
        if (!isBrowserOpen() || drmWebView == null) {
            showDrmBrowser(browserLibraryStore.getLastUrlOrHome());
        }
        PiperTtsEngine.get(this).stopSpeaking();
        beginPageTranslateStereoHold();
        startTranslateUi(1);
        if (!ttsEnabled) {
            PanelAlerts.show(this, R.string.tts_turn_on_first);
        }
        PanelAlerts.show(this, R.string.browser_google_waking);
        pageTranslator().googleTranslatePage(drmWebView, ttsEnabled);
    }

    private void beginPageTranslateStereoHold() {
        // Pause 3D capture during Translate so Adreno stays free for OPUS / Piper.
        if (!forceStereoEnabled || pageTranslateHeldStereo) {
            return;
        }
        pageTranslateHeldStereo = true;
        stereoBeforePageTranslate = true;
        setSessionStereo(false);
        PanelAlerts.show(this, R.string.page_translate_3d_off);
    }

    private void endPageTranslateStereoHold() {
        if (!pageTranslateHeldStereo) {
            return;
        }
        pageTranslateHeldStereo = false;
        setSessionStereo(stereoBeforePageTranslate);
    }

    private void startTranslateUi(int total) {
        translateUiActive = true;
        translateTotal = Math.max(1, total);
        translateDone = 0;
        translateStartedAt = android.os.SystemClock.elapsedRealtime();
        if (browserTranslateStatus != null) {
            browserTranslateStatus.setVisibility(View.GONE);
        }
        if (browserTranslateMeter != null) {
            browserTranslateMeter.setVisibility(View.VISIBLE);
        }
        if (browserTranslateProgress != null) {
            browserTranslateProgress.setVisibility(View.VISIBLE);
            browserTranslateProgress.setMax(Math.max(1, total));
            browserTranslateProgress.setIndeterminate(false);
            browserTranslateProgress.setProgress(0);
        }
        if (browserTranslateEta != null) {
            browserTranslateEta.setText(R.string.browser_translating_loading);
        }
        if (browserTranslateButton != null) {
            browserTranslateButton.setAlpha(1f);
            browserTranslateButton.setBackgroundResource(R.drawable.bg_translate_active);
            browserTranslateButton.setText(R.string.browser_translate_page);
        }
        PanelAlerts.show(this, R.string.browser_translating_loading);
        translateUiHandler.removeCallbacks(translateUiTick);
        translateUiHandler.post(translateUiTick);
        refreshTranslateOverlay();
    }

    private void refreshTranslateOverlay() {
        if (!translateUiActive) {
            return;
        }
        int done = Math.max(0, translateDone);
        int total = Math.max(1, translateTotal);
        long elapsedMs = android.os.SystemClock.elapsedRealtime() - translateStartedAt;
        int elapsedSec = (int) Math.max(0, elapsedMs / 1000);
        int etaSec = 0;
        if (done > 0 && done < total) {
            long eta = elapsedMs * (total - done) / Math.max(1, done);
            etaSec = (int) Math.max(1, (eta + 500) / 1000);
        }
        String etaText;
        if (done <= 0 && total <= 1) {
            etaText = getString(R.string.browser_translating_loading)
                    + " · " + elapsedSec + "s";
        } else if (done < total) {
            // done = completed; current chunk is done+1 while work is in flight.
            int working = Math.min(total, done + 1);
            if (done == 0) {
                etaText = getString(R.string.browser_translating_working, working, total)
                        + " · " + elapsedSec + "s";
            } else {
                etaText = getString(R.string.browser_translating_status, done, total, etaSec);
            }
        } else {
            etaText = getString(R.string.browser_translating_status, done, total, 0);
        }
        if (browserTranslateMeter != null) {
            browserTranslateMeter.setVisibility(View.VISIBLE);
        }
        if (browserTranslateProgress != null) {
            browserTranslateProgress.setVisibility(View.VISIBLE);
            browserTranslateProgress.setIndeterminate(false);
            browserTranslateProgress.setMax(total);
            // Show in-flight chunk as partial step so the bar moves before the first finish.
            int shown = done < total ? Math.min(total, done + 1) : done;
            // Half-step while first chunk still running: keep at least 1 once work started.
            if (done == 0 && total > 1 && elapsedSec > 0) {
                shown = 1;
            }
            browserTranslateProgress.setProgress(shown);
            browserTranslateProgress.setContentDescription(etaText);
        }
        if (browserTranslateEta != null) {
            browserTranslateEta.setText(etaText);
        }
        if (browserTranslateButton != null) {
            browserTranslateButton.setBackgroundResource(R.drawable.bg_translate_active);
            browserTranslateButton.setText(R.string.browser_translate_page);
        }
        translateUiHandler.removeCallbacks(translateUiTick);
        translateUiHandler.postDelayed(translateUiTick, 400);
    }

    private void stopTranslateUi() {
        translateUiActive = false;
        translateUiHandler.removeCallbacks(translateUiTick);
        if (browserTranslateButton != null) {
            browserTranslateButton.setAlpha(1f);
            browserTranslateButton.setText(R.string.browser_translate_page);
            if (browserTranslateButtonIdleBg != null) {
                browserTranslateButton.setBackground(browserTranslateButtonIdleBg);
            }
        }
        if (browserTranslateProgress != null) {
            browserTranslateProgress.setIndeterminate(false);
            browserTranslateProgress.setProgress(100);
        }
        if (browserTranslateEta != null) {
            browserTranslateEta.setText("");
        }
        if (browserTranslateMeter != null) {
            browserTranslateMeter.setVisibility(View.GONE);
        }
        if (browserTranslateProgress != null) {
            browserTranslateProgress.setVisibility(View.GONE);
        }
        if (browserTranslateStatus != null) {
            browserTranslateStatus.setVisibility(View.GONE);
        }
        endPageTranslateStereoHold();
    }

    private void onEpubButtonClicked() {
        if (myBooksDialog != null && myBooksDialog.isShowing()) {
            myBooksDialog.dismiss();
            return;
        }
        // Tap book again while reading closes the reader (same idea as toggling the shelf).
        if (epubSession != null && isBrowserOpen()) {
            closeEpubReader();
            return;
        }
        showMyBooksShelf();
    }

    private void closeEpubReader() {
        epubSession = null;
        currentEpubPath = "";
        setEpubPageBarVisible(false);
        hideDrmBrowser();
        PanelAlerts.show(this, R.string.epub_closed);
    }

    private void openEpubPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/epub+zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/epub+zip", "application/octet-stream"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OPEN_EPUB);
    }

    private void showMyBooksShelf() {
        if (epubLibraryStore == null) {
            epubLibraryStore = new EpubLibraryStore(this);
        }
        if (myBooksDialog != null && myBooksDialog.isShowing()) {
            myBooksDialog.dismiss();
            return;
        }
        epubLibraryStore.scanInbox();
        List<EpubLibraryStore.BookEntry> books = epubLibraryStore.list(epubSortByTitle);
        if (books.isEmpty()) {
            // Empty shelf: importing is the primary action (Open other stays too).
            AlertDialog.Builder emptyBuilder = new AlertDialog.Builder(this)
                    .setTitle(R.string.my_books_title)
                    .setMessage(R.string.my_books_empty)
                    .setPositiveButton(bookImportRunning
                            ? R.string.my_books_stop_import : R.string.my_books_import_wifi,
                            (d, w) -> toggleBookImportServer())
                    .setNeutralButton(R.string.my_books_open_other, (d, w) -> openEpubPicker())
                    .setNegativeButton(R.string.my_books_close, null);
            myBooksDialog = emptyBuilder.create();
            myBooksDialog.setOnDismissListener(d -> myBooksDialog = null);
            myBooksDialog.show();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.my_books_title)
                .setPositiveButton(R.string.my_books_open_other, (d, w) -> openEpubPicker())
                .setNeutralButton(
                        epubSortByTitle ? R.string.my_books_sort_title : R.string.my_books_sort_recent,
                        (d, w) -> {
                            epubSortByTitle = !epubSortByTitle;
                            showMyBooksShelf();
                        })
                .setNegativeButton(R.string.my_books_close, null);
        String[] labels = new String[books.size() + 1];
        labels[0] = getString(bookImportRunning
                ? R.string.my_books_stop_import : R.string.my_books_import_wifi);
        for (int i = 0; i < books.size(); i++) {
            labels[i + 1] = books.get(i).title;
        }
        myBooksDialog = builder
                .setItems(labels, (d, which) -> {
                    if (which == 0) {
                        toggleBookImportServer();
                        return;
                    }
                    EpubLibraryStore.BookEntry entry = books.get(which - 1);
                    openEpubFromUri(Uri.fromFile(new File(entry.path)));
                })
                .create();
        myBooksDialog.setOnDismissListener(d -> myBooksDialog = null);
        myBooksDialog.show();
    }

    private void openEpubFromUri(Uri uri) {
        try {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
            File shelfFile = ensureEpubInInbox(uri);
            epubSession = EpubSession.open(this, shelfFile != null ? Uri.fromFile(shelfFile) : uri);
            currentEpubPath = shelfFile != null ? shelfFile.getAbsolutePath() : (uri.getPath() != null ? uri.getPath() : "");
            if (epubLibraryStore != null && shelfFile != null) {
                epubLibraryStore.markOpened(shelfFile.getAbsolutePath(), epubSession.title);
            }
            showDrmBrowserHostOnly();
            loadEpubChapter();
            PanelAlerts.show(this, getString(R.string.browser_epub_opened, epubSession.title, epubSession.chapterUrls.size()));
        } catch (Exception e) {
            Log.w("PanelMainActivity", "EPUB open failed", e);
            PanelAlerts.show(this, R.string.browser_epub_failed);
        }
    }

    /** Copy content:// picks into inbox so they can reopen from My Books. */
    private File ensureEpubInInbox(Uri uri) {
        if (uri == null) {
            return null;
        }
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            File file = new File(uri.getPath());
            if (file.isFile()) {
                return file;
            }
        }
        if (epubLibraryStore == null) {
            epubLibraryStore = new EpubLibraryStore(this);
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) {
            name = "book.epub";
        }
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (!name.toLowerCase(Locale.US).endsWith(".epub")) {
            name = name + ".epub";
        }
        File dest = new File(epubLibraryStore.getInboxDir(), name);
        if (dest.exists()) {
            String base = name.substring(0, name.length() - 5);
            dest = new File(epubLibraryStore.getInboxDir(), base + "-" + System.currentTimeMillis() + ".epub");
        }
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                return null;
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return dest;
        } catch (Exception e) {
            Log.w("PanelMainActivity", "copy to inbox failed", e);
            return null;
        }
    }

    // ------------------------------ Video player ------------------------------
    // Reel opens the system picker; playback fills the main viewer with ExoPlayer.
    // Frames feed OCR/TTS/Share/3D; TeeAudioProcessor feeds Listen.

    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "video/*", "application/octet-stream"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OPEN_VIDEO);
    }

    /**
     * Copy content:// picks into the video inbox so they reopen from the gallery.
     * Verifies byte count against the source — a truncated copy plays as a black
     * failure, so partials are deleted instead of shelved.
     */
    private File ensureVideoInInbox(Uri uri) {
        if (uri == null) {
            return null;
        }
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            File file = new File(uri.getPath());
            if (file.isFile() && file.length() > 0
                    && VideoLibraryStore.isVideoName(file.getName())) {
                return file;
            }
            return null;
        }
        if (videoLibraryStore == null) {
            videoLibraryStore = new VideoLibraryStore(this);
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) {
            name = "video.mp4";
        }
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (!VideoLibraryStore.isVideoName(name)) {
            name = name + ".mp4";
        }
        File dest = new File(videoLibraryStore.getInboxDir(), name);
        if (dest.exists()) {
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : ".mp4";
            dest = new File(videoLibraryStore.getInboxDir(),
                    base + "-" + System.currentTimeMillis() + ext);
        }
        long expected = querySourceSize(uri);
        long copied = 0;
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                return null;
            }
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                copied += n;
            }
        } catch (Exception e) {
            Log.w("PanelMainActivity", "Video import failed", e);
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            return null;
        }
        if (copied <= 0 || (expected > 0 && copied != expected)) {
            Log.w("PanelMainActivity", "Video import short: " + copied + "/" + expected);
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            return null;
        }
        return dest;
    }

    private long querySourceSize(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[] {
                        android.provider.OpenableColumns.SIZE }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getLong(0);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private void playVideoFile(File file, String title) {
        if (file == null || !file.isFile() || file.length() <= 0) {
            Log.w(TAG, "playVideoFile: missing or empty file");
            return;
        }
        if (hostedPlayerView == null || videoHost == null) {
            Log.w(TAG, "playVideoFile: player views missing");
            return;
        }
        if (videoLibraryStore != null) {
            videoLibraryStore.markOpened(file.getAbsolutePath(), title);
        }
        stopVideoPlayback();
        if (mirroringApp != null) {
            stopMirroring();
        }
        if (isBrowserOpen()) {
            hideDrmBrowser();
        }
        // Mono first so transport controls work; user can turn 3D on afterward.
        stereoBeforeVideo = forceStereoEnabled;
        if (forceStereoEnabled) {
            setSessionStereo(false);
        }
        videoPcmTap.clear();
        ExoPlayer player;
        try {
            DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this) {
                @Override
                protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(
                        Context context,
                        boolean enableFloatOutput,
                        boolean enableAudioTrackPlaybackParams) {
                    TeeAudioProcessor tee = new TeeAudioProcessor(videoPcmTap);
                    return new DefaultAudioSink.Builder()
                            .setEnableFloatOutput(enableFloatOutput)
                            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                            .setAudioProcessors(new androidx.media3.common.audio.AudioProcessor[] { tee })
                            .build();
                }
            };
            player = new ExoPlayer.Builder(this, renderersFactory).build();
        } catch (Throwable t) {
            Log.w("PanelMainActivity", "ExoPlayer init failed", t);
            // Fallback without PCM tap (Listen on video won't hear soundtrack).
            try {
                player = new ExoPlayer.Builder(this).build();
            } catch (Throwable t2) {
                Log.w("PanelMainActivity", "ExoPlayer fallback failed", t2);
                return;
            }
        }
        videoPlayer = player;
        hostedPlayerView.setPlayer(player);
        videoContentWidth = 0;
        videoContentHeight = 0;
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w("PanelMainActivity", "ExoPlayer error file=" + file.getAbsolutePath()
                        + " size=" + file.length() + " "
                        + (error != null
                                ? ("code=" + error.errorCode + " " + error.getMessage()) : "null"));
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                if (videoSize == null || videoSize.width <= 0 || videoSize.height <= 0) {
                    return;
                }
                videoContentWidth = videoSize.width;
                videoContentHeight = videoSize.height;
                runOnUiThread(() -> fitVideoStageToAspect());
            }

        });
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        player.setPlayWhenReady(true);
        player.prepare();
        showVideoHostOnly();
        videoPlaying = true;
        videoPausedForBackground = false;
        fitVideoStageToAspect();
        setVideoDisplayMode();
        if (assistMode == AssistMode.LISTEN) {
            syncListenEngine(false);
        }
        showVideoBar(true);
    }

    /**
     * Mono = PlayerView + optional OCR pump. 3D = PixelCopy player SurfaceView →
     * stereo pipeline. Stereo overlay is shown only after the first good frame so
     * toggling 3D never covers the picture with a blank surface.
     */
    private void setVideoDisplayMode() {
        if (!videoPlaying) {
            stopVideoFramePump();
            hideVideoStereoOverlay();
            return;
        }
        boolean want3d = forceStereoEnabled;
        boolean wantOcrFrames = wantScreenOcr() || (ttsEnabled && ttsManualMode);
        boolean needPump = want3d || wantOcrFrames;
        if (needPump && !videoFramePumpRunning) {
            startVideoFramePump();
        } else if (!needPump && videoFramePumpRunning) {
            stopVideoFramePump();
        }
        if (!want3d) {
            hideVideoStereoOverlay();
        }
        // want3d: wait for publishVideoFrame → showVideoStereoOverlayAfterFirstFrame
    }

    private void hideVideoStereoOverlay() {
        videoStereoSurfaceShown = false;
        if (overlayView != null && mirroringApp == null && !browserStereoRunning) {
            overlayView.setVisibility(View.GONE);
            overlayView.setOnTouchListener(null);
        }
        restoreVideoPlayerChrome();
        if (mirroringApp == null && !isBrowserOpen()) {
            setStereoOutputVisible(false);
            setStereoComposition(false);
        }
        if (gameRenderSurface != null) {
            FrameLayout.LayoutParams surfLp =
                    (FrameLayout.LayoutParams) gameRenderSurface.getLayoutParams();
            surfLp.topMargin = 0;
            surfLp.bottomMargin = 0;
            surfLp.gravity = android.view.Gravity.CENTER;
            gameRenderSurface.setLayoutParams(surfLp);
            gameRenderSurface.setTranslationY(0f);
        }
    }

    /** Restore flat PlayerView surface after leaving video 3D (transport is external). */
    private void restoreVideoPlayerChrome() {
        if (hostedPlayerView != null) {
            View surface = hostedPlayerView.getVideoSurfaceView();
            if (surface != null) {
                surface.setVisibility(View.VISIBLE);
            }
            hostedPlayerView.setBackgroundColor(Color.BLACK);
            hostedPlayerView.setShutterBackgroundColor(Color.BLACK);
            hostedPlayerView.setUseController(false);
            hostedPlayerView.setAlpha(1f);
        }
        if (videoHost != null && videoPlaying) {
            videoHost.setVisibility(View.VISIBLE);
            videoHost.setAlpha(1f);
            videoHost.bringToFront();
        }
        showVideoBar(videoPlaying);
    }

    /**
     * Stereo SBS over the letterboxed stage. Decoder SurfaceView stays alive but
     * invisible for PixelCopy. Transport lives outside content_area (not on the
     * Horizon stereo surface), so flat chrome never draws over SBS.
     */
    private void showVideoStereoOverlayAfterFirstFrame() {
        if (!videoPlaying || !forceStereoEnabled || videoStereoSurfaceShown) {
            return;
        }
        videoStereoSurfaceShown = true;
        setStereoOutputVisible(true);
        setStereoComposition(true);
        gameRenderSurface.setClickable(false);
        fitVideoStageToAspect();
        if (videoHost != null) {
            videoHost.post(this::fitVideoStageToAspect);
        }
        activeStereoSurface().post(this::lockSurfaceBufferSize);

        if (hostedPlayerView != null) {
            View surface = hostedPlayerView.getVideoSurfaceView();
            if (surface != null) {
                surface.setVisibility(View.INVISIBLE);
            }
            // Flat Exo chrome on SBS causes the fold — hide controller in 3D.
            hostedPlayerView.setUseController(false);
            hostedPlayerView.hideController();
            hostedPlayerView.setBackgroundColor(Color.TRANSPARENT);
            hostedPlayerView.setShutterBackgroundColor(Color.TRANSPARENT);
            hostedPlayerView.setAlpha(1f);
        }
        if (videoHost != null) {
            videoHost.setVisibility(View.VISIBLE);
            videoHost.setAlpha(1f);
        }
        if (gameRenderSurface != null) {
            gameRenderSurface.bringToFront();
        }
        showVideoBar(true);
        if (overlayView != null) {
            overlayView.setVisibility(View.GONE);
            overlayView.setOnTouchListener(null);
        }
    }

    /**
     * Letterbox {@code video_host} (+ stereo surface when 3D) to the video's aspect
     * inside {@code content_area}. Transport chrome lives outside content_area.
     */
    private void fitVideoStageToAspect() {
        if (!videoPlaying || contentArea == null) {
            return;
        }
        int areaW = contentArea.getWidth();
        int areaH = contentArea.getHeight();
        if (areaW <= 0 || areaH <= 0) {
            contentArea.post(this::fitVideoStageToAspect);
            return;
        }
        int vw = videoContentWidth;
        int vh = videoContentHeight;
        if ((vw <= 1 || vh <= 1) && videoPlayer != null) {
            VideoSize vs = videoPlayer.getVideoSize();
            if (vs != null && vs.width > 1 && vs.height > 1) {
                vw = vs.width;
                vh = vs.height;
                videoContentWidth = vw;
                videoContentHeight = vh;
            }
        }
        if (vw <= 1 || vh <= 1) {
            sizeVideoStage(areaW, areaH, android.view.Gravity.CENTER);
            return;
        }
        float scale = Math.min(areaW / (float) vw, areaH / (float) vh);
        int sw = Math.max(1, Math.round(vw * scale));
        int sh = Math.max(1, Math.round(vh * scale));
        sizeVideoStage(sw, sh, android.view.Gravity.CENTER);
    }

    private void sizeVideoStage(int pictureW, int pictureH, int gravity) {
        if (videoHost != null) {
            FrameLayout.LayoutParams hostLp =
                    (FrameLayout.LayoutParams) videoHost.getLayoutParams();
            if (hostLp.width != pictureW
                    || hostLp.height != pictureH
                    || hostLp.gravity != gravity) {
                hostLp.width = pictureW;
                hostLp.height = pictureH;
                hostLp.gravity = gravity;
                videoHost.setLayoutParams(hostLp);
            }
        }
        if (gameRenderSurface != null && videoStereoSurfaceShown) {
            FrameLayout.LayoutParams surfLp =
                    (FrameLayout.LayoutParams) gameRenderSurface.getLayoutParams();
            boolean changed = surfLp.width != pictureW
                    || surfLp.height != pictureH
                    || surfLp.gravity != gravity
                    || surfLp.topMargin != 0
                    || surfLp.bottomMargin != 0;
            if (changed) {
                surfLp.width = pictureW;
                surfLp.height = pictureH;
                surfLp.gravity = gravity;
                surfLp.topMargin = 0;
                surfLp.bottomMargin = 0;
                gameRenderSurface.setLayoutParams(surfLp);
            }
            gameRenderSurface.setTranslationY(0f);
            gameRenderSurface.post(this::lockSurfaceBufferSize);
        } else if (gameRenderSurface != null) {
            gameRenderSurface.setTranslationY(0f);
            FrameLayout.LayoutParams surfLp =
                    (FrameLayout.LayoutParams) gameRenderSurface.getLayoutParams();
            if (surfLp.topMargin != 0
                    || surfLp.bottomMargin != 0
                    || surfLp.gravity != android.view.Gravity.CENTER) {
                surfLp.topMargin = 0;
                surfLp.bottomMargin = 0;
                surfLp.gravity = android.view.Gravity.CENTER;
                gameRenderSurface.setLayoutParams(surfLp);
            }
        }
    }


    private void bindVideoBar() {
        videoBar = findViewById(R.id.video_player);
        videoBarRew = findViewById(R.id.video_bar_rew);
        videoBarPlay = findViewById(R.id.video_bar_play);
        videoBarFfwd = findViewById(R.id.video_bar_ffwd);
        videoBarSeek = findViewById(R.id.video_bar_seek);
        if (videoBarRew != null) {
            videoBarRew.setOnClickListener(v -> seekVideoByMs(-10_000));
        }
        if (videoBarFfwd != null) {
            videoBarFfwd.setOnClickListener(v -> seekVideoByMs(10_000));
        }
        if (videoBarPlay != null) {
            videoBarPlay.setOnClickListener(v -> toggleVideoPlayPause());
        }
        if (videoBarSeek != null) {
            videoBarSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    videoBarSeekDragging = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    videoBarSeekDragging = false;
                    if (videoPlayer == null) {
                        return;
                    }
                    long dur = videoPlayer.getDuration();
                    if (dur <= 0 || dur == androidx.media3.common.C.TIME_UNSET) {
                        return;
                    }
                    long pos = seekBar.getProgress() * dur / Math.max(1, seekBar.getMax());
                    videoPlayer.seekTo(pos);
                    updateVideoBarUi();
                }
            });
        }
        showVideoBar(false);
    }

    private void showVideoBar(boolean show) {
        if (videoBar == null) {
            return;
        }
        boolean on = show && videoPlaying;
        videoBar.setVisibility(on ? View.VISIBLE : View.GONE);
        // Collapse the app dock while a reel plays so the transport owns that end.
        if (dockContainer != null) {
            dockContainer.setVisibility(on ? View.GONE : View.VISIBLE);
        }
        if (on) {
            videoBarHandler.removeCallbacks(videoBarTick);
            videoBarHandler.post(videoBarTick);
        } else {
            videoBarHandler.removeCallbacks(videoBarTick);
        }
    }

    private void updateVideoBarUi() {
        if (!videoPlaying || videoPlayer == null) {
            videoBarHandler.removeCallbacks(videoBarTick);
            if (videoBar != null) {
                videoBar.setVisibility(View.GONE);
            }
            if (dockContainer != null) {
                dockContainer.setVisibility(View.VISIBLE);
            }
            return;
        }
        long pos = Math.max(0, videoPlayer.getCurrentPosition());
        long dur = videoPlayer.getDuration();
        if (dur < 0 || dur == androidx.media3.common.C.TIME_UNSET) {
            dur = 0;
        }
        if (videoBarSeek != null && !videoBarSeekDragging) {
            int max = Math.max(1, videoBarSeek.getMax());
            int progress = dur > 0 ? (int) Math.min(max, pos * max / dur) : 0;
            videoBarSeek.setProgress(progress);
        }
        if (videoBarPlay != null) {
            videoBarPlay.setImageResource(
                    videoPlayer.isPlaying()
                            ? android.R.drawable.ic_media_pause
                            : android.R.drawable.ic_media_play);
        }
        videoBarHandler.removeCallbacks(videoBarTick);
        videoBarHandler.postDelayed(videoBarTick, 500);
    }

    private void toggleVideoPlayPause() {
        if (videoPlayer == null) {
            return;
        }
        if (videoPlayer.isPlaying()) {
            videoPlayer.pause();
        } else {
            videoPlayer.play();
        }
        updateVideoBarUi();
    }

    private void seekVideoByMs(long deltaMs) {
        if (videoPlayer == null) {
            return;
        }
        long pos = Math.max(0, videoPlayer.getCurrentPosition() + deltaMs);
        long dur = videoPlayer.getDuration();
        if (dur > 0 && dur != androidx.media3.common.C.TIME_UNSET) {
            pos = Math.min(pos, dur);
        }
        videoPlayer.seekTo(pos);
        updateVideoBarUi();
    }

    private void fitSurfaceForVideoStereo() {
        fitVideoStageToAspect();
    }

    private void refreshVideoTouchOverlay() {
        // Overlay is wired in showVideoStereoOverlayAfterFirstFrame / hideVideoStereoOverlay.
    }

    private void startVideoFramePump() {
        if (videoFramePumpRunning) {
            return;
        }
        videoFramePumpRunning = true;
        Log.i(TAG, "VideoFramePump start gles=" + useGlesZMesh
                + " ownsSurface=" + glesZMeshView.ownsSurface());
        videoFramePumpThread = new Thread(() -> {
            android.os.Handler mainH =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            int iters = 0;
            int drawn = 0;
            while (videoFramePumpRunning && videoPlaying) {
                try {
                    iters++;
                    boolean want3d = forceStereoEnabled;
                    boolean wantOcr = wantScreenOcr() || (ttsEnabled && ttsManualMode);
                    if (!want3d && !wantOcr) {
                        sleepQuiet(200);
                        continue;
                    }
                    View surfaceChild = hostedPlayerView != null
                            ? hostedPlayerView.getVideoSurfaceView() : null;
                    ExoPlayer pl = videoPlayer;
                    if (pl == null) {
                        sleepQuiet(150);
                        continue;
                    }
                    // Prefer SurfaceView PixelCopy — reads the decoder buffer even when
                    // the stereo SurfaceView is stacked above (TextureView.getBitmap often
                    // returns black once covered / HW-composited).
                    if (!(surfaceChild instanceof SurfaceView)) {
                        if (iters % 40 == 1) {
                            Log.i(TAG, "VideoFramePump waiting SurfaceView child="
                                    + (surfaceChild == null ? "null"
                                            : surfaceChild.getClass().getSimpleName()));
                        }
                        sleepQuiet(150);
                        continue;
                    }
                    SurfaceView sv = (SurfaceView) surfaceChild;
                    if (sv.getHolder() == null
                            || !sv.getHolder().getSurface().isValid()) {
                        sleepQuiet(150);
                        continue;
                    }
                    int sw = sv.getWidth();
                    int sh = sv.getHeight();
                    if (sw <= 0 || sh <= 0) {
                        sleepQuiet(150);
                        continue;
                    }
                    int tw = Math.min(960, sw);
                    int th = Math.max(2, Math.round((float) tw * sh / sw));
                    final Bitmap bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                    final int[] status = {PixelCopy.ERROR_SOURCE_INVALID};
                    final java.util.concurrent.CountDownLatch latch =
                            new java.util.concurrent.CountDownLatch(1);
                    try {
                        PixelCopy.request(sv, bmp, copyResult -> {
                            status[0] = copyResult;
                            latch.countDown();
                        }, mainH);
                    } catch (Throwable t) {
                        bmp.recycle();
                        sleepQuiet(150);
                        continue;
                    }
                    boolean done = false;
                    try {
                        done = latch.await(800, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        bmp.recycle();
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!done || status[0] != PixelCopy.SUCCESS
                            || !videoFramePumpRunning || !videoPlaying) {
                        if (iters % 40 == 1) {
                            Log.i(TAG, "VideoFramePump no frame done=" + done
                                    + " status=" + status[0]);
                        }
                        bmp.recycle();
                        sleepQuiet(80);
                        continue;
                    }
                    if (isMostlyBlack(bmp)) {
                        if (iters % 40 == 1) {
                            Log.i(TAG, "VideoFramePump skipped near-black frame");
                        }
                        bmp.recycle();
                        sleepQuiet(80);
                        continue;
                    }
                    try {
                        if (want3d && !videoStereoSurfaceShown) {
                            final java.util.concurrent.CountDownLatch shown =
                                    new java.util.concurrent.CountDownLatch(1);
                            mainH.post(() -> {
                                try {
                                    showVideoStereoOverlayAfterFirstFrame();
                                } finally {
                                    shown.countDown();
                                }
                            });
                            try {
                                shown.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                bmp.recycle();
                                return;
                            }
                        }
                        publishVideoFrame(bmp, want3d, wantOcr);
                        drawn++;
                        if (drawn == 1 || drawn % 120 == 0) {
                            Log.i(TAG, "VideoFramePump drew frame #" + drawn
                                    + " 3d=" + want3d + " ocr=" + wantOcr);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "VideoFramePump publish failed", t);
                    } finally {
                        bmp.recycle();
                    }
                    sleepQuiet(want3d ? 70 : 200);
                } catch (Throwable t) {
                    Log.w(TAG, "VideoFramePump loop failed", t);
                    sleepQuiet(200);
                }
            }
            Log.i(TAG, "VideoFramePump exit");
        }, "VideoFramePump");
        videoFramePumpThread.start();
    }

    /** Reject empty / not-yet-decoded frames so we never cover the player with black. */
    private static boolean isMostlyBlack(Bitmap bmp) {
        if (bmp == null || bmp.getWidth() < 2 || bmp.getHeight() < 2) {
            return true;
        }
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int samples = 0;
        long lumSum = 0;
        for (int y = h / 8; y < h; y += Math.max(1, h / 8)) {
            for (int x = w / 8; x < w; x += Math.max(1, w / 8)) {
                int c = bmp.getPixel(x, y);
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                lumSum += (r * 3 + g * 6 + b) / 10;
                samples++;
            }
        }
        return samples == 0 || (lumSum / samples) < 12;
    }

    /** Same publish contract as {@link #publishBrowserStereoFrame()}. */
    private void publishVideoFrame(Bitmap frame, boolean want3d, boolean wantOcr) {
        if (frame == null) {
            return;
        }
        // Keep fit helpers honest if anything else reads capture size during video.
        captureWidth = Math.max(1, frame.getWidth());
        captureHeight = Math.max(1, frame.getHeight());
        if (ttsEnabled) {
            screenFrameCapture.retainSourceForTap(frame);
        }
        if (want3d) {
            try {
                requestDepthUpdate(frame);
            } catch (Throwable t) {
                Log.w(TAG, "VideoFramePump depth failed", t);
            }
            try {
                drawStereoMirrorFrame(frame);
            } catch (Throwable t) {
                Log.w(TAG, "VideoFramePump draw failed", t);
            }
        }
        if (wantOcr && wantScreenOcr()) {
            screenFrameCapture.offerFromScreenBuffer(frame);
        }
    }

    private void stopVideoFramePump() {
        videoFramePumpRunning = false;
        Thread t = videoFramePumpThread;
        videoFramePumpThread = null;
        if (t != null) {
            try {
                t.interrupt();
                t.join(800);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void showVideoHostOnly() {
        emptyStateText.setVisibility(View.GONE);
        if (videoHost != null) {
            videoHost.setVisibility(View.VISIBLE);
        }
        // Clear any leftover status banner so reel playback stays quiet.
        View banner = findViewById(R.id.panel_message_bar);
        if (banner != null) {
            banner.setVisibility(View.GONE);
        }
        setHomeRowCompact(true);
        setCastTheme(true);
        persistSession();
        refreshDock();
    }

    private void stopVideoPlayback() {
        boolean wasPlaying = videoPlaying;
        videoPlaying = false;
        videoPausedForBackground = false;
        videoContentWidth = 0;
        videoContentHeight = 0;
        showVideoBar(false);
        stopVideoFramePump();
        hideVideoStereoOverlay();
        if (listenEngine != null && listenEngine.isRunning() && assistMode == AssistMode.LISTEN
                && mediaProjection == null) {
            listenEngine.stop();
        }
        if (videoPlayer != null) {
            try {
                videoPlayer.stop();
                videoPlayer.release();
            } catch (Throwable ignored) {
            }
            videoPlayer = null;
        }
        if (hostedPlayerView != null) {
            try {
                hostedPlayerView.setPlayer(null);
            } catch (Throwable ignored) {
            }
        }
        videoPcmTap.clear();
        if (videoHost != null) {
            videoHost.setVisibility(View.GONE);
            // Restore full-bleed host for the next session.
            FrameLayout.LayoutParams hostLp =
                    (FrameLayout.LayoutParams) videoHost.getLayoutParams();
            hostLp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            hostLp.height = FrameLayout.LayoutParams.MATCH_PARENT;
            hostLp.gravity = android.view.Gravity.FILL;
            videoHost.setLayoutParams(hostLp);
        }
        if (wasPlaying && assistMode != AssistMode.LISTEN) {
            setSessionStereo(stereoBeforeVideo);
        }
        setHomeRowCompact(false);
        setCastTheme(false);
        if (emptyStateText != null
                && mirroringApp == null && !isBrowserOpen()) {
            emptyStateText.setVisibility(View.VISIBLE);
        }
        persistSession();
        refreshDock();
    }

    private void showDrmBrowserHostOnly() {
        emptyStateText.setVisibility(View.GONE);
        browserHost.setVisibility(View.VISIBLE);
        drmWebView.setVisibility(View.VISIBLE);
        browserChrome.setVisibility(View.VISIBLE);
        setEpubPageBarVisible(true);
        refreshBrowserChrome();
        setHomeRowCompact(true);
        setCastTheme(true);
        persistSession();
        drmWebView.post(() -> {
            if (forceStereoEnabled) {
                startBrowserStereo();
            } else {
                stopBrowserStereo();
            }
        });
    }

    private void loadEpubChapter() {
        if (epubSession == null || drmWebView == null) {
            return;
        }
        String url = epubSession.currentUrl();
        if (url == null) {
            return;
        }
        drmWebView.loadUrl(url);
        if (browserAddress != null) {
            browserAddress.setText(epubSession.title + "  " + (epubSession.index + 1)
                    + "/" + epubSession.chapterUrls.size());
        }
        setEpubPageBarVisible(true);
        refreshBrowserChrome();
        if (epubScrollToBottomOnLoad) {
            epubScrollToBottomOnLoad = false;
            drmWebView.postDelayed(this::epubScrollToBottom, 120);
        }
    }

    private void setEpubPageBarVisible(boolean visible) {
        if (epubPageBar != null) {
            epubPageBar.setVisibility(visible && epubSession != null ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Turn one viewport page inside the chapter. At the top/bottom, move to the
     * previous/next spine chapter so reading feels continuous.
     */
    private void epubTurnPage(boolean forward) {
        if (epubSession == null || drmWebView == null) {
            return;
        }
        String js = forward
                ? "(function(){var h=Math.floor(window.innerHeight*0.92);var max=Math.max(0,(document.documentElement.scrollHeight||document.body.scrollHeight)-window.innerHeight);var y=window.pageYOffset||document.documentElement.scrollTop||0;if(y>=max-4)return 'END';window.scrollTo(0,Math.min(max,y+h));return 'OK';})();"
                : "(function(){var h=Math.floor(window.innerHeight*0.92);var y=window.pageYOffset||document.documentElement.scrollTop||0;if(y<=2)return 'TOP';window.scrollTo(0,Math.max(0,y-h));return 'OK';})();";
        drmWebView.evaluateJavascript(js, value -> {
            String result = value == null ? "" : value.replace("\"", "");
            if (forward && "END".equals(result)) {
                if (epubSession.hasNext()) {
                    epubSession.index++;
                    epubScrollToBottomOnLoad = false;
                    loadEpubChapter();
                } else {
                    PanelAlerts.show(this, getString(R.string.epub_end_of_book));
                }
                return;
            }
            if (!forward && "TOP".equals(result)) {
                if (epubSession.hasPrev()) {
                    epubSession.index--;
                    epubScrollToBottomOnLoad = true;
                    loadEpubChapter();
                }
                return;
            }
            refreshBrowserChrome();
        });
    }

    private void epubScrollToBottom() {
        if (drmWebView == null || epubSession == null) {
            return;
        }
        drmWebView.evaluateJavascript(
                "(function(){var max=Math.max(0,(document.documentElement.scrollHeight||document.body.scrollHeight)-window.innerHeight);window.scrollTo(0,max);})();",
                null);
    }

    private void ensureDialogueTts() {
        if (screenDialogueReader != null) {
            return;
        }
        screenDialogueReader = new ScreenDialogueReader(this);
    }

    private boolean isTtsContinuousActive() {
        return ttsEnabled && !ttsManualMode;
    }

    private boolean wantScreenOcr() {
        // Page Translate owns speech/MT — OCR continuous would re-read the page/UI and loop.
        if (translateUiActive || (pageTranslator != null && pageTranslator.isBusy())) {
            return false;
        }
        if (assistMode == AssistMode.LISTEN) {
            return false;
        }
        if (assistMode == AssistMode.SHARE) {
            return true;
        }
        return isTtsContinuousActive();
    }

    private void handleAssistText(String text, boolean forced) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (translateUiActive || (pageTranslator != null && pageTranslator.isBusy())) {
            return;
        }
        if (assistMode == AssistMode.SHARE) {
            updateShareCaption(text);
        }
        if (forced) {
            if (!ttsEnabled) {
                return;
            }
            Log.i("PanelMainActivity", "Dialogue forced: " + text);
            adoptTtsPlaylist(text);
            speakPlaylistFrom(0);
            return;
        }
        if (!isTtsContinuousActive()) {
            return;
        }
        Log.i("PanelMainActivity", "Dialogue: " + text);
        adoptTtsPlaylist(text);
        ensureDialogueTts();
        if (screenDialogueReader != null) {
            screenDialogueReader.speakDialogue(text, false);
        }
    }

    private void updateShareCaption(String text) {
        if (shareCaption == null) {
            return;
        }
        shareCaption.setText(text);
        shareCaption.setVisibility(assistMode == AssistMode.SHARE ? View.VISIBLE : View.GONE);
    }

    private void setAssistMode(AssistMode mode) {
        if (mode == AssistMode.LISTEN
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            PanelAlerts.show(this, R.string.assist_listen_need_mic);
            ActivityCompat.requestPermissions(
                    this, new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_LISTEN_AUDIO);
            return;
        }
        applyAssistMode(mode, true);
    }

    /**
     * Main-row ear button: one tap turns on Listen with the settings it needs
     * (TTS on + continuous, assist=Listen, 3D off). Tap again to leave Listen.
     */
    private void toggleListenFromToolbar() {
        if (assistMode == AssistMode.LISTEN) {
            setAssistMode(AssistMode.DEFAULT);
            // Drop TTS so off→on can restart STT/speech cleanly (continuous OCR
            // also won't steal the queue after Listen).
            if (ttsEnabled) {
                setTtsEnabled(false);
            }
            PanelAlerts.show(this, R.string.listen_mode_off);
            return;
        }
        if (!ttsEnabled) {
            setTtsEnabled(true);
        }
        if (ttsManualMode) {
            setTtsManualMode(false);
        }
        warmListenMt();
        setAssistMode(AssistMode.LISTEN);
    }

    private void postModelProgress(long downloadedBytes) {
        long mb = downloadedBytes / (1024 * 1024);
        PanelAlerts.show(this, getString(R.string.model_dl_progress, mb));
    }

    /** One-time prompt before the ~1 GB SenseVoice fetch (Listen). */
    private void showModelConsentDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (OfflineModelPack.isAsrReady(this)
                || OfflineModelPack.getModelConsent(this) == OfflineModelPack.CONSENT_ALLOWED) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.model_dl_title)
                .setMessage(R.string.model_dl_body)
                .setPositiveButton(R.string.model_dl_download, (d, w) -> {
                    OfflineModelPack.setModelConsent(this, OfflineModelPack.CONSENT_ALLOWED);
                    OfflineModelPack.startAsrFetch(getApplicationContext(), this::postModelProgress);
                    PanelAlerts.show(this, R.string.model_dl_started);
                })
                .setNegativeButton(R.string.model_dl_later, (d, w) -> {
                    OfflineModelPack.setModelConsent(this, OfflineModelPack.CONSENT_LATER);
                })
                .setCancelable(true)
                .show();
    }

    /** True when Listen may start; otherwise prompts / informs and returns false. */
    private boolean ensureAsrForListen() {
        switch (OfflineModelPack.asrState(this)) {
            case OfflineModelPack.ASR_READY:
                return true;
            case OfflineModelPack.ASR_FETCHING:
                PanelAlerts.show(this, R.string.listen_downloading_retry);
                return false;
            case OfflineModelPack.ASR_NEED_WIFI:
                PanelAlerts.show(this, R.string.listen_need_wifi);
                return false;
            case OfflineModelPack.ASR_READY_TO_FETCH:
                OfflineModelPack.startAsrFetch(getApplicationContext(), this::postModelProgress);
                PanelAlerts.show(this, R.string.model_dl_started);
                return false;
            case OfflineModelPack.ASR_NEED_CONSENT:
            default:
                showModelConsentDialog();
                return false;
        }
    }

    private void warmListenMt() {
        PanelAlerts.show(this, R.string.listen_mt_downloading);
        ListenMtTranslator.get(this).ensureReady((ok, message) -> {
            if (ok) {
                PanelAlerts.show(this, R.string.listen_mt_ready);
            } else {
                PanelAlerts.show(this, R.string.listen_mt_failed);
            }
        });
    }

    private void refreshListenButton() {
        ImageButton btn = findViewById(R.id.listen_button);
        if (btn == null) {
            return;
        }
        boolean on = assistMode == AssistMode.LISTEN;
        btn.setBackgroundResource(on ? R.drawable.bg_circle_button_listen_active : R.drawable.bg_circle_button);
        btn.setAlpha(1f);
    }

    private void applyAssistMode(AssistMode mode, boolean userPicked) {
        AssistMode previous = assistMode;
        assistMode = mode == null ? AssistMode.DEFAULT : mode;
        if (userPicked) {
            settingsStore.setAssistMode(assistMode);
        }
        // Read always OCR→Piper. Translate/Share add OPUS only when the engine switch is OPUS.
        boolean wantTranslateModes = assistMode == AssistMode.TRANSLATE || assistMode == AssistMode.SHARE;
        boolean useOpus = settingsStore.getUseOpusTranslate();
        boolean translateOcr = wantTranslateModes && useOpus;
        dialogueTextExtractor.setCjkOcr(wantTranslateModes);
        dialogueTextExtractor.setTranslateToEnglish(translateOcr);
        if (shareCaption != null) {
            shareCaption.setVisibility(assistMode == AssistMode.SHARE ? View.VISIBLE : View.GONE);
            if (assistMode != AssistMode.SHARE) {
                shareCaption.setText("");
            }
        }
        refreshAssistModeButtons();
        refreshMtEngineButtons();
        refreshListenButton();
        if (translateOcr) {
            OnDeviceTranslator.get(this).ensureReady(ok -> { });
        }
        if (assistMode == AssistMode.LISTEN) {
            if (previous != AssistMode.LISTEN) {
                stereoBeforeListen = forceStereoEnabled;
                // Settings chip path — warm bundled OPUS (toolbar already called warmListenMt).
                ListenMtTranslator.get(this).ensureReady((ok, message) -> { });
            }
            setSessionStereo(false);
            syncListenEngine(userPicked);
            if (userPicked && previous != AssistMode.LISTEN) {
                PanelAlerts.show(this, R.string.listen_mode_on);
            }
        } else {
            if (listenEngine != null) {
                listenEngine.stop();
            }
            if (previous == AssistMode.LISTEN) {
                setSessionStereo(stereoBeforeListen);
            }
        }
        refreshControlRowChrome();
        if (videoPlaying) {
            setVideoDisplayMode();
        }
    }

    /**
     * Share mode crowds the bottom bar with captions + TTS — hide browser (globe)
     * and My Books only while Share is active; restore them as soon as Share ends.
     */
    private void refreshControlRowChrome() {
        boolean hideReadingApps = assistMode == AssistMode.SHARE;
        int readingVisibility = hideReadingApps ? View.GONE : View.VISIBLE;
        if (browserButton != null) {
            browserButton.setVisibility(readingVisibility);
        }
        View epub = findViewById(R.id.epub_button);
        if (epub != null) {
            epub.setVisibility(readingVisibility);
        }
        View video = findViewById(R.id.video_button);
        if (video != null) {
            video.setVisibility(readingVisibility);
        }
    }

    private void refreshAssistModeButtons() {
        styleAssistChip(findViewById(R.id.assist_mode_default), assistMode == AssistMode.DEFAULT);
        styleAssistChip(findViewById(R.id.assist_mode_translate), assistMode == AssistMode.TRANSLATE);
        styleAssistChip(findViewById(R.id.assist_mode_listen), assistMode == AssistMode.LISTEN);
        styleAssistChip(findViewById(R.id.assist_mode_share), assistMode == AssistMode.SHARE);
        TextView pipeline = findViewById(R.id.assist_pipeline_status);
        if (pipeline != null) {
            pipeline.setText(assistMode.pipelineStringRes(settingsStore.getUseOpusTranslate()));
        }
    }

    private void styleAssistChip(Button btn, boolean active) {
        if (btn == null) {
            return;
        }
        btn.setAlpha(1f);
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(active ? R.color.assist_chip_active_bg : R.color.dock_icon_bg)));
        btn.setTextColor(active ? 0xFFFFFFFF : getColor(R.color.text_primary));
    }

    private void setUseOpusTranslate(boolean useOpus) {
        settingsStore.setUseOpusTranslate(useOpus);
        applyAssistMode(assistMode, false);
        refreshMtEngineButtons();
    }

    private void refreshMtEngineButtons() {
        boolean useOpus = settingsStore.getUseOpusTranslate();
        styleAssistChip(findViewById(R.id.mt_engine_opus), useOpus);
        styleAssistChip(findViewById(R.id.mt_engine_mlkit), !useOpus);
    }

    private void setSessionStereo(boolean on) {
        ToggleButton toggle = findViewById(R.id.toggle_stereo_3d);
        if (toggle == null) {
            forceStereoEnabled = on;
            return;
        }
        if (toggle.isChecked() == on) {
            forceStereoEnabled = on;
            return;
        }
        suppressStereoPersist = true;
        toggle.setChecked(on);
        suppressStereoPersist = false;
    }

    private void syncListenEngine(boolean notifyIfNoSource) {
        if (assistMode != AssistMode.LISTEN || listenEngine == null) {
            return;
        }
        if (videoPlaying && videoPlayer != null) {
            if (!listenEngine.isRunning()) {
                if (!ensureAsrForListen()) {
                    return;
                }
                listenEngine.setSmoothnessPercent(settingsStore.getListenSmoothnessPercent());
                listenEngine.startFromPcm(videoPcmTap);
            }
            return;
        }
        if (mediaProjection == null) {
            if (notifyIfNoSource) {
                PanelAlerts.show(this, R.string.video_listen_no_file);
            }
            return;
        }
        if (!listenEngine.isRunning()) {
            if (!ensureAsrForListen()) {
                return;
            }
            listenEngine.setSmoothnessPercent(settingsStore.getListenSmoothnessPercent());
            listenEngine.start(mediaProjection);
        }
    }

    private void setTtsEnabled(boolean enabled) {
        ttsEnabled = enabled;
        settingsStore.setTtsEnabled(enabled);
        if (enabled) {
            PiperTtsEngine.get(this).ensureReadyAsync();
            ensureDialogueTts();
        } else {
            PiperTtsEngine.get(this).stopSpeaking();
            if (screenDialogueReader != null) {
                screenDialogueReader.shutdown();
                screenDialogueReader = null;
            }
        }
        Switch master = findViewById(R.id.toggle_tts);
        if (master != null && master.isChecked() != enabled) {
            master.setChecked(enabled);
        }
        refreshTtsModeSwitches();
        refreshTtsSpeakButton();
        if (videoPlaying) {
            setVideoDisplayMode();
        }
    }

    private void setTtsManualMode(boolean manual) {
        ttsManualMode = manual;
        settingsStore.setTtsManualMode(manual);
        if (manual) {
            // Auto-read off while manual is on — stop continuous OCR/speech queue.
            PiperTtsEngine.get(this).stopSpeaking();
        }
        if (ttsEnabled) {
            ensureDialogueTts();
        }
        refreshTtsModeSwitches();
        refreshTtsSpeakButton();
        if (videoPlaying) {
            setVideoDisplayMode();
        }
    }

    private void refreshTtsModeSwitches() {
        Switch auto = findViewById(R.id.toggle_tts_auto);
        Switch manual = findViewById(R.id.toggle_tts_manual);
        if (auto == null || manual == null) {
            return;
        }
        auto.setEnabled(ttsEnabled);
        manual.setEnabled(ttsEnabled);
        boolean wantAuto = ttsEnabled && !ttsManualMode;
        boolean wantManual = ttsEnabled && ttsManualMode;
        if (auto.isChecked() != wantAuto) {
            auto.setChecked(wantAuto);
        }
        if (manual.isChecked() != wantManual) {
            manual.setChecked(wantManual);
        }
        auto.setAlpha(ttsEnabled ? 1f : 0.4f);
        manual.setAlpha(ttsEnabled ? 1f : 0.4f);
    }

    private boolean isTtsPlayerAvailable() {
        return ttsEnabled && ttsManualMode;
    }

    private boolean isPlaybackPaused() {
        return ttsHeldPaused || PiperTtsEngine.get(this).isPaused();
    }

    private static final long TTS_PLAYER_HIDE_MS = 900L;
    private final Runnable hideTtsPlayerPopupRunnable = () -> {
        if (ttsPlayer != null) {
            ttsPlayer.setVisibility(View.GONE);
        }
    };

    private void wireTtsPlayerHoverPopup() {
        View.OnHoverListener hover = (v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_MOVE:
                    showTtsPlayerPopup();
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    scheduleHideTtsPlayerPopup();
                    break;
                default:
                    break;
            }
            return false;
        };
        if (ttsSpeakButton != null) {
            ttsSpeakButton.setOnHoverListener(hover);
        }
        if (ttsPlayer != null) {
            ttsPlayer.setOnHoverListener(hover);
        }
        View[] transport = {ttsPrevButton, ttsPlayButton, ttsPauseButton, ttsStopButton, ttsNextButton};
        for (View btn : transport) {
            if (btn != null) {
                btn.setOnHoverListener(hover);
            }
        }
    }

    private void showTtsPlayerPopup() {
        if (ttsPlayer == null || !isTtsPlayerAvailable()) {
            return;
        }
        ttsPreviewHandler.removeCallbacks(hideTtsPlayerPopupRunnable);
        ttsPlayer.setVisibility(View.VISIBLE);
    }

    private void scheduleHideTtsPlayerPopup() {
        ttsPreviewHandler.removeCallbacks(hideTtsPlayerPopupRunnable);
        ttsPreviewHandler.postDelayed(hideTtsPlayerPopupRunnable, TTS_PLAYER_HIDE_MS);
    }

    private void refreshTtsSpeakButton() {
        // Transport stays off the bar until the ray hovers the speaker.
        if (ttsPlayer != null && !isTtsPlayerAvailable()) {
            ttsPreviewHandler.removeCallbacks(hideTtsPlayerPopupRunnable);
            ttsPlayer.setVisibility(View.GONE);
        }
        refreshControlRowChrome();
        if (!isTtsPlayerAvailable()) {
            hideTtsKaraoke();
        }
        if (ttsSpeakButton == null) {
            return;
        }
        ttsSpeakButton.setVisibility(View.VISIBLE);
        ttsSpeakButton.setColorFilter(null);
        PiperTtsEngine piper = PiperTtsEngine.get(this);
        boolean playing = (piper.isSpeaking() || ttsEngineSpeaking) && !isPlaybackPaused();
        boolean paused = isPlaybackPaused();
        if (!ttsEnabled) {
            ttsSpeakButton.setImageResource(R.drawable.ic_tts_speaker);
            ttsSpeakButton.setBackgroundResource(R.drawable.bg_circle_button);
            ttsSpeakButton.setAlpha(0.4f);
            ttsSpeakButton.setContentDescription(getString(R.string.tts_turn_on_first));
        } else if (!ttsManualMode) {
            ttsSpeakButton.setImageResource(R.drawable.ic_tts_loop);
            ttsSpeakButton.setBackgroundResource(R.drawable.bg_circle_button_tts_loop);
            ttsSpeakButton.setAlpha(1f);
            ttsSpeakButton.setContentDescription(getString(R.string.tts_speak_continuous_description));
        } else {
            ttsSpeakButton.setImageResource(R.drawable.ic_tts_speaker);
            ttsSpeakButton.setBackgroundResource(R.drawable.bg_circle_button_tts_once);
            ttsSpeakButton.setAlpha(paused ? 0.7f : 1f);
            ttsSpeakButton.setContentDescription(getString(R.string.tts_speak_once_hover_description));
        }
        if (ttsPlayButton != null) {
            ttsPlayButton.setImageResource(R.drawable.ic_tts_play);
            ttsPlayButton.setContentDescription(getString(R.string.tts_play_description));
            ttsPlayButton.setBackgroundResource(playing
                    ? R.drawable.bg_circle_button_tts_once
                    : R.drawable.bg_circle_button);
        }
        if (ttsPauseButton != null) {
            ttsPauseButton.setImageResource(R.drawable.ic_tts_pause);
            ttsPauseButton.setContentDescription(getString(R.string.tts_pause_description));
            ttsPauseButton.setBackgroundResource(paused
                    ? R.drawable.bg_circle_button_tts_once
                    : R.drawable.bg_circle_button);
        }
    }

    private void onTtsSpeakButtonClicked() {
        if (!ttsEnabled) {
            PanelAlerts.show(this, R.string.tts_turn_on_first);
            return;
        }
        if (!ttsManualMode) {
            return;
        }
        ttsHeldPaused = false;
        PiperTtsEngine.get(this).stopSpeaking();
        requestTapToSpeak();
    }

    private void onTtsPlayClicked() {
        if (!ttsEnabled) {
            PanelAlerts.show(this, R.string.tts_turn_on_first);
            return;
        }
        PiperTtsEngine piper = PiperTtsEngine.get(this);
        ttsHeldPaused = false;
        if (piper.hasPausedTrack()) {
            piper.resumePlayback();
            refreshTtsSpeakButton();
            return;
        }
        if (!ttsPlaylist.isEmpty()) {
            speakPlaylistFrom(ttsPlaylistIndex);
            refreshTtsSpeakButton();
            return;
        }
        requestTapToSpeak();
    }

    private void onTtsPauseClicked() {
        if (!ttsEnabled) {
            PanelAlerts.show(this, R.string.tts_turn_on_first);
            return;
        }
        PiperTtsEngine piper = PiperTtsEngine.get(this);
        if (piper.isSpeaking() || ttsEngineSpeaking) {
            piper.pausePlayback();
            ttsHeldPaused = true;
            refreshTtsSpeakButton();
        }
    }

    private void onTtsStopClicked() {
        ttsHeldPaused = false;
        PiperTtsEngine.get(this).stopSpeaking();
        hideTtsKaraoke();
        refreshTtsSpeakButton();
        PanelAlerts.show(this, R.string.tts_stopped);
    }

    private void onTtsPrevClicked() {
        stepTtsPlaylist(-1);
    }

    private void onTtsNextClicked() {
        stepTtsPlaylist(1);
    }

    private void stepTtsPlaylist(int delta) {
        if (!ttsEnabled || !ttsManualMode) {
            return;
        }
        if (ttsPlaylist.isEmpty()) {
            PanelAlerts.show(this, R.string.tts_nothing_to_read);
            requestTapToSpeak();
            return;
        }
        int next = ttsPlaylistIndex + delta;
        if (delta < 0 && next < 0) {
            next = 0;
        }
        if (delta > 0 && next >= ttsPlaylist.size()) {
            PanelAlerts.show(this, R.string.tts_end_of_lines);
            return;
        }
        ttsPlaylistIndex = Math.max(0, Math.min(next, ttsPlaylist.size() - 1));
        ttsHeldPaused = false;
        speakPlaylistFrom(ttsPlaylistIndex);
        refreshTtsSpeakButton();
    }

    private void showPausedKaraokeLine(String line) {
        if (ttsKaraoke == null || line == null) {
            return;
        }
        ttsKaraoke.setText(line);
        ttsKaraoke.setVisibility(View.VISIBLE);
        ttsKaraoke.bringToFront();
    }

    private void adoptTtsPlaylist(String text) {
        ttsPlaylist.clear();
        ttsPlaylist.addAll(splitUtterances(text));
        ttsPlaylistIndex = 0;
    }

    private void speakPlaylistFrom(int start) {
        if (ttsPlaylist.isEmpty()) {
            return;
        }
        int index = Math.max(0, Math.min(start, ttsPlaylist.size() - 1));
        ttsPlaylistIndex = index;
        ttsPreviewActive = false;
        ensureDialogueTts();
        ArrayList<String> rest = new ArrayList<>(ttsPlaylist.subList(index, ttsPlaylist.size()));
        PiperTtsEngine.get(this).speakAll(rest);
    }

    private void speakPlaylistIndex(int index) {
        if (index < 0 || index >= ttsPlaylist.size()) {
            return;
        }
        ttsPlaylistIndex = index;
        ttsPreviewActive = false;
        ensureDialogueTts();
        PiperTtsEngine.get(this).interruptAndSpeak(ttsPlaylist.get(index));
    }

    private static List<String> splitUtterances(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String[] parts = text.trim().split("(?<=[.!?。！？])\\s*|\\n+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        if (out.isEmpty() && !text.trim().isEmpty()) {
            out.add(text.trim());
        }
        return out;
    }

    private void scheduleTtsVoicePreview(int delayMs) {
        ttsPreviewHandler.removeCallbacks(ttsPreviewRunnable);
        ttsPreviewHandler.postDelayed(ttsPreviewRunnable, delayMs);
    }

    private void playTtsVoicePreview() {
        ttsPreviewActive = true;
        PiperTtsEngine.get(this).interruptAndSpeak(getString(R.string.tts_preview_sample));
    }

    private void updateTtsKaraoke(String text, int start, int end) {
        if (ttsKaraoke == null) {
            return;
        }
        boolean show = ttsPreviewActive || ttsManualMode;
        if (ttsHeldPaused && (text == null || text.isEmpty() || start < 0)) {
            return;
        }
        if (!show || text == null || text.isEmpty() || start < 0) {
            hideTtsKaraoke();
            return;
        }
        SpannableString span = new SpannableString(text);
        int s = Math.max(0, Math.min(start, text.length()));
        int e = Math.max(s, Math.min(end, text.length()));
        if (e > s) {
            span.setSpan(new BackgroundColorSpan(0xCC22C55E), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new ForegroundColorSpan(0xFF052E16), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        ttsKaraoke.setText(span);
        ttsKaraoke.setVisibility(View.VISIBLE);
        ttsKaraoke.bringToFront();
        if (ocrEditorBar != null && ocrEditorBar.getVisibility() == View.VISIBLE) {
            ocrEditorBar.bringToFront();
        }
    }

    private void hideTtsKaraoke() {
        if (ttsKaraoke != null) {
            ttsKaraoke.setVisibility(View.GONE);
            ttsKaraoke.setText("");
        }
    }

    private void requestTapToSpeak() {
        ensureDialogueTts();
        Bitmap raw = null;
        boolean recycleRaw = true;
        if (isBrowserOpen() && drmWebView != null
                && drmWebView.getWidth() > 0 && drmWebView.getHeight() > 0) {
            // Fresh WebView snapshot — do not wait on the 3D compose buffer.
            int w = drmWebView.getWidth();
            int h = drmWebView.getHeight();
            raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(raw);
            canvas.drawColor(Color.BLACK);
            pollBrowserPageScroll();
            canvas.save();
            canvas.translate(-browserCaptureScrollX(), -browserCaptureScrollY());
            drmWebView.draw(canvas);
            canvas.restore();
        } else {
            raw = screenFrameCapture.copyLatestSource();
        }
        if (raw == null) {
            PanelAlerts.show(this, R.string.tts_nothing_to_read);
            return;
        }
        Bitmap dialogue = screenFrameCapture.prepareDialogueFrame(raw);
        raw.recycle();
        if (dialogue == null) {
            PanelAlerts.show(this, R.string.tts_nothing_to_read);
            return;
        }
        PanelAlerts.show(this, R.string.tts_reading_now);
        dialogueTextExtractor.analyzeForced(dialogue);
        dialogue.recycle();
    }

    private void refreshVoiceButtons(boolean male) {
        Button female = findViewById(R.id.tts_voice_female);
        Button maleBtn = findViewById(R.id.tts_voice_male);
        if (female == null || maleBtn == null) {
            return;
        }
        female.setAlpha(male ? 0.45f : 1f);
        maleBtn.setAlpha(male ? 1f : 0.45f);
    }

    private String ocrSmoothnessLabel(int percent) {
        if (percent >= 70) {
            return getString(R.string.ocr_smoothness_smooth);
        }
        if (percent >= 35) {
            return getString(R.string.ocr_smoothness_balanced);
        }
        return getString(R.string.ocr_smoothness_fast);
    }

    private String listenSmoothnessLabel(int percent) {
        if (percent >= 70) {
            return getString(R.string.listen_smoothness_smooth);
        }
        if (percent >= 35) {
            return getString(R.string.listen_smoothness_balanced);
        }
        return getString(R.string.listen_smoothness_fast);
    }

    private List<OcrRegion> currentOcrRegionsForSave() {
        if (ocrRegionOverlay != null && ocrRegionOverlay.getVisibility() == View.VISIBLE) {
            return ocrRegionOverlay.getRegions();
        }
        if (ocrRegionStore.hasCustomRegions()) {
            return ocrRegionStore.load();
        }
        return java.util.Collections.singletonList(OcrRegion.defaultSubtitleBand());
    }

    private void loadOcrPreset(int slot) {
        if (slot <= 0) {
            ocrRegionStore.clearCustom();
            screenFrameCapture.setOcrRegions(java.util.Collections.emptyList());
            if (ocrRegionOverlay != null && ocrRegionOverlay.getVisibility() == View.VISIBLE) {
                ocrRegionOverlay.setRegions(
                        java.util.Collections.singletonList(OcrRegion.defaultSubtitleBand()));
            }
            return;
        }
        if (!ocrRegionStore.hasPreset(slot)) {
            return;
        }
        ocrRegionStore.applySlot(slot, screenFrameCapture);
        if (ocrRegionOverlay != null && ocrRegionOverlay.getVisibility() == View.VISIBLE) {
            ocrRegionOverlay.setRegions(ocrRegionStore.load());
        }
    }

    private String currentOcrAppKey() {
        if (mirroringApp != null && mirroringApp.packageName != null) {
            return mirroringApp.packageName;
        }
        if (isBrowserOpen()) {
            return OcrRegionStore.BROWSER_KEY;
        }
        return null;
    }

    private void applyAssignedOcrPreset(String key) {
        List<OcrRegion> forApp = ocrRegionStore.loadForApp(key);
        if (!forApp.isEmpty()) {
            ocrRegionStore.save(forApp);
            screenFrameCapture.setOcrRegions(forApp);
            return;
        }
        int slot = ocrRegionStore.assignedSlot(key);
        if (slot < 0) {
            return;
        }
        loadOcrPreset(slot);
    }

    private void openOcrRegionEditor() {
        if (ocrRegionOverlay == null) {
            return;
        }
        if (settingsDrawer != null && settingsDrawer.getVisibility() == View.VISIBLE) {
            closeSideDrawer(settingsDrawer);
        }
        if (helpDrawer != null && helpDrawer.getVisibility() == View.VISIBLE) {
            closeSideDrawer(helpDrawer);
        }
        ocrEditTargetKey = currentOcrAppKey();
        List<OcrRegion> regions;
        if (ocrEditTargetKey != null && ocrRegionStore.hasAppLayout(ocrEditTargetKey)) {
            regions = ocrRegionStore.loadForApp(ocrEditTargetKey);
        } else if (ocrRegionStore.hasCustomRegions()) {
            regions = ocrRegionStore.load();
        } else {
            regions = java.util.Collections.singletonList(OcrRegion.defaultSubtitleBand());
        }
        ocrRegionOverlay.setRegions(regions);
        ocrRegionOverlay.setVisibility(View.VISIBLE);
        ocrRegionOverlay.bringToFront();
        if (ocrEditorBar != null) {
            ocrEditorBar.setVisibility(View.VISIBLE);
            ocrEditorBar.bringToFront();
        }
        refreshOcrAppStrip();
    }

    private void closeOcrRegionEditor() {
        if (ocrRegionOverlay != null) {
            ocrRegionOverlay.setVisibility(View.GONE);
        }
        if (ocrEditorBar != null) {
            ocrEditorBar.setVisibility(View.GONE);
        }
    }

    private void saveOcrLayoutForSelectedApp() {
        if (ocrEditTargetKey == null) {
            PanelAlerts.show(this, R.string.ocr_assign_need_app);
            return;
        }
        List<OcrRegion> regions = currentOcrRegionsForSave();
        String label = labelForOcrKey(ocrEditTargetKey);
        ocrRegionStore.saveForApp(ocrEditTargetKey, label, regions);
        screenFrameCapture.setOcrRegions(regions);
        PanelAlerts.show(this, getString(R.string.ocr_saved_for, label));
        refreshOcrAppStrip();
    }

    private String labelForOcrKey(String key) {
        if (OcrRegionStore.BROWSER_KEY.equals(key)) {
            return getString(R.string.ocr_browser);
        }
        InstalledAppInfo app = resolveInstalledApp(key);
        if (app != null) {
            return app.label;
        }
        return ocrRegionStore.labelForApp(key);
    }

    private void selectOcrTarget(String key, boolean loadSaved) {
        ocrEditTargetKey = key;
        if (loadSaved && ocrRegionStore.hasAppLayout(key)) {
            List<OcrRegion> regions = ocrRegionStore.loadForApp(key);
            if (!regions.isEmpty()) {
                ocrRegionOverlay.setRegions(regions);
            }
        }
        refreshOcrAppStrip();
    }

    private void refreshOcrAppStrip() {
        if (ocrAppStrip == null) {
            return;
        }
        ocrAppStrip.removeAllViews();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(OcrRegionStore.BROWSER_KEY);
        if (mirroringApp != null && mirroringApp.packageName != null) {
            keys.add(mirroringApp.packageName);
        }
        for (String pkg : libraryStore.getPinnedPackages()) {
            keys.add(pkg);
        }
        keys.addAll(ocrRegionStore.savedAppKeys());

        if (ocrEditTargetKey == null && !keys.isEmpty()) {
            ocrEditTargetKey = currentOcrAppKey() != null
                    ? currentOcrAppKey()
                    : keys.iterator().next();
        }

        for (String key : keys) {
            View chip = LayoutInflater.from(this).inflate(R.layout.item_ocr_app_chip, ocrAppStrip, false);
            ImageView icon = chip.findViewById(R.id.ocr_chip_icon);
            TextView label = chip.findViewById(R.id.ocr_chip_label);
            String name = labelForOcrKey(key);
            label.setText(name);
            if (OcrRegionStore.BROWSER_KEY.equals(key)) {
                icon.setImageResource(R.drawable.ic_globe);
            } else {
                InstalledAppInfo app = resolveInstalledApp(key);
                if (app != null && app.icon != null) {
                    icon.setImageDrawable(app.icon);
                } else {
                    icon.setImageResource(R.drawable.ic_ocr_region);
                }
            }
            boolean selected = key.equals(ocrEditTargetKey);
            chip.setBackgroundResource(selected
                    ? R.drawable.bg_ocr_app_chip_selected
                    : R.drawable.bg_ocr_app_chip);
            chip.setOnClickListener(v -> selectOcrTarget(key, true));
            ocrAppStrip.addView(chip);
        }

        if (ocrTargetLabel != null) {
            String name = ocrEditTargetKey != null
                    ? labelForOcrKey(ocrEditTargetKey)
                    : getString(R.string.ocr_pick_app);
            ocrTargetLabel.setText(ocrEditTargetKey != null
                    ? getString(R.string.ocr_zones_for, name)
                    : getString(R.string.ocr_pick_app));
        }
    }

    private WebView createBrowserPopupTab() {
        WebView tab = new WebView(this);
        tab.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyBrowserWebView(tab);
        browserHost.addView(tab);
        browserTabs.add(tab);
        switchToBrowserTab(tab);
        return tab;
    }

    private void switchToBrowserTab(WebView tab) {
        if (tab == null) {
            return;
        }
        drmWebView = tab;
        for (WebView other : browserTabs) {
            other.setVisibility(other == tab ? View.VISIBLE : View.GONE);
        }
        String url = tab.getUrl();
        if (browserAddress != null && url != null && !browserAddress.hasFocus()) {
            browserAddress.setText(url);
        }
        tab.requestFocus();
        refreshBrowserChrome();
        refreshBrowserTabs();
    }

    private void closeBrowserTab(WebView tab) {
        if (tab == null || !browserTabs.contains(tab)) {
            return;
        }
        if (browserTabs.size() <= 1) {
            hideDrmBrowser();
            return;
        }
        int index = browserTabs.indexOf(tab);
        browserTabs.remove(tab);
        browserHost.removeView(tab);
        tab.destroy();
        WebView next = browserTabs.get(Math.max(0, index - 1));
        switchToBrowserTab(next);
    }

    private void refreshBrowserTabs() {
        if (browserTabStrip == null || browserTabScroll == null) {
            return;
        }
        boolean show = isBrowserOpen() && browserTabs.size() > 1;
        browserTabScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        browserTabStrip.removeAllViews();
        if (!show) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (WebView tab : browserTabs) {
            View item = inflater.inflate(R.layout.item_browser_tab, browserTabStrip, false);
            TextView titleView = item.findViewById(R.id.browser_tab_title);
            String title = tab.getTitle();
            if (title == null || title.trim().isEmpty()) {
                title = tab.getUrl() != null ? tab.getUrl() : "Tab";
            }
            titleView.setText(title);
            item.setAlpha(tab == drmWebView ? 1f : 0.55f);
            item.setOnClickListener(v -> switchToBrowserTab(tab));
            item.findViewById(R.id.browser_tab_close).setOnClickListener(v -> closeBrowserTab(tab));
            browserTabStrip.addView(item);
        }
    }

    private void refreshBrowserChrome() {
        ImageButton back = findViewById(R.id.browser_back_button);
        ImageButton forward = findViewById(R.id.browser_forward_button);
        boolean canBack;
        boolean canForward;
        if (epubSession != null) {
            // Page turn can always try; chapter edges are handled inside epubTurnPage.
            canBack = true;
            canForward = true;
            setEpubPageBarVisible(true);
        } else {
            canBack = drmWebView != null && drmWebView.canGoBack();
            canForward = drmWebView != null && drmWebView.canGoForward();
            setEpubPageBarVisible(false);
        }
        if (back != null) {
            back.setEnabled(canBack);
            back.setAlpha(canBack ? 1f : 0.35f);
        }
        if (forward != null) {
            forward.setEnabled(canForward);
            forward.setAlpha(canForward ? 1f : 0.35f);
        }
        refreshBrowserTabs();
        refreshBookmarkButton();
    }

    private void rememberBrowserVisit(WebView view, String url) {
        if (view == null || url == null) {
            return;
        }
        if (epubSession != null || url.startsWith("file:")) {
            return;
        }
        browserLibraryStore.rememberVisit(url, view.getTitle());
        refreshBookmarkButton();
    }

    private void refreshBookmarkButton() {
        ImageButton bookmark = findViewById(R.id.browser_bookmark_button);
        if (bookmark == null || drmWebView == null) {
            return;
        }
        boolean on = browserLibraryStore.isBookmarked(drmWebView.getUrl());
        bookmark.setAlpha(on ? 1f : 0.45f);
    }

    private void toggleCurrentBookmark() {
        if (drmWebView == null) {
            return;
        }
        String url = drmWebView.getUrl();
        browserLibraryStore.toggleBookmark(url, drmWebView.getTitle());
        refreshBookmarkButton();
    }

    private void showBrowserLists() {
        String[] choices = new String[] {
                getString(R.string.browser_bookmarks_title),
                getString(R.string.browser_history_title)
        };
        new AlertDialog.Builder(this)
                .setItems(choices, (d, which) -> {
                    if (which == 0) {
                        showPageList(getString(R.string.browser_bookmarks_title),
                                browserLibraryStore.getBookmarks());
                    } else {
                        showPageList(getString(R.string.browser_history_title),
                                browserLibraryStore.getHistory());
                    }
                })
                .show();
    }

    private void showPageList(String title, java.util.List<BrowserLibraryStore.PageEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(R.string.browser_empty_list)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            labels[i] = entries.get(i).title;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, (d, which) -> {
                    BrowserLibraryStore.PageEntry entry = entries.get(which);
                    if (!isBrowserOpen()) {
                        showDrmBrowser(entry.url);
                    } else {
                        WidevineWebViewConfig.loadSecureUrl(drmWebView, entry.url);
                    }
                })
                .show();
    }

    private String currentBrowserUrlOrHome() {
        String typed = browserAddress.getText() != null ? browserAddress.getText().toString().trim() : "";
        if (!typed.isEmpty()) {
            return typed;
        }
        String current = drmWebView.getUrl();
        if (current != null && !current.isEmpty() && !"about:blank".equals(current)) {
            return current;
        }
        return "https://www.google.com";
    }

    private void goToAddressBarUrl() {
        String raw = browserAddress.getText() != null ? browserAddress.getText().toString().trim() : "";
        if (raw.isEmpty()) {
            return;
        }
        epubSession = null;
        String url = raw;
        if (!raw.contains(".") && !raw.startsWith("http")) {
            url = "https://www.google.com/search?q=" + android.net.Uri.encode(raw);
        }
        hideKeyboard();
        if (!isBrowserOpen()) {
            showDrmBrowser(url);
        } else {
            WidevineWebViewConfig.loadSecureUrl(drmWebView, url);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && browserAddress != null) {
            imm.hideSoftInputFromWindow(browserAddress.getWindowToken(), 0);
        }
    }

    private void showDrmBrowser(String httpsUrl) {
        epubSession = null;
        setEpubPageBarVisible(false);
        emptyStateText.setVisibility(View.GONE);
        browserHost.setVisibility(View.VISIBLE);
        drmWebView.setVisibility(View.VISIBLE);
        browserChrome.setVisibility(View.VISIBLE);
        WidevineWebViewConfig.loadSecureUrl(drmWebView, httpsUrl);
        browserAddress.setText(httpsUrl);
        refreshBrowserChrome();
        setHomeRowCompact(true);
        setCastTheme(true);
        applyAssignedOcrPreset(OcrRegionStore.BROWSER_KEY);
        persistSession();
        drmWebView.post(() -> {
            if (forceStereoEnabled) {
                startBrowserStereo();
            } else {
                stopBrowserStereo();
            }
        });
    }

    private void hideDrmBrowser() {
        if (drmWebView == null) {
            return;
        }
        setEpubPageBarVisible(false);
        stopTranslateUi();
        hideKeyboard();
        if (drmWebView != null) {
            rememberBrowserVisit(drmWebView, drmWebView.getUrl());
        }
        stopBrowserStereo();
        for (int i = browserTabs.size() - 1; i >= 0; i--) {
            WebView tab = browserTabs.get(i);
            tab.stopLoading();
            if (tab != findViewById(R.id.webView)) {
                browserHost.removeView(tab);
                tab.destroy();
                browserTabs.remove(i);
            } else {
                tab.loadUrl("about:blank");
                tab.setVisibility(View.VISIBLE);
                drmWebView = tab;
            }
        }
        browserHost.setVisibility(View.GONE);
        if (browserChrome != null) {
            browserChrome.setVisibility(View.GONE);
        }
        if (browserTabScroll != null) {
            browserTabScroll.setVisibility(View.GONE);
        }
        if (mirroringApp != null) {
            setStereoOutputVisible(true);
            setHomeRowCompact(true);
            setCastTheme(true);
        } else {
            emptyStateText.setVisibility(View.VISIBLE);
            setHomeRowCompact(false);
            setCastTheme(false);
        }
        persistSession();
    }

    private final Handler browserStereoHandler = new Handler(Looper.getMainLooper());
    private boolean browserStereoRunning = false;
    /** True after stereo has a frame and the live WebView is hidden (avoids PiP). */
    private boolean browserStereoLiveHidden;
    private Bitmap browserCaptureBitmap;
    private boolean browserGpuCanvas;
    private final AtomicBoolean browserPixelCopyBusy = new AtomicBoolean(false);

    private final Runnable browserStereoTick = new Runnable() {
        @Override
        public void run() {
            captureBrowserStereoFrame();
            if (browserStereoRunning) {
                browserStereoHandler.postDelayed(this, 40);
            }
        }
    };

    private void startBrowserStereo() {
        if (!isBrowserOpen()) {
            return;
        }
        finishStartBrowserStereo();
    }

    private void finishStartBrowserStereo() {
        if (!isBrowserOpen()) {
            return;
        }
        browserStereoLiveHidden = false;
        setBrowserStereoCaptureMode(true);
        setStereoOutputVisible(true);
        gameRenderSurface.setClickable(false);
        if (gameRenderSurface != null) {
            gameRenderSurface.bringToFront();
        }
        if (overlayView != null) {
            overlayView.setVisibility(View.VISIBLE);
            overlayView.bringToFront();
            overlayView.setOnTouchListener((v, event) -> {
                if (requiresUiInputChannel() || !canPassThroughCastTouches()) {
                    return false;
                }
                View castView = resolveCastTarget();
                if (castView == null) {
                    return false;
                }
                return forwardTouchToCastView(event, castView, v);
            });
        }
        setStereoComposition(forceStereoEnabled);
        activeStereoSurface().post(() -> {
            fitSurfaceToCaptureAspectRatio();
            activeStereoSurface().post(this::lockSurfaceBufferSize);
        });
        if (!browserStereoRunning) {
            browserStereoRunning = true;
            browserStereoHandler.removeCallbacks(browserStereoTick);
            browserStereoHandler.post(browserStereoTick);
        }
    }

    /**
     * Key difference: casting target dependencies.
     *
     * <pre>
     * | Cast source type                      | Touch pass-through behavior                                      |
     * | Local App Stream (internal cast view) | Direct / instant: dispatchTouchEvent as long as the view sits    |
     * |                                       | in the local view hierarchy.                                     |
     * | Remote screen cast                    | Requires a UI input channel. Touches must be serialized and sent |
     * | (Miracast / Scrcpy / WebRTC /         | over a control socket (ADB, HID over USB/IP, or a WebRTC data    |
     * | MediaProjection of another app)       | channel) to register clicks on the host. Local dispatchTouchEvent|
     * |                                       | does not reach that process.                                     |
     * | No cast                               | Overlay hidden; home row / dock get the poke                     |
     * </pre>
     */
    private enum CastSourceType {
        LOCAL_APP_STREAM,
        REMOTE_SCREEN_CAST,
        NONE
    }

    private CastSourceType currentCastSourceType() {
        if (videoPlaying && forceStereoEnabled && hostedPlayerView != null) {
            return CastSourceType.LOCAL_APP_STREAM;
        }
        if (isBrowserOpen() && drmWebView != null && drmWebView.getVisibility() == View.VISIBLE) {
            return CastSourceType.LOCAL_APP_STREAM;
        }
        if (mirroringApp != null) {
            return CastSourceType.REMOTE_SCREEN_CAST;
        }
        return CastSourceType.NONE;
    }

    /** Direct pass-through only for a local-hierarchy internal cast view. */
    private boolean canPassThroughCastTouches() {
        return currentCastSourceType() == CastSourceType.LOCAL_APP_STREAM;
    }

    /** Remote pixels only; injecting would need ADB/HID/WebRTC, which this panel does not open. */
    private boolean requiresUiInputChannel() {
        return currentCastSourceType() == CastSourceType.REMOTE_SCREEN_CAST;
    }

    private View resolveCastTarget() {
        if (videoPlaying && forceStereoEnabled && hostedPlayerView != null) {
            return hostedPlayerView;
        }
        if (currentCastSourceType() == CastSourceType.LOCAL_APP_STREAM) {
            return drmWebView;
        }
        return null;
    }

    private void muteCastPointerChrome() {
        View.OnHoverListener eatHover = (v, event) ->
                mirroringApp != null || browserStereoRunning || (videoPlaying && forceStereoEnabled);
        if (contentArea != null) {
            contentArea.setSoundEffectsEnabled(false);
            contentArea.setHapticFeedbackEnabled(false);
            contentArea.setOnHoverListener(eatHover);
        }
        gameRenderSurface.setClickable(false);
        gameRenderSurface.setFocusable(false);
        gameRenderSurface.setSoundEffectsEnabled(false);
        gameRenderSurface.setHapticFeedbackEnabled(false);
        gameRenderSurface.setOnHoverListener(eatHover);
        if (overlayView != null) {
            overlayView.setSoundEffectsEnabled(false);
            overlayView.setHapticFeedbackEnabled(false);
            overlayView.setOnHoverListener(eatHover);
        }
    }

    /**
     * Direct motion forwarding from the stereo overlay onto the underlying cast view.
     * Copies the event, remaps if the 3D mesh / SBS / aspect distorts layout, dispatches,
     * then recycles the copy.
     */
    private boolean forwardTouchToCastView(MotionEvent event, View castView, View overlay) {
        if (event == null || castView == null || overlay == null) {
            return false;
        }
        // Keep the original pointer stream (DOWN/MOVE/UP, history, pointer id) so
        // WebView can scroll and show press highlights. Live mesh invert made MOVE
        // coordinates jump every depth frame and killed fling/scroll.
        float overlayW = Math.max(1, overlay.getWidth());
        float overlayH = Math.max(1, overlay.getHeight());
        float castW = Math.max(1, castView.getWidth());
        float castH = Math.max(1, castView.getHeight());
        float nx = event.getX() / overlayW;
        float ny = event.getY() / overlayH;
        if (!stretchFill) {
            // Fit mode letterboxes: discount the bars so pokes land on content.
            // (Stretch mode fills edge-to-edge, so raw ratios already match.)
            RectF content = fittedContentRect(overlayW, overlayH, castW, castH);
            if (content.width() < overlayW - 0.5f || content.height() < overlayH - 0.5f) {
                nx = clamp01((event.getX() - content.left) / Math.max(1f, content.width()));
                ny = clamp01((event.getY() - content.top) / Math.max(1f, content.height()));
            }
        }
        if (forceStereoEnabled && !isHorizonStereoCompositionActive()) {
            nx = nx >= 0.5f ? (nx - 0.5f) * 2f : nx * 2f;
        }
        float mappedX = clamp01(nx) * castW;
        float mappedY = clamp01(ny) * castH;

        MotionEvent mappedEvent = MotionEvent.obtain(event);
        mappedEvent.offsetLocation(mappedX - event.getX(), mappedY - event.getY());
        try {
            if (event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER
                    || event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE
                    || event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                overlay.getParent().requestDisallowInterceptTouchEvent(true);
                castView.requestFocus();
                if (wantScreenOcr() || (ttsEnabled && ttsManualMode)) {
                    screenFrameCapture.requestCaptureOnTap();
                }
            }
            castView.dispatchTouchEvent(mappedEvent);
        } finally {
            mappedEvent.recycle();
        }
        return true;
    }

    /**
     * If the 3D surface mesh distorts or scales the casting layout (side-by-side stereo
     * or a non-1:1 aspect ratio), raw overlay coordinates are offset. Start from
     * {@code xRatio}/{@code yRatio} (event / overlay size), map through the visible
     * mesh/layout, then scale into the casting view before dispatch.
     */
    private float[] overlayRatiosToCastingView(
            View overlay, View castingView, float xRatio, float yRatio, float overlayX, float overlayY) {
        float overlayW = Math.max(1, overlay.getWidth());
        float overlayH = Math.max(1, overlay.getHeight());
        float castW = Math.max(1, castingView.getWidth());
        float castH = Math.max(1, castingView.getHeight());

        float nx = clamp01(xRatio);
        float ny = clamp01(yRatio);

        // Letterbox / pillarbox when overlay aspect != casting aspect.
        RectF content = fittedContentRect(overlayW, overlayH, castW, castH);
        if (content.width() < overlayW - 0.5f || content.height() < overlayH - 0.5f) {
            nx = clamp01((overlayX - content.left) / Math.max(1f, content.width()));
            ny = clamp01((overlayY - content.top) / Math.max(1f, content.height()));
        }

        if (forceStereoEnabled && !isHorizonStereoCompositionActive()) {
            if (nx >= 0.5f) {
                nx = (nx - 0.5f) * 2f;
            } else {
                nx = nx * 2f;
            }
        }

        if (forceStereoEnabled) {
            int canvasW = Math.max(1, activeStereoSurface().getWidth());
            int canvasH = Math.max(1, activeStereoSurface().getHeight());
            int halfWidth = Math.max(1, canvasW / 2);
            boolean rightEye = !isHorizonStereoCompositionActive() && xRatio >= 0.5f;
            Rect eyeBounds = rightEye
                    ? new Rect(halfWidth, 0, canvasW, canvasH)
                    : new Rect(0, 0, halfWidth, canvasH);
            int direction = rightEye ? -1 : 1;
            // Match drawStereoMirrorFrame: eyes fill the half-canvas (no letterbox).
            Rect dest = eyeBounds;
            float destX = dest.left + nx * dest.width();
            float destY = dest.top + ny * dest.height();
            float[] uv = invertParallaxMesh(destX, destY, dest, cachedParallaxGrid, direction);
            nx = uv[0];
            ny = uv[1];
        }

        return new float[] { nx, ny };
    }

    /**
     * Contain-fit the casting layout inside the overlay. When aspects match this is the
     * full overlay; otherwise empty bars so a poke on the pad does not map into the page.
     */
    private static RectF fittedContentRect(float boxW, float boxH, float contentW, float contentH) {
        float boxAspect = boxW / boxH;
        float contentAspect = contentW / contentH;
        if (Math.abs(boxAspect - contentAspect) < 0.002f) {
            return new RectF(0f, 0f, boxW, boxH);
        }
        if (boxAspect > contentAspect) {
            float w = boxH * contentAspect;
            float x = (boxW - w) / 2f;
            return new RectF(x, 0f, x + w, boxH);
        }
        float h = boxW / contentAspect;
        float y = (boxH - h) / 2f;
        return new RectF(0f, y, boxW, y + h);
    }

    private boolean isHorizonStereoCompositionActive() {
        return forceStereoEnabled && horizonStereoCompositionApplied;
    }

    /**
     * Inverse of the scaled stereo mesh: dest = eyeLeft + u * eyeWidth + parallax(u,v).
     * Iterates because parallax depends on u.
     */
    private float[] invertParallaxMesh(
            float destX, float destY, Rect eyeBounds, float[][] parallaxGrid, int direction) {
        float eyeW = Math.max(1f, eyeBounds.width());
        float eyeH = Math.max(1f, eyeBounds.height());
        float v = clamp01((destY - eyeBounds.top) / eyeH);
        float u = clamp01((destX - eyeBounds.left) / eyeW);
        if (parallaxGrid == null || parallaxGrid.length < 2) {
            return new float[] { u, v };
        }
        int meshCols = parallaxGrid[0].length - 1;
        for (int i = 0; i < 8; i++) {
            float col = u * meshCols;
            float edgeFade = edgeFadeForColumn(col, meshCols);
            float shift = direction * (sampleParallaxGrid(parallaxGrid, u, v) + convergenceOffsetPx) * edgeFade;
            u = clamp01((destX - eyeBounds.left - shift) / eyeW);
        }
        return new float[] { u, v };
    }

    private static float sampleParallaxGrid(float[][] grid, float u, float v) {
        int rows = grid.length - 1;
        int cols = grid[0].length - 1;
        float x = clamp01(u) * cols;
        float y = clamp01(v) * rows;
        int c0 = (int) Math.floor(x);
        int r0 = (int) Math.floor(y);
        int c1 = Math.min(cols, c0 + 1);
        int r1 = Math.min(rows, r0 + 1);
        float fx = x - c0;
        float fy = y - r0;
        float s00 = grid[r0][c0];
        float s10 = grid[r0][c1];
        float s01 = grid[r1][c0];
        float s11 = grid[r1][c1];
        return s00 * (1f - fx) * (1f - fy)
                + s10 * fx * (1f - fy)
                + s01 * (1f - fx) * fy
                + s11 * fx * fy;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private void setBrowserStereoCaptureMode(boolean stereo) {
        // Always hardware. Software layer made HTML 3D capturable but left the live
        // WebView blank after 3D was turned off (and it kills WebGL/Spine).
        for (WebView tab : browserTabs) {
            if (tab != null) {
                tab.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
        }
    }

    private void pollBrowserPageScroll() {
        if (drmWebView == null) {
            return;
        }
        drmWebView.evaluateJavascript(
                "(function(){return Math.round(window.scrollX||0)+','+Math.round(window.scrollY||0);})()",
                value -> {
                    if (value == null || "null".equals(value)) {
                        return;
                    }
                    String raw = value.replace("\"", "");
                    int comma = raw.indexOf(',');
                    if (comma <= 0) {
                        return;
                    }
                    try {
                        browserPageScrollX = Integer.parseInt(raw.substring(0, comma));
                        browserPageScrollY = Integer.parseInt(raw.substring(comma + 1));
                    } catch (NumberFormatException ignored) {
                    }
                });
    }

    private int browserCaptureScrollX() {
        return Math.max(browserPageScrollX, drmWebView.getScrollX());
    }

    private int browserCaptureScrollY() {
        return Math.max(browserPageScrollY, drmWebView.getScrollY());
    }

    private void stopBrowserStereo() {
        browserStereoRunning = false;
        browserStereoHandler.removeCallbacks(browserStereoTick);
        browserGpuCanvas = false;
        browserStereoLiveHidden = false;
        setBrowserStereoCaptureMode(false);
        setBrowserLiveUnderStereo(false);
        if (overlayView != null) {
            overlayView.setOnTouchListener(null);
            overlayView.setVisibility(View.GONE);
        }
        if (gameRenderSurface != null && mirroringApp == null) {
            gameRenderSurface.setOnTouchListener(null);
            gameRenderSurface.setClickable(false);
            setStereoComposition(false);
            setStereoOutputVisible(false);
        }
        if (drmWebView != null) {
            drmWebView.setVisibility(View.VISIBLE);
            drmWebView.setAlpha(1f);
            drmWebView.invalidate();
        }
        if (browserHost != null && isBrowserOpen()) {
            browserHost.setAlpha(1f);
            browserHost.bringToFront();
        }
    }

    private void captureBrowserStereoFrame() {
        if (!isBrowserOpen() || drmWebView == null
                || drmWebView.getWidth() <= 0 || drmWebView.getHeight() <= 0) {
            return;
        }
        // Prefer HTML5 <video> / canvas via JS (draw() cannot see HW video overlays).
        // SurfaceView PixelCopy is a second chance for decoder surfaces inside WebView.
        // Viewport draw is last resort (static chrome / posters only).
        if (tryPixelCopyWebViewSurface()) {
            return;
        }
        captureBrowserCanvasViaJs();
    }

    private boolean ensureBrowserCaptureBitmap(int w, int h) {
        if (browserCaptureBitmap == null
                || browserCaptureBitmap.getWidth() != w
                || browserCaptureBitmap.getHeight() != h) {
            if (browserCaptureBitmap != null) {
                browserCaptureBitmap.recycle();
            }
            browserCaptureBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        return browserCaptureBitmap != null;
    }

    private void captureBrowserViewportDraw() {
        int w = drmWebView.getWidth();
        int h = drmWebView.getHeight();
        if (!ensureBrowserCaptureBitmap(w, h)) {
            return;
        }
        Canvas canvas = new Canvas(browserCaptureBitmap);
        canvas.drawColor(Color.BLACK);
        drmWebView.draw(canvas);
        publishBrowserStereoFrame();
    }

    private void captureBrowserCanvasViaJs() {
        if (drmWebView == null) {
            return;
        }
        drmWebView.evaluateJavascript(
                "(function(){"
                        + "function pickVideo(){"
                        + "var list=document.querySelectorAll('video'),best=null,area=0;"
                        + "for(var i=0;i<list.length;i++){"
                        + "var t=list[i];"
                        + "if(!t||t.readyState<2)continue;"
                        + "var s=(t.videoWidth||t.clientWidth||0)*(t.videoHeight||t.clientHeight||0);"
                        + "if(s>area){area=s;best=t;}"
                        + "}"
                        + "if(!best||area<160*160)return '';"
                        + "try{"
                        + "var cv=document.createElement('canvas');"
                        + "cv.width=best.videoWidth||best.clientWidth;"
                        + "cv.height=best.videoHeight||best.clientHeight;"
                        + "if(cv.width<2||cv.height<2)return '';"
                        + "cv.getContext('2d').drawImage(best,0,0,cv.width,cv.height);"
                        + "return cv.toDataURL('image/jpeg',0.85);"
                        + "}catch(e){return 'VIDEO_BLOCKED';}"
                        + "}"
                        + "function pickCanvas(){"
                        + "var list=document.querySelectorAll('canvas'),c=null,best=0;"
                        + "for(var i=0;i<list.length;i++){"
                        + "var t=list[i];"
                        + "var s=Math.max((t.width||0)*(t.height||0),(t.clientWidth||0)*(t.clientHeight||0));"
                        + "if(s>best){best=s;c=t;}"
                        + "}"
                        + "if(!c||best<160*160)return '';"
                        + "try{return c.toDataURL('image/jpeg',0.8);}catch(e){return '';}"
                        + "}"
                        + "function pickImg(){"
                        + "var list=document.querySelectorAll('img'),best=null,area=0;"
                        + "for(var i=0;i<list.length;i++){"
                        + "var t=list[i];"
                        + "var vis=(t.clientWidth||0)*(t.clientHeight||0);"
                        + "var nat=(t.naturalWidth||0)*(t.naturalHeight||0);"
                        + "if(vis<180*180&&nat<400*400)continue;"
                        + "var s=Math.max(vis,nat);"
                        + "if(s>area){area=s;best=t;}"
                        + "}"
                        + "if(!best)return '';"
                        + "try{"
                        + "var cv=document.createElement('canvas');"
                        + "cv.width=best.naturalWidth||best.width;"
                        + "cv.height=best.naturalHeight||best.height;"
                        + "if(cv.width<2||cv.height<2)return '';"
                        + "cv.getContext('2d').drawImage(best,0,0);"
                        + "return cv.toDataURL('image/jpeg',0.85);"
                        + "}catch(e){return '';}"
                        + "}"
                        + "var hasVideo=document.querySelectorAll('video').length>0;"
                        + "var u=pickVideo();"
                        + "if(u&&u!=='VIDEO_BLOCKED')return u;"
                        + "u=pickCanvas();"
                        + "if(u)return u;"
                        + "if(hasVideo)return '';"
                        + "return pickImg();"
                        + "})()",
                value -> {
                    if (!browserStereoRunning) {
                        return;
                    }
                    Bitmap fromJs = decodeDataUrlBitmap(value);
                    if (fromJs == null) {
                        // Keep trying video/canvas next tick; avoid locking onto a
                        // one-shot WebView.draw() freeze of page chrome.
                        if (!browserStereoLiveHidden) {
                            captureBrowserViewportDraw();
                        }
                        return;
                    }
                    if (browserCaptureBitmap != null && browserCaptureBitmap != fromJs) {
                        browserCaptureBitmap.recycle();
                    }
                    browserCaptureBitmap = fromJs;
                    publishBrowserStereoFrame();
                });
    }

    private void pixelCopyBrowserWindow() {
        if (!browserPixelCopyBusy.compareAndSet(false, true)) {
            return;
        }
        int w = drmWebView.getWidth();
        int h = drmWebView.getHeight();
        if (!ensureBrowserCaptureBitmap(w, h)) {
            browserPixelCopyBusy.set(false);
            return;
        }
        int[] loc = new int[2];
        drmWebView.getLocationInWindow(loc);
        Rect src = new Rect(loc[0], loc[1], loc[0] + w, loc[1] + h);
        PixelCopy.request(getWindow(), src, browserCaptureBitmap, result -> {
            browserPixelCopyBusy.set(false);
            if (result == PixelCopy.SUCCESS) {
                publishBrowserStereoFrame();
            }
        }, browserStereoHandler);
    }

    private static Bitmap decodeDataUrlBitmap(String jsValue) {
        if (jsValue == null || jsValue.length() < 32 || "null".equals(jsValue) || "\"\"".equals(jsValue)) {
            return null;
        }
        String raw = jsValue;
        if (raw.length() >= 2 && raw.charAt(0) == '"') {
            raw = raw.substring(1, raw.length() - 1).replace("\\/", "/");
        }
        int comma = raw.indexOf(',');
        if (comma < 0 || !raw.startsWith("data:image")) {
            return null;
        }
        try {
            byte[] bytes = Base64.decode(raw.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void publishBrowserStereoFrame() {
        if (browserCaptureBitmap == null) {
            return;
        }
        if (ttsEnabled) {
            screenFrameCapture.retainSourceForTap(browserCaptureBitmap);
        }
        drawStereoMirrorFrame(browserCaptureBitmap);
        if (forceStereoEnabled) {
            requestDepthUpdate(browserCaptureBitmap);
        }
        if (wantScreenOcr()) {
            screenFrameCapture.offerFromScreenBuffer(browserCaptureBitmap);
        }
        // Hide the live WebView under the stereo surface so letterboxed fit mode
        // does not show a flat PiP beside / behind the 3D picture.
        if (browserStereoRunning && !browserStereoLiveHidden) {
            browserStereoLiveHidden = true;
            setBrowserLiveUnderStereo(true);
            if (gameRenderSurface != null) {
                gameRenderSurface.bringToFront();
            }
            if (overlayView != null) {
                overlayView.bringToFront();
            }
        }
    }

    /**
     * Keep the WebView attached and layout-sized (touches / JS capture still work)
     * but invisible so it cannot PiP through stereo letterboxing.
     */
    private void setBrowserLiveUnderStereo(boolean hide) {
        if (drmWebView != null) {
            drmWebView.setAlpha(hide ? 0f : 1f);
        }
        if (browserHost != null) {
            // Host stays VISIBLE so isBrowserOpen() and layout keep working.
            browserHost.setAlpha(hide ? 0f : 1f);
        }
    }

    /** PixelCopy a decoder SurfaceView child inside the WebView (HTML5 video). */
    private boolean tryPixelCopyWebViewSurface() {
        if (drmWebView == null || !browserPixelCopyBusy.compareAndSet(false, true)) {
            return false;
        }
        SurfaceView sv = findDescendantSurfaceView(drmWebView);
        if (sv == null || sv.getWidth() <= 0 || sv.getHeight() <= 0
                || sv.getHolder() == null || !sv.getHolder().getSurface().isValid()) {
            browserPixelCopyBusy.set(false);
            return false;
        }
        int tw = Math.min(960, sv.getWidth());
        int th = Math.max(2, Math.round((float) tw * sv.getHeight() / Math.max(1, sv.getWidth())));
        if (!ensureBrowserCaptureBitmap(tw, th)) {
            browserPixelCopyBusy.set(false);
            return false;
        }
        try {
            PixelCopy.request(sv, browserCaptureBitmap, result -> {
                browserPixelCopyBusy.set(false);
                if (result == PixelCopy.SUCCESS && browserStereoRunning) {
                    publishBrowserStereoFrame();
                }
            }, browserStereoHandler);
            return true;
        } catch (Throwable t) {
            browserPixelCopyBusy.set(false);
            return false;
        }
    }

    private static SurfaceView findDescendantSurfaceView(View root) {
        if (root instanceof SurfaceView) {
            return (SurfaceView) root;
        }
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            SurfaceView found = findDescendantSurfaceView(group.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void openSideDrawer(View drawer) {
        if (drawer == null) {
            return;
        }
        drawer.bringToFront();
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(drawer.getWidth() > 0 ? drawer.getWidth() : 320f);
        drawer.setAlpha(0f);
        drawer.animate().translationX(0f).alpha(1f).setDuration(180).start();
    }

    private void closeSideDrawer(View drawer) {
        if (drawer == null) {
            return;
        }
        drawer.animate()
                .translationX(drawer.getWidth() > 0 ? drawer.getWidth() : 320f)
                .alpha(0f)
                .setDuration(150)
                .withEndAction(() -> drawer.setVisibility(View.GONE))
                .start();
    }

    private void populateHelpContent() {
        LinearLayout content = findViewById(R.id.help_content);
        if (content == null) {
            return;
        }
        content.removeAllViews();

        addHelpSection(content, R.string.help_section_start);
        addHelpCombo(content, R.string.help_start_cast_title, R.string.help_start_cast_body);
        addHelpCombo(content, R.string.help_start_subs_title, R.string.help_start_subs_body);
        addHelpCombo(content, R.string.help_start_listen_title, R.string.help_start_listen_body);
        addHelpCombo(content, R.string.help_start_translate_ocr_title, R.string.help_start_translate_ocr_body);
        addHelpCombo(content, R.string.help_start_browser_title, R.string.help_start_browser_body);
        addHelpCombo(content, R.string.help_start_books_title, R.string.help_start_books_body);
        addHelpCombo(content, R.string.help_start_videos_title, R.string.help_start_videos_body);
        addHelpCombo(content, R.string.help_start_desktop_link_title, R.string.help_start_desktop_link_body);

        addHelpSection(content, R.string.help_section_downloads);
        addHelpCombo(content, R.string.help_dl_intro_title, R.string.help_dl_intro_body);
        addHelpRow(content, R.drawable.ic_listen_ear, R.string.help_dl_listen_title, R.string.help_dl_listen_body);
        addHelpRow(content, R.drawable.ic_tts_speaker, R.string.help_dl_male_title, R.string.help_dl_male_body);
        addHelpRow(content, R.drawable.ic_ocr_region, R.string.help_dl_captions_title, R.string.help_dl_captions_body);
        addHelpRow(content, R.drawable.ic_google_g, R.string.help_dl_google_title, R.string.help_dl_google_body);
        addHelpCombo(content, R.string.help_dl_bundled_title, R.string.help_dl_bundled_body);

        addHelpSection(content, R.string.help_section_dual);
        addHelpDual(content,
                0, R.string.toggle_3d_label, R.string.help_dual_3d_a, R.drawable.bg_circle_button,
                R.drawable.ic_3d_glasses, 0, R.string.help_dual_3d_b, R.drawable.bg_circle_button,
                R.string.help_dual_3d_title, R.string.help_dual_3d_body);
        addHelpDual(content,
                R.drawable.ic_tts_speaker, 0, R.string.help_dual_tts_a, R.drawable.bg_circle_button_tts_once,
                R.drawable.ic_tts_loop, 0, R.string.help_dual_tts_b, R.drawable.bg_circle_button_tts_loop,
                R.string.help_dual_tts_title, R.string.help_dual_tts_body);
        addHelpDual(content,
                R.drawable.ic_listen_ear, 0, R.string.help_dual_listen_a, R.drawable.bg_circle_button,
                R.drawable.ic_listen_ear, 0, R.string.help_dual_listen_b, R.drawable.bg_circle_button_listen_active,
                R.string.help_dual_listen_title, R.string.help_dual_listen_body);
        addHelpDual(content,
                R.drawable.ic_epub_book, 0, R.string.help_dual_books_a, R.drawable.bg_circle_button,
                R.drawable.ic_epub_book, 0, R.string.help_dual_books_b, R.drawable.bg_circle_button,
                R.string.help_dual_books_title, R.string.help_dual_books_body);
        addHelpDual(content,
                R.drawable.ic_dock_add_new_game, 0, R.string.help_dual_dock_a, R.drawable.bg_circle_button,
                R.drawable.ic_close, 0, R.string.help_dual_dock_b, R.drawable.bg_circle_button,
                R.string.help_dual_dock_title, R.string.help_dual_dock_body);
        addHelpCombo(content, R.string.help_dual_assist_title, R.string.help_dual_assist_body);
        addHelpCombo(content, R.string.help_dual_depth_title, R.string.help_dual_depth_body);

        addHelpSection(content, R.string.help_section_buttons);
        addHelpRow(content, R.drawable.ic_power, R.string.help_btn_stop_title, R.string.help_btn_stop_body);
        addHelpRow(content, R.drawable.ic_3d_glasses, R.string.help_btn_3d_title, R.string.help_btn_3d_body);
        addHelpRow(content, R.drawable.ic_globe, R.string.help_btn_browser_title, R.string.help_btn_browser_body);
        addHelpRow(content, R.drawable.ic_epub_book, R.string.help_btn_books_title, R.string.help_btn_books_body);
        addHelpRow(content, R.drawable.ic_movie_reel, R.string.help_btn_videos_title, R.string.help_btn_videos_body);
        addHelpRow(content, R.drawable.ic_tts_speaker, R.string.help_btn_tts_title, R.string.help_btn_tts_body);
        addHelpRow(content, R.drawable.ic_listen_ear, R.string.help_btn_listen_title, R.string.help_btn_listen_body);
        addHelpRow(content, R.drawable.ic_ocr_region, R.string.help_btn_ocr_title, R.string.help_btn_ocr_body);
        addHelpRow(content, R.drawable.ic_settings_gear, R.string.help_btn_settings_title, R.string.help_btn_settings_body);
        addHelpRow(content, R.drawable.ic_help, R.string.help_btn_help_title, R.string.help_btn_help_body);

        addHelpSection(content, R.string.help_section_browser);
        addHelpRow(content, R.drawable.ic_globe, R.string.help_btn_page_translate_title, R.string.help_btn_page_translate_body);
        addHelpRow(content, R.drawable.ic_google_g, R.string.help_btn_google_translate_title, R.string.help_btn_google_translate_body);
        addHelpRow(content, R.drawable.ic_tts_speaker, R.string.help_btn_read_title, R.string.help_btn_read_body);

        addHelpSection(content, R.string.help_section_combos);
        addHelpCombo(content, R.string.help_combo_cast_3d_title, R.string.help_combo_cast_3d_body);
        addHelpCombo(content, R.string.help_combo_ocr_tts_title, R.string.help_combo_ocr_tts_body);
        addHelpCombo(content, R.string.help_combo_ocr_translate_title, R.string.help_combo_ocr_translate_body);
        addHelpCombo(content, R.string.help_combo_ocr_share_title, R.string.help_combo_ocr_share_body);
        addHelpCombo(content, R.string.help_combo_listen_title, R.string.help_combo_listen_body);
        addHelpCombo(content, R.string.help_combo_browser_translate_title, R.string.help_combo_browser_translate_body);
        addHelpCombo(content, R.string.help_combo_books_import_title, R.string.help_combo_books_import_body);
        addHelpCombo(content, R.string.help_combo_listen_vs_ocr_title, R.string.help_combo_listen_vs_ocr_body);
    }

    private void addHelpDual(LinearLayout parent,
            int iconA, int textA, int labelA, int bgA,
            int iconB, int textB, int labelB, int bgB,
            int titleRes, int bodyRes) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_help_dual, parent, false);
        TextView title = row.findViewById(R.id.help_dual_title);
        TextView body = row.findViewById(R.id.help_dual_body);
        ImageView a = row.findViewById(R.id.help_dual_icon_a);
        ImageView b = row.findViewById(R.id.help_dual_icon_b);
        TextView textAv = row.findViewById(R.id.help_dual_text_a);
        TextView la = row.findViewById(R.id.help_dual_label_a);
        TextView lb = row.findViewById(R.id.help_dual_label_b);
        View bgAv = row.findViewById(R.id.help_dual_a_bg);
        View bgBv = row.findViewById(R.id.help_dual_b_bg);
        title.setText(titleRes);
        body.setText(bodyRes);
        if (textA != 0) {
            a.setVisibility(View.GONE);
            textAv.setVisibility(View.VISIBLE);
            textAv.setText(textA);
        } else {
            a.setVisibility(View.VISIBLE);
            textAv.setVisibility(View.GONE);
            a.setImageResource(iconA);
        }
        if (textB != 0) {
            b.setVisibility(View.GONE);
        } else {
            b.setVisibility(View.VISIBLE);
            b.setImageResource(iconB);
        }
        la.setText(labelA);
        lb.setText(labelB);
        bgAv.setBackgroundResource(bgA);
        bgBv.setBackgroundResource(bgB);
        parent.addView(row);
    }

    private void addHelpSection(LinearLayout parent, int titleRes) {
        TextView section = new TextView(this);
        section.setText(titleRes);
        section.setTextColor(getResources().getColor(R.color.help_text_primary, getTheme()));
        section.setTextSize(14f);
        section.setTypeface(section.getTypeface(), android.graphics.Typeface.BOLD);
        int top = parent.getChildCount() == 0 ? 0 : dp(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        parent.addView(section, lp);
    }

    private void addHelpRow(LinearLayout parent, int iconRes, int titleRes, int bodyRes) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_help_row, parent, false);
        ImageView icon = row.findViewById(R.id.help_row_icon);
        TextView title = row.findViewById(R.id.help_row_title);
        TextView body = row.findViewById(R.id.help_row_body);
        icon.setImageResource(iconRes);
        title.setText(titleRes);
        body.setText(bodyRes);
        parent.addView(row);
    }

    private void addHelpCombo(LinearLayout parent, int titleRes, int bodyRes) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_help_combo, parent, false);
        TextView title = row.findViewById(R.id.help_combo_title);
        TextView body = row.findViewById(R.id.help_combo_body);
        title.setText(titleRes);
        body.setText(bodyRes);
        parent.addView(row);
    }

    @Override
    protected void onPause() {
        persistSession();
        // Drop LAN import before a game takes focus — never fight VR for process/focus.
        stopBookImportServerQuiet();
        glesZMeshView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        backgrounded = true;
        if (listenEngine != null) {
            listenEngine.stop();
        }
        if (videoPlayer != null) {
            try {
                if (videoPlayer.isPlaying()) {
                    videoPlayer.pause();
                    videoPausedForBackground = true;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            PiperTtsEngine piper = PiperTtsEngine.get(this);
            if (piper.isSpeaking()) {
                piper.pausePlayback();
                ttsPausedForBackground = true;
            }
        } catch (Throwable ignored) {
        }
        for (WebView tab : browserTabs) {
            try {
                if (tab != null) {
                    tab.onPause();
                }
            } catch (Throwable ignored) {
            }
        }
        super.onStop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        backgrounded = false;
        for (WebView tab : browserTabs) {
            try {
                if (tab != null) {
                    tab.onResume();
                }
            } catch (Throwable ignored) {
            }
        }
        if (ttsPausedForBackground) {
            ttsPausedForBackground = false;
            try {
                PiperTtsEngine.get(this).resumePlayback();
            } catch (Throwable ignored) {
            }
        }
        if (videoPausedForBackground && videoPlayer != null && videoPlaying) {
            videoPausedForBackground = false;
            try {
                videoPlayer.play();
            } catch (Throwable ignored) {
            }
        }
        if (videoPlaying) {
            setVideoDisplayMode();
        }
        if (assistMode == AssistMode.LISTEN) {
            syncListenEngine(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean glesLive = useGlesZMesh
                && (mirroringApp != null || browserStereoRunning
                || (videoPlaying && forceStereoEnabled));
        glesZMeshView.onResume(gameRenderSurface, glesLive);
        // A pinned app may have been uninstalled while we were in the background;
        // re-resolving on every resume keeps the dock honest without extra bookkeeping.
        refreshDock();
        // Book import is user-started only (EPUB long-press). Do not auto-start on resume.
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // If the panel loses focus (e.g. VR game), stop PC listen so it can't fight the title.
        if (!hasFocus) {
            stopBookImportServerQuiet();
        }
    }

    /** Pinned apps per dock row while idle; a 5th app starts a new row that grows upward. */
    private static final int DOCK_APPS_PER_ROW = 4;

    /** Last dock layout mode: true = compact content row, false = idle (may wrap). */
    private Boolean dockLaidOutCompact;

    /**
     * Rebuilds the dock: one item per pinned, still-installed package (in the order
     * they were added), followed by an always-present "Add Game" control. Deliberately
     * shows zero per-game icons until the user has actually pinned one — the dock pill
     * itself still renders (as the way to reach "Add Game"), matching the requirement
     * that game icons only appear once a game has been added.
     * <p>
     * Idle home: after four pinned apps, further icons wrap onto rows above so the TTS
     * player can stay open without chopping the dock. Casting / browser / books collapse
     * back to a single compact icon row under the content.
     */
    private void refreshDock() {
        rebuildDockForCompact(isContentViewActive());
    }

    /** True while cast, browser, book, or video is filling the content area. */
    private boolean isContentViewActive() {
        return mirroringApp != null || isBrowserOpen() || videoPlaying;
    }

    private void rebuildDockForCompact(boolean compact) {
        dockContainer.removeAllViews();

        Set<String> pinnedPackages = libraryStore.getPinnedPackages();
        List<String> stalePackages = new ArrayList<>();
        List<View> appItems = new ArrayList<>();

        for (String packageName : pinnedPackages) {
            InstalledAppInfo app = resolveInstalledApp(packageName);
            if (app == null) {
                stalePackages.add(packageName);
                continue;
            }
            appItems.add(createDockItemView(app));
        }

        for (String stalePackage : stalePackages) {
            libraryStore.removePinnedPackage(stalePackage);
        }

        dockLaidOutCompact = compact;
        layoutDockItems(appItems, /*wrapUp=*/ !compact);
        applyHomeRowCompact(compact);
    }

    /**
     * @param wrapUp idle multi-row (expand upward after 4 apps); false = one content row
     */
    private void layoutDockItems(List<View> appItems, boolean wrapUp) {
        int appCount = appItems.size();
        if (!wrapUp) {
            LinearLayout row = newDockRow();
            for (View item : appItems) {
                row.addView(item);
            }
            row.addView(createAddGameItemView());
            dockContainer.addView(row);
            return;
        }

        // First four apps stay on the bottom row (aligned with TTS). Extra apps wrap
        // onto rows above so the dock expands upward into the cast area.
        int appRowCount = Math.max(1, (appCount + DOCK_APPS_PER_ROW - 1) / DOCK_APPS_PER_ROW);
        if (appCount == 0) {
            appRowCount = 1;
        }
        boolean addNeedsOwnRow = appCount > 0 && (appCount % DOCK_APPS_PER_ROW == 0);
        int rowCount = appRowCount + (addNeedsOwnRow ? 1 : 0);

        // Add top→bottom: overflow rows first, primary (first 4 apps) last.
        for (int visual = 0; visual < rowCount; visual++) {
            int rowIndex = rowCount - 1 - visual;
            LinearLayout row = newDockRow();
            if (addNeedsOwnRow && rowIndex == appRowCount) {
                row.addView(createAddGameItemView());
            } else {
                int start = rowIndex * DOCK_APPS_PER_ROW;
                int end = Math.min(start + DOCK_APPS_PER_ROW, appCount);
                for (int i = start; i < end; i++) {
                    row.addView(appItems.get(i));
                }
                boolean isLastAppRow = rowIndex == appRowCount - 1;
                if (appCount == 0 || (isLastAppRow && !addNeedsOwnRow)) {
                    row.addView(createAddGameItemView());
                }
            }
            dockContainer.addView(row);
        }
    }

    private LinearLayout newDockRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(params);
        return row;
    }

    private View createDockItemView(InstalledAppInfo app) {
        View item = LayoutInflater.from(this).inflate(R.layout.item_dock_icon, dockContainer, false);
        ((ImageView) item.findViewById(R.id.dock_item_icon)).setImageDrawable(app.icon);
        ((TextView) item.findViewById(R.id.dock_item_label)).setText(app.label);

        // Standard View click/long-click listeners — the panel framework delivers
        // ordinary pointer/hand-ray input to these exactly like any other Android app.
        item.setOnClickListener(v -> launchGame(app));
        item.setOnLongClickListener(v -> {
            confirmRemoveFromDock(app);
            return true;
        });
        return item;
    }

    private View createAddGameItemView() {
        View item = LayoutInflater.from(this).inflate(R.layout.item_dock_icon, dockContainer, false);
        ((ImageView) item.findViewById(R.id.dock_item_icon)).setImageResource(R.drawable.ic_dock_add_new_game);
        ((TextView) item.findViewById(R.id.dock_item_label)).setText(R.string.add_game_dock_label);
        item.setOnClickListener(v -> showAddGameDialog());
        return item;
    }

    /** Re-resolves label/icon/launchability from PackageManager; null if no longer installed. */
    private InstalledAppInfo resolveInstalledApp(String packageName) {
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            String label = packageManager.getApplicationLabel(appInfo).toString();
            return new InstalledAppInfo(packageName, label, packageManager.getApplicationIcon(appInfo));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void launchGame(InstalledAppInfo app) {
        // Don't keep LAN import alive while we hand focus to a VR title.
        stopBookImportServerQuiet();
        Intent launchIntent = packageManager.getLaunchIntentForPackage(app.packageName);
        if (launchIntent == null) {
            Log.w(TAG, "launchGame: no launch intent for " + app.packageName);
            PanelAlerts.show(this, getString(R.string.launch_failed_message, app.label));
            return;
        }

        if (!mirrorServiceBound) {
            // Extremely small window right after onCreate() where bindService() hasn't
            // completed yet — queue and retry from onServiceConnected() instead of
            // calling into MediaProjection too early (see onCreate()'s comment on why
            // that throws).
            pendingLaunchAwaitingService = app;
            return;
        }

        // Always re-prompt the Horizon OS share sheet so the user can pick *this app's
        // window* ("Just this window") instead of reusing a previous "Entire view"
        // grant — entire-view tokens keep capturing the whole dashboard, which is what
        // made the Firefox stream look framed and then crop-in when the video went
        // fullscreen inside that window.
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        releaseCapturePipeline();

        pendingLaunchApp = app;
        pendingLaunchAlreadyStarted = true;
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launchIntent);
        // API 34 / Horizon OS validates the grant against a live mediaProjection
        // FGS server-side: getMediaProjection() throws SecurityException without
        // one. Promote the (already bound) service here — tap time is foreground,
        // so no background-start restriction. Dropped on dismiss/stop.
        try {
            if (mirrorCaptureService != null) {
                mirrorCaptureService.enterProjectionForeground();
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "enterProjectionForeground failed", e);
            pendingLaunchApp = null;
            pendingLaunchAlreadyStarted = false;
            PanelAlerts.show(this, getString(R.string.launch_failed_message, app.label));
            return;
        }
        // App window first, then share sheet — otherwise the user dismisses capture,
        // the app appears after, and they have to tap the dock again.
        contentArea.postDelayed(() -> {
            if (pendingLaunchApp == null) {
                return;
            }
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
        }, 1600);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN_EPUB) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                openEpubFromUri(data.getData());
            }
            return;
        }
        if (requestCode == REQUEST_OPEN_VIDEO) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                // Large videos must not copy on the UI thread (ANR + truncated
                // files when the panel backgrounds mid-copy).
                Uri pick = data.getData();
                new Thread(() -> {
                    File imported = ensureVideoInInbox(pick);
                    runOnUiThread(() -> {
                        if (imported != null) {
                            if (videoLibraryStore == null) {
                                videoLibraryStore = new VideoLibraryStore(this);
                            }
                            VideoLibraryStore.VideoEntry entry =
                                    videoLibraryStore.upsert(imported, imported.getName());
                            playVideoFile(imported,
                                    entry != null ? entry.title : imported.getName());
                        } else {
                            Log.w(TAG, "Video import failed uri=" + pick);
                        }
                    });
                }, "VideoImport").start();
            }
            return;
        }
        if (requestCode != REQUEST_MEDIA_PROJECTION) {
            return;
        }

        InstalledAppInfo app = pendingLaunchApp;
        pendingLaunchApp = null;
        if (app == null) {
            return;
        }

        Intent launchIntent = packageManager.getLaunchIntentForPackage(app.packageName);
        if (launchIntent == null) {
            Log.w(TAG, "onActivityResult: no launch intent for " + app.packageName
                    + " resultCode=" + resultCode);
            PanelAlerts.show(this, getString(R.string.launch_failed_message, app.label));
            return;
        }

        Log.i(TAG, "onActivityResult: media projection resultCode=" + resultCode
                + " dataNull=" + (data == null)
                + " serviceBound=" + mirrorServiceBound
                + " serviceNull=" + (mirrorCaptureService == null));
        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                // FGS was promoted at tap time (see launchGame) so the grant
                // validates; this re-enter is an idempotent no-op.
                mediaProjection = projectionManager.getMediaProjection(resultCode, data);
                if (mirrorCaptureService != null) {
                    mirrorCaptureService.enterProjectionForeground();
                }
                startMirroringAndLaunch(app, launchIntent);
                if (assistMode == AssistMode.LISTEN) {
                    syncListenEngine(false);
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "MediaProjection / mirror start failed", e);
                if (mirrorCaptureService != null) {
                    mirrorCaptureService.leaveProjectionForeground();
                }
                if (mediaProjection != null) {
                    try {
                        mediaProjection.stop();
                    } catch (RuntimeException ignored) {
                    }
                    mediaProjection = null;
                }
                PanelAlerts.show(this, getString(R.string.launch_failed_message, app.label));
            }
        } else if (!pendingLaunchAlreadyStarted) {
            startActivity(launchIntent);
        } else {
            Log.w(TAG, "onActivityResult: projection not granted resultCode=" + resultCode
                    + " dataNull=" + (data == null) + " appAlreadyStarted=" + pendingLaunchAlreadyStarted);
            // Sheet dismissed: drop the tap-time FGS promotion (stopMirroring
            // covers the session-stop case).
            if (mirrorCaptureService != null) {
                mirrorCaptureService.leaveProjectionForeground();
            }
        }
        pendingLaunchAlreadyStarted = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LISTEN_AUDIO) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            applyAssistMode(AssistMode.LISTEN, true);
        } else {
            // Security.2: deny must not crash — stay on current mode and explain.
            PanelAlerts.show(this, R.string.assist_listen_mic_denied);
        }
    }

    /**
     * CONFIRMED ON-DEVICE (Horizon OS, this Quest build): launching a third-party
     * Activity onto a private VirtualDisplay via ActivityOptions.setLaunchDisplayId()
     * is rejected —
     *   "Permission Denial: starting Intent { ... cmp=com.proximabeta.nikke/... }
     *   from ProcessRecord{...com.spatiallauncher.app...} with launchDisplayId=..."
     * — for every app tried (NIKKE, Netflix). That confirms Horizon OS does not let a
     * normal app redirect another app's window onto a display only it owns; this is
     * the real platform boundary discussed earlier, not a guess. A second, independent
     * bug this caused: MediaProjection.createVirtualDisplay() only supports being
     * called ONCE per granted projection — retrying it after the rejected isolated
     * attempt (as a "fallback") silently produced a dead, all-black capture surface
     * instead of throwing, which is why the mirror went black. Both problems are fixed
     * by not attempting per-app display isolation at all: this now always does the one
     * whole-screen AUTO_MIRROR capture (previously working) in a single
     * createVirtualDisplay() call. The game still gets its own real window — that part
     * remains an unavoidable platform limitation for a normal, non-privileged app.
     */
    private void startMirroringAndLaunch(InstalledAppInfo app, Intent launchIntent) {
        releaseCapturePipeline();
        ensureMirrorThread();

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        // Capture at full device resolution now — this was halved back when depth
        // inference ran synchronously per-frame and every extra pixel cost mirror frame
        // rate directly. Now that depth runs decoupled on its own thread (see
        // requestDepthUpdate()), the draw path can afford the sharper source image; the
        // old half-res capture was very likely why the mirror looked soft/unclear
        // compared to the very first working version.
        int width = Math.max(1, metrics.widthPixels);
        int height = Math.max(1, metrics.heightPixels);
        // getWindowManager().getDefaultDisplay() on a Horizon OS panel Activity can
        // report *this panel window's own* bounds rather than the physical headset
        // display — which is very likely why the capture looked "1:1" instead of
        // matching the actual mirrored content's real aspect ratio. Rather than trying
        // to out-guess what these numbers mean, fitSurfaceToCaptureAspectRatio() below
        // uses the ImageReader's *actual* delivered image dimensions (the one
        // authoritative source for what's really being captured) to letterbox the
        // SurfaceView correctly, whatever this capture resolution actually turns out
        // to be.
        captureWidth = width;
        captureHeight = height;
        capturedWindowRect = null;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onMirrorFrameAvailable, mirrorHandler);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "SpatialLauncherMirror", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, mirrorHandler);

        if (!pendingLaunchAlreadyStarted) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launchIntent);
        }
        onMirrorSessionStarted(app);

        // The game has its own real window (see doc comment above) — give it a moment
        // to come up, then bring this panel back to front so the mirror is what's
        // actually in view.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent bringToFront = new Intent(this, PanelMainActivity.class);
            bringToFront.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(bringToFront);
        }, 1200);
    }

    private void onMirrorSessionStarted(InstalledAppInfo app) {
        mirroringApp = app;
        hideDrmBrowser();
        emptyStateText.setVisibility(View.GONE);
        setStereoOutputVisible(true);
        stopMirrorButton.setVisibility(View.VISIBLE);
        // drawStereoMirrorFrame() already lays out left/right eye images side by side in
        // one buffer — this tells the OS compositor to actually route each half to its
        // own eye instead of showing the whole side-by-side buffer flat to both eyes.
        //
        // Toggling stereo composition makes Horizon OS renegotiate the SurfaceView's
        // BLASTBufferQueue geometry (confirmed in logcat: "rejecting buffer: active_size=
        // ... requested_size=..."), but the View's own layout size doesn't get remeasured
        // in lockCanvas()'s favor at the same moment, so every subsequent lockCanvas()
        // frame gets produced at the stale size and is silently dropped by the queue —
        // that's the "ghosting" (last-accepted stale frame stuck on screen while every
        // new one is rejected). Locking the holder's buffer size explicitly, after layout
        // has settled, keeps the producer and the compositor's expected geometry in sync.
        setStereoComposition(forceStereoEnabled);
        setHomeRowCompact(true);
        setCastTheme(true);
        if (app != null) {
            applyAssignedOcrPreset(app.packageName);
            persistSession();
        }
        gameRenderSurface.post(() -> {
            fitSurfaceToCaptureAspectRatio();
            // setLayoutParams() above only requests a new layout pass — getWidth()/
            // getHeight() won't reflect it until that pass runs, so lockSurfaceBufferSize()
            // needs its own, later post to see the post-resize dimensions.
            activeStereoSurface().post(this::lockSurfaceBufferSize);
        });
    }

    /**
     * Resizes gameRenderSurface's own LayoutParams (not the SurfaceView's underlying
     * buffer — see lockSurfaceBufferSize() for that) so it letterboxes at the capture's
     * real aspect ratio (captureWidth x captureHeight) inside content_area, centered,
     * instead of stretching to fill content_area's own (usually different) aspect ratio.
     * That stretch mismatch was very likely why the mirrored content didn't fit
     * correctly — content_area's shape has nothing to do with the captured content's
     * actual shape.
     */
    private void fitSurfaceToCaptureAspectRatio() {
        if (contentArea == null || gameRenderSurface == null) {
            return;
        }
        int areaW = contentArea.getWidth();
        int areaH = contentArea.getHeight();
        if (areaW <= 0 || areaH <= 0) {
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) gameRenderSurface.getLayoutParams();
        // Size from whatever is actually on the stereo stage — NOT always the cast.
        // Browser stereo and video 3D reused the cast dims and shrank to a PiP box.
        int contentW = captureWidth;
        int contentH = captureHeight;
        if (mirroringApp == null) {
            if (videoPlaying && videoPlayer != null) {
                try {
                    androidx.media3.common.Format vf = videoPlayer.getVideoFormat();
                    if (vf != null && vf.width > 0 && vf.height > 0) {
                        contentW = vf.width;
                        contentH = vf.height;
                    }
                } catch (Throwable ignored) {
                }
            } else if (browserStereoRunning && drmWebView != null
                    && drmWebView.getWidth() > 0 && drmWebView.getHeight() > 0) {
                contentW = drmWebView.getWidth();
                contentH = drmWebView.getHeight();
            }
        }
        if (!stretchFill && contentW > 0 && contentH > 0) {
            // Fit mode: size the surface to the content aspect, centered, so tall
            // apps pillarbox instead of stretching. Guard layout churn the same way
            // as the fill path below (requestLayout storms blank the SurfaceView).
            float scale = Math.min(
                    areaW / (float) contentW, areaH / (float) contentH);
            int sw = Math.max(1, Math.round(contentW * scale));
            int sh = Math.max(1, Math.round(contentH * scale));
            if (params.width == sw
                    && params.height == sh
                    && params.gravity == android.view.Gravity.CENTER) {
                return;
            }
            params.width = sw;
            params.height = sh;
            params.gravity = android.view.Gravity.CENTER;
            gameRenderSurface.setLayoutParams(params);
            return;
        }
        // Fill content_area edge-to-edge. Only touch LayoutParams when size actually
        // changes — setLayoutParams on every layout pass caused an infinite
        // requestLayout storm that blanked the SurfaceView (black cast).
        if (params.width == areaW
                && params.height == areaH
                && params.gravity == android.view.Gravity.FILL) {
            return;
        }
        params.width = areaW;
        params.height = areaH;
        params.gravity = android.view.Gravity.FILL;
        gameRenderSurface.setLayoutParams(params);
    }

    /** GLES needs a full 3D stop/start so EGL is not attached over a live Canvas producer. */
    private void restartStereoAfterGlesToggle() {
        if (browserStereoRunning || (isBrowserOpen() && forceStereoEnabled)) {
            stereoHandoff = true;
            stopBrowserStereo();
            contentArea.postDelayed(() -> {
                if (isBrowserOpen() && forceStereoEnabled) {
                    startBrowserStereo();
                }
                stereoHandoff = false;
            }, 120);
            return;
        }
        if (mirroringApp != null) {
            stereoHandoff = true;
            glesHandoffTries = 0;
            glesZMeshView.stop();
            contentArea.postDelayed(this::finishMirrorGlesHandoff, 80);
        }
    }

    private void finishMirrorGlesHandoff() {
        if (mirroringApp == null) {
            stereoHandoff = false;
            return;
        }
        if (glesZMeshView.ownsSurface() && glesHandoffTries++ < 20) {
            contentArea.postDelayed(this::finishMirrorGlesHandoff, 40);
            return;
        }
        if (useGlesZMesh) {
            recycleMirrorSurfaceForGles();
            return;
        }
        lockSurfaceBufferSize();
        stereoHandoff = false;
    }

    /**
     * lockCanvas leaves BLAST in a canvas-producer state. Hide + reformat the
     * SurfaceView before EGL or mid-cast GLES-on freezes the app stream.
     */
    private void recycleMirrorSurfaceForGles() {
        glesZMeshView.stop();
        gameRenderSurface.setVisibility(View.GONE);
        gameRenderSurface.post(() -> {
            if (mirroringApp == null || !useGlesZMesh) {
                stereoHandoff = false;
                return;
            }
            gameRenderSurface.getHolder().setFormat(PixelFormat.OPAQUE);
            gameRenderSurface.setVisibility(View.VISIBLE);
            gameRenderSurface.post(() -> {
                if (mirroringApp == null || !useGlesZMesh) {
                    stereoHandoff = false;
                    return;
                }
                glesZMeshView.startOn(gameRenderSurface);
                contentArea.postDelayed(() -> stereoHandoff = false, 1500);
            });
        });
    }

    private SurfaceView activeStereoSurface() {
        return gameRenderSurface;
    }

    private void setStereoOutputVisible(boolean show) {
        if (!show) {
            glesZMeshView.stop();
            gameRenderSurface.setVisibility(View.GONE);
            return;
        }
        gameRenderSurface.setVisibility(View.VISIBLE);
        if (useGlesZMesh) {
            glesZMeshView.startOn(gameRenderSurface);
        } else {
            glesZMeshView.stop();
        }
    }

    /**
     * Forces the SurfaceView's SurfaceHolder to a fixed buffer size matching its current
     * laid-out pixel dimensions, so lockCanvas() always hands back a canvas of exactly
     * that size instead of racing with a geometry renegotiation triggered by
     * setStereoComposition(). See onMirrorSessionStarted() for why this exists.
     */
    private void lockSurfaceBufferSize() {
        SurfaceView surface = activeStereoSurface();
        int w = surface.getWidth();
        int h = surface.getHeight();
        if (w <= 0 || h <= 0 || stereoHandoff) {
            return;
        }
        if (useGlesZMesh && glesZMeshView.ownsSurface()) {
            glesZMeshView.onBufferResize(w, h);
            return;
        }
        if (!useGlesZMesh) {
            surface.getHolder().setFixedSize(w, h);
        }
    }

    /**
     * Reflection-based call to horizonos.view.SurfaceViewExt.setStereoComposition() —
     * see the field javadoc above for why reflection instead of a direct import. Safe
     * no-op (just logs once) on any device/OS build where these classes don't exist.
     */
    private void setStereoComposition(boolean stereo) {
        try {
            Class<?> extClass = Class.forName(HORIZONOS_SURFACE_VIEW_EXT_CLASS);
            Class<?> controlExtClass = Class.forName(HORIZONOS_SURFACE_CONTROL_EXT_CLASS);
            int mode = controlExtClass
                    .getField(stereo ? "STEREO_COMPOSITION_SIDE_BY_SIDE" : "STEREO_COMPOSITION_MONO")
                    .getInt(null);
            Method method = extClass.getMethod("setStereoComposition", SurfaceView.class, int.class);
            method.invoke(null, gameRenderSurface, mode);
            horizonStereoCompositionApplied = stereo;
            Log.i(TAG, "Horizon OS stereo surface composition set to "
                    + (stereo ? "SIDE_BY_SIDE" : "MONO")
                    + (useGlesZMesh ? " (GLES Z-mesh)" : " (Canvas mesh)"));
        } catch (ReflectiveOperationException e) {
            horizonStereoCompositionApplied = false;
            Log.w(TAG, "horizonos.view.SurfaceViewExt unavailable on this device/OS build "
                    + "(needs Horizon OS SDK 204+) — panel stays mono.", e);
        }
    }

    private void ensureMirrorThread() {
        if (mirrorThread == null) {
            mirrorThread = new HandlerThread("SpatialLauncherMirror");
            mirrorThread.start();
            mirrorHandler = new Handler(mirrorThread.getLooper());
        }
    }

    /** Tears down the capture surfaces only — leaves mediaProjection/service alone. */
    private void releaseCapturePipeline() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    private void onMirrorFrameAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }
        try {
            Bitmap frame = imageToBitmap(image);
            requestWindowBoundsUpdate(frame);
            Bitmap windowOnly = cropToCapturedWindow(frame);
            int w = windowOnly.getWidth();
            int h = windowOnly.getHeight();
            if (w != captureWidth || h != captureHeight) {
                captureWidth = w;
                captureHeight = h;
                runOnUiThread(() -> {
                    fitSurfaceToCaptureAspectRatio();
                    activeStereoSurface().post(this::lockSurfaceBufferSize);
                });
            }
            if (ttsEnabled) {
                screenFrameCapture.retainSourceForTap(windowOnly);
            }
            drawStereoMirrorFrame(windowOnly);
            if (forceStereoEnabled) {
                requestDepthUpdate(windowOnly);
            }
            if (wantScreenOcr()) {
                screenFrameCapture.offerFromScreenBuffer(windowOnly);
            }
        } finally {
            image.close();
        }
    }

    private Bitmap cropToCapturedWindow(Bitmap frame) {
        Rect rect = capturedWindowRect;
        if (rect == null
                || (rect.left == 0 && rect.top == 0
                && rect.width() == frame.getWidth() && rect.height() == frame.getHeight())) {
            return frame;
        }
        try {
            return Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height());
        } catch (IllegalArgumentException e) {
            return frame;
        }
    }

    private void requestWindowBoundsUpdate(Bitmap rawFrame) {
        if (!windowBoundsBusy.compareAndSet(false, true)) {
            return;
        }
        Bitmap frameCopy = rawFrame.copy(rawFrame.getConfig(), false);
        depthExecutor.execute(() -> {
            try {
                Rect detected = detectPaddedWindowBounds(frameCopy);
                if (detected == null) {
                    return;
                }
                Rect locked = capturedWindowRect;
                if (locked == null) {
                    capturedWindowRect = detected;
                    return;
                }
                // Only grow — never shrink. Fullscreen-in-window video often adds dark
                // letterbox inside the app; shrinking to that is the crop-in bug.
                Rect grown = new Rect(
                        Math.min(locked.left, detected.left),
                        Math.min(locked.top, detected.top),
                        Math.max(locked.right, detected.right),
                        Math.max(locked.bottom, detected.bottom));
                if (!grown.equals(locked)) {
                    capturedWindowRect = grown;
                }
            } finally {
                frameCopy.recycle();
                windowBoundsBusy.set(false);
            }
        });
    }

    private static final int PAD_COLOR_DELTA = 10;

    /**
     * Finds the selected app window inside a VirtualDisplay frame by treating the
     * corner color as the OS pad (often a flat gray, not black) and scanning inward
     * until a row/column is no longer that pad. This does not look for "dark video
     * bars", so maximizing a video inside Firefox won't shrink the crop.
     */
    private Rect detectPaddedWindowBounds(Bitmap frame) {
        int w = frame.getWidth();
        int h = frame.getHeight();
        if (w < 8 || h < 8) {
            return null;
        }
        int padColor = frame.getPixel(0, 0);
        int stepX = Math.max(1, w / 64);
        int stepY = Math.max(1, h / 64);

        int top = 0;
        while (top < h / 2 && isRowPadColor(frame, top, stepX, padColor)) {
            top += stepY;
        }
        int bottom = h - 1;
        while (bottom > h / 2 && isRowPadColor(frame, bottom, stepX, padColor)) {
            bottom -= stepY;
        }
        int left = 0;
        while (left < w / 2 && isColPadColor(frame, left, stepY, padColor)) {
            left += stepX;
        }
        int right = w - 1;
        while (right > w / 2 && isColPadColor(frame, right, stepY, padColor)) {
            right -= stepX;
        }

        if (right - left < w / 8 || bottom - top < h / 8) {
            return null;
        }
        return new Rect(left, top, right + 1, bottom + 1);
    }

    private boolean isRowPadColor(Bitmap frame, int y, int stepX, int padColor) {
        int total = 0;
        int pad = 0;
        for (int x = 0; x < frame.getWidth(); x += stepX) {
            total++;
            if (isNearColor(frame.getPixel(x, y), padColor)) {
                pad++;
            }
        }
        return total > 0 && pad / (float) total >= 0.97f;
    }

    private boolean isColPadColor(Bitmap frame, int x, int stepY, int padColor) {
        int total = 0;
        int pad = 0;
        for (int y = 0; y < frame.getHeight(); y += stepY) {
            total++;
            if (isNearColor(frame.getPixel(x, y), padColor)) {
                pad++;
            }
        }
        return total > 0 && pad / (float) total >= 0.97f;
    }

    private static boolean isNearColor(int pixel, int padColor) {
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        int pr = (padColor >> 16) & 0xFF;
        int pg = (padColor >> 8) & 0xFF;
        int pb = padColor & 0xFF;
        return Math.abs(r - pr) <= PAD_COLOR_DELTA
                && Math.abs(g - pg) <= PAD_COLOR_DELTA
                && Math.abs(b - pb) <= PAD_COLOR_DELTA;
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        ByteBuffer buffer = plane.getBuffer();
        Bitmap bitmap = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride, image.getHeight(), Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        return Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
    }

    // Mesh grid for the parallax warp — a real 2D grid now (not just rows), since actual
    // depth varies horizontally too, not only top-to-bottom.
    private static final int BASE_MIN_PSEUDO_PARALLAX_PX = 2;
    private static final int BASE_MAX_PSEUDO_PARALLAX_PX = 16;
    private int depthGridCols = 16;
    private int depthGridRows = 24;
    private volatile boolean invertDepth;
    private boolean applyingDepthProfile;
    private volatile float edgeFadeFraction = 0.125f;
    private volatile long depthUpdateMinIntervalMs;
    private volatile long lastDepthKickMs;
    private volatile boolean depthModeStatic;
    private boolean useGlesZMesh;
    /** True = stretch capture to fill (legacy); false = contain-fit tall apps. */
    private boolean stretchFill;
    /**
     * True while the panel is fully hidden (another VR app in front). Heavy
     * pipelines (depth inference, OCR captures, Listen, TTS, WebViews) pause so
     * they don't lag the foreground title. MediaProjection itself stays alive
     * (re-consent is too expensive); everything here restarts in onStart().
     */
    private volatile boolean backgrounded;
    /** True only when *we* paused TTS for backgrounding (vs. the user's own pause). */
    private boolean ttsPausedForBackground;
    private volatile boolean stereoHandoff;
    private int glesHandoffTries;
    private int lastStaticDepthFingerprint = Integer.MIN_VALUE;
    private int pendingStaticFingerprint = Integer.MIN_VALUE;
    private long staticStableSinceMs;
    private static final long STATIC_DEPTH_SETTLE_MS = 450L;

    // "Depth strength" slider multiplier (50%-300%), applied to the base min/max shift
    // amounts above. 100% = the original tuning. Read on the depth-inference thread and
    // written on the UI thread from the slider callback; volatile is enough since it's a
    // single primitive read/write, no compound state.
    private volatile float depthStrengthMultiplier = 1f;

    private static final int CONVERGENCE_RANGE_PX = 14;
    private volatile float convergenceOffsetPx = 0f;

    private float minParallaxPx() {
        return BASE_MIN_PSEUDO_PARALLAX_PX * depthStrengthMultiplier;
    }

    private float maxParallaxPx() {
        return BASE_MAX_PSEUDO_PARALLAX_PX * depthStrengthMultiplier;
    }

    // The most recently computed parallax grid, shared between the fast draw path (every
    // captured frame) and the slow depth-inference path (see requestDepthUpdate()).
    // Starts as a flat mid-value grid so the very first frames still show *something*
    // before the first depth estimate lands.
    private volatile float[][] cachedParallaxGrid = flatParallaxGrid();
    private volatile float[][] cachedDepth01;
    private final java.util.concurrent.ExecutorService depthExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean depthInferenceBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Off → "3D" text. On → 3D glasses icon (same circle button chrome).
     */
    private void updateStereoToggleLook(ToggleButton toggleStereo3d, boolean isOn) {
        toggleStereo3d.setAlpha(1f);
        int color = getResources().getColor(R.color.text_primary, getTheme());
        toggleStereo3d.setTextColor(color);
        String label = getString(R.string.toggle_3d_label);
        // Clear any previous compound/foreground so off/on don't stack.
        toggleStereo3d.setCompoundDrawables(null, null, null, null);
        toggleStereo3d.setForeground(null);
        if (isOn) {
            toggleStereo3d.setTextOn("");
            toggleStereo3d.setTextOff(label);
            toggleStereo3d.setText("");
            android.graphics.drawable.Drawable glasses =
                    ContextCompat.getDrawable(this, R.drawable.ic_3d_glasses);
            if (glasses != null) {
                glasses = glasses.mutate();
                glasses.setTint(color);
                // Match ImageButton icon padding (globe/gear use ~10dp).
                int pad = Math.round(10f * getResources().getDisplayMetrics().density);
                toggleStereo3d.setForeground(glasses);
                toggleStereo3d.setForegroundGravity(android.view.Gravity.CENTER);
                toggleStereo3d.setPadding(pad, pad, pad, pad);
            }
        } else {
            toggleStereo3d.setTextOn(label);
            toggleStereo3d.setTextOff(label);
            toggleStereo3d.setText(label);
            toggleStereo3d.setPadding(0, 0, 0, 0);
        }
    }

    private void bindAdvanced3dSettings() {
        depthModeStatic = settingsStore.getDepthModeStatic();
        if (depthModeStatic) {
            settingsStore.seedStaticDepthProfileIfNeeded();
        }
        refreshDepthModeButtons();

        findViewById(R.id.depth_mode_live).setOnClickListener(v -> setDepthModeStatic(false));
        findViewById(R.id.depth_mode_static).setOnClickListener(v -> setDepthModeStatic(true));

        Switch glesToggle = findViewById(R.id.toggle_gles_z_mesh);
        useGlesZMesh = settingsStore.getGlesZMesh();
        glesToggle.setChecked(useGlesZMesh);
        glesToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            useGlesZMesh = isChecked;
            settingsStore.setGlesZMesh(isChecked);
            restartStereoAfterGlesToggle();
        });

        stretchFill = settingsStore.getStretchFill();
        Switch stretchToggle = findViewById(R.id.toggle_stretch_fill);
        if (stretchToggle != null) {
            stretchToggle.setChecked(stretchFill);
            stretchToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                stretchFill = isChecked;
                settingsStore.setStretchFill(isChecked);
                if (videoPlaying) {
                    fitVideoStageToAspect();
                } else {
                    fitSurfaceToCaptureAspectRatio();
                }
                contentArea.post(() -> {
                    if (videoPlaying) {
                        fitVideoStageToAspect();
                    } else {
                        fitSurfaceToCaptureAspectRatio();
                    }
                    lockSurfaceBufferSize();
                });
            });
        }

        SeekBar contrastBar = findViewById(R.id.seekbar_depth_contrast);
        TextView contrastValue = findViewById(R.id.depth_contrast_value);
        contrastBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                applyDepthContrast(progress);
                contrastValue.setText(String.valueOf(progress));
                if (fromUser && !applyingDepthProfile) {
                    settingsStore.setDepthContrastPercent(depthModeStatic, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        Switch invert = findViewById(R.id.toggle_invert_depth);
        invert.setOnCheckedChangeListener((buttonView, isChecked) -> {
            invertDepth = isChecked;
            if (!applyingDepthProfile) {
                settingsStore.setInvertDepth(depthModeStatic, isChecked);
            }
        });

        SeekBar softnessBar = findViewById(R.id.seekbar_edge_softness);
        TextView softnessValue = findViewById(R.id.edge_softness_value);
        softnessBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                applyEdgeSoftness(progress);
                softnessValue.setText(String.valueOf(progress));
                if (fromUser && !applyingDepthProfile) {
                    settingsStore.setEdgeSoftnessPercent(depthModeStatic, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        SeekBar smoothBar = findViewById(R.id.seekbar_depth_smoothness);
        TextView smoothValue = findViewById(R.id.depth_smoothness_value);
        smoothBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                applySmoothness(progress);
                smoothValue.setText(String.valueOf(progress));
                if (fromUser && !applyingDepthProfile) {
                    settingsStore.setDepthSmoothnessPercent(depthModeStatic, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        SeekBar updateBar = findViewById(R.id.seekbar_depth_update_speed);
        TextView updateValue = findViewById(R.id.depth_update_speed_value);
        updateBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                applyDepthUpdateSpeed(progress);
                updateValue.setText(String.valueOf(progress));
                if (fromUser && !applyingDepthProfile) {
                    settingsStore.setDepthUpdateSpeedPercent(depthModeStatic, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        findViewById(R.id.reset_depth_defaults).setOnClickListener(v -> resetCurrentDepthDefaults());

        applyDepthProfileToUi();
    }

    private void resetCurrentDepthDefaults() {
        settingsStore.resetDepthProfile(depthModeStatic);
        lastStaticDepthFingerprint = Integer.MIN_VALUE;
        pendingStaticFingerprint = Integer.MIN_VALUE;
        cachedParallaxGrid = flatParallaxGrid();
        cachedDepth01 = null;
        applyDepthProfileToUi();
    }

    private void applyDepthProfileToUi() {
        boolean stills = depthModeStatic;
        applyingDepthProfile = true;
        try {
            int strength = Math.max(10, settingsStore.getDepthStrengthPercent(stills));
            SeekBar strengthBar = findViewById(R.id.seekbar_depth_strength);
            TextView strengthValue = findViewById(R.id.depth_strength_value);
            strengthBar.setProgress(strength);
            depthStrengthMultiplier = strength / 100f;
            strengthValue.setText(strength + "%");

            int conv = settingsStore.getConvergenceProgress(stills);
            SeekBar convBar = findViewById(R.id.seekbar_convergence);
            TextView convValue = findViewById(R.id.convergence_value);
            convBar.setProgress(conv);
            int px = Math.round((conv - 50) / 50f * CONVERGENCE_RANGE_PX);
            convergenceOffsetPx = px;
            convValue.setText(String.valueOf(px));

            int contrast = settingsStore.getDepthContrastPercent(stills);
            ((SeekBar) findViewById(R.id.seekbar_depth_contrast)).setProgress(contrast);
            applyDepthContrast(contrast);

            invertDepth = settingsStore.getInvertDepth(stills);
            ((Switch) findViewById(R.id.toggle_invert_depth)).setChecked(invertDepth);

            int softness = settingsStore.getEdgeSoftnessPercent(stills);
            ((SeekBar) findViewById(R.id.seekbar_edge_softness)).setProgress(softness);
            applyEdgeSoftness(softness);

            int smoothness = settingsStore.getDepthSmoothnessPercent(stills);
            ((SeekBar) findViewById(R.id.seekbar_depth_smoothness)).setProgress(smoothness);
            applySmoothness(smoothness);

            int updateSpeed = settingsStore.getDepthUpdateSpeedPercent(stills);
            ((SeekBar) findViewById(R.id.seekbar_depth_update_speed)).setProgress(updateSpeed);
            applyDepthUpdateSpeed(updateSpeed);
        } finally {
            applyingDepthProfile = false;
        }
    }

    private static float contrastPercentToGamma(int percent) {
        return Math.max(0.3f, Math.min(1.0f, 1f - percent * 0.0073f));
    }

    private void setDepthModeStatic(boolean stills) {
        depthModeStatic = stills;
        settingsStore.setDepthModeStatic(stills);
        lastStaticDepthFingerprint = Integer.MIN_VALUE;
        pendingStaticFingerprint = Integer.MIN_VALUE;
        if (depthEstimator != null) {
            depthEstimator.resetTemporal();
        }
        if (depthStaticEstimator != null) {
            depthStaticEstimator.resetTemporal();
        }
        if (stills) {
            ensureStaticDepthModel();
        }
        applyDepthProfileToUi();
        refreshDepthModeButtons();
    }

    private void ensureStaticDepthModel() {
        if (depthStaticEstimator != null || staticDepthLoadStarted) {
            return;
        }
        staticDepthLoadStarted = true;
        new Thread(() -> {
            DepthEstimator estimator = new DepthEstimator(
                    getApplicationContext(),
                    DepthEstimator.STATIC_MODEL_ASSET,
                    true,
                    4);
            estimator.setDepthGamma(contrastPercentToGamma(settingsStore.getDepthContrastPercent(true)));
            if (estimator.isAvailable()) {
                depthStaticEstimator = estimator;
                Log.i(TAG, "Static Depth Anything V2 model ready");
            } else {
                staticDepthLoadStarted = false;
                Log.w(TAG, "Static depth model failed; Static mode will use MiDaS");
            }
        }, "StaticDepthLoader").start();
    }

    private void refreshDepthModeButtons() {
        Button live = findViewById(R.id.depth_mode_live);
        Button still = findViewById(R.id.depth_mode_static);
        if (live == null || still == null) {
            return;
        }
        live.setAlpha(depthModeStatic ? 0.45f : 1f);
        still.setAlpha(depthModeStatic ? 1f : 0.45f);
    }

    private void applyDepthContrast(int percent) {
        float gamma = contrastPercentToGamma(percent);
        DepthEstimator target = depthModeStatic ? depthStaticEstimator : depthEstimator;
        if (target != null) {
            target.setDepthGamma(gamma);
        }
    }

    private void applyEdgeSoftness(int percent) {
        edgeFadeFraction = percent / 100f * 0.45f;
    }

    private void applySmoothness(int percent) {
        float t = percent / 100f;
        depthGridCols = 8 + Math.round(t * 16);
        depthGridRows = 12 + Math.round(t * 24);
        cachedParallaxGrid = flatParallaxGrid();
        cachedDepth01 = null;
    }

    private void applyDepthUpdateSpeed(int percent) {
        depthUpdateMinIntervalMs = (100L - percent) * 12L;
    }

    private float edgeFadeForColumn(float col, int meshCols) {
        if (edgeFadeFraction <= 0.001f || meshCols <= 0) {
            return 1f;
        }
        // Softness only pins the outermost column(s). A wide fade bows the whole frame
        // into a "window" while content stays flat — exactly the Static look we hate.
        float cells = Math.max(1f, Math.min(2f, meshCols * edgeFadeFraction * 0.25f));
        float dist = Math.min(col, meshCols - col);
        if (dist >= cells) {
            return 1f;
        }
        return 0.2f + 0.8f * (dist / cells);
    }

    private float[][] flatParallaxGrid() {
        // All-zero (flush with the screen), not a uniform nonzero shift — a uniform
        // nonzero placeholder is itself indistinguishable from "the whole window is
        // popping out", which is exactly the unwanted effect being fixed here.
        return new float[depthGridRows + 1][depthGridCols + 1];
    }

    /**
     * Renders {@code frame} into the mirror surface. With 3D on: side-by-side eyes +
     * parallax mesh. With 3D off: one full-bleed image (no mesh, no half-width stretch).
     * Stretch-fill mode fills each destination rect edge-to-edge; fit mode contain-fits
     * the frame (letterbox bars) so tall apps are not distorted — matching what the
     * overlay/TTS mapping math assumes.
     */
    private static Rect containRect(int boxW, int boxH, int contentW, int contentH) {
        if (boxW <= 0 || boxH <= 0 || contentW <= 0 || contentH <= 0) {
            return new Rect(0, 0, Math.max(1, boxW), Math.max(1, boxH));
        }
        float scale = Math.min(boxW / (float) contentW, boxH / (float) contentH);
        int dw = Math.max(1, Math.round(contentW * scale));
        int dh = Math.max(1, Math.round(contentH * scale));
        int dx = (boxW - dw) / 2;
        int dy = (boxH - dh) / 2;
        return new Rect(dx, dy, dx + dw, dy + dh);
    }

    private static Rect offsetRect(Rect r, int dx, int dy) {
        return new Rect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy);
    }

    private void drawStereoMirrorFrame(Bitmap frame) {
        if (stereoHandoff) {
            return;
        }
        if (useGlesZMesh) {
            glesZMeshView.submit(
                    frame,
                    cachedDepth01,
                    forceStereoEnabled,
                    depthStrengthMultiplier,
                    convergenceOffsetPx,
                    edgeFadeFraction,
                    !depthModeStatic);
            return;
        }
        if (glesZMeshView.ownsSurface()) {
            return;
        }
        if (!gameRenderSurface.getHolder().getSurface().isValid()) {
            return;
        }
        Canvas canvas = gameRenderSurface.getHolder().lockCanvas();
        if (canvas == null) {
            return;
        }
        try {
            canvas.drawColor(Color.BLACK);
            int cw = canvas.getWidth();
            int ch = canvas.getHeight();
            int fw = frame.getWidth();
            int fh = frame.getHeight();
            // Video 3D always fills the stereo surface (avoid cast-style letterbox PiP).
            boolean fillEyes = stretchFill || videoPlaying;
            if (!forceStereoEnabled) {
                Rect dest = fillEyes
                        ? new Rect(0, 0, cw, ch)
                        : containRect(cw, ch, fw, fh);
                canvas.drawBitmap(frame, null, dest, MESH_PAINT);
                return;
            }
            int halfWidth = cw / 2;
            float[][] parallaxGrid = cachedParallaxGrid;
            Rect leftEye = fillEyes
                    ? new Rect(0, 0, halfWidth, ch)
                    : containRect(halfWidth, ch, fw, fh);
            // Same width as left — avoid odd-pixel right eye looking wider.
            Rect rightEye = fillEyes
                    ? new Rect(halfWidth, 0, halfWidth + halfWidth, ch)
                    : offsetRect(containRect(halfWidth, ch, fw, fh), halfWidth, 0);
            drawEyeWithParallaxMesh(canvas, frame, leftEye, parallaxGrid, 1);
            drawEyeWithParallaxMesh(canvas, frame, rightEye, parallaxGrid, -1);
        } finally {
            gameRenderSurface.getHolder().unlockCanvasAndPost(canvas);
        }
    }

    /**
     * Kicks off one depth-model inference pass on a dedicated background thread, if one
     * isn't already running. MiDaS inference is far slower than the mirror's frame
     * interval (that's what was capping the whole mirror to ~2fps before this), so this
     * intentionally skips/drops frames for depth purposes — it only ever processes the
     * latest frame, never queues up a backlog — and only updates cachedParallaxGrid
     * (read by the fast draw path above) once each pass finishes.
     */
    private void requestDepthUpdate(Bitmap frame) {
        if (backgrounded) {
            return;
        }
        DepthEstimator estimator = depthModeStatic && depthStaticEstimator != null
                && depthStaticEstimator.isAvailable()
                ? depthStaticEstimator
                : depthEstimator;
        if (estimator == null || !estimator.isAvailable() || frame == null) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        int cols = depthGridCols;
        int rows = depthGridRows;
        if (depthModeStatic) {
            int fp = stillDepthFingerprint(frame);
            if (fp == lastStaticDepthFingerprint) {
                return;
            }
            if (fp != pendingStaticFingerprint) {
                pendingStaticFingerprint = fp;
                staticStableSinceMs = now;
                return;
            }
            if (now - staticStableSinceMs < STATIC_DEPTH_SETTLE_MS) {
                return;
            }
            cols = Math.min(32, depthGridCols + 8);
            rows = Math.min(48, depthGridRows + 12);
        } else if (now - lastDepthKickMs < depthUpdateMinIntervalMs) {
            return;
        }
        if (!depthInferenceBusy.compareAndSet(false, true)) {
            return;
        }
        lastDepthKickMs = now;
        Bitmap frameCopy = frame.copy(frame.getConfig(), false);
        final int gridCols = cols;
        final int gridRows = rows;
        final boolean invert = invertDepth;
        final boolean stills = depthModeStatic;
        final int settledFp = pendingStaticFingerprint;
        depthExecutor.execute(() -> {
            try {
                float[][] depth01 = estimator.estimate(frameCopy, gridCols + 1, gridRows + 1);
                if (depth01 != null) {
                    float[][] depthCopy = new float[gridRows + 1][gridCols + 1];
                    for (int row = 0; row <= gridRows; row++) {
                        for (int col = 0; col <= gridCols; col++) {
                            float d = depth01[row][col];
                            if (invert) {
                                d = 1f - d;
                            }
                            depthCopy[row][col] = d;
                        }
                    }
                    // Reference + flatten frame borders so letterbox/chrome don't bow
                    // the window. Content pop comes from interior deltas only.
                    float ref01 = centerDepth01(depthCopy);
                    flattenDepthBorder(depthCopy, ref01, 2);
                    float span = (maxParallaxPx() - minParallaxPx()) * (stills ? 1.9f : 1.3f);
                    float[][] grid = new float[gridRows + 1][gridCols + 1];
                    for (int row = 0; row <= gridRows; row++) {
                        for (int col = 0; col <= gridCols; col++) {
                            grid[row][col] = (depthCopy[row][col] - ref01) * span;
                        }
                    }
                    cachedParallaxGrid = grid;
                    cachedDepth01 = depthCopy;
                    if (stills) {
                        lastStaticDepthFingerprint = settledFp;
                    }
                }
            } finally {
                frameCopy.recycle();
                depthInferenceBusy.set(false);
            }
        });
    }

    private static float centerDepth01(float[][] depth) {
        if (depth == null || depth.length < 3 || depth[0].length < 3) {
            return 0.5f;
        }
        int rows = depth.length;
        int cols = depth[0].length;
        int r0 = rows / 5;
        int r1 = rows - 1 - r0;
        int c0 = cols / 5;
        int c1 = cols - 1 - c0;
        float s = 0f;
        int n = 0;
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                s += depth[r][c];
                n++;
            }
        }
        return n == 0 ? 0.5f : s / n;
    }

    /** Pull frame-border depths toward the content reference so edges don't warp. */
    private static void flattenDepthBorder(float[][] depth, float ref01, int margin) {
        if (depth == null || margin <= 0) {
            return;
        }
        int rows = depth.length - 1;
        int cols = depth[0].length - 1;
        for (int r = 0; r <= rows; r++) {
            for (int c = 0; c <= cols; c++) {
                int dist = Math.min(Math.min(r, rows - r), Math.min(c, cols - c));
                if (dist >= margin) {
                    continue;
                }
                float t = dist / (float) margin;
                depth[r][c] = depth[r][c] * t + ref01 * (1f - t);
            }
        }
    }

    private static int stillDepthFingerprint(Bitmap frame) {
        int srcW = frame.getWidth();
        int srcH = frame.getHeight();
        if (srcW <= 0 || srcH <= 0) {
            return 0;
        }
        Bitmap tiny = Bitmap.createScaledBitmap(frame, 16, 9, true);
        int hash = 17;
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 16; x++) {
                int c = tiny.getPixel(x, y);
                int lum = (((c >> 16) & 0xff) * 3 + ((c >> 8) & 0xff) * 6 + (c & 0xff)) / 10;
                hash = 31 * hash + (lum >> 3);
            }
        }
        if (tiny != frame) {
            tiny.recycle();
        }
        return hash;
    }

    private static final Paint MESH_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /**
     * Draws the whole frame through a bitmap mesh, displacing each grid vertex
     * horizontally by its parallax amount. drawBitmapMesh continuously interpolates
     * between vertices, so there are no hard seams/grid lines between cells and no extra
     * independent-resample blur like separate drawBitmap() calls per band would cause.
     */

    private void drawEyeWithParallaxMesh(Canvas canvas, Bitmap frame, Rect eyeBounds, float[][] parallaxGrid,
            int direction) {
        int meshRows = parallaxGrid.length - 1;
        int meshCols = parallaxGrid[0].length - 1;
        float[] verts = new float[(meshCols + 1) * (meshRows + 1) * 2];

        // Edge shift is already flattened in the depth grid; only a 1-column pin remains
        // via edgeFadeForColumn so the bitmap never samples outside the eye clip.
        int k = 0;
        for (int row = 0; row <= meshRows; row++) {
            float v = row / (float) meshRows;
            float yDst = eyeBounds.top + v * eyeBounds.height();
            for (int col = 0; col <= meshCols; col++) {
                float u = col / (float) meshCols;
                float edgeFade = edgeFadeForColumn(col, meshCols);
                float shift = direction * (parallaxGrid[row][col] + convergenceOffsetPx) * edgeFade;
                float xDst = eyeBounds.left + u * eyeBounds.width() + shift;
                verts[k++] = xDst;
                verts[k++] = yDst;
            }
        }

        canvas.save();
        canvas.clipRect(eyeBounds);
        canvas.drawBitmapMesh(frame, meshCols, meshRows, verts, 0, null, 0, MESH_PAINT);
        canvas.restore();
    }

    /**
     * Desktop Link is a thin SBS viewer — PC owns depth/OCR/TTS/Listen.
     * Pause Quest-side heavy pipelines so they do not run alongside the PC stream.
     */
    private void pausePipelinesForDesktopLink() {
        if (listenEngine != null) {
            listenEngine.stop();
        }
        if (assistMode == AssistMode.LISTEN) {
            setAssistMode(AssistMode.DEFAULT);
        }
        setSessionStereo(false);
        // Keep cast surface if any, but stop forcing Quest depth composition for Desktop Link.
        setStereoComposition(false);
        if (shareCaption != null) {
            shareCaption.setVisibility(View.GONE);
        }
        PanelAlerts.show(this,
                "Needs Spatial Launcher Desktop on PC (github.com/saogalaxy/SpatialLauncher). Quest depth/OCR/Listen paused while linked.");
    }

    private void stopMirroring() {
        if (listenEngine != null) {
            listenEngine.stop();
        }
        releaseCapturePipeline();
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        // Bound service stays alive for the next cast; drop mediaProjection FGS until then.
        if (mirrorCaptureService != null) {
            mirrorCaptureService.leaveProjectionForeground();
        }
        mirroringApp = null;
        setStereoComposition(false);
        setStereoOutputVisible(false);
        stopMirrorButton.setVisibility(View.GONE);
        emptyStateText.setVisibility(View.VISIBLE);
        setHomeRowCompact(false);
        setCastTheme(false);
        persistSession();
    }

    /**
     * While a cast is running, the panel chrome (letterbox around the stream, card, and
     * window fill) goes black so those bars don't read as a bright frame around the
     * video. Idle frosted-glass styling is restored when the mirror stops.
     */
    private void setCastTheme(boolean casting) {
        int chrome = getResources().getColor(R.color.cast_chrome, getTheme());
        int idle = getResources().getColor(R.color.panel_bg_solid, getTheme());
        panelRoot.setBackgroundColor(casting ? chrome : idle);
        cardRoot.setBackgroundResource(casting ? R.drawable.bg_cast_panel : R.drawable.bg_frosted_panel);
        contentArea.setBackgroundColor(casting ? chrome : Color.TRANSPARENT);
        controlRow.setBackgroundColor(casting ? chrome : Color.TRANSPARENT);
        dockContainer.setBackgroundResource(
                casting ? R.drawable.bg_dock_pill_cast : R.drawable.bg_dock_pill);
        // Always opaque dark — frosted idle glass washes out Settings / Help text.
        settingsDrawer.setBackgroundResource(R.drawable.bg_settings_drawer);
        if (helpDrawer != null) {
            helpDrawer.setBackgroundResource(R.drawable.bg_help_drawer);
        }
        int closeBg = getResources().getColor(R.color.dock_icon_bg, getTheme());
        int closeText = getResources().getColor(R.color.help_text_primary, getTheme());
        settingsCloseButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(closeBg));
        settingsCloseButton.setTextColor(closeText);
        if (helpCloseButton != null) {
            helpCloseButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(closeBg));
            helpCloseButton.setTextColor(closeText);
        }
        getWindow().getDecorView().setBackgroundColor(casting ? chrome : idle);
    }

    /**
     * Idle dock is the full icon+label tiles (and may wrap upward after 4 apps). While
     * cast / browser / book fills the content area it collapses into a thin single home
     * row under the stream (icons only, smaller buttons) so the dock never covers the
     * content being viewed.
     */
    private void setHomeRowCompact(boolean compact) {
        if (dockLaidOutCompact == null || dockLaidOutCompact != compact) {
            rebuildDockForCompact(compact);
            return;
        }
        applyHomeRowCompact(compact);
    }

    private void applyHomeRowCompact(boolean compact) {
        int rowPadH = dp(compact ? 8 : 10);
        int rowPadV = dp(compact ? 4 : 8);
        controlRow.setPadding(rowPadH, rowPadV, rowPadH, rowPadV);

        int pillPad = dp(compact ? 4 : 12);
        dockContainer.setPadding(pillPad, pillPad, pillPad, pillPad);

        int buttonSize = dp(compact ? 36 : 48);
        int buttonPad = dp(compact ? 6 : 10);
        if (!compact && panelRoot != null && panelRoot.getWidth() > 0) {
            int minSize = dp(32);
            int maxSize = dp(48);
            int scaled = Math.round(panelRoot.getWidth() / 22f);
            buttonSize = Math.max(minSize, Math.min(maxSize, scaled));
            buttonPad = Math.max(dp(4), buttonSize / 5);
        }
        resizeSquareView(stopMirrorButton, buttonSize, buttonPad);
        resizeSquareView(findViewById(R.id.toggle_stereo_3d), buttonSize, 0);
        resizeSquareView(findViewById(R.id.browser_button), buttonSize, buttonPad);
        resizeSquareView(findViewById(R.id.epub_button), buttonSize, buttonPad);
        resizeSquareView(findViewById(R.id.video_button), buttonSize, buttonPad);
        resizeSquareView(findViewById(R.id.tts_speak_button), buttonSize, buttonPad);
        resizeSquareView(findViewById(R.id.listen_button), buttonSize, buttonPad);
        resizeSquareView(findViewById(R.id.desktop_link_button), buttonSize, buttonPad);
        // Transport cluster: slightly tighter than the main round buttons.
        int playerSize = Math.max(dp(32), Math.round(buttonSize * 0.9f));
        int playerPad = Math.max(dp(4), playerSize / 5);
        resizeSquareView(findViewById(R.id.video_bar_rew), playerSize, playerPad);
        resizeSquareView(findViewById(R.id.video_bar_play), playerSize, playerPad);
        resizeSquareView(findViewById(R.id.video_bar_ffwd), playerSize, playerPad);
        resizeSquareView(findViewById(R.id.tts_prev_button), playerSize, playerPad);
        resizeSquareView(findViewById(R.id.tts_play_button), playerSize, playerPad);
        resizeSquareView(findViewById(R.id.tts_pause_button), playerSize, playerPad);
        resizeSquareView(findViewById(R.id.tts_stop_button), playerSize, playerPad);
        resizeSquareView(findViewById(R.id.tts_next_button), playerSize, playerPad);
        resizeSquareView(findViewById(R.id.ocr_region_button), buttonSize, buttonPad);
        resizeSquareView(findViewById(R.id.help_button), buttonSize, buttonPad);
        resizeSquareView(findViewById(R.id.settings_button), buttonSize, buttonPad);
        refreshTtsSpeakButton();

        int iconSize = compact ? dp(28) : buttonSize;
        int tileSize = compact ? dp(40) : Math.max(dp(56), buttonSize + dp(16));
        int itemPad = dp(compact ? 6 : 8);
        int itemMargin = dp(compact ? 6 : 8);
        int rowGap = dp(compact ? 4 : 6);
        for (int r = 0; r < dockContainer.getChildCount(); r++) {
            View rowView = dockContainer.getChildAt(r);
            if (!(rowView instanceof LinearLayout)) {
                continue;
            }
            LinearLayout row = (LinearLayout) rowView;
            LinearLayout.LayoutParams rowParams = (LinearLayout.LayoutParams) row.getLayoutParams();
            rowParams.topMargin = r == 0 ? 0 : rowGap;
            row.setLayoutParams(rowParams);
            for (int i = 0; i < row.getChildCount(); i++) {
                View item = row.getChildAt(i);
                LinearLayout.LayoutParams itemParams = (LinearLayout.LayoutParams) item.getLayoutParams();
                itemParams.width = tileSize;
                itemParams.height = compact ? tileSize : LinearLayout.LayoutParams.WRAP_CONTENT;
                itemParams.setMarginEnd(itemMargin);
                item.setLayoutParams(itemParams);
                item.setPadding(itemPad, itemPad, itemPad, itemPad);
                item.setBackgroundResource(
                        compact ? R.drawable.bg_dock_icon_compact : R.drawable.bg_dock_icon);

                ImageView icon = item.findViewById(R.id.dock_item_icon);
                if (icon != null) {
                    LinearLayout.LayoutParams iconParams = (LinearLayout.LayoutParams) icon.getLayoutParams();
                    iconParams.width = iconSize;
                    iconParams.height = iconSize;
                    icon.setLayoutParams(iconParams);
                }
                TextView label = item.findViewById(R.id.dock_item_label);
                if (label != null) {
                    label.setVisibility(compact ? View.GONE : View.VISIBLE);
                }
            }
        }

        if (isContentViewActive()) {
            contentArea.post(() -> {
                fitSurfaceToCaptureAspectRatio();
                activeStereoSurface().post(this::lockSurfaceBufferSize);
            });
        }
    }

    private void resizeSquareView(View view, int sizePx, int paddingPx) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        params.width = sizePx;
        params.height = sizePx;
        view.setLayoutParams(params);
        view.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        stopBrowserStereo();
        screenFrameCapture.release();
        ttsPreviewHandler.removeCallbacks(ttsPreviewRunnable);
        PiperTtsEngine.get(this).setPlaybackListener(null);
        if (screenDialogueReader != null) {
            screenDialogueReader.shutdown();
            screenDialogueReader = null;
        }
        if (dialogueTextExtractor != null) {
            dialogueTextExtractor.close();
        }
        if (browserCaptureBitmap != null) {
            browserCaptureBitmap.recycle();
            browserCaptureBitmap = null;
        }
        stopMirroring();
        if (mirrorThread != null) {
            mirrorThread.quitSafely();
            mirrorThread = null;
        }
        if (mirrorServiceBound) {
            unbindService(mirrorServiceConnection);
            mirrorServiceBound = false;
            mirrorCaptureService = null;
        }
        try {
            unregisterReceiver(bookImportReceiver);
        } catch (Exception ignored) {
        }
        if (bookImportRunning) {
            stopBookImportServerQuiet();
        }
        if (depthEstimator != null) {
            depthEstimator.close();
            depthEstimator = null;
        }
        if (depthStaticEstimator != null) {
            depthStaticEstimator.close();
            depthStaticEstimator = null;
        }
        depthExecutor.shutdownNow();
        stopVideoPlayback();
        for (WebView tab : browserTabs) {
            if (tab != null) {
                tab.destroy();
            }
        }
        browserTabs.clear();
        drmWebView = null;
        super.onDestroy();
    }

    /** X button on the bar: confirm, then kill the process (frees RAM/CPU now). */
    private void confirmQuitApp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.quit_dialog_title)
                .setMessage(R.string.quit_dialog_message)
                .setPositiveButton(R.string.action_quit, (dialog, which) -> quitAppNow())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void quitAppNow() {
        try {
            stopBookImportServerQuiet();
        } catch (Throwable ignored) {
        }
        try {
            finishAndRemoveTask();
        } catch (Throwable t) {
            finish();
        }
        // Let finish()/onPause()/onDestroy persist + release, then kill outright
        // so no cached process (or :opusmt helper) lingers behind.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Process.killProcess(Process.myPid());
            System.exit(0);
        }, 400);
    }

    private void confirmRemoveFromDock(InstalledAppInfo app) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_game_dialog_title)
                .setMessage(getString(R.string.remove_game_dialog_message, app.label))
                .setPositiveButton(R.string.action_remove, (dialog, which) -> {
                    libraryStore.removePinnedPackage(app.packageName);
                    refreshDock();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Lists installed, launchable apps (minus this app and anything already pinned). */
    private void showAddGameDialog() {
        List<InstalledAppInfo> candidates = queryLaunchableApps();
        if (candidates.isEmpty()) {
            PanelAlerts.show(this, "No more installed apps to add");
            return;
        }

        InstalledAppsAdapter adapter = new InstalledAppsAdapter(this, candidates);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.add_game_dialog_title)
                .setAdapter(adapter, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();

        dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            InstalledAppInfo selected = adapter.getItem(position);
            if (selected != null) {
                libraryStore.addPinnedPackage(selected.packageName);
                refreshDock();
            }
            dialog.dismiss();
        });
        dialog.show();
    }

    private List<InstalledAppInfo> queryLaunchableApps() {
        Set<String> alreadyPinned = libraryStore.getPinnedPackages();
        String ownPackage = getPackageName();

        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = packageManager.queryIntentActivities(launcherIntent, 0);

        List<InstalledAppInfo> result = new ArrayList<>();
        for (ResolveInfo resolveInfo : resolveInfos) {
            String packageName = resolveInfo.activityInfo.packageName;
            if (packageName.equals(ownPackage) || alreadyPinned.contains(packageName)) {
                continue;
            }
            result.add(new InstalledAppInfo(
                    packageName,
                    resolveInfo.loadLabel(packageManager).toString(),
                    resolveInfo.loadIcon(packageManager)));
        }
        Collections.sort(result, Comparator.comparing(a -> a.label.toLowerCase()));
        return result;
    }

    /**
     * The in-panel "stereo view" is implemented in startMirroringAndLaunch() /
     * onMirrorFrameAvailable() / drawStereoMirrorFrame(), using MediaProjection +
     * ImageReader to mirror the screen into game_render_surface as a duplicated
     * side-by-side frame.
     *
     * Known, load-bearing limitation (not a bug — an Android platform boundary): a
     * normal app has no public API to redirect another app's Activity onto a display
     * only it owns (that needs ACTIVITY_EMBEDDING/MANAGE_ACTIVITY_STACKS, which are
     * system/OEM-privileged permissions). So the launched game still gets its own real
     * window; we mirror + reorder-to-front around that rather than truly suppressing it.
     * If this app is ever shipped as a privileged/system Horizon OS component instead of
     * a sideloaded APK, that's the API boundary to revisit for genuine window ownership
     * instead of a screen mirror. Left as a no-op hook in case that path opens up.
     */
    private void prepareStereoRenderSurface() {
        // See doc comment — the working mirror pipeline lives in the methods above.
    }
}
