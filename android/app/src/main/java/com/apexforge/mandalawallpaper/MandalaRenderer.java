package com.apexforge.mandalawallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static android.opengl.GLES20.*;

/**
 * Mandala Eclipse Live renderer.
 *
 * Layers (far -> near):
 *  1. mandala_base.png, aspect-fill, slow "breathing" zoom (+-1.5% / 12s)
 *  2. eclipse_core.png sprite + procedural additive glow, parallax drift, gentle pulse
 *  3. GPU point-sprite particle field (~120 motes), strongest parallax
 *  4. Tap rings: expanding additive rings from touch points (pool of 8)
 *
 * Inputs: accelerometer tilt, home-screen scroll offset, taps.
 * Budget: 30fps cap, render only when visible, zero allocations in the draw loop.
 */
public class MandalaRenderer implements GLSurfaceView.Renderer {

    private static final int PARTICLE_COUNT = 120;
    private static final int RING_POOL = 8;
    private static final long FRAME_NS = 33_333_333L; // 30fps cap

    // Eclipse core location inside mandala_base.png (texture UV, origin top-left)
    private static final float CORE_U = 270f / 560f;
    private static final float CORE_V = 300f / 745f;
    private static final float CORE_TEX_PX = 380f; // eclipse_core.png is 380x380

    // Palette sampled from the mandala artwork
    private static final float[][] PALETTE = {
            {0.20f, 0.88f, 1.00f}, // cyan
            {1.00f, 0.79f, 0.24f}, // gold
            {1.00f, 0.31f, 0.85f}, // magenta
            {0.62f, 0.35f, 1.00f}, // violet
    };

    // Parallax depths in px at full deflection (tilt | scroll)
    private static final float DEPTH_BASE_TILT = 10f, DEPTH_BASE_SCROLL = 24f;
    private static final float DEPTH_CORE_TILT = 26f, DEPTH_CORE_SCROLL = 60f;
    private static final float DEPTH_PART_TILT = 55f, DEPTH_PART_SCROLL = 110f;

    private final Context ctx;

    private int progTex, progGlow, progPts, progRing;
    private int texBase, texCore;

    private int viewW, viewH;
    // aspect-fill UV window into the base texture
    private float u0, u1, v0, v1;
    // core sprite anchor on screen (px, y-up), derived from UV window
    private float coreX, coreY;
    private float coreSizePx;

    private final float[] mvp = new float[16];

    // Inputs (written from engine thread, read on GL thread — benign races ok)
    private volatile float tiltX, tiltY;
    private volatile float scrollX; // -1..1
    // -1 = no audio capture available (no RECORD_AUDIO grant); renderer breathes on its own
    private volatile float audioEnergy = -1f;

    // Time-of-day aura: recomputed when the minute changes (no per-frame allocation)
    private int lastGradeMinute = -1;
    private float gradeR = 1f, gradeG = 1f, gradeB = 1f; // base color grade
    private float glowR = 1f, glowG = 0.72f, glowB = 0.32f; // glow tint
    private float warmth = 1f;

    // Particles (preallocated, updated on CPU)
    private final float[] pX = new float[PARTICLE_COUNT];
    private final float[] pY = new float[PARTICLE_COUNT];
    private final float[] pSpeed = new float[PARTICLE_COUNT];
    private final float[] pSize = new float[PARTICLE_COUNT];
    private final float[] pPhase = new float[PARTICLE_COUNT];
    private final float[] pTw = new float[PARTICLE_COUNT];
    private final float[] pCol = new float[PARTICLE_COUNT * 3];
    private final FloatBuffer ptsBuf;

    // Tap rings (pool, no allocation)
    private final float[] ringX = new float[RING_POOL];
    private final float[] ringY = new float[RING_POOL];
    private final float[] ringT0 = new float[RING_POOL];
    private int ringCursor;

    // Quad vertex scratch (2 pos + 2 uv) * 6 verts
    private final FloatBuffer quadBuf;

    private long startNs;
    private double tSec;

    public MandalaRenderer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        ptsBuf = ByteBuffer.allocateDirect(PARTICLE_COUNT * 7 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadBuf = ByteBuffer.allocateDirect(6 * 4 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        Random r = new Random(1337);
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            pX[i] = r.nextFloat();
            pY[i] = r.nextFloat();
            pSpeed[i] = 0.008f + r.nextFloat() * 0.030f; // fraction of height / sec, upward
            pSize[i] = 3f + r.nextFloat() * 7f;
            pPhase[i] = r.nextFloat() * (float) (Math.PI * 2);
            pTw[i] = 0.6f + r.nextFloat() * 2.2f;
            float[] c = PALETTE[r.nextInt(PALETTE.length)];
            pCol[i * 3] = c[0];
            pCol[i * 3 + 1] = c[1];
            pCol[i * 3 + 2] = c[2];
        }
        for (int i = 0; i < RING_POOL; i++) ringT0[i] = -100f;
    }

    // ---- inputs from the engine ----
    public void setTilt(float x, float y) { tiltX = x; tiltY = y; }
    public void setScroll(float x01) { scrollX = (x01 - 0.5f) * 2f; }

    public void addRing(float xPxTopDown, float yPxTopDown) {
        int i = ringCursor;
        ringCursor = (ringCursor + 1) % RING_POOL;
        ringX[i] = xPxTopDown;
        ringY[i] = viewH - yPxTopDown; // to y-up
        ringT0[i] = (float) tSec;
    }

    public void setAudioEnergy(float e) { audioEnergy = e; }

    /** Unlock burst: one slow ring of light expanding from the eclipse core. */
    public void burstFromCore() {
        if (viewW == 0) return; // surface not sized yet
        int i = ringCursor;
        ringCursor = (ringCursor + 1) % RING_POOL;
        ringX[i] = coreX;
        ringY[i] = coreY;
        ringT0[i] = (float) tSec;
    }

    private void updateTimeGrade() {
        long now = System.currentTimeMillis();
        int minLocal = (int) (((now + java.util.TimeZone.getDefault().getOffset(now)) / 60000L) % 1440L);
        if (minLocal < 0) minLocal += 1440;
        if (minLocal == lastGradeMinute) return;
        lastGradeMinute = minLocal;
        float h = minLocal / 60f;
        // warmth peaks at 13:00, bottoms out at 01:00
        warmth = 0.5f + 0.5f * (float) Math.cos((h - 13f) * Math.PI * 2f / 24f);
        // base grade: cool dim night (0.84, 0.90, 1.06) -> warm golden day (1.07, 1.00, 0.90)
        gradeR = 0.84f + (1.07f - 0.84f) * warmth;
        gradeG = 0.90f + (1.00f - 0.90f) * warmth;
        gradeB = 1.06f + (0.90f - 1.06f) * warmth;
        // glow tint: cool moon-blue night -> warm gold day
        glowR = 0.55f + (1.00f - 0.55f) * warmth;
        glowG = 0.75f + (0.72f - 0.75f) * warmth;
        glowB = 1.00f + (0.32f - 1.00f) * warmth;
    }

    // ---- GL lifecycle ----
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        progTex = buildProgram(VERT_TEX, FRAG_TEX);
        progGlow = buildProgram(VERT_TEX, FRAG_GLOW);
        progPts = buildProgram(VERT_PTS, FRAG_PTS);
        progRing = buildProgram(VERT_TEX, FRAG_RING);
        texBase = loadTexture("mandala_base.png");
        texCore = loadTexture("eclipse_core.png");
        glClearColor(0f, 0f, 0f, 1f);
        glDisable(GL_DEPTH_TEST);
        startNs = System.nanoTime();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        viewW = width;
        viewH = height;
        glViewport(0, 0, width, height);
        Matrix.orthoM(mvp, 0, 0, width, 0, height, -1, 1);

        float screenAspect = width / (float) height;
        float texAspect = 560f / 745f;
        if (texAspect > screenAspect) {
            float crop = 1f - screenAspect / texAspect;
            u0 = crop / 2f; u1 = 1f - crop / 2f; v0 = 0f; v1 = 1f;
        } else {
            float crop = 1f - texAspect / screenAspect;
            v0 = crop / 2f; v1 = 1f - crop / 2f; u0 = 0f; u1 = 1f;
        }
        float fx = (CORE_U - u0) / (u1 - u0);
        float fyTop = (CORE_V - v0) / (v1 - v0);
        coreX = fx * width;
        coreY = height - fyTop * height;
        float texPxPerScreenPx = (u1 - u0) * 560f / width;
        coreSizePx = CORE_TEX_PX / texPxPerScreenPx;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long frameStart = System.nanoTime();
        tSec = (frameStart - startNs) / 1e9;
        float t = (float) tSec;
        updateTimeGrade();

        float parTiltX = tiltX, parTiltY = tiltY, parScroll = scrollX;

        glClear(GL_COLOR_BUFFER_BIT);

        // ---- Layer 1: base, aspect-fill, breathing zoom ----
        float breathe = 1f + 0.015f * (float) Math.sin(t * Math.PI * 2f / 12f);
        float bw = viewW * breathe, bh = viewH * breathe;
        float bx = (viewW - bw) / 2f + parTiltX * DEPTH_BASE_TILT + parScroll * DEPTH_BASE_SCROLL;
        float by = (viewH - bh) / 2f + parTiltY * DEPTH_BASE_TILT;
        glDisable(GL_BLEND);
        drawTextured(progTex, texBase, bx, by, bw, bh, u0, v0, u1, v1, 1f);

        float corePx = coreX + parTiltX * DEPTH_CORE_TILT + parScroll * DEPTH_CORE_SCROLL;
        float corePy = coreY + parTiltY * DEPTH_CORE_TILT;

        // ---- Layer 2a: additive glow behind the core, pulsing ----
        // Music-reactive when audio capture is live; autonomous breath otherwise.
        float ae = audioEnergy;
        float pulse;
        if (ae >= 0f) {
            if (ae > 1f) ae = 1f;
            pulse = 0.26f + 0.50f * ae;
        } else {
            pulse = 0.32f + 0.14f * (float) Math.sin(t * Math.PI * 2f / 6f);
        }
        float glowSize = coreSizePx * 2.1f;
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        drawGlow(corePx, corePy, glowSize, glowR, glowG, glowB, pulse);

        // ---- Layer 2b: eclipse core sprite, gentle scale breath ----
        float coreScale = 1f + 0.02f * (float) Math.sin(t * Math.PI * 2f / 6f + 1f);
        float cs = coreSizePx * coreScale;
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        drawTextured(progTex, texCore, corePx - cs / 2f, corePy - cs / 2f, cs, cs,
                0f, 0f, 1f, 1f, 1f);

        // ---- Layer 3: particle field, additive ----
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        updateAndDrawParticles(t, parTiltX, parTiltY, parScroll);

        // ---- Layer 4: tap rings ----
        drawRings(t);

        glDisable(GL_BLEND);

        // 30fps cap
        long elapsed = System.nanoTime() - frameStart;
        long sleepNs = FRAME_NS - elapsed;
        if (sleepNs > 0) {
            try {
                Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
            } catch (InterruptedException ignored) {
            }
        }
    }

    // ---- draw helpers (no allocation) ----
    private void drawTextured(int prog, int tex, float x, float y, float w, float h,
                              float tu0, float tv0, float tu1, float tv1, float alpha) {
        glUseProgram(prog);
        float[] q = quadArray(x, y, w, h, tu0, tv0, tu1, tv1);
        quadBuf.clear();
        quadBuf.put(q);
        quadBuf.position(0);
        int aPos = glGetAttribLocation(prog, "aPos");
        int aUV = glGetAttribLocation(prog, "aUV");
        glEnableVertexAttribArray(aPos);
        glEnableVertexAttribArray(aUV);
        glVertexAttribPointer(aPos, 2, GL_FLOAT, false, 16, quadBuf);
        quadBuf.position(2);
        glVertexAttribPointer(aUV, 2, GL_FLOAT, false, 16, quadBuf);
        glUniformMatrix4fv(glGetUniformLocation(prog, "uMVP"), 1, false, mvp, 0);
        glUniform1i(glGetUniformLocation(prog, "uTex"), 0);
        glUniform1f(glGetUniformLocation(prog, "uAlpha"), alpha);
        int uGrade = glGetUniformLocation(prog, "uGrade");
        if (uGrade >= 0) glUniform3f(uGrade, gradeR, gradeG, gradeB);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glDisableVertexAttribArray(aPos);
        glDisableVertexAttribArray(aUV);
    }

    private final float[] quadScratch = new float[24];

    private float[] quadArray(float x, float y, float w, float h,
                              float tu0, float tv0, float tu1, float tv1) {
        // y-up; note texture V: bitmap top row = v1 (flip)
        float[] q = quadScratch;
        // tri 1
        q[0] = x; q[1] = y; q[2] = tu0; q[3] = tv1;
        q[4] = x + w; q[5] = y; q[6] = tu1; q[7] = tv1;
        q[8] = x + w; q[9] = y + h; q[10] = tu1; q[11] = tv0;
        // tri 2
        q[12] = x; q[13] = y; q[14] = tu0; q[15] = tv1;
        q[16] = x + w; q[17] = y + h; q[18] = tu1; q[19] = tv0;
        q[20] = x; q[21] = y + h; q[22] = tu0; q[23] = tv0;
        return q;
    }

    private void drawGlow(float cx, float cy, float size, float r, float g, float b, float alpha) {
        glUseProgram(progGlow);
        float x = cx - size / 2f, y = cy - size / 2f;
        float[] q = quadArray(x, y, size, size, 0f, 0f, 1f, 1f);
        quadBuf.clear();
        quadBuf.put(q);
        quadBuf.position(0);
        int aPos = glGetAttribLocation(progGlow, "aPos");
        int aUV = glGetAttribLocation(progGlow, "aUV");
        glEnableVertexAttribArray(aPos);
        glEnableVertexAttribArray(aUV);
        glVertexAttribPointer(aPos, 2, GL_FLOAT, false, 16, quadBuf);
        quadBuf.position(2);
        glVertexAttribPointer(aUV, 2, GL_FLOAT, false, 16, quadBuf);
        glUniformMatrix4fv(glGetUniformLocation(progGlow, "uMVP"), 1, false, mvp, 0);
        glUniform3f(glGetUniformLocation(progGlow, "uColor"), r, g, b);
        glUniform1f(glGetUniformLocation(progGlow, "uAlpha"), alpha);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glDisableVertexAttribArray(aPos);
        glDisableVertexAttribArray(aUV);
    }

    private void updateAndDrawParticles(float t, float tiltX, float tiltY, float scroll) {
        float offX = tiltX * DEPTH_PART_TILT + scroll * DEPTH_PART_SCROLL;
        float offY = tiltY * DEPTH_PART_TILT;
        float ae = audioEnergy;
        float pmul = (ae >= 0f) ? (0.55f + 0.9f * Math.min(ae, 1f)) : 1f;
        ptsBuf.clear();
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            float y = pY[i] + pSpeed[i] * t;
            y = y - (float) Math.floor(y); // wrap upward drift
            float twinkle = (0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * pTw[i] + pPhase[i]))) * pmul;
            if (twinkle > 1f) twinkle = 1f;
            float sway = 12f * (float) Math.sin(t * 0.5f + pPhase[i]);
            ptsBuf.put(pX[i] * viewW + offX + sway);
            ptsBuf.put(y * viewH + offY);
            ptsBuf.put(pSize[i]);
            ptsBuf.put(pCol[i * 3]);
            ptsBuf.put(pCol[i * 3 + 1]);
            ptsBuf.put(pCol[i * 3 + 2]);
            ptsBuf.put(twinkle);
        }
        ptsBuf.position(0);

        glUseProgram(progPts);
        int aPos = glGetAttribLocation(progPts, "aPos");
        int aSize = glGetAttribLocation(progPts, "aSize");
        int aCol = glGetAttribLocation(progPts, "aCol");
        glEnableVertexAttribArray(aPos);
        glEnableVertexAttribArray(aSize);
        glEnableVertexAttribArray(aCol);
        glVertexAttribPointer(aPos, 2, GL_FLOAT, false, 28, ptsBuf);
        ptsBuf.position(2);
        glVertexAttribPointer(aSize, 1, GL_FLOAT, false, 28, ptsBuf);
        ptsBuf.position(3);
        glVertexAttribPointer(aCol, 4, GL_FLOAT, false, 28, ptsBuf);
        glUniformMatrix4fv(glGetUniformLocation(progPts, "uMVP"), 1, false, mvp, 0);
        glDrawArrays(GL_POINTS, 0, PARTICLE_COUNT);
        glDisableVertexAttribArray(aPos);
        glDisableVertexAttribArray(aSize);
        glDisableVertexAttribArray(aCol);
    }

    private void drawRings(float t) {
        glUseProgram(progRing);
        int aPos = glGetAttribLocation(progRing, "aPos");
        int aUV = glGetAttribLocation(progRing, "aUV");
        int uMVP = glGetUniformLocation(progRing, "uMVP");
        int uR = glGetUniformLocation(progRing, "uR");
        int uT = glGetUniformLocation(progRing, "uT");
        int uColor = glGetUniformLocation(progRing, "uColor");
        glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        glUniform3f(uColor, 1.0f, 0.85f, 0.45f);
        glEnableVertexAttribArray(aPos);
        glEnableVertexAttribArray(aUV);
        for (int i = 0; i < RING_POOL; i++) {
            float age = t - ringT0[i];
            if (age < 0f || age > 1.1f) continue;
            float prog = age / 1.1f;
            float size = 640f * prog + 24f;
            float[] q = quadArray(ringX[i] - size / 2f, ringY[i] - size / 2f, size, size,
                    0f, 0f, 1f, 1f);
            quadBuf.clear();
            quadBuf.put(q);
            quadBuf.position(0);
            glVertexAttribPointer(aPos, 2, GL_FLOAT, false, 16, quadBuf);
            quadBuf.position(2);
            glVertexAttribPointer(aUV, 2, GL_FLOAT, false, 16, quadBuf);
            glUniform1f(uR, prog);
            glUniform1f(uT, prog);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }
        glDisableVertexAttribArray(aPos);
        glDisableVertexAttribArray(aUV);
    }

    // ---- shaders ----
    private static final String VERT_TEX =
            "uniform mat4 uMVP;\n" +
            "attribute vec2 aPos;\n" +
            "attribute vec2 aUV;\n" +
            "varying vec2 vUV;\n" +
            "void main(){ vUV = aUV; gl_Position = uMVP * vec4(aPos, 0.0, 1.0); }\n";

    private static final String FRAG_TEX =
            "precision mediump float;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform float uAlpha;\n" +
            "uniform vec3 uGrade;\n" +
            "varying vec2 vUV;\n" +
            "void main(){ vec4 c = texture2D(uTex, vUV); gl_FragColor = vec4(c.rgb * uGrade, c.a * uAlpha); }\n";

    private static final String FRAG_GLOW =
            "precision mediump float;\n" +
            "uniform vec3 uColor;\n" +
            "uniform float uAlpha;\n" +
            "varying vec2 vUV;\n" +
            "void main(){\n" +
            "  float d = length(vUV - 0.5) * 2.0;\n" +
            "  float a = pow(max(0.0, 1.0 - d), 2.2);\n" +
            "  gl_FragColor = vec4(uColor, a * uAlpha);\n" +
            "}\n";

    private static final String VERT_PTS =
            "uniform mat4 uMVP;\n" +
            "attribute vec2 aPos;\n" +
            "attribute float aSize;\n" +
            "attribute vec4 aCol;\n" +
            "varying vec4 vCol;\n" +
            "void main(){ vCol = aCol; gl_PointSize = aSize; gl_Position = uMVP * vec4(aPos, 0.0, 1.0); }\n";

    private static final String FRAG_PTS =
            "precision mediump float;\n" +
            "varying vec4 vCol;\n" +
            "void main(){\n" +
            "  float d = length(gl_PointCoord - 0.5);\n" +
            "  float a = smoothstep(0.5, 0.05, d);\n" +
            "  gl_FragColor = vec4(vCol.rgb, vCol.a * a);\n" +
            "}\n";

    private static final String FRAG_RING =
            "precision mediump float;\n" +
            "uniform vec3 uColor;\n" +
            "uniform float uR;\n" +
            "uniform float uT;\n" +
            "varying vec2 vUV;\n" +
            "void main(){\n" +
            "  float d = length(vUV - 0.5) * 2.0;\n" +
            "  float ring = 1.0 - smoothstep(0.0, 0.07, abs(d - uR));\n" +
            "  gl_FragColor = vec4(uColor, ring * (1.0 - uT) * 0.9);\n" +
            "}\n";

    private int buildProgram(String vert, String frag) {
        int vs = compile(GL_VERTEX_SHADER, vert);
        int fs = compile(GL_FRAGMENT_SHADER, frag);
        int prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glLinkProgram(prog);
        int[] linked = new int[1];
        glGetProgramiv(prog, GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            throw new RuntimeException("program link failed: " + glGetProgramInfoLog(prog));
        }
        return prog;
    }

    private int compile(int type, String src) {
        int sh = glCreateShader(type);
        glShaderSource(sh, src);
        glCompileShader(sh);
        int[] ok = new int[1];
        glGetShaderiv(sh, GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            throw new RuntimeException("shader compile failed: " + glGetShaderInfoLog(sh));
        }
        return sh;
    }

    private int loadTexture(String assetName) {
        int[] tex = new int[1];
        glGenTextures(1, tex, 0);
        glBindTexture(GL_TEXTURE_2D, tex[0]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        try {
            InputStream is = ctx.getAssets().open(assetName);
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            if (bmp == null) throw new IOException("decode failed: " + assetName);
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0);
            bmp.recycle();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return tex[0];
    }
}
