// Minimal immersive container: OpenXR session + FB passthrough layer only.
// No scene, no swapchains, no input. The panel (2D overlay) provides all UI.
// Errors are logged and degraded gracefully; nothing here may crash the app.

#include <android/log.h>
#include <android/native_activity.h>
#include <android_native_app_glue.h>
#include <jni.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

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

    pthread_t thread;
    int threadStarted;
    volatile int requestExit;
} VrApp;

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
    // Loader init is mandatory on Android before xrCreateInstance.
    {
        PFN_xrInitializeLoaderKHR pfnInitLoader = NULL;
        XrResult lr = xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR",
                (PFN_xrVoidFunction*)&pfnInitLoader);
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
    if (xrCreateSession(app->instance, &sci, &app->session) != XR_SUCCESS) {
        LOGE("xrCreateSession failed");
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

    // Blend mode: prefer OPAQUE for a passthrough room.
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
                    if (modes[i] == XR_ENVIRONMENT_BLEND_MODE_OPAQUE) {
                        app->blendMode = modes[i];
                        break;
                    }
                }
            }
            free(modes);
        } else {
            app->blendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
        }
    }

    LOGI("XR ready (passthrough=%d)", app->hasPassthrough);
    return 1;
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
    XrFrameBeginInfo beginInfo;
    memset(&beginInfo, 0, sizeof(beginInfo));
    beginInfo.type = XR_TYPE_FRAME_BEGIN_INFO;
    if (xrBeginFrame(app->session, &beginInfo) != XR_SUCCESS) {
        return;
    }

    const XrCompositionLayerBaseHeader* layers[1];
    uint32_t layerCount = 0;
    XrCompositionLayerPassthroughFB ptLayer;
    if (app->hasPassthrough && frameState.shouldRender) {
        memset(&ptLayer, 0, sizeof(ptLayer));
        ptLayer.type = XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB;
        ptLayer.layerHandle = app->passthroughLayer;
        ptLayer.flags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
        layers[0] = (const XrCompositionLayerBaseHeader*)&ptLayer;
        layerCount = 1;
    }
    XrFrameEndInfo endInfo;
    memset(&endInfo, 0, sizeof(endInfo));
    endInfo.type = XR_TYPE_FRAME_END_INFO;
    endInfo.displayTime = frameState.predictedDisplayTime;
    endInfo.environmentBlendMode = app->blendMode;
    endInfo.layerCount = layerCount;
    endInfo.layers = layers;
    XRCHECK(xrEndFrame(app->session, &endInfo));
}

static void* xrThread(void* arg) {
    VrApp* app = (VrApp*)arg;
    // Attach for any JNI use (loader init already done on main thread).
    JavaVM* vm = app->vm;
    JNIEnv* env = NULL;
    int attached = 0;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) == JNI_OK) {
            attached = 1;
        }
    }
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
        if (state.vm != NULL
                && (*state.vm)->GetEnv(state.vm, (void**)&env, JNI_VERSION_1_6) == JNI_OK
                && env != NULL && app->activity->clazz != NULL) {
            state.activityRef = (*env)->NewGlobalRef(env, app->activity->clazz);
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
