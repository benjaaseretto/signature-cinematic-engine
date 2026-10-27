package com.signature.cinematic.gl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;

/** Contexto EGL/GLES 3 grabable (sirve para la superficie de entrada de MediaCodec). */
public final class EglCore {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private EGLDisplay display;
    private EGLContext context;
    private EGLConfig config;

    public EglCore() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw new IllegalStateException("eglInitialize falló");
        }
        int[] attribs = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            throw new IllegalStateException("Sin configuración EGL GLES3 grabable");
        }
        config = configs[0];
        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("eglCreateContext falló");
        }
    }

    public EGLSurface createWindowSurface(Object surface) {
        int[] attribs = {EGL14.EGL_NONE};
        EGLSurface s = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0);
        if (s == null || s == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("eglCreateWindowSurface: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }
        return s;
    }

    public EGLSurface createPbuffer(int width, int height) {
        int[] attribs = {EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE};
        return EGL14.eglCreatePbufferSurface(display, config, attribs, 0);
    }

    public void makeCurrent(EGLSurface surface) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw new IllegalStateException("eglMakeCurrent: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }
    }

    public boolean swap(EGLSurface surface) {
        return EGL14.eglSwapBuffers(display, surface);
    }

    public void setPresentationTime(EGLSurface surface, long nanos) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos);
    }

    public String eglExtensions() {
        return EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS);
    }

    public void releaseSurface(EGLSurface surface) {
        if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, surface);
        }
    }

    public void release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
    }
}
