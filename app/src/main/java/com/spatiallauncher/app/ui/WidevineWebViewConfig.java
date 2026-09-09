package com.spatiallauncher.app.ui;

import android.annotation.SuppressLint;
import android.media.MediaDrm;
import android.os.Build;
import android.util.Log;
import android.os.Message;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.UUID;

/**
 * Applies WebSettings, DRM permission handling, and in-app HTTPS navigation needed for
 * Widevine / protected-media playback inside an integrated WebView.
 *
 * Widevine security levels (L1 vs L3): default Android WebView runs Widevine at L3
 * (software) or L1 (hardware) depending on whether the device TEE passes verification.
 * Horizon OS documents support for both L1 and L3 for 2D panel apps; HD/UHD streams
 * typically require L1. Most commercial DRM sites play fine in a properly configured
 * WebView at 720p/1080p when RESOURCE_PROTECTED_MEDIA_ID is granted and the page is
 * loaded over HTTPS.
 *
 * Texture rendering for 3D/VR meshes: WebView natively renders to a 2D surface. Feeding
 * that into the MiDaS depth / stereo pipeline requires an explicit capture or texture
 * bind of the WebView — not a plain screen View. To pipe live browser/DRM video into a
 * 3D shader, capture via SurfaceTexture / OpenGL texture ID or an offscreen Canvas
 * pipeline. Note: Widevine L1 secure surfaces may still block PixelCopy / CPU readback
 * (black frames); L3 and non-protected layers are usually readable.
 */
public final class WidevineWebViewConfig {

    private static final String TAG = "WidevineWebView";
    private static boolean emeProbeDone = false;

    /** EME / Clear Key system name used by Shaka, Bitmovin, Crunchyroll, etc. */
    public static final String WIDEVINE_KEY_SYSTEM = "com.widevine.alpha";

    /** Android MediaDrm UUID for Widevine. */
    private static final UUID WIDEVINE_UUID =
            UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed");

    public interface EmeSupportCallback {
        void onResult(boolean supported, String detail);
    }

    public interface UrlCallback {
        void onUrlChanged(WebView webView, String url);
    }

    public interface TitleCallback {
        void onTitle(WebView webView, String title);
    }

    public interface WindowHandler {
        WebView createPopupWindow();
        void closePopupWindow(WebView webView);
    }

    private WidevineWebViewConfig() {}

    @SuppressLint("SetJavaScriptEnabled")
    public static void apply(WebView webView) {
        apply(webView, null, null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static void apply(WebView webView, EmeSupportCallback emeCallback) {
        apply(webView, emeCallback, null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static void apply(WebView webView, EmeSupportCallback emeCallback, UrlCallback urlCallback) {
        apply(webView, emeCallback, urlCallback, null, null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static void apply(
            WebView webView,
            EmeSupportCallback emeCallback,
            UrlCallback urlCallback,
            WindowHandler windowHandler,
            TitleCallback titleCallback) {
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // Widevine license / EME flows often rely on the WebView database store.
        // Deprecated on API 33+, but still required by many DRM player pages.
        settings.setDatabaseEnabled(true);

        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        // Modern mobile Chrome UA so streaming sites don't treat the WebView as a
        // blocked / outdated browser and refuse Widevine / EME playback.
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);

        // Cookies (incl. third-party) — login / license redirects on many DRM sites
        // fail without this; often omitted from short paste snippets.
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        // Step 2: grant RESOURCE_PROTECTED_MEDIA_ID so EME / Widevine license requests
        // are allowed. Without this, DRM playback is denied by default.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                if (request == null) {
                    return;
                }
                String[] resources = request.getResources();
                for (String resource : resources) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(resource)) {
                        request.grant(new String[] {
                                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                        });
                        return;
                    }
                }
                super.onPermissionRequest(request);
            }

            @Override
            public boolean onCreateWindow(
                    WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (windowHandler == null || resultMsg == null || resultMsg.obj == null) {
                    return false;
                }
                WebView child = windowHandler.createPopupWindow();
                if (child == null) {
                    return false;
                }
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(child);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                if (windowHandler != null) {
                    windowHandler.closePopupWindow(window);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (titleCallback != null) {
                    titleCallback.onTitle(view, title);
                }
            }
        });

        // Step 3: keep navigation inside this WebView (never hand off to an external
        // browser). return false = WebView loads the URL itself. Initial loads still go
        // through loadSecureUrl() so the session starts on https:// for Widevine.
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Keeps navigation inside the built-in browser
                return false;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Keeps navigation inside the built-in browser
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Verification: check Widevine + EME with key system com.widevine.alpha.
                probeEmeWidevineSupport(view, emeCallback);
                if (urlCallback != null && url != null && !url.isEmpty()) {
                    urlCallback.onUrlChanged(view, url);
                }
            }
        });

        logNativeWidevineSupport();
    }

    /**
     * Native MediaDrm check (device TEE / L1-L3 capability), independent of the page.
     */
    public static boolean isNativeWidevineSupported() {
        try {
            return MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID);
        } catch (Throwable t) {
            Log.w(TAG, "MediaDrm Widevine probe failed", t);
            return false;
        }
    }

    private static void logNativeWidevineSupport() {
        boolean supported = isNativeWidevineSupported();
        Log.i(TAG, "Native MediaDrm Widevine (" + WIDEVINE_UUID + "): "
                + (supported ? "SUPPORTED" : "NOT SUPPORTED"));
    }

    /**
     * Runs navigator.requestMediaKeySystemAccess('com.widevine.alpha', ...) in-page
     * and reports whether Encrypted Media Extensions + Widevine are available.
     * WebView resolves the returned Promise into the ValueCallback on current Chromium.
     */
    public static void probeEmeWidevineSupport(WebView webView, EmeSupportCallback callback) {
        if (webView == null || emeProbeDone) {
            return;
        }
        emeProbeDone = true;
        String probe =
                "navigator.requestMediaKeySystemAccess('"
                        + WIDEVINE_KEY_SYSTEM
                        + "',[{"
                        + "initDataTypes:['cenc'],"
                        + "audioCapabilities:[{contentType:'audio/mp4; codecs=\"mp4a.40.2\"'}],"
                        + "videoCapabilities:[{contentType:'video/mp4; codecs=\"avc1.42E01E\"'}]"
                        + "}]).then(function(){ return 'SUPPORTED:' + '"
                        + WIDEVINE_KEY_SYSTEM
                        + "'; }).catch(function(e){ return 'UNSUPPORTED:' + e; })";

        webView.evaluateJavascript(probe, value -> {
            String detail = value == null ? "null" : value.replace("\"", "");
            boolean ok = detail.startsWith("SUPPORTED:");
            Log.i(TAG, "EME key system " + WIDEVINE_KEY_SYSTEM + ": " + detail);
            if (callback != null) {
                callback.onResult(ok, detail);
            }
        });
    }

    /**
     * Loads a site inside the WebView. Forces https:// when the caller omits a scheme
     * or passes http://, since Widevine / EME require a secure origin.
     * Example: Load your DRM-enabled video site over HTTPS.
     */
    public static void loadSecureUrl(WebView webView, String url) {
        if (webView == null || url == null || url.trim().isEmpty()) {
            return;
        }
        String secure = toHttpsUrl(url.trim());
        if (secure == null) {
            Log.w(TAG, "Refusing non-HTTPS URL for Widevine secure context: " + url);
            return;
        }
        webView.loadUrl(secure);
    }

    private static String toHttpsUrl(String url) {
        if (url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("http://")) {
            return "https://" + url.substring("http://".length());
        }
        if (url.contains("://")) {
            return null;
        }
        return "https://" + url;
    }
}
