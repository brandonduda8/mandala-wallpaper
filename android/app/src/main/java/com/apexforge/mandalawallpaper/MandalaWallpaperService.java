package com.apexforge.mandalawallpaper;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.audiofx.Visualizer;
import android.opengl.GLSurfaceView;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

/**
 * Mandala Eclipse Live — a GPU live wallpaper built from the user's mandala art.
 *
 * Engine owns a GLSurfaceView whose SurfaceHolder is swapped for the wallpaper
 * surface (the classic GLWallpaperService pattern). Rendering runs only while
 * the wallpaper is visible; tilt comes from the accelerometer, scroll from
 * home-screen offsets, and taps spawn light rings.
 */
public class MandalaWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new MandalaEngine();
    }

    class MandalaEngine extends Engine {

        private final MandalaRenderer renderer = new MandalaRenderer(MandalaWallpaperService.this);
        private GLView glView;
        private SensorManager sensorManager;
        private Sensor accelerometer;

        // Music-reactive glow: Visualizer on the output mix (session 0).
        // Platform contract requires RECORD_AUDIO for this — a wallpaper has no UI
        // to request the runtime grant, so we attempt it opportunistically and
        // degrade gracefully to the autonomous breath rhythm. Never crashes.
        private Visualizer visualizer;
        private float audioLevel;

        private float tiltX, tiltY;

        private final SensorEventListener tiltListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                // Low-pass the raw accelerometer; map to a -1..1 tilt vector.
                float nx = -e.values[0] / 9.8f;
                float ny = e.values[1] / 9.8f;
                tiltX = tiltX * 0.92f + nx * 0.08f;
                tiltY = tiltY * 0.92f + ny * 0.08f;
                renderer.setTilt(clamp(tiltX), clamp(tiltY));
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }

            private float clamp(float v) {
                return v < -1f ? -1f : (v > 1f ? 1f : v);
            }
        };

        /** GLSurfaceView that draws into the wallpaper's own SurfaceHolder. */
        class GLView extends GLSurfaceView {
            GLView(Context context) {
                super(context);
            }

            @Override
            public SurfaceHolder getHolder() {
                return MandalaEngine.this.getSurfaceHolder();
            }
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setTouchEventsEnabled(true);
            setOffsetNotificationsEnabled(true);
            glView = new GLView(MandalaWallpaperService.this);
            glView.setEGLContextClientVersion(2);
            glView.setRenderer(renderer);
            glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                renderer.burstFromCore(); // unlock burst: one slow ring from the eclipse
                glView.onResume();
                startVisualizer();
                if (sensorManager != null && accelerometer != null) {
                    sensorManager.registerListener(tiltListener, accelerometer,
                            SensorManager.SENSOR_DELAY_UI);
                }
            } else {
                glView.onPause();
                stopVisualizer();
                if (sensorManager != null) {
                    sensorManager.unregisterListener(tiltListener);
                }
            }
        }

        private void startVisualizer() {
            stopVisualizer();
            audioLevel = 0f;
            try {
                Visualizer v = new Visualizer(0); // output mix
                int capSize = Visualizer.getCaptureSizeRange()[1];
                v.setCaptureSize(capSize);
                int rate = Visualizer.getMaxCaptureRate() / 2;
                v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                    @Override
                    public void onWaveFormDataCapture(Visualizer vis, byte[] waveform, int samplingRate) {
                        float sum = 0f;
                        for (byte b : waveform) {
                            float d = (b & 0xFF) - 128f;
                            sum += d * d;
                        }
                        float e = (float) Math.sqrt(sum / waveform.length) / 128f;
                        if (e > 1f) e = 1f;
                        audioLevel = audioLevel * 0.7f + e * 0.3f; // smooth the energy
                        renderer.setAudioEnergy(audioLevel);
                    }

                    @Override
                    public void onFftDataCapture(Visualizer vis, byte[] fft, int samplingRate) {
                    }
                }, rate, true, false);
                v.setEnabled(true);
                visualizer = v;
            } catch (Exception ignored) {
                // Expected on devices without the RECORD_AUDIO grant (or no audio
                // stack, e.g. emulator): renderer keeps its autonomous breath.
                visualizer = null;
                renderer.setAudioEnergy(-1f);
            }
        }

        private void stopVisualizer() {
            if (visualizer != null) {
                try {
                    visualizer.setEnabled(false);
                    visualizer.release();
                } catch (Exception ignored) {
                }
                visualizer = null;
            }
            renderer.setAudioEnergy(-1f);
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset,
                                      float xOffsetStep, float yOffsetStep,
                                      int xPixelOffset, int yPixelOffset) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep,
                    xPixelOffset, yPixelOffset);
            renderer.setScroll(xOffset);
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            super.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                renderer.addRing(event.getX(), event.getY());
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            glView.onPause();
            stopVisualizer();
            if (sensorManager != null) {
                sensorManager.unregisterListener(tiltListener);
            }
        }
    }
}
