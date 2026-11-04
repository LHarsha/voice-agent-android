package com.voiceagent;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A glowing, breathing orb drawn with layered radial gradients (no images needed).
 * It reacts to the microphone level while listening, spins while thinking and
 * pulses while speaking.
 */
public class OrbView extends View {

    public enum State { IDLE, LISTENING, THINKING, SPEAKING }

    private static final int[] COLORS = {
            Color.rgb(51, 209, 255),   // cyan
            Color.rgb(201, 75, 255),   // magenta
            Color.rgb(124, 92, 255),   // violet
            Color.rgb(255, 92, 168),   // pink
    };

    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator clock;

    private State state = State.IDLE;
    private final long startNanos = System.nanoTime();
    private float phase;          // seconds since start
    private float targetLevel;    // 0..1 from the microphone
    private float level;          // smoothed level

    public OrbView(Context context) {
        super(context);
        blobPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(1000);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> {
            phase = (System.nanoTime() - startNanos) / 1e9f;
            level += (targetLevel - level) * 0.25f;
            if (state != State.LISTENING) targetLevel *= 0.9f;
            invalidate();
        });
    }

    public void setState(State s) {
        state = s;
        if (s != State.LISTENING) targetLevel = 0f;
        invalidate();
    }

    public State getState() {
        return state;
    }

    /** Feed the speech recognizer RMS value (roughly -2..10 dB). */
    public void setRms(float rmsDb) {
        float v = (rmsDb + 2f) / 12f;
        targetLevel = Math.max(0f, Math.min(1f, v));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        clock.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        clock.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float base = Math.min(w, h) / 2f;

        float energy;
        float speed;
        switch (state) {
            case LISTENING:
                energy = 0.12f + level * 0.55f;
                speed = 1.6f;
                break;
            case THINKING:
                energy = 0.10f;
                speed = 4.0f;
                break;
            case SPEAKING:
                energy = 0.12f + 0.10f * (float) Math.abs(Math.sin(phase * 7.0));
                speed = 2.2f;
                break;
            default:
                energy = 0.05f + 0.03f * (float) Math.sin(phase * 1.5);
                speed = 0.6f;
        }

        int save = canvas.saveLayer(0, 0, w, h, null);
        for (int i = 0; i < COLORS.length; i++) {
            double angle = phase * speed * (0.6 + i * 0.25) + i * Math.PI / 2;
            float wobble = base * (0.10f + energy * 0.45f);
            float bx = cx + (float) Math.cos(angle) * wobble;
            float by = cy + (float) Math.sin(angle * 1.3) * wobble;
            float r = base * (0.55f + energy * 0.5f + 0.04f * (float) Math.sin(phase * 2 + i));
            int c = COLORS[i];
            blobPaint.setShader(new RadialGradient(bx, by, Math.max(1f, r),
                    new int[]{c, Color.argb(120, Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(bx, by, r, blobPaint);
        }
        canvas.restoreToCount(save);

        float coreR = base * (0.22f + energy * 0.25f);
        corePaint.setShader(new RadialGradient(cx, cy, Math.max(1f, coreR),
                new int[]{Color.argb(230, 255, 255, 255), Color.argb(0, 255, 255, 255)},
                null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, coreR, corePaint);
    }
}
