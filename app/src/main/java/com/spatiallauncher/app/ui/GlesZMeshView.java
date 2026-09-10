package com.spatiallauncher.app.ui;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * GLES Z-mesh on the same SurfaceView Horizon already stereo-composes.
 * Never uses a second GLSurfaceView (that put SBS in the left eye only).
 * Recreates the surface before EGL so lockCanvas and GL are not producers at once.
 */
public class GlesZMeshView {

    private static final String TAG = "GlesZMesh";
    private final MeshRenderer renderer = new MeshRenderer();
    private final Object lock = new Object();
    private final Thread glThread;

    private SurfaceView target;
    private SurfaceView recreateAfterRelease;
    private SurfaceHolder holder;
    private volatile boolean attached;
    private volatile boolean ownsSurface;
    private volatile boolean paused;
    private volatile boolean nativeSurfaceValid = true;
    private volatile boolean eglWindowStale;
    private boolean running = true;
    private boolean renderRequested;
    private Runnable onBound;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;

    public GlesZMeshView() {
        glThread = new Thread(this::glLoop, "GlesZMesh");
        glThread.start();
    }

    public void setOnBound(Runnable callback) {
        onBound = callback;
    }

    public boolean ownsSurface() {
        return ownsSurface;
    }

    public void startOn(SurfaceView view) {
        synchronized (lock) {
            if (attached && target == view && ownsSurface && nativeSurfaceValid && !paused) {
                renderRequested = true;
                lock.notifyAll();
                return;
            }
            unbindLocked();
            paused = false;
            nativeSurfaceValid = true;
            target = view;
            holder = view.getHolder();
            holder.addCallback(callback);
            attached = true;
            renderRequested = true;
            lock.notifyAll();
        }
        recreateSurface(view);
    }

    public void stop() {
        synchronized (lock) {
            recreateAfterRelease = target;
            unbindLocked();
            paused = false;
            lock.notifyAll();
        }
    }

    public void onPause() {
        synchronized (lock) {
            paused = true;
            lock.notifyAll();
        }
    }

    public void onResume(SurfaceView view, boolean shouldRun) {
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
        if (shouldRun && view != null) {
            startOn(view);
        }
    }

    public void onBufferResize(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        synchronized (lock) {
            eglWindowStale = true;
            lock.notifyAll();
        }
        SurfaceView view = target;
        if (view != null) {
            view.getHolder().setFixedSize(w, h);
        }
    }

    public void submit(
            Bitmap frame,
            float[][] depth01,
            boolean stereo,
            float strength,
            float convergencePx,
            float edgeFade,
            boolean smoothLiveDepth) {
        renderer.queueFrame(frame, depth01, stereo, strength, convergencePx, edgeFade, smoothLiveDepth);
        synchronized (lock) {
            renderRequested = true;
            lock.notifyAll();
        }
    }

    private void unbindLocked() {
        attached = false;
        if (holder != null) {
            holder.removeCallback(callback);
        }
        holder = null;
        target = null;
    }

    private static void recreateSurface(SurfaceView view) {
        view.post(() -> {
            int w = view.getWidth();
            int h = view.getHeight();
            if (w > 0 && h > 0) {
                view.getHolder().setFixedSize(w, h == 1 ? 2 : h - 1);
                view.getHolder().setFixedSize(w, h);
            } else {
                view.getHolder().setSizeFromLayout();
            }
        });
    }

    private final SurfaceHolder.Callback callback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder h) {
            synchronized (lock) {
                nativeSurfaceValid = true;
                eglWindowStale = true;
                renderRequested = true;
                lock.notifyAll();
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
            renderer.setViewport(width, height);
            synchronized (lock) {
                nativeSurfaceValid = true;
                eglWindowStale = true;
                renderRequested = true;
                lock.notifyAll();
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder h) {
            synchronized (lock) {
                nativeSurfaceValid = false;
                lock.notifyAll();
            }
        }
    };

    private void glLoop() {
        while (true) {
            SurfaceHolder readyHolder;
            synchronized (lock) {
                if (!running) {
                    releaseEglLocked();
                    return;
                }
                while (running && (paused || !attached || holder == null
                        || !nativeSurfaceValid || !holder.getSurface().isValid()
                        || !renderRequested)) {
                    if (!attached || paused || !nativeSurfaceValid || holder == null
                            || (holder != null && !holder.getSurface().isValid())
                            || eglWindowStale) {
                        releaseWindowLocked();
                        if (!attached || paused) {
                            releaseEglLocked();
                        }
                    }
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        releaseEglLocked();
                        return;
                    }
                    if (!running) {
                        releaseEglLocked();
                        return;
                    }
                }
                renderRequested = false;
                readyHolder = holder;
            }
            if (readyHolder == null || !nativeSurfaceValid || !readyHolder.getSurface().isValid()) {
                continue;
            }
            if (eglWindowStale) {
                synchronized (lock) {
                    releaseWindowLocked();
                    eglWindowStale = false;
                }
            }
            if (!ensureEgl(readyHolder)) {
                synchronized (lock) {
                    renderRequested = attached && !paused;
                }
                try {
                    Thread.sleep(16);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            renderer.draw();
            if (!nativeSurfaceValid || eglWindowStale || eglSurface == EGL14.EGL_NO_SURFACE) {
                continue;
            }
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        }
    }

    private boolean ensureEgl(SurfaceHolder h) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1);
            int[] spec = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 0,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            EGL14.eglChooseConfig(eglDisplay, spec, 0, configs, 0, 1, num, 0);
            if (num[0] <= 0 || configs[0] == null) {
                Log.e(TAG, "eglChooseConfig failed");
                return false;
            }
            eglConfig = configs[0];
            int[] ctx = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(
                    eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctx, 0);
        }
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            eglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, h.getSurface(), new int[]{EGL14.EGL_NONE}, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface failed");
                return false;
            }
            ownsSurface = true;
            SurfaceView view = target;
            Runnable bound = onBound;
            if (view != null && bound != null) {
                view.post(bound);
            }
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent failed");
            return false;
        }
        int[] wh = new int[1];
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, wh, 0);
        int w = Math.max(1, wh[0]);
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, wh, 0);
        renderer.setViewport(w, Math.max(1, wh[0]));
        if (!renderer.hasProgram()) {
            renderer.onSurfaceCreated();
        }
        return true;
    }

    private void releaseWindowLocked() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            eglSurface = EGL14.EGL_NO_SURFACE;
        }
        ownsSurface = false;
        lock.notifyAll();
    }

    private void releaseEglLocked() {
        releaseWindowLocked();
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
        renderer.dropGpu();
        SurfaceView view = recreateAfterRelease;
        recreateAfterRelease = null;
        if (view != null && !attached) {
            recreateSurface(view);
        }
        lock.notifyAll();
    }

    private static final class MeshRenderer {
        private static final String VERT =
                "uniform mat4 uMvp;"
                        + "attribute vec4 aPos;"
                        + "attribute vec2 aUv;"
                        + "varying vec2 vUv;"
                        + "void main(){vUv=aUv;gl_Position=uMvp*aPos;}";
        private static final String FRAG =
                "precision mediump float;"
                        + "uniform sampler2D uTex;"
                        + "varying vec2 vUv;"
                        + "void main(){gl_FragColor=texture2D(uTex,vUv);}";
        private static final int MAX_GPU_EDGE = 1280;

        private final Object frameLock = new Object();
        private Bitmap pendingFrame;
        private float[][] pendingDepth;
        private boolean pendingStereo = true;
        private float pendingStrength = 1f;
        private float pendingConverge;
        private float pendingFade = 0.12f;
        private boolean pendingSmooth;
        private int program;
        private int aPos;
        private int aUv;
        private int uMvp;
        private int texId;
        private int viewportW = 1;
        private int viewportH = 1;
        private java.nio.FloatBuffer posBuf;
        private java.nio.FloatBuffer uvBuf;
        private java.nio.ShortBuffer idxBuf;
        private int indexCount;
        private Bitmap gpuFrame;
        private float planeW = 1f;
        private float[][] smoothDepth;
        private boolean liveMesh;

        void queueFrame(
                Bitmap frame,
                float[][] depth01,
                boolean stereo,
                float strength,
                float convergencePx,
                float edgeFade,
                boolean smoothLiveDepth) {
            synchronized (frameLock) {
                if (pendingFrame != null && pendingFrame != frame) {
                    pendingFrame.recycle();
                }
                pendingFrame = scaleForGpu(frame);
                pendingDepth = copyDepth(depth01);
                pendingStereo = stereo;
                pendingStrength = strength;
                pendingConverge = convergencePx;
                pendingFade = edgeFade;
                pendingSmooth = smoothLiveDepth;
            }
        }

        void setViewport(int width, int height) {
            viewportW = Math.max(1, width);
            viewportH = Math.max(1, height);
        }

        boolean hasProgram() {
            return program != 0;
        }

        void dropGpu() {
            program = 0;
            texId = 0;
        }

        void onSurfaceCreated() {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            program = link(VERT, FRAG);
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            aUv = GLES20.glGetAttribLocation(program, "aUv");
            uMvp = GLES20.glGetUniformLocation(program, "uMvp");
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            texId = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        void draw() {
            Bitmap frame;
            float[][] depth;
            boolean stereo;
            float strength;
            float converge;
            float fade;
            boolean smooth;
            synchronized (frameLock) {
                frame = pendingFrame;
                pendingFrame = null;
                depth = pendingDepth;
                stereo = pendingStereo;
                strength = pendingStrength;
                converge = pendingConverge;
                fade = pendingFade;
                smooth = pendingSmooth;
                liveMesh = smooth;
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (frame != null && texId != 0) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0);
                if (gpuFrame != null && gpuFrame != frame) {
                    gpuFrame.recycle();
                }
                gpuFrame = frame;
            }
            if (gpuFrame == null || program == 0) {
                return;
            }
            float[][] used = smooth ? blendDepth(depth) : depth;
            rebuildMesh(
                    used,
                    gpuFrame.getWidth() / (float) Math.max(1, gpuFrame.getHeight()),
                    strength,
                    fade);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            posBuf.position(0);
            uvBuf.position(0);
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, posBuf);
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uvBuf);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glEnableVertexAttribArray(aUv);
            if (!stereo) {
                GLES20.glViewport(0, 0, viewportW, viewportH);
                drawEye(0f, viewportW / (float) viewportH);
            } else {
                int half = Math.max(1, viewportW / 2);
                float eyeA = half / (float) viewportH;
                float ipd = 0.028f + (converge / 600f);
                GLES20.glViewport(0, 0, half, viewportH);
                drawEye(+ipd, eyeA);
                GLES20.glViewport(half, 0, viewportW - half, viewportH);
                drawEye(-ipd, eyeA);
            }
        }

        private float[][] blendDepth(float[][] incoming) {
            if (incoming == null) {
                return smoothDepth;
            }
            if (smoothDepth == null
                    || smoothDepth.length != incoming.length
                    || smoothDepth[0].length != incoming[0].length) {
                smoothDepth = copyDepth(incoming);
                return smoothDepth;
            }
            for (int r = 0; r < incoming.length; r++) {
                for (int c = 0; c < incoming[r].length; c++) {
                    smoothDepth[r][c] = smoothDepth[r][c] * 0.9f + incoming[r][c] * 0.1f;
                }
            }
            return smoothDepth;
        }

        private void drawEye(float camX, float projAspect) {
            float[] p = new float[16];
            float[] v = new float[16];
            float[] mvp = new float[16];
            float va = Math.max(0.2f, projAspect);
            float pad = liveMesh ? 1.24f : 1.08f;
            float needH = Math.max(1f, planeW / va) * pad;
            float fovY = (float) Math.toDegrees(2.0 * Math.atan((needH * 0.5f) / 1.0));
            Matrix.perspectiveM(p, 0, fovY, va, 0.25f, 8f);
            Matrix.setLookAtM(v, 0, camX, 0f, 0f, camX, 0f, -1f, 0f, 1f, 0f);
            Matrix.multiplyMM(mvp, 0, p, 0, v, 0);
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
            idxBuf.position(0);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, idxBuf);
        }

        private void rebuildMesh(float[][] depth, float frameAspect, float strength, float fade) {
            int rows = 12;
            int cols = 16;
            if (depth != null && depth.length >= 2 && depth[0].length >= 2) {
                rows = depth.length - 1;
                cols = depth[0].length - 1;
            }
            int verts = (rows + 1) * (cols + 1);
            float[] pos = new float[verts * 3];
            float[] uv = new float[verts * 2];
            float mean = meanDepth(depth);
            float pop = (liveMesh ? 0.07f : 0.20f) * Math.max(0.2f, strength);
            planeW = Math.max(0.4f, frameAspect);
            int i = 0;
            int t = 0;
            for (int r = 0; r <= rows; r++) {
                float vv = r / (float) rows;
                float fadeR = edge(r, rows, fade);
                for (int c = 0; c <= cols; c++) {
                    float u = c / (float) cols;
                    float fadeC = edge(c, cols, fade) * fadeR;
                    float d = 0.5f;
                    if (depth != null && r < depth.length && c < depth[r].length) {
                        d = depth[r][c];
                        if (liveMesh) {
                            d = 0.5f + (d - 0.5f) * 0.55f;
                        }
                    }
                    float lift = (d - mean) * fadeC * pop;
                    pos[i++] = (u - 0.5f) * planeW;
                    pos[i++] = (0.5f - vv);
                    pos[i++] = -1f + lift;
                    uv[t++] = u;
                    uv[t++] = vv;
                }
            }
            short[] idx = new short[rows * cols * 6];
            int k = 0;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int a = r * (cols + 1) + c;
                    idx[k++] = (short) a;
                    idx[k++] = (short) (a + 1);
                    idx[k++] = (short) (a + cols + 1);
                    idx[k++] = (short) (a + 1);
                    idx[k++] = (short) (a + cols + 2);
                    idx[k++] = (short) (a + cols + 1);
                }
            }
            indexCount = idx.length;
            posBuf = buf(pos);
            uvBuf = buf(uv);
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocateDirect(idx.length * 2);
            bb.order(java.nio.ByteOrder.nativeOrder());
            idxBuf = bb.asShortBuffer();
            idxBuf.put(idx);
            idxBuf.position(0);
        }

        private static Bitmap scaleForGpu(Bitmap frame) {
            if (frame == null) {
                return null;
            }
            int w = frame.getWidth();
            int h = frame.getHeight();
            int edge = Math.max(w, h);
            if (edge <= MAX_GPU_EDGE) {
                return frame.copy(frame.getConfig(), false);
            }
            float s = MAX_GPU_EDGE / (float) edge;
            return Bitmap.createScaledBitmap(
                    frame,
                    Math.max(1, Math.round(w * s)),
                    Math.max(1, Math.round(h * s)),
                    true);
        }

        private static float meanDepth(float[][] depth) {
            if (depth == null) {
                return 0.5f;
            }
            float s = 0f;
            int n = 0;
            for (float[] row : depth) {
                for (float d : row) {
                    s += d;
                    n++;
                }
            }
            return n == 0 ? 0.5f : s / n;
        }

        private static float edge(int i, int n, float fade) {
            if (fade <= 0.001f || n <= 0) {
                return 1f;
            }
            float cells = Math.max(1f, n * fade);
            return Math.min(1f, Math.min(i, n - i) / cells);
        }

        private static float[][] copyDepth(float[][] src) {
            if (src == null) {
                return null;
            }
            float[][] out = new float[src.length][];
            for (int i = 0; i < src.length; i++) {
                out[i] = src[i].clone();
            }
            return out;
        }

        private static java.nio.FloatBuffer buf(float[] data) {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocateDirect(data.length * 4);
            bb.order(java.nio.ByteOrder.nativeOrder());
            java.nio.FloatBuffer fb = bb.asFloatBuffer();
            fb.put(data);
            fb.position(0);
            return fb;
        }

        private static int link(String vs, String fs) {
            int v = compile(GLES20.GL_VERTEX_SHADER, vs);
            int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                Log.e(TAG, GLES20.glGetProgramInfoLog(p));
            }
            return p;
        }

        private static int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            return s;
        }
    }
}
