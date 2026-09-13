package com.spatiallauncher.app.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the user's stereo / depth settings so they survive app restarts and
 * starting/stopping a cast.
 */
public class UserSettingsStore {

    private static final String PREFS_NAME = "spatial_launcher_settings";
    private static final String KEY_FORCE_STEREO = "force_stereo";
    private static final String KEY_DEPTH_STRENGTH_PERCENT = "depth_strength_percent";
    private static final String KEY_CONVERGENCE_PROGRESS = "convergence_progress";
    private static final String KEY_DEPTH_CONTRAST = "depth_contrast_percent";
    private static final String KEY_INVERT_DEPTH = "invert_depth";
    private static final String KEY_EDGE_SOFTNESS = "edge_softness_percent";
    private static final String KEY_DEPTH_SMOOTHNESS = "depth_smoothness_percent";
    private static final String KEY_DEPTH_UPDATE_SPEED = "depth_update_speed_percent";
    private static final String SUFFIX_STATIC = "_static";
    private static final String KEY_DEPTH_STATIC = "depth_mode_static";
    private static final String KEY_GLES_Z_MESH = "gles_z_mesh";
    private static final String KEY_TTS_ENABLED = "tts_enabled";
    private static final String KEY_TTS_SPEED_PERCENT = "tts_speed_percent";
    private static final String KEY_TTS_MANUAL = "tts_manual_mode";
    private static final String KEY_TTS_MALE = "tts_male_voice";
    private static final String KEY_TTS_TONE_PERCENT = "tts_tone_percent";
    /** 100 = smoothest Listen chunks (original); 0 = smallest/fastest. */
    private static final String KEY_LISTEN_SMOOTHNESS_PERCENT = "listen_smoothness_percent";
    private static final String KEY_ASSIST_MODE = "assist_mode";
    private static final String KEY_3D_OFF_PAGE_TRANSLATE = "3d_off_page_translate";
    private static final String KEY_SESSION_MODE = "session_mode";
    private static final String KEY_SESSION_APP = "session_app_package";
    static final String SESSION_IDLE = "idle";
    static final String SESSION_BROWSER = "browser";
    static final String SESSION_APP = "app";

    static final int DEFAULT_DEPTH_STRENGTH_PERCENT = 100;
    static final int DEFAULT_CONVERGENCE_PROGRESS = 50;
    static final int DEFAULT_DEPTH_CONTRAST = 55;
    static final boolean DEFAULT_INVERT_DEPTH = false;
    static final int DEFAULT_EDGE_SOFTNESS = 25;
    static final int DEFAULT_DEPTH_SMOOTHNESS = 50;
    static final int DEFAULT_DEPTH_UPDATE_SPEED = 100;
    static final boolean DEFAULT_DEPTH_STATIC = false;
    static final boolean DEFAULT_GLES_Z_MESH = true;
    static final boolean DEFAULT_FORCE_STEREO = true;
    static final boolean DEFAULT_TTS_ENABLED = false;
    /** 100 = normal Piper pace; slider range 50–200. */
    static final int DEFAULT_TTS_SPEED_PERCENT = 100;
    static final boolean DEFAULT_TTS_MANUAL = false;
    /** Default matches the original smooth Listen flush (700ms / 8s). */
    static final int DEFAULT_LISTEN_SMOOTHNESS_PERCENT = 100;

    private final SharedPreferences prefs;

    public UserSettingsStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean getForceStereo() {
        return prefs.getBoolean(KEY_FORCE_STEREO, DEFAULT_FORCE_STEREO);
    }

    public void setForceStereo(boolean enabled) {
        prefs.edit().putBoolean(KEY_FORCE_STEREO, enabled).apply();
    }

    public int getDepthStrengthPercent(boolean stills) {
        return Math.max(10, getIntForMode(stills, KEY_DEPTH_STRENGTH_PERCENT, DEFAULT_DEPTH_STRENGTH_PERCENT));
    }

    public void setDepthStrengthPercent(boolean stills, int percent) {
        prefs.edit().putInt(modeKey(KEY_DEPTH_STRENGTH_PERCENT, stills), Math.max(10, percent)).apply();
    }

    public int getConvergenceProgress(boolean stills) {
        return getIntForMode(stills, KEY_CONVERGENCE_PROGRESS, DEFAULT_CONVERGENCE_PROGRESS);
    }

    public void setConvergenceProgress(boolean stills, int progress) {
        prefs.edit().putInt(modeKey(KEY_CONVERGENCE_PROGRESS, stills), progress).apply();
    }

    public int getDepthContrastPercent(boolean stills) {
        return Math.max(0, Math.min(100, getIntForMode(stills, KEY_DEPTH_CONTRAST, DEFAULT_DEPTH_CONTRAST)));
    }

    public void setDepthContrastPercent(boolean stills, int percent) {
        prefs.edit().putInt(modeKey(KEY_DEPTH_CONTRAST, stills), Math.max(0, Math.min(100, percent))).apply();
    }

    public boolean getInvertDepth(boolean stills) {
        return getBooleanForMode(stills, KEY_INVERT_DEPTH, DEFAULT_INVERT_DEPTH);
    }

    public void setInvertDepth(boolean stills, boolean invert) {
        prefs.edit().putBoolean(modeKey(KEY_INVERT_DEPTH, stills), invert).apply();
    }

    public int getEdgeSoftnessPercent(boolean stills) {
        return Math.max(0, Math.min(100, getIntForMode(stills, KEY_EDGE_SOFTNESS, DEFAULT_EDGE_SOFTNESS)));
    }

    public void setEdgeSoftnessPercent(boolean stills, int percent) {
        prefs.edit().putInt(modeKey(KEY_EDGE_SOFTNESS, stills), Math.max(0, Math.min(100, percent))).apply();
    }

    public int getDepthSmoothnessPercent(boolean stills) {
        return Math.max(0, Math.min(100, getIntForMode(stills, KEY_DEPTH_SMOOTHNESS, DEFAULT_DEPTH_SMOOTHNESS)));
    }

    public void setDepthSmoothnessPercent(boolean stills, int percent) {
        prefs.edit().putInt(modeKey(KEY_DEPTH_SMOOTHNESS, stills), Math.max(0, Math.min(100, percent))).apply();
    }

    public int getDepthUpdateSpeedPercent(boolean stills) {
        return Math.max(0, Math.min(100, getIntForMode(stills, KEY_DEPTH_UPDATE_SPEED, DEFAULT_DEPTH_UPDATE_SPEED)));
    }

    public void setDepthUpdateSpeedPercent(boolean stills, int percent) {
        prefs.edit().putInt(modeKey(KEY_DEPTH_UPDATE_SPEED, stills), Math.max(0, Math.min(100, percent))).apply();
    }

    private static String modeKey(String liveKey, boolean stills) {
        return stills ? liveKey + SUFFIX_STATIC : liveKey;
    }

    /** Static keys fall back to the live value until Static has been saved once. */
    private int getIntForMode(boolean stills, String liveKey, int def) {
        String key = modeKey(liveKey, stills);
        if (stills && !prefs.contains(key) && prefs.contains(liveKey)) {
            return prefs.getInt(liveKey, def);
        }
        return prefs.getInt(key, def);
    }

    private boolean getBooleanForMode(boolean stills, String liveKey, boolean def) {
        String key = modeKey(liveKey, stills);
        if (stills && !prefs.contains(key) && prefs.contains(liveKey)) {
            return prefs.getBoolean(liveKey, def);
        }
        return prefs.getBoolean(key, def);
    }

    public boolean getGlesZMesh() {
        return prefs.getBoolean(KEY_GLES_Z_MESH, DEFAULT_GLES_Z_MESH);
    }

    public void setGlesZMesh(boolean enabled) {
        prefs.edit().putBoolean(KEY_GLES_Z_MESH, enabled).apply();
    }

    public boolean getDepthModeStatic() {
        return prefs.getBoolean(KEY_DEPTH_STATIC, DEFAULT_DEPTH_STATIC);
    }

    public void setDepthModeStatic(boolean stills) {
        prefs.edit().putBoolean(KEY_DEPTH_STATIC, stills).apply();
        if (stills) {
            seedStaticDepthProfileIfNeeded();
        }
    }

    /** Copy Live sliders into Static once, so the two profiles start equal then diverge. */
    /** Factory defaults for Live or Static depth sliders (does not change Live/Static mode). */
    public void resetDepthProfile(boolean stills) {
        prefs.edit()
                .putInt(modeKey(KEY_DEPTH_STRENGTH_PERCENT, stills), DEFAULT_DEPTH_STRENGTH_PERCENT)
                .putInt(modeKey(KEY_CONVERGENCE_PROGRESS, stills), DEFAULT_CONVERGENCE_PROGRESS)
                .putInt(modeKey(KEY_DEPTH_CONTRAST, stills), DEFAULT_DEPTH_CONTRAST)
                .putBoolean(modeKey(KEY_INVERT_DEPTH, stills), DEFAULT_INVERT_DEPTH)
                .putInt(modeKey(KEY_EDGE_SOFTNESS, stills), DEFAULT_EDGE_SOFTNESS)
                .putInt(modeKey(KEY_DEPTH_SMOOTHNESS, stills), DEFAULT_DEPTH_SMOOTHNESS)
                .putInt(modeKey(KEY_DEPTH_UPDATE_SPEED, stills), DEFAULT_DEPTH_UPDATE_SPEED)
                .apply();
    }

    public void seedStaticDepthProfileIfNeeded() {
        String sentinel = modeKey(KEY_DEPTH_STRENGTH_PERCENT, true);
        if (prefs.contains(sentinel)) {
            return;
        }
        prefs.edit()
                .putInt(modeKey(KEY_DEPTH_STRENGTH_PERCENT, true),
                        getIntForMode(false, KEY_DEPTH_STRENGTH_PERCENT, DEFAULT_DEPTH_STRENGTH_PERCENT))
                .putInt(modeKey(KEY_CONVERGENCE_PROGRESS, true),
                        getIntForMode(false, KEY_CONVERGENCE_PROGRESS, DEFAULT_CONVERGENCE_PROGRESS))
                .putInt(modeKey(KEY_DEPTH_CONTRAST, true),
                        getIntForMode(false, KEY_DEPTH_CONTRAST, DEFAULT_DEPTH_CONTRAST))
                .putBoolean(modeKey(KEY_INVERT_DEPTH, true),
                        getBooleanForMode(false, KEY_INVERT_DEPTH, DEFAULT_INVERT_DEPTH))
                .putInt(modeKey(KEY_EDGE_SOFTNESS, true),
                        getIntForMode(false, KEY_EDGE_SOFTNESS, DEFAULT_EDGE_SOFTNESS))
                .putInt(modeKey(KEY_DEPTH_SMOOTHNESS, true),
                        getIntForMode(false, KEY_DEPTH_SMOOTHNESS, DEFAULT_DEPTH_SMOOTHNESS))
                .putInt(modeKey(KEY_DEPTH_UPDATE_SPEED, true),
                        getIntForMode(false, KEY_DEPTH_UPDATE_SPEED, DEFAULT_DEPTH_UPDATE_SPEED))
                .apply();
    }

    public boolean getTtsEnabled() {
        return prefs.getBoolean(KEY_TTS_ENABLED, DEFAULT_TTS_ENABLED);
    }

    public void setTtsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TTS_ENABLED, enabled).apply();
    }

    public int getTtsSpeedPercent() {
        int v = prefs.getInt(KEY_TTS_SPEED_PERCENT, DEFAULT_TTS_SPEED_PERCENT);
        return Math.max(50, Math.min(200, v));
    }

    public void setTtsSpeedPercent(int percent) {
        prefs.edit().putInt(KEY_TTS_SPEED_PERCENT, Math.max(50, Math.min(200, percent))).apply();
    }

    /** True = tap-to-speak; false = continuous automatic reading. */
    public boolean getTtsManualMode() {
        return prefs.getBoolean(KEY_TTS_MANUAL, DEFAULT_TTS_MANUAL);
    }

    public void setTtsManualMode(boolean manual) {
        prefs.edit().putBoolean(KEY_TTS_MANUAL, manual).apply();
    }

    public boolean getTtsMaleVoice() {
        return prefs.getBoolean(KEY_TTS_MALE, false);
    }

    public void setTtsMaleVoice(boolean male) {
        prefs.edit().putBoolean(KEY_TTS_MALE, male).apply();
    }

    public int getTtsTonePercent() {
        return Math.max(0, Math.min(100, prefs.getInt(KEY_TTS_TONE_PERCENT, 50)));
    }

    public void setTtsTonePercent(int percent) {
        prefs.edit().putInt(KEY_TTS_TONE_PERCENT, Math.max(0, Math.min(100, percent))).apply();
    }

    /** 0 = smaller/faster Listen chunks; 100 = smoother longer phrases (default). */
    public int getListenSmoothnessPercent() {
        return Math.max(0, Math.min(100,
                prefs.getInt(KEY_LISTEN_SMOOTHNESS_PERCENT, DEFAULT_LISTEN_SMOOTHNESS_PERCENT)));
    }

    public void setListenSmoothnessPercent(int percent) {
        prefs.edit().putInt(KEY_LISTEN_SMOOTHNESS_PERCENT, Math.max(0, Math.min(100, percent))).apply();
    }

    /** Current OCR→Piper pipeline. Extra modes: on-screen translate, listen, share overlay. */
    public AssistMode getAssistMode() {
        return AssistMode.fromPref(prefs.getString(KEY_ASSIST_MODE, AssistMode.DEFAULT.prefKey));
    }

    public void setAssistMode(AssistMode mode) {
        AssistMode next = mode == null ? AssistMode.DEFAULT : mode;
        prefs.edit().putString(KEY_ASSIST_MODE, next.prefKey).apply();
    }

    /** When true, page Translate turns 3D off for the job and restores it after. */
    public boolean get3dOffForPageTranslate() {
        return prefs.getBoolean(KEY_3D_OFF_PAGE_TRANSLATE, true);
    }

    public void set3dOffForPageTranslate(boolean off) {
        prefs.edit().putBoolean(KEY_3D_OFF_PAGE_TRANSLATE, off).apply();
    }

    public String getSessionMode() {
        String mode = prefs.getString(KEY_SESSION_MODE, SESSION_IDLE);
        return mode == null ? SESSION_IDLE : mode;
    }

    public String getSessionAppPackage() {
        return prefs.getString(KEY_SESSION_APP, "");
    }

    public void setSessionIdle() {
        prefs.edit().putString(KEY_SESSION_MODE, SESSION_IDLE).remove(KEY_SESSION_APP).apply();
    }

    public void setSessionBrowser() {
        prefs.edit().putString(KEY_SESSION_MODE, SESSION_BROWSER).apply();
    }

    public void setSessionApp(String packageName) {
        prefs.edit()
                .putString(KEY_SESSION_MODE, SESSION_APP)
                .putString(KEY_SESSION_APP, packageName == null ? "" : packageName)
                .apply();
    }
}
