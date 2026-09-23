// Minimal immersive container: OpenXR session + FB passthrough layer only.
// No scene, no swapchains, no input. The panel (2D overlay) provides all UI.
// Errors are logged and degraded gracefully; nothing here may crash the app.

#include <android/asset_manager.h>
#include <android/log.h>
#include <android/native_activity.h>
#include <android_native_app_glue.h>
#include <jni.h>

// Single-header GLB decoder (vendored like the OpenXR headers). Flat colors
// only: no image textures, so no stb dependency and no decode stalls.
#define CGLTF_IMPLEMENTATION
#include "cgltf.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <math.h>
#include <dlfcn.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VrRoom", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "VrRoom", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VrRoom", __VA_ARGS__)

#define XRCHECK(x)                                                            \
    do {                                                                      \
        XrResult _r = (x);                                                    \
        if (_r != XR_SUCCESS) {                                               \
            LOGE("XR fail %d at %s:%d", (int)_r, __FILE__, __LINE__);         \
        }                                                                     \
    } while (0)

// The framework loads ANativeActivity_onCreate out of libvr.so via dlsym, so
// nothing references it and section GC would discard the glue object that
// defines it (the -u link flag alone did not survive this toolchain).
// A used-anchor keeps it linked unconditionally.
extern void ANativeActivity_onCreate(ANativeActivity* activity,
    void* savedState, size_t savedStateSize);
__attribute__((used)) static void* vr_keep_glue_ref =
    (void*)ANativeActivity_onCreate;

// ---- FB_passthrough extension entry points (loaded per instance) ----
static PFN_xrCreatePassthroughFB pfnCreatePassthrough = NULL;
static PFN_xrDestroyPassthroughFB pfnDestroyPassthrough = NULL;
static PFN_xrPassthroughStartFB pfnPassthroughStart = NULL;
static PFN_xrCreatePassthroughLayerFB pfnCreatePassthroughLayer = NULL;
static PFN_xrDestroyPassthroughLayerFB pfnDestroyPassthroughLayer = NULL;

#define LOAD_XR_FN(instance, name, pfn)                                        \
    do {                                                                      \
        XrResult _lr = xrGetInstanceProcAddr((instance), (name),               \
                (PFN_xrVoidFunction*)(&(pfn)));                                \
        if (_lr != XR_SUCCESS || (pfn) == NULL) {                              \
            LOGW("xrGetInstanceProcAddr(%s) failed: %d", (name), (int)_lr);    \
            (pfn) = NULL;                                                     \
        }                                                                     \
    } while (0)

typedef struct {
    ANativeActivity* activity;
    JavaVM* vm;
    // Global ref: the activity clazz local ref must not cross threads.
    jobject activityRef;

    XrInstance instance;
    XrSystemId systemId;
    XrSession session;
    XrSessionState sessionState;
    int sessionRunning;

    XrPassthroughFB passthrough;
    XrPassthroughLayerFB passthroughLayer;
    int hasPassthrough;

    EGLDisplay eglDisplay;
    EGLConfig eglConfig;
    EGLContext eglContext;
    EGLSurface eglSurface;

    XrEnvironmentBlendMode blendMode;

    XrTime lastFrameTime;

    pthread_t thread;
    int threadStarted;
    volatile int requestExit;

    // ---- Theater screen (Phase 1): shared panel frames on a curved quad ----
    XrSpace localSpace;
    XrSwapchain viewSwap[2];
    int swapReady;
    uint32_t swapW, swapH;
    GLuint screenProg;
    GLuint screenTex;
    int texW, texH;
    GLuint screenFbo[2];
    GLuint meshVbo, meshUvbo, meshIbo;
    int meshIndexCount;
    int attrPos, attrUv, uniMvp, uniTex, uniEye, uniSbs;
} VrApp;

// Staging for VrBridge.pushFrame (panel thread writes, render thread reads).
static pthread_mutex_t g_frameLock = PTHREAD_MUTEX_INITIALIZER;
static unsigned char* g_stageBuf = NULL;
static size_t g_stageCap = 0;
static int g_stageW, g_stageH, g_stageSbs;
static int g_stageFresh;
static float g_stageAspect = 1.7778f;

// Theater geometry constants (match the WebXR prototype feel).
#define THEATER_R 5.5f
#define THEATER_ARC 1.15f
#define THEATER_H 2.6f
#define THEATER_Y 1.55f
#define THEATER_SEG_U 64
#define THEATER_SEG_V 10

static int extensionSupported(const char* name) {
    uint32_t count = 0;
    if (xrEnumerateInstanceExtensionProperties(NULL, 0, &count, NULL) != XR_SUCCESS) {
        return 0;
    }
    XrExtensionProperties* props =
        (XrExtensionProperties*)calloc(count, sizeof(XrExtensionProperties));
    if (props == NULL) {
        return 0;
    }
    for (uint32_t i = 0; i < count; i++) {
        props[i].type = XR_TYPE_EXTENSION_PROPERTIES;
    }
    int found = 0;
    if (xrEnumerateInstanceExtensionProperties(NULL, count, &count, props) == XR_SUCCESS) {
        for (uint32_t i = 0; i < count; i++) {
            if (strcmp(props[i].extensionName, name) == 0) {
                found = 1;
                break;
            }
        }
    }
    free(props);
    return found;
}

static int initEgl(VrApp* app) {
    app->eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (app->eglDisplay == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return 0;
    }
    if (!eglInitialize(app->eglDisplay, NULL, NULL)) {
        LOGE("eglInitialize failed");
        return 0;
    }
    const EGLint cfgAttrs[] = {
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_NONE,
    };
    EGLConfig config = NULL;
    EGLint ncfg = 0;
    if (!eglChooseConfig(app->eglDisplay, cfgAttrs, &config, 1, &ncfg) || ncfg < 1) {
        LOGE("eglChooseConfig failed");
        return 0;
    }
    app->eglConfig = config;
    const EGLint ctxAttrs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    app->eglContext = eglCreateContext(app->eglDisplay, config, EGL_NO_CONTEXT, ctxAttrs);
    if (app->eglContext == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext ES3 failed");
        return 0;
    }
    const EGLint surfAttrs[] = { EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE };
    app->eglSurface =
        eglCreatePbufferSurface(app->eglDisplay, config, surfAttrs);
    if (app->eglSurface == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface failed");
        return 0;
    }
    if (!eglMakeCurrent(app->eglDisplay, app->eglSurface,
                app->eglSurface, app->eglContext)) {
        LOGE("eglMakeCurrent failed");
        return 0;
    }
    return 1;
}

static int initXr(VrApp* app) {
    if (app->activityRef == NULL) {
        LOGE("no activity context; aborting before loader init");
        return 0;
    }
    // Loader init is mandatory on Android before xrCreateInstance.
    LOGI("loader init: vm=%p ctx=%p", (void*)app->vm, (void*)app->activityRef);
    {
        // Prefer the SYSTEM loader (version-matched to the runtime): the
        // bundled 1.1.63 init rejects our params with -6 for unknown reasons.
        // Fall back to the bundled loader's own entry point if absent.
        PFN_xrInitializeLoaderKHR pfnInitLoader = NULL;
        XrResult lr = XR_ERROR_INITIALIZATION_FAILED;
        void* sysLoader = dlopen("libopenxr_loader.so", RTLD_NOW | RTLD_LOCAL);
        if (sysLoader != NULL) {
            pfnInitLoader = (PFN_xrInitializeLoaderKHR)dlsym(
                    sysLoader, "xrInitializeLoaderKHR");
            LOGI("system loader: handle=%p initFn=%p", sysLoader, (void*)pfnInitLoader);
            // Intentionally leaked: needed for the process lifetime.
            (void)sysLoader;
            if (pfnInitLoader != NULL) {
                lr = XR_SUCCESS;
            }
        }
        if (pfnInitLoader == NULL) {
            lr = xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR",
                    (PFN_xrVoidFunction*)&pfnInitLoader);
            LOGI("bundled getInitLoader: lr=%d fn=%p", (int)lr, (void*)pfnInitLoader);
        }
        if (lr == XR_SUCCESS && pfnInitLoader != NULL) {
            XrLoaderInitInfoAndroidKHR initInfo;
            memset(&initInfo, 0, sizeof(initInfo));
            initInfo.type = XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR;
            initInfo.applicationVM = app->vm;
            initInfo.applicationContext = app->activityRef != NULL
                ? app->activityRef : (jobject)app->activity->clazz;
            XrResult ir = pfnInitLoader((const XrLoaderInitInfoBaseHeaderKHR*)&initInfo);
            if (ir != XR_SUCCESS) {
                LOGE("xrInitializeLoaderKHR failed: %d", (int)ir);
                return 0;
            }
        } else {
            LOGE("xrInitializeLoaderKHR missing: %d", (int)lr);
            return 0;
        }
    }

    int wantPassthrough = extensionSupported("XR_FB_passthrough");
    const char* exts[2];
    uint32_t next = 0;
    exts[next++] = "XR_KHR_opengl_es_enable";
    if (wantPassthrough) {
        exts[next++] = "XR_FB_passthrough";
    }
    XrInstanceCreateInfo ici;
    memset(&ici, 0, sizeof(ici));
    ici.type = XR_TYPE_INSTANCE_CREATE_INFO;
    strncpy(ici.applicationInfo.applicationName, "SpatialLauncherVR",
            XR_MAX_APPLICATION_NAME_SIZE - 1);
    ici.applicationInfo.apiVersion = XR_API_VERSION_1_0;
    ici.enabledExtensionCount = next;
    ici.enabledExtensionNames = exts;
    if (xrCreateInstance(&ici, &app->instance) != XR_SUCCESS) {
        LOGE("xrCreateInstance failed");
        return 0;
    }

    if (wantPassthrough) {
        LOAD_XR_FN(app->instance, "xrCreatePassthroughFB", pfnCreatePassthrough);
        LOAD_XR_FN(app->instance, "xrDestroyPassthroughFB", pfnDestroyPassthrough);
        LOAD_XR_FN(app->instance, "xrPassthroughStartFB", pfnPassthroughStart);
        LOAD_XR_FN(app->instance, "xrCreatePassthroughLayerFB", pfnCreatePassthroughLayer);
        LOAD_XR_FN(app->instance, "xrDestroyPassthroughLayerFB", pfnDestroyPassthroughLayer);
        if (pfnCreatePassthrough == NULL || pfnDestroyPassthrough == NULL
                || pfnPassthroughStart == NULL || pfnCreatePassthroughLayer == NULL
                || pfnDestroyPassthroughLayer == NULL) {
            LOGW("passthrough entry points incomplete; room will be black");
            wantPassthrough = 0;
        }
    }
    app->hasPassthrough = wantPassthrough;

    XrSystemGetInfo sgi;
    memset(&sgi, 0, sizeof(sgi));
    sgi.type = XR_TYPE_SYSTEM_GET_INFO;
    sgi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    if (xrGetSystem(app->instance, &sgi, &app->systemId) != XR_SUCCESS) {
        LOGE("xrGetSystem failed");
        return 0;
    }

    if (!initEgl(app)) {
        return 0;
    }
    XrGraphicsBindingOpenGLESAndroidKHR binding;
    memset(&binding, 0, sizeof(binding));
    binding.type = XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR;
    binding.display = app->eglDisplay;
    binding.config = app->eglConfig;
    binding.context = app->eglContext;

    XrSessionCreateInfo sci;
    memset(&sci, 0, sizeof(sci));
    sci.type = XR_TYPE_SESSION_CREATE_INFO;
    sci.next = &binding;
    sci.systemId = app->systemId;
    // Runtimes may refuse session creation until the app has queried the
    // GLES requirements (hello_xr does this unconditionally).
    {
        PFN_xrGetOpenGLESGraphicsRequirementsKHR pfnReq = NULL;
        XrResult gr = xrGetInstanceProcAddr(app->instance,
                "xrGetOpenGLESGraphicsRequirementsKHR",
                (PFN_xrVoidFunction*)&pfnReq);
        LOGI("graphicsRequirements fn: lr=%d fn=%p", (int)gr, (void*)pfnReq);
        if (gr == XR_SUCCESS && pfnReq != NULL) {
            XrGraphicsRequirementsOpenGLESKHR req;
            memset(&req, 0, sizeof(req));
            req.type = XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR;
            XrResult rr = pfnReq(app->instance, app->systemId, &req);
            LOGI("graphicsRequirements: lr=%d min=%08x max=%08x",
                    (int)rr, (unsigned)req.minApiVersionSupported,
                    (unsigned)req.maxApiVersionSupported);
        }
    }
    XrResult scr = xrCreateSession(app->instance, &sci, &app->session);
    if (scr != XR_SUCCESS) {
        LOGE("xrCreateSession failed: %d", (int)scr);
        return 0;
    }

    if (app->hasPassthrough) {
        XrPassthroughCreateInfoFB pci;
        memset(&pci, 0, sizeof(pci));
        pci.type = XR_TYPE_PASSTHROUGH_CREATE_INFO_FB;
        if (pfnCreatePassthrough(app->session, &pci, &app->passthrough) != XR_SUCCESS) {
            LOGW("xrCreatePassthroughFB failed; room will be black");
            app->hasPassthrough = 0;
        } else {
            XrPassthroughLayerCreateInfoFB pli;
            memset(&pli, 0, sizeof(pli));
            pli.type = XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB;
            pli.passthrough = app->passthrough;
            pli.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
            if (pfnCreatePassthroughLayer(app->session, &pli,
                        &app->passthroughLayer) != XR_SUCCESS) {
                LOGW("xrCreatePassthroughLayerFB failed; room will be black");
                pfnDestroyPassthrough(app->passthrough);
                app->passthrough = XR_NULL_HANDLE;
                app->hasPassthrough = 0;
            } else if (pfnPassthroughStart(app->passthrough) != XR_SUCCESS) {
                LOGW("xrPassthroughStartFB failed");
                app->hasPassthrough = 0;
            }
        }
    }

    // Blend mode: games composite scene OVER passthrough with ALPHA_BLEND.
    // Prefer it; fall back to whatever the runtime offers first.
    {
        uint32_t count = 0;
        xrEnumerateEnvironmentBlendModes(app->instance, app->systemId,
                XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &count, NULL);
        XrEnvironmentBlendMode* modes = NULL;
        if (count > 0) {
            modes = (XrEnvironmentBlendMode*)calloc(count, sizeof(XrEnvironmentBlendMode));
            if (modes != NULL
                    && xrEnumerateEnvironmentBlendModes(app->instance, app->systemId,
                        XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, &count, modes)
                        == XR_SUCCESS) {
                app->blendMode = modes[0];
                for (uint32_t i = 0; i < count; i++) {
                    if (modes[i] == XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND) {
                        app->blendMode = modes[i];
                        break;
                    }
                }
            }
            free(modes);
        } else {
            app->blendMode = XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND;
        }
        LOGI("blendMode=%d", (int)app->blendMode);
    }

    LOGI("XR ready (passthrough=%d)", app->hasPassthrough);
    return 1;
}

/* ---------------- Theater screen (shared panel frames) ---------------- */

JNIEXPORT void JNICALL
Java_com_spatiallauncher_vr_VrBridge_pushFrame(JNIEnv* env, jclass clazz,
        jobject buf, jint w, jint h, jboolean sbs) {
    (void)clazz;
    if (env == NULL || buf == NULL || w <= 0 || h <= 0 || w > 2048 || h > 2048) {
        return;
    }
    void* src = (*env)->GetDirectBufferAddress(env, buf);
    jlong cap = (*env)->GetDirectBufferCapacity(env, buf);
    long need = (long)w * h * 4;
    if (src == NULL || cap < need) {
        return;
    }
    pthread_mutex_lock(&g_frameLock);
    if ((size_t)need > g_stageCap) {
        free(g_stageBuf);
        g_stageBuf = NULL;
        g_stageCap = 0;
    }
    if (g_stageBuf == NULL) {
        g_stageBuf = (unsigned char*)malloc(need > 0 ? (size_t)need : 1);
        if (g_stageBuf != NULL) {
            g_stageCap = (size_t)need;
        }
    }
    if (g_stageBuf != NULL) {
        memcpy(g_stageBuf, src, (size_t)need);
        g_stageW = w;
        g_stageH = h;
        g_stageSbs = sbs ? 1 : 0;
        g_stageAspect = (float)w / (float)h;
        g_stageFresh = 1;
    }
    pthread_mutex_unlock(&g_frameLock);
}

// Column-major 4x4 helpers.
static void matMul44(float out[16], const float a[16], const float b[16]) {
    float t[16];
    for (int c = 0; c < 4; c++) {
        for (int r = 0; r < 4; r++) {
            t[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1]
                + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
        }
    }
    memcpy(out, t, sizeof(t));
}

static void viewMatrixFromPose(float out[16], const XrPosef* pose) {
    float x = pose->orientation.x, y = pose->orientation.y;
    float z = pose->orientation.z, w = pose->orientation.w;
    float xx = x * x, yy = y * y, zz = z * z;
    float xy = x * y, xz = x * z, yz = y * z;
    float wx = w * x, wy = w * y, wz = w * z;
    // Rotation rows (world-from-view), then transpose for the view matrix.
    float r00 = 1.0f - 2.0f * (yy + zz), r01 = 2.0f * (xy - wz), r02 = 2.0f * (xz + wy);
    float r10 = 2.0f * (xy + wz), r11 = 1.0f - 2.0f * (xx + zz), r12 = 2.0f * (yz - wx);
    float r20 = 2.0f * (xz - wy), r21 = 2.0f * (yz + wx), r22 = 1.0f - 2.0f * (xx + yy);
    // View = inverse of the camera world transform. Rotation is transposed
    // (raw values mirror head tracking: the room swims opposite the look).
    // Translation stays dotted with the matrix COLUMNS (that part was already
    // the correct -R^T*t; transposing it too makes head moves zoom/dip).
    float tx = pose->position.x, ty = pose->position.y, tz = pose->position.z;
    out[0] = r00; out[1] = r01; out[2] = r02; out[3] = 0.0f;
    out[4] = r10; out[5] = r11; out[6] = r12; out[7] = 0.0f;
    out[8] = r20; out[9] = r21; out[10] = r22; out[11] = 0.0f;
    out[12] = -(r00 * tx + r10 * ty + r20 * tz);
    out[13] = -(r01 * tx + r11 * ty + r21 * tz);
    out[14] = -(r02 * tx + r12 * ty + r22 * tz);
    out[15] = 1.0f;
}

static void perspectiveFromFov(float out[16], const XrFovf* fov,
        float nearZ, float farZ) {
    float l = tanf(fov->angleLeft) * nearZ;
    float r = tanf(fov->angleRight) * nearZ;
    float d = tanf(fov->angleDown) * nearZ;
    float u = tanf(fov->angleUp) * nearZ;
    float w = r - l, h = u - d, fd = farZ - nearZ;
    memset(out, 0, sizeof(float) * 16);
    out[0] = 2.0f * nearZ / w;
    out[5] = 2.0f * nearZ / h;
    out[8] = (r + l) / w;
    out[9] = (u + d) / h;
    out[10] = -(farZ + nearZ) / fd;
    out[11] = -1.0f;
    out[14] = -2.0f * farZ * nearZ / fd;
}

static GLuint theaterCompileShader(GLenum type, const char* src) {
    GLuint sh = glCreateShader(type);
    glShaderSource(sh, 1, &src, NULL);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(sh, sizeof(log), NULL, log);
        LOGE("shader compile failed: %s", log);
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

static const char* THEATER_VS =
    "attribute vec3 aPos;\n"
    "attribute vec2 aUv;\n"
    "uniform mat4 uMvp;\n"
    "varying vec2 vUv;\n"
    "void main() { vUv = aUv; gl_Position = uMvp * vec4(aPos, 1.0); }\n";
static const char* THEATER_FS =
    "precision mediump float;\n"
    "varying vec2 vUv;\n"
    "uniform sampler2D uTex;\n"
    "uniform float uEye;\n"
    "uniform float uSbs;\n"
    "void main() {\n"
    "  vec2 uv = vUv;\n"
    "  uv.x = mix(uv.x, uv.x * 0.5 + uEye * 0.5, uSbs);\n"
    "  gl_FragColor = texture2D(uTex, uv);\n"
    "}\n";

static void theaterGlInit(VrApp* app) {
    if (app->screenProg != 0) {
        return;
    }
    GLuint vs = theaterCompileShader(GL_VERTEX_SHADER, THEATER_VS);
    GLuint fs = theaterCompileShader(GL_FRAGMENT_SHADER, THEATER_FS);
    if (vs == 0 || fs == 0) {
        LOGE("theater shaders failed");
        return;
    }
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    GLint ok = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (!ok) {
        LOGE("theater link failed");
        glDeleteProgram(prog);
        return;
    }
    app->screenProg = prog;
    app->attrPos = glGetAttribLocation(prog, "aPos");
    app->attrUv = glGetAttribLocation(prog, "aUv");
    app->uniMvp = glGetUniformLocation(prog, "uMvp");
    app->uniTex = glGetUniformLocation(prog, "uTex");
    app->uniEye = glGetUniformLocation(prog, "uEye");
    app->uniSbs = glGetUniformLocation(prog, "uSbs");

    glGenTextures(1, &app->screenTex);
    glBindTexture(GL_TEXTURE_2D, app->screenTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    app->texW = 0;
    app->texH = 0;

    const int SU = THEATER_SEG_U, SV = THEATER_SEG_V;
    const int vcount = (SU + 1) * (SV + 1);
    float* pos = (float*)malloc(sizeof(float) * vcount * 3);
    float* uv = (float*)malloc(sizeof(float) * vcount * 2);
    if (pos != NULL && uv != NULL) {
        int k = 0, t = 0;
        for (int r = 0; r <= SV; r++) {
            float v = r / (float)SV;
            float y = THEATER_Y + (0.5f - v) * THEATER_H;
            for (int c = 0; c <= SU; c++) {
                float u = c / (float)SU;
                float theta = 3.14159265f - THEATER_ARC / 2.0f + u * THEATER_ARC;
                pos[k++] = THEATER_R * sinf(theta);
                pos[k++] = y;
                pos[k++] = THEATER_R * cosf(theta);
                uv[t++] = 1.0f - u;
                uv[t++] = v;
            }
        }
        glGenBuffers(1, &app->meshVbo);
        glBindBuffer(GL_ARRAY_BUFFER, app->meshVbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(float) * vcount * 3, pos, GL_STATIC_DRAW);
        glGenBuffers(1, &app->meshUvbo);
        glBindBuffer(GL_ARRAY_BUFFER, app->meshUvbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(float) * vcount * 2, uv, GL_STATIC_DRAW);
        int quads = SU * SV;
        app->meshIndexCount = quads * 6;
        unsigned short* idx = (unsigned short*)malloc(
            sizeof(unsigned short) * app->meshIndexCount);
        if (idx != NULL) {
            int n = 0;
            for (int r = 0; r < SV; r++) {
                for (int c = 0; c < SU; c++) {
                    unsigned short a = (unsigned short)(r * (SU + 1) + c);
                    unsigned short b = (unsigned short)(a + 1);
                    unsigned short d = (unsigned short)(a + SU + 1);
                    unsigned short e = (unsigned short)(d + 1);
                    idx[n++] = a; idx[n++] = d; idx[n++] = b;
                    idx[n++] = b; idx[n++] = d; idx[n++] = e;
                }
            }
            glGenBuffers(1, &app->meshIbo);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, app->meshIbo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER,
                sizeof(unsigned short) * app->meshIndexCount, idx, GL_STATIC_DRAW);
            free(idx);
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    }
    free(pos);
    free(uv);
    glGenFramebuffers(2, app->screenFbo);
    LOGI("theater GL ready");
}

/* ---------------- Blender room (room/theater.glb, one prop per primitive) ----
 * Minimal flat-color loader: positions + normals baked to XR world space at
 * load (model matrix stays identity), tint from baseColorFactor, emissive
 * from emissive_factor luminance. No textures, no indices (DrawArrays only).
 * Every skipped primitive logs its reason.
 * A Y-180 spin maps the authored facing (+Z) onto OpenXR forward (-Z).
 */
typedef struct {
    GLuint vbo;
    int vertCount;
    float tint[3];
    float emissive;
} RoomProp;

// Room lighting mood, written from Java (VrBridge.setEnvironment).
static volatile int g_env = 0;

static GLuint roomProg = 0;
static int roomAttrPos, roomAttrNorm;
static int roomUniMvp, roomUniTint;
static int roomUniEmissive, roomUniAmbient, roomUniFogColor, roomUniCamPos;
static RoomProp* roomProps = NULL;
static int roomPropCount = 0;
static GLuint roomDepth[2] = {0, 0};
static uint32_t roomDepthW = 0, roomDepthH = 0;
static EGLContext roomCtx = EGL_NO_CONTEXT;
static GLuint testVbo = 0; // Procedural fallback room (floor + 3 walls).

static const char* ROOM_VS =
    "attribute vec3 aPos;\n"
    "attribute vec3 aNorm;\n"
    "uniform mat4 uMvp;\n"
    "uniform vec3 uCamPos;\n"
    "varying vec3 vN;\n"
    "varying float vDist;\n"
    "void main() {\n"
    "  vN = aNorm;\n"
    "  vDist = distance(aPos, uCamPos);\n"
    "  gl_Position = uMvp * vec4(aPos, 1.0);\n"
    "}\n";
static const char* ROOM_FS =
    "precision mediump float;\n"
    "varying vec3 vN;\n"
    "varying float vDist;\n"
    "uniform vec3 uTint;\n"
    "uniform float uEmissive;\n"
    "uniform float uAmbient;\n"
    "uniform vec3 uFogColor;\n"
    "void main() {\n"
    "  vec3 N = normalize(vN);\n"
    "  vec3 L = normalize(vec3(0.35, 0.8, 0.25));\n"
    "  float diff = max(dot(N, L), 0.0);\n"
    "  vec3 col = uTint * (uAmbient + 0.85 * diff) + uTint * uEmissive;\n"
    "  float fog = smoothstep(9.0, 26.0, vDist);\n"
    "  col = mix(col, uFogColor, fog);\n"
    "  gl_FragColor = vec4(col, 1.0);\n"
    "}\n";

static void roomNodeWorld(const cgltf_node* node, float out[16]) {
    // Our export is a flat scene (no parents), so local == world.
    cgltf_node_transform_local(node, out);
    static const float ry[16] =
        {-1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1};
    float tmp[16];
    matMul44(tmp, ry, out);
    memcpy(out, tmp, sizeof(tmp));
}

static int roomLoadTheater(VrApp* app) {
    AAssetManager* mgr = app->activity ? app->activity->assetManager : NULL;
    if (mgr == NULL) {
        LOGW("room: no asset manager");
        return 0;
    }
    AAsset* a = AAssetManager_open(mgr, "room/theater.glb", AASSET_MODE_BUFFER);
    if (a == NULL) {
        LOGW("room: missing room/theater.glb");
        return 0;
    }
    size_t len = (size_t)AAsset_getLength(a);
    const void* buf = AAsset_getBuffer(a);
    if (buf == NULL || len < 20) {
        AAsset_close(a);
        return 0;
    }
    cgltf_options opt;
    memset(&opt, 0, sizeof(opt));
    cgltf_data* data = NULL;
    if (cgltf_parse(&opt, buf, len, &data) != cgltf_result_success) {
        LOGW("room: parse failed");
        AAsset_close(a);
        return 0;
    }
    // GLB bin chunk lives inside buf: keep the asset open until cgltf_free.
    int cap = 64, n = 0;
    int skipNoTri = 0, skipNoPos = 0, skipNoMem = 0;
    float bmn[3] = {1e30f, 1e30f, 1e30f}, bmx[3] = {-1e30f, -1e30f, -1e30f};
    RoomProp* props = (RoomProp*)calloc((size_t)cap, sizeof(RoomProp));
    if (props == NULL) {
        cgltf_free(data);
        AAsset_close(a);
        return 0;
    }
    for (size_t s = 0; s < data->scenes_count; s++) {
        cgltf_scene* scene = &data->scenes[s];
        for (size_t ni = 0; ni < scene->nodes_count; ni++) {
            cgltf_node* node = scene->nodes[ni];
            if (node->mesh == NULL) {
                continue;
            }
            float M[16];
            roomNodeWorld(node, M);
            cgltf_mesh* mesh = node->mesh;
            for (size_t pi = 0; pi < mesh->primitives_count; pi++) {
                cgltf_primitive* prim = &mesh->primitives[pi];
                if (prim->type != cgltf_primitive_type_triangles) {
                    skipNoTri++;
                    continue;
                }
                cgltf_accessor* pos = NULL, *nrm = NULL;
                for (size_t k = 0; k < prim->attributes_count; k++) {
                    const char* an = prim->attributes[k].name;
                    if (strcmp(an, "POSITION") == 0) {
                        pos = prim->attributes[k].data;
                    } else if (strcmp(an, "NORMAL") == 0) {
                        nrm = prim->attributes[k].data;
                    }
                }
                if (pos == NULL || pos->type != cgltf_type_vec3
                        || pos->component_type != cgltf_component_type_r_32f
                        || pos->count == 0 || pos->count > 300000) {
                    skipNoPos++;
                    continue;
                }
                size_t vc = pos->count;
                float* verts = (float*)malloc(sizeof(float) * vc * 6);
                if (verts == NULL) {
                    skipNoMem++;
                    continue;
                }
                for (size_t v = 0; v < vc; v++) {
                    float p[3], nr[3] = {0.0f, 1.0f, 0.0f};
                    cgltf_accessor_read_float(pos, v, p, 3);
                    if (nrm != NULL) {
                        cgltf_accessor_read_float(nrm, v, nr, 3);
                    }
                    // Bake node transform; rotation-only normals (uniform
                    // scales in our export) + normalize for safety.
                    float wp[3] = {
                        M[0] * p[0] + M[4] * p[1] + M[8] * p[2] + M[12],
                        M[1] * p[0] + M[5] * p[1] + M[9] * p[2] + M[13],
                        M[2] * p[0] + M[6] * p[1] + M[10] * p[2] + M[14],
                    };
                    float wn[3] = {
                        M[0] * nr[0] + M[4] * nr[1] + M[8] * nr[2],
                        M[1] * nr[0] + M[5] * nr[1] + M[9] * nr[2],
                        M[2] * nr[0] + M[6] * nr[1] + M[10] * nr[2],
                    };
                    float il = 1.0f / (sqrtf(wn[0] * wn[0] + wn[1] * wn[1] + wn[2] * wn[2]) + 1e-9f);
                    verts[v * 6 + 0] = wp[0];
                    verts[v * 6 + 1] = wp[1];
                    verts[v * 6 + 2] = wp[2];
                    verts[v * 6 + 3] = wn[0] * il;
                    verts[v * 6 + 4] = wn[1] * il;
                    verts[v * 6 + 5] = wn[2] * il;
                    for (int q = 0; q < 3; q++) {
                        if (wp[q] < bmn[q]) {
                            bmn[q] = wp[q];
                        }
                        if (wp[q] > bmx[q]) {
                            bmx[q] = wp[q];
                        }
                    }
                }
                float tint[3] = {1.0f, 1.0f, 1.0f};
                float emissive = 0.0f;
                if (prim->material != NULL) {
                    float* bf = prim->material->pbr_metallic_roughness.base_color_factor;
                    tint[0] = bf[0];
                    tint[1] = bf[1];
                    tint[2] = bf[2];
                    float* ef = prim->material->emissive_factor;
                    emissive = 0.299f * ef[0] + 0.587f * ef[1] + 0.114f * ef[2];
                }
                if (n >= cap) {
                    int ncap = cap * 2;
                    RoomProp* np = (RoomProp*)realloc(props, sizeof(RoomProp) * (size_t)ncap);
                    if (np == NULL) {
                        free(verts);
                        skipNoMem++;
                        continue;
                    }
                    props = np;
                    cap = ncap;
                }
                RoomProp* out = &props[n];
                memset(out, 0, sizeof(*out));
                glGenBuffers(1, &out->vbo);
                glBindBuffer(GL_ARRAY_BUFFER, out->vbo);
                glBufferData(GL_ARRAY_BUFFER, sizeof(float) * vc * 6, verts, GL_STATIC_DRAW);
                glBindBuffer(GL_ARRAY_BUFFER, 0);
                out->vertCount = (int)vc;
                out->tint[0] = tint[0];
                out->tint[1] = tint[1];
                out->tint[2] = tint[2];
                out->emissive = emissive;
                n++;
                free(verts);
            }
        }
    }
    cgltf_free(data);
    AAsset_close(a);
    roomProps = props;
    roomPropCount = n;
    LOGI("room: theater.glb props=%d skip(tri=%d,pos=%d,mem=%d) baked x=[%.2f,%.2f] y=[%.2f,%.2f] z=[%.2f,%.2f]",
        n, skipNoTri, skipNoPos, skipNoMem,
        bmn[0], bmx[0], bmn[1], bmx[1], bmn[2], bmx[2]);
    return n > 0;
}

static void roomGlInit(VrApp* app) {
    // EGL contexts die with the activity while statics survive in-process.
    EGLContext cur = eglGetCurrentContext();
    if (cur != roomCtx) {
        if (roomProps != NULL) {
            free(roomProps);
            roomProps = NULL;
        }
        roomProg = 0;
        roomPropCount = 0;
        testVbo = 0;
        roomDepth[0] = roomDepth[1] = 0;
        roomDepthW = roomDepthH = 0;
        roomCtx = cur;
        LOGI("room: GL context changed; state reset");
    }
    if (roomProg != 0) {
        return;
    }
    GLuint vs = theaterCompileShader(GL_VERTEX_SHADER, ROOM_VS);
    GLuint fs = theaterCompileShader(GL_FRAGMENT_SHADER, ROOM_FS);
    if (vs == 0 || fs == 0) {
        LOGE("room shaders failed");
        return;
    }
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    GLint ok = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (!ok) {
        LOGE("room link failed");
        glDeleteProgram(prog);
        return;
    }
    roomProg = prog;
    // Procedural fallback room: floor + 3 walls, same interleaved layout
    // and draw path as GLB props (pos+norm stride 24, DrawArrays).
    {
        static const float q[] = {
            // floor y=0, x/z +/-5, normal +Y
            -5,0,-5, 0,1,0,  5,0,-5, 0,1,0,  5,0,5, 0,1,0,
            -5,0,-5, 0,1,0,  5,0,5, 0,1,0,  -5,0,5, 0,1,0,
            // back wall z=+5 facing -Z, x +/-5, y 0..2.5
            -5,0,5, 0,0,-1,  5,0,5, 0,0,-1,  5,2.5f,5, 0,0,-1,
            -5,0,5, 0,0,-1,  5,2.5f,5, 0,0,-1,  -5,2.5f,5, 0,0,-1,
            // left wall x=-5 facing +X
            -5,0,-5, 1,0,0,  -5,0,5, 1,0,0,  -5,2.5f,5, 1,0,0,
            -5,0,-5, 1,0,0,  -5,2.5f,5, 1,0,0,  -5,2.5f,-5, 1,0,0,
            // right wall x=+5 facing -X
            5,0,-5, -1,0,0,  5,0,5, -1,0,0,  5,2.5f,5, -1,0,0,
            5,0,-5, -1,0,0,  5,2.5f,5, -1,0,0,  5,2.5f,-5, -1,0,0,
        };
        glGenBuffers(1, &testVbo);
        glBindBuffer(GL_ARRAY_BUFFER, testVbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(q), q, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }
    roomAttrPos = glGetAttribLocation(prog, "aPos");
    roomAttrNorm = glGetAttribLocation(prog, "aNorm");
    roomUniMvp = glGetUniformLocation(prog, "uMvp");
    roomUniTint = glGetUniformLocation(prog, "uTint");
    roomUniEmissive = glGetUniformLocation(prog, "uEmissive");
    roomUniAmbient = glGetUniformLocation(prog, "uAmbient");
    roomUniFogColor = glGetUniformLocation(prog, "uFogColor");
    roomUniCamPos = glGetUniformLocation(prog, "uCamPos");
    LOGI("room: loc pos=%d norm=%d mvp=%d tint=%d emis=%d amb=%d fog=%d cam=%d",
        roomAttrPos, roomAttrNorm, roomUniMvp, roomUniTint,
        roomUniEmissive, roomUniAmbient, roomUniFogColor, roomUniCamPos);
    roomLoadTheater(app);
    LOGI("room GL ready props=%d", roomPropCount);
}

static void roomDrawProp(const RoomProp* p, const float pm[16],
        const float vm[16], const float eye[3]) {
    float mvp[16];
    matMul44(mvp, pm, vm); // model = identity (baked at load)
    glUniformMatrix4fv(roomUniMvp, 1, GL_FALSE, mvp);
    glUniform3f(roomUniCamPos, eye[0], eye[1], eye[2]);
    glUniform3f(roomUniTint, p->tint[0], p->tint[1], p->tint[2]);
    glUniform1f(roomUniEmissive, p->emissive);
    glBindBuffer(GL_ARRAY_BUFFER, p->vbo);
    glVertexAttribPointer(roomAttrPos, 3, GL_FLOAT, GL_FALSE, 6 * sizeof(float),
        (const void*)0);
    glEnableVertexAttribArray(roomAttrPos);
    glVertexAttribPointer(roomAttrNorm, 3, GL_FLOAT, GL_FALSE, 6 * sizeof(float),
        (const void*)(3 * sizeof(float)));
    glEnableVertexAttribArray(roomAttrNorm);
    glDrawArrays(GL_TRIANGLES, 0, p->vertCount);
    glDisableVertexAttribArray(roomAttrPos);
    glDisableVertexAttribArray(roomAttrNorm);
}

JNIEXPORT void JNICALL
Java_com_spatiallauncher_vr_VrBridge_nativeSetEnvironment(JNIEnv* env,
        jclass clazz, jint index) {
    (void)env;
    (void)clazz;
    int clamped = index < 0 ? 0 : (index > 3 ? 3 : index);
    if (clamped != g_env) {
        g_env = clamped;
        LOGI("room env=%d", clamped);
    }
}

/** Creates the LOCAL_FLOOR space + one swapchain per view. Needs session. */
static void theaterEnsureSession(VrApp* app) {
    if (app->localSpace == XR_NULL_HANDLE && app->session != XR_NULL_HANDLE) {
        XrReferenceSpaceCreateInfo spi;
        memset(&spi, 0, sizeof(spi));
        spi.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
        spi.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR;
        spi.poseInReferenceSpace.orientation.w = 1.0f;
        if (xrCreateReferenceSpace(app->session, &spi, &app->localSpace) != XR_SUCCESS) {
            LOGW("LOCAL_FLOOR unavailable; theater screen disabled");
            app->localSpace = XR_NULL_HANDLE;
        }
    }
    if (!app->swapReady && app->session != XR_NULL_HANDLE) {
        XrViewConfigurationView views[4];
        for (int i = 0; i < 4; i++) {
            views[i].type = XR_TYPE_VIEW_CONFIGURATION_VIEW;
        }
        uint32_t count = 0;
        if (xrEnumerateViewConfigurationViews(app->instance, app->systemId,
                    XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &count, NULL)
                != XR_SUCCESS
                || count < 2 || count > 4) {
            LOGW("view config query failed");
            return;
        }
        if (xrEnumerateViewConfigurationViews(app->instance, app->systemId,
                    XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, &count, views)
                != XR_SUCCESS) {
            LOGW("view config list failed");
            return;
        }
        uint32_t fmtCount = 0;
        xrEnumerateSwapchainFormats(app->session, 0, &fmtCount, NULL);
        int64_t chosen = 0x8058; // GL_RGBA8 fallback
        if (fmtCount > 0) {
            int64_t* fmts = (int64_t*)malloc(sizeof(int64_t) * fmtCount);
            if (fmts != NULL
                    && xrEnumerateSwapchainFormats(app->session, fmtCount, &fmtCount, fmts)
                        == XR_SUCCESS) {
                chosen = fmts[0];
                for (uint32_t i = 0; i < fmtCount; i++) {
                    if (fmts[i] == (int64_t)0x8C43) { // GL_SRGB8_ALPHA8
                        chosen = fmts[i];
                        break;
                    }
                }
            }
            free(fmts);
        }
        app->swapW = views[0].recommendedImageRectWidth;
        app->swapH = views[0].recommendedImageRectHeight;
        int ok = 1;
        for (int i = 0; i < 2; i++) {
            XrSwapchainCreateInfo sci;
            memset(&sci, 0, sizeof(sci));
            sci.type = XR_TYPE_SWAPCHAIN_CREATE_INFO;
            sci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
            sci.format = chosen;
            sci.sampleCount = 1;
            sci.width = app->swapW;
            sci.height = app->swapH;
            sci.faceCount = 1;
            sci.arraySize = 1;
            sci.mipCount = 1;
            if (xrCreateSwapchain(app->session, &sci, &app->viewSwap[i]) != XR_SUCCESS) {
                LOGW("swapchain %d failed", i);
                ok = 0;
                break;
            }
        }
        if (ok) {
            app->swapReady = 1;
            LOGI("theater swapchains %ux%u", (unsigned)app->swapW, (unsigned)app->swapH);
        }
    }
}

/** Uploads a fresh staged frame. Returns 1 when a texture is displayable. */
static int theaterPushTexture(VrApp* app) {
    int uploaded = (app->texW > 0);
    pthread_mutex_lock(&g_frameLock);
    if (g_stageFresh && g_stageBuf != NULL && g_stageW > 0 && g_stageH > 0) {
        glBindTexture(GL_TEXTURE_2D, app->screenTex);
        if (g_stageW != app->texW || g_stageH != app->texH) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA,
                g_stageW, g_stageH, 0, GL_RGBA, GL_UNSIGNED_BYTE, g_stageBuf);
            app->texW = g_stageW;
            app->texH = g_stageH;
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                g_stageW, g_stageH, GL_RGBA, GL_UNSIGNED_BYTE, g_stageBuf);
        }
        g_stageFresh = 0;
        uploaded = 1;
    }
    pthread_mutex_unlock(&g_frameLock);
    return uploaded;
}

/**
 * Locates both views, renders the screen into each swapchain, and fills the
 * projection layer. Returns 1 when the layer is submittable.
 */
static int theaterRenderViews(VrApp* app,
        XrCompositionLayerProjection* projLayer,
        XrCompositionLayerProjectionView* projViews) {
    // The room submits on its own; the video quad still waits for a frame.
    if (!app->swapReady) {
        return 0;
    }
    if (roomProg == 0 && (app->screenProg == 0 || app->texW <= 0)) {
        return 0;
    }
    XrViewLocateInfo locate;
    memset(&locate, 0, sizeof(locate));
    locate.type = XR_TYPE_VIEW_LOCATE_INFO;
    locate.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    locate.displayTime = app->lastFrameTime;
    locate.space = app->localSpace;
    XrView views[2];
    for (int i = 0; i < 2; i++) {
        views[i].type = XR_TYPE_VIEW;
    }
    XrViewState viewState;
    memset(&viewState, 0, sizeof(viewState));
    viewState.type = XR_TYPE_VIEW_STATE;
    uint32_t viewCount = 0;
    if (xrLocateViews(app->session, &locate, &viewState, 2, &viewCount, views)
            != XR_SUCCESS
            || viewCount != 2) {
        return 0;
    }
    int showScreen = (app->screenProg != 0) && theaterPushTexture(app);
    // Pass (env 0) is passthrough only: no shell drawn.
    int showRoom = (roomProg != 0) && (roomPropCount > 0) && (g_env > 0);
    float aspect = g_stageAspect > 0.01f ? g_stageAspect : 1.7778f;
    float scaleX = (THEATER_H * aspect) / (THEATER_R * THEATER_ARC);
    for (uint32_t i = 0; i < 2; i++) {
        XrSwapchainImageAcquireInfo acquire;
        memset(&acquire, 0, sizeof(acquire));
        acquire.type = XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO;
        uint32_t imgIndex = 0;
        if (xrAcquireSwapchainImage(app->viewSwap[i], &acquire, &imgIndex) != XR_SUCCESS) {
            return 0;
        }
        XrSwapchainImageWaitInfo wait;
        memset(&wait, 0, sizeof(wait));
        wait.type = XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO;
        wait.timeout = 100 * 1000 * 1000LL;
        xrWaitSwapchainImage(app->viewSwap[i], &wait);

        uint32_t imgCount = 0;
        xrEnumerateSwapchainImages(app->viewSwap[i], 0, &imgCount, NULL);
        int rendered = 0;
        if (imgCount > 0 && imgIndex < imgCount) {
            XrSwapchainImageOpenGLESKHR* imgs =
                (XrSwapchainImageOpenGLESKHR*)malloc(
                    sizeof(XrSwapchainImageOpenGLESKHR) * imgCount);
            if (imgs != NULL) {
                for (uint32_t k = 0; k < imgCount; k++) {
                    imgs[k].type = XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR;
                }
                if (xrEnumerateSwapchainImages(app->viewSwap[i], imgCount, &imgCount,
                            (XrSwapchainImageBaseHeader*)imgs) == XR_SUCCESS) {
                    glBindFramebuffer(GL_FRAMEBUFFER, app->screenFbo[i]);
                    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                        GL_TEXTURE_2D, imgs[imgIndex].image, 0);
                    if (showRoom
                            && (roomDepthW != app->swapW || roomDepthH != app->swapH)) {
                        for (int d = 0; d < 2; d++) {
                            if (roomDepth[d] == 0) {
                                glGenRenderbuffers(1, &roomDepth[d]);
                            }
                            glBindRenderbuffer(GL_RENDERBUFFER, roomDepth[d]);
                            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16,
                                app->swapW, app->swapH);
                        }
                        glBindRenderbuffer(GL_RENDERBUFFER, 0);
                        roomDepthW = app->swapW;
                        roomDepthH = app->swapH;
                    }
                    if (showRoom) {
                        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                            GL_RENDERBUFFER, roomDepth[i]);
                        // Framebuffer completeness, logged once per process.
                        static int fboLogged = 0;
                        if (!fboLogged) {
                            fboLogged = 1;
                            GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
                            LOGW("room: fbo status 0x%x eye %u", st, i);
                        }
                    }
                    glViewport(0, 0, app->swapW, app->swapH);
                    glClearColor(0.02f, 0.02f, 0.03f, 1.0f);
                    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    float vm[16], pm[16], tv[16], mvp[16], model[16];
                    viewMatrixFromPose(vm, &views[i].pose);
                    perspectiveFromFov(pm, &views[i].fov, 0.1f, 60.0f);
                    // Raw tracked poses are correct here; a transposed view
                    // puts the whole room behind the camera (seen in testing).
                    memcpy(tv, vm, sizeof(tv));
                    if (showRoom) {
                        glEnable(GL_DEPTH_TEST);
                        glDepthMask(GL_TRUE);
                        glUseProgram(roomProg);
                        // Room lighting moods follow the panel Room picker
                        // (0 Pass neutral, 1 Dusk, 2 Night, 3 Day).
                        {
                            float ambient = 0.45f;
                            float fogc[3] = {0.015f, 0.015f, 0.025f};
                            if (g_env == 1) {
                                ambient = 0.35f;
                                fogc[0] = 0.05f; fogc[1] = 0.02f; fogc[2] = 0.03f;
                            } else if (g_env == 2) {
                                ambient = 0.20f;
                                fogc[0] = 0.005f; fogc[1] = 0.005f; fogc[2] = 0.01f;
                            } else if (g_env >= 3) {
                                ambient = 0.65f;
                                fogc[0] = 0.10f; fogc[1] = 0.12f; fogc[2] = 0.15f;
                            }
                            glUniform1f(roomUniAmbient, ambient);
                            glUniform3f(roomUniFogColor, fogc[0], fogc[1], fogc[2]);
                        }
                        matMul44(mvp, pm, tv);
                        glUniformMatrix4fv(roomUniMvp, 1, GL_FALSE, mvp);
                        if (testVbo != 0) {
                            glBindBuffer(GL_ARRAY_BUFFER, testVbo);
                            glVertexAttribPointer(roomAttrPos, 3, GL_FLOAT,
                                GL_FALSE, 6 * sizeof(float), (const void*)0);
                            glEnableVertexAttribArray(roomAttrPos);
                            glVertexAttribPointer(roomAttrNorm, 3, GL_FLOAT,
                                GL_FALSE, 6 * sizeof(float),
                                (const void*)(3 * sizeof(float)));
                            glEnableVertexAttribArray(roomAttrNorm);
                            glUniform3f(roomUniCamPos,
                                views[i].pose.position.x,
                                views[i].pose.position.y,
                                views[i].pose.position.z);
                            glUniform1f(roomUniEmissive, 0.0f);
                            glUniform3f(roomUniTint, 0.32f, 0.27f, 0.23f);
                            glDrawArrays(GL_TRIANGLES, 0, 6); // floor
                            glUniform3f(roomUniTint, 0.45f, 0.28f, 0.15f);
                            glDrawArrays(GL_TRIANGLES, 6, 18); // walls
                            glDisableVertexAttribArray(roomAttrPos);
                            glDisableVertexAttribArray(roomAttrNorm);
                        }
                        for (int k = 0; k < roomPropCount; k++) {
                            roomDrawProp(&roomProps[k], pm, tv,
                                &views[i].pose.position.x);
                        }
                        glBindBuffer(GL_ARRAY_BUFFER, 0);
                        glDisable(GL_DEPTH_TEST);
                        {
                            static int errLogged = 0;
                            if (!errLogged) {
                                errLogged = 1;
                                LOGW("room: first-frame glGetError=0x%x",
                                    glGetError());
                            }
                        }
                        rendered = 1;
                    }
                    if (showScreen) {
                    glUseProgram(app->screenProg);
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, app->screenTex);
                    glUniform1i(app->uniTex, 0);
                    glUniform1f(app->uniEye, (float)i);
                    glUniform1f(app->uniSbs, (float)g_stageSbs);
                    float vm[16], pm[16], mvp[16], model[16];
                    viewMatrixFromPose(vm, &views[i].pose);
                    perspectiveFromFov(pm, &views[i].fov, 0.1f, 60.0f);
                    memset(model, 0, sizeof(model));
                    model[0] = scaleX; model[5] = 1.0f; model[10] = 1.0f; model[15] = 1.0f;
                    // Centered straight ahead (was offset +2.4 to dodge the
                    // panel overlay; the panel is user-movable, the offset
                    // just read as a misplaced, foreshortened window).
                    model[12] = 0.0f;
                    float tmp[16];
                    matMul44(tmp, vm, model);
                    matMul44(mvp, pm, tmp);
                    glUniformMatrix4fv(app->uniMvp, 1, GL_FALSE, mvp);
                    glBindBuffer(GL_ARRAY_BUFFER, app->meshVbo);
                    glVertexAttribPointer(app->attrPos, 3, GL_FLOAT, GL_FALSE, 0, 0);
                    glEnableVertexAttribArray(app->attrPos);
                    glBindBuffer(GL_ARRAY_BUFFER, app->meshUvbo);
                    glVertexAttribPointer(app->attrUv, 2, GL_FLOAT, GL_FALSE, 0, 0);
                    glEnableVertexAttribArray(app->attrUv);
                    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, app->meshIbo);
                    glDrawElements(GL_TRIANGLES, app->meshIndexCount, GL_UNSIGNED_SHORT, 0);
                        glDisableVertexAttribArray(app->attrPos);
                        glDisableVertexAttribArray(app->attrUv);
                        rendered = 1;
                    } // showScreen
                    glBindFramebuffer(GL_FRAMEBUFFER, 0);
                }
                free(imgs);
            }
        }
        XrSwapchainImageReleaseInfo rel;
        memset(&rel, 0, sizeof(rel));
        rel.type = XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO;
        xrReleaseSwapchainImage(app->viewSwap[i], &rel);
        if (!rendered) {
            return 0;
        }
        projViews[i].type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
        projViews[i].pose = views[i].pose;
        projViews[i].fov = views[i].fov;
        projViews[i].subImage.swapchain = app->viewSwap[i];
        projViews[i].subImage.imageRect.offset.x = 0;
        projViews[i].subImage.imageRect.offset.y = 0;
        projViews[i].subImage.imageRect.extent.width = (int32_t)app->swapW;
        projViews[i].subImage.imageRect.extent.height = (int32_t)app->swapH;
        projViews[i].subImage.imageArrayIndex = 0;
    }
    memset(projLayer, 0, sizeof(*projLayer));
    projLayer->type = XR_TYPE_COMPOSITION_LAYER_PROJECTION;
    projLayer->space = app->localSpace;
    projLayer->viewCount = 2;
    projLayer->views = projViews;
    return 1;
}

static void theaterShutdown(VrApp* app) {
    if (roomProg != 0) {
        glDeleteProgram(roomProg);
        roomProg = 0;
    }
    for (int k = 0; k < roomPropCount; k++) {
        if (roomProps[k].vbo != 0) {
            glDeleteBuffers(1, &roomProps[k].vbo);
        }
    }
    free(roomProps);
    roomProps = NULL;
    roomPropCount = 0;
    if (testVbo != 0) {
        glDeleteBuffers(1, &testVbo);
        testVbo = 0;
    }
    if (roomDepth[0] != 0 || roomDepth[1] != 0) {
        glDeleteRenderbuffers(2, roomDepth);
        roomDepth[0] = roomDepth[1] = 0;
    }
    roomDepthW = roomDepthH = 0;
    if (app->screenProg != 0) {
        glDeleteProgram(app->screenProg);
        app->screenProg = 0;
    }
    if (app->screenTex != 0) {
        glDeleteTextures(1, &app->screenTex);
        app->screenTex = 0;
    }
    if (app->meshVbo != 0) {
        glDeleteBuffers(1, &app->meshVbo);
        app->meshVbo = 0;
    }
    if (app->meshUvbo != 0) {
        glDeleteBuffers(1, &app->meshUvbo);
        app->meshUvbo = 0;
    }
    if (app->meshIbo != 0) {
        glDeleteBuffers(1, &app->meshIbo);
        app->meshIbo = 0;
    }
    if (app->screenFbo[0] != 0) {
        glDeleteFramebuffers(2, app->screenFbo);
        app->screenFbo[0] = app->screenFbo[1] = 0;
    }
    for (int i = 0; i < 2; i++) {
        if (app->viewSwap[i] != XR_NULL_HANDLE) {
            xrDestroySwapchain(app->viewSwap[i]);
            app->viewSwap[i] = XR_NULL_HANDLE;
        }
    }
    app->swapReady = 0;
    if (app->localSpace != XR_NULL_HANDLE) {
        xrDestroySpace(app->localSpace);
        app->localSpace = XR_NULL_HANDLE;
    }
}

static void pumpSessionEvents(VrApp* app) {
    XrEventDataBuffer ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = XR_TYPE_EVENT_DATA_BUFFER;
    while (xrPollEvent(app->instance, &ev) == XR_SUCCESS) {
        if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            XrEventDataSessionStateChanged* sc = (XrEventDataSessionStateChanged*)&ev;
            app->sessionState = sc->state;
            if (sc->state == XR_SESSION_STATE_READY) {
                XrSessionBeginInfo bi;
                memset(&bi, 0, sizeof(bi));
                bi.type = XR_TYPE_SESSION_BEGIN_INFO;
                bi.primaryViewConfigurationType =
                    XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                if (xrBeginSession(app->session, &bi) == XR_SUCCESS) {
                    app->sessionRunning = 1;
                    LOGI("session begun");
                    theaterGlInit(app);
                    roomGlInit(app);
                    theaterEnsureSession(app);
                }
            } else if (sc->state == XR_SESSION_STATE_STOPPING) {
                app->sessionRunning = 0;
                XRCHECK(xrEndSession(app->session));
            } else if (sc->state == XR_SESSION_STATE_EXITING
                    || sc->state == XR_SESSION_STATE_LOSS_PENDING) {
                app->sessionRunning = 0;
                app->requestExit = 1;
            }
        }
        memset(&ev, 0, sizeof(ev));
        ev.type = XR_TYPE_EVENT_DATA_BUFFER;
    }
}

static void drawFrame(VrApp* app) {
    XrFrameWaitInfo waitInfo;
    memset(&waitInfo, 0, sizeof(waitInfo));
    waitInfo.type = XR_TYPE_FRAME_WAIT_INFO;
    XrFrameState frameState;
    memset(&frameState, 0, sizeof(frameState));
    frameState.type = XR_TYPE_FRAME_STATE;
    if (xrWaitFrame(app->session, &waitInfo, &frameState) != XR_SUCCESS) {
        return;
    }
    app->lastFrameTime = frameState.predictedDisplayTime;
    XrFrameBeginInfo beginInfo;
    memset(&beginInfo, 0, sizeof(beginInfo));
    beginInfo.type = XR_TYPE_FRAME_BEGIN_INFO;
    if (xrBeginFrame(app->session, &beginInfo) != XR_SUCCESS) {
        return;
    }

    const XrCompositionLayerBaseHeader* layers[2];
    uint32_t layerCount = 0;
    XrCompositionLayerPassthroughFB ptLayer;
    if (app->hasPassthrough && frameState.shouldRender) {
        memset(&ptLayer, 0, sizeof(ptLayer));
        ptLayer.type = XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB;
        ptLayer.layerHandle = app->passthroughLayer;
        ptLayer.flags = 0;
        layers[layerCount++] = (const XrCompositionLayerBaseHeader*)&ptLayer;
    }
    XrCompositionLayerProjection projLayer;
    XrCompositionLayerProjectionView projViews[2];
    if (frameState.shouldRender
            && theaterRenderViews(app, &projLayer, projViews)) {
        layers[layerCount++] =
            (const XrCompositionLayerBaseHeader*)&projLayer;
    }
    XrFrameEndInfo endInfo;
    memset(&endInfo, 0, sizeof(endInfo));
    endInfo.type = XR_TYPE_FRAME_END_INFO;
    endInfo.displayTime = frameState.predictedDisplayTime;
    endInfo.environmentBlendMode = app->blendMode;
    endInfo.layerCount = layerCount;
    endInfo.layers = layers;
    {
        static unsigned long frames = 0;
        XrResult er = xrEndFrame(app->session, &endInfo);
        // Quiet heartbeat (was every ~4s, evicting one-shot init lines from
        // the on-device tag buffer before anyone could read them).
        if ((++frames % 3600) == 1 || er != XR_SUCCESS) {
            LOGI("endFrame #%lu: %d (layers=%u) swap=%d prog=%d tex=%dx%d room=%d env=%d",
                frames, (int)er, (unsigned)layerCount,
                app->swapReady, app->screenProg != 0, app->texW, app->texH,
                roomPropCount, g_env);
        }
    }
}

static void* xrThread(void* arg) {
    VrApp* app = (VrApp*)arg;
    // Attach for any JNI use (loader init already done on main thread).
    JavaVM* vm = app->vm;
    JNIEnv* env = NULL;
    int attached = 0;
    XrResult genv = (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6);
    if (genv != JNI_OK) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) == JNI_OK) {
            attached = 1;
        }
    }
    LOGI("xr thread: GetEnv=%d attached=%d env=%p", (int)genv, attached, (void*)env);
    if (!initXr(app)) {
        LOGE("initXr failed; room unavailable");
        if (attached) {
            (*vm)->DetachCurrentThread(vm);
        }
        return NULL;
    }
    while (!app->requestExit) {
        pumpSessionEvents(app);
        if (app->requestExit) {
            break;
        }
        if (app->sessionRunning) {
            drawFrame(app);
        } else {
            usleep(50 * 1000);
        }
    }
    if (app->hasPassthrough) {
        if (pfnDestroyPassthroughLayer != NULL) {
            pfnDestroyPassthroughLayer(app->passthroughLayer);
        }
        if (pfnDestroyPassthrough != NULL) {
            pfnDestroyPassthrough(app->passthrough);
        }
        app->hasPassthrough = 0;
    }
    if (app->session != XR_NULL_HANDLE) {
        xrDestroySession(app->session);
        app->session = XR_NULL_HANDLE;
    }
    theaterShutdown(app);
    if (app->instance != XR_NULL_HANDLE) {
        xrDestroyInstance(app->instance);
        app->instance = XR_NULL_HANDLE;
    }
    if (app->eglDisplay != NULL) {
        eglMakeCurrent(app->eglDisplay, EGL_NO_SURFACE,
            EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (app->eglSurface != NULL) {
            eglDestroySurface(app->eglDisplay, app->eglSurface);
            app->eglSurface = NULL;
        }
        if (app->eglContext != NULL) {
            eglDestroyContext(app->eglDisplay, app->eglContext);
            app->eglContext = NULL;
        }
        eglTerminate(app->eglDisplay);
        app->eglDisplay = NULL;
    }
    if (attached) {
        (*vm)->DetachCurrentThread(vm);
    }
    LOGI("xr thread exiting");
    return NULL;
}

static void handleCmd(struct android_app* app, int32_t cmd) {
    VrApp* state = (VrApp*)app->userData;
    if (state == NULL) {
        return;
    }
    if (cmd == APP_CMD_INIT_WINDOW) {
        if (!state->threadStarted) {
            state->threadStarted = 1;
            if (pthread_create(&state->thread, NULL, xrThread, state) != 0) {
                LOGE("pthread_create failed");
                state->threadStarted = 0;
            }
        }
    } else if (cmd == APP_CMD_DESTROY) {
        state->requestExit = 1;
        if (state->threadStarted) {
            pthread_join(state->thread, NULL);
            state->threadStarted = 0;
        }
    }
}

void android_main(struct android_app* app) {
    VrApp state;
    memset(&state, 0, sizeof(state));
    app->userData = &state;
    app->onAppCmd = handleCmd;
    if (app->activity != NULL) {
        state.activity = app->activity;
        // android_main already runs attached; just retain the VM pointer.
        state.vm = app->activity->vm;
        JNIEnv* env = NULL;
        if (state.vm != NULL) {
            if ((*state.vm)->GetEnv(state.vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
                // Glue thread starts detached: attach once for the process
                // lifetime (never detached; the glue needs it attached).
                if ((*state.vm)->AttachCurrentThread(state.vm, &env, NULL) != JNI_OK) {
                    LOGE("AttachCurrentThread(main) failed");
                    env = NULL;
                } else {
                    LOGI("attached main thread for global refs");
                }
            }
            if (env != NULL && app->activity->clazz != NULL) {
                state.activityRef = (*env)->NewGlobalRef(env, app->activity->clazz);
            }
        }
        if (state.activityRef == NULL) {
            LOGE("activityRef is NULL; loader init cannot succeed");
        }
    } else {
        LOGE("no activity; cannot init loader");
        return;
    }
    LOGI("VrRoom starting");
    while (!app->destroyRequested) {
        int events = 0;
        struct android_poll_source* source = NULL;
        // Blocking poll: XR pacing happens on the worker thread.
        if (ALooper_pollOnce(-1, NULL, &events, (void**)&source) >= 0) {
            if (source != NULL) {
                source->process(app, source);
            }
        }
    }
    state.requestExit = 1;
    if (state.threadStarted) {
        pthread_join(state.thread, NULL);
    }
    if (state.activityRef != NULL && state.vm != NULL) {
        JNIEnv* env = NULL;
        if ((*state.vm)->GetEnv(state.vm, (void**)&env, JNI_VERSION_1_6) == JNI_OK
                && env != NULL) {
            (*env)->DeleteGlobalRef(env, state.activityRef);
        }
        state.activityRef = NULL;
    }
    LOGI("VrRoom exiting");
}
