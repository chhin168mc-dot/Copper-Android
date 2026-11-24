package git.artdeell.mojo.space;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Random;

/**
 * Animated space backdrop: a deep gradient, two soft nebulae, parallax stars that twinkle and drift,
 * and a shooting star every few seconds. {@link #setWarp} stretches the stars away from the center,
 * which the intro animation uses to fly into the launcher.
 */
public class SpaceBackgroundView extends View {
    private static final int STAR_COUNT = 150;
    private static final int[] STAR_TINTS = {0xFFFFFFFF, 0xFFBBD7FF, 0xFFFFE9C4};

    private final float[] mX = new float[STAR_COUNT];
    private final float[] mY = new float[STAR_COUNT];
    private final float[] mDepth = new float[STAR_COUNT];
    private final float[] mPhase = new float[STAR_COUNT];

    private final Paint mBgPaint = new Paint();
    private final Paint mNebulaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMeteorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private RadialGradient mNebulaA, mNebulaB;
    private float mNebulaAX, mNebulaAY, mNebulaAR, mNebulaBX, mNebulaBY, mNebulaBR;

    private final Random mRandom = new Random(42);
    private final float mDensity;
    private float mWarp;
    private boolean mActive = true;
    private boolean mAttached;
    private long mLastFrame;
    private float mTime;

    private float mMeteorProgress = -1f;
    private float mMeteorX, mMeteorY, mMeteorDx, mMeteorDy;
    private float mNextMeteorAt = 3f;

    public SpaceBackgroundView(Context context) {
        this(context, null);
    }

    public SpaceBackgroundView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        for (int i = 0; i < STAR_COUNT; i++) {
            mX[i] = mRandom.nextFloat();
            mY[i] = mRandom.nextFloat();
            mDepth[i] = 0.2f + mRandom.nextFloat() * 0.8f;
            mPhase[i] = mRandom.nextFloat() * 6.2832f;
        }
        mStarPaint.setStrokeCap(Paint.Cap.ROUND);
        mMeteorPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** @param warp 0 is calm space, 1 is the stars streaking away from the center */
    public void setWarp(float warp) {
        mWarp = Math.max(0f, Math.min(1f, warp));
        invalidate();
    }

    /** Stops drawing while the home screen is covered by another screen */
    public void setActive(boolean active) {
        if (mActive == active) return;
        mActive = active;
        if (active) {
            mLastFrame = 0;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBgPaint.setShader(new LinearGradient(0, 0, w * 0.4f, h,
                new int[]{0xFF02030A, 0xFF0A1030, 0xFF160C2E},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        float big = Math.max(w, h);
        mNebulaAX = w * 0.18f; mNebulaAY = h * 0.78f; mNebulaAR = big * 0.55f;
        mNebulaA = new RadialGradient(mNebulaAX, mNebulaAY, mNebulaAR,
                new int[]{0x66532A9E, 0x00532A9E}, null, Shader.TileMode.CLAMP);
        mNebulaBX = w * 0.86f; mNebulaBY = h * 0.22f; mNebulaBR = big * 0.5f;
        mNebulaB = new RadialGradient(mNebulaBX, mNebulaBY, mNebulaBR,
                new int[]{0x5520609E, 0x0020609E}, null, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttached = true;
        mLastFrame = 0;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        mAttached = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = mLastFrame == 0 ? 0f : Math.min(0.05f, (now - mLastFrame) / 1000f);
        mLastFrame = now;
        mTime += dt;

        canvas.drawRect(0, 0, w, h, mBgPaint);
        float pulse = 0.85f + 0.15f * (float) Math.sin(mTime * 0.4f);
        mNebulaPaint.setShader(mNebulaA);
        mNebulaPaint.setAlpha((int) (255 * pulse));
        canvas.drawCircle(mNebulaAX, mNebulaAY, mNebulaAR, mNebulaPaint);
        mNebulaPaint.setShader(mNebulaB);
        mNebulaPaint.setAlpha((int) (255 * Math.min(1f, 1.7f - pulse)));
        canvas.drawCircle(mNebulaBX, mNebulaBY, mNebulaBR, mNebulaPaint);

        float cx = w / 2f, cy = h / 2f;
        float streak = mWarp * 0.45f;
        for (int i = 0; i < STAR_COUNT; i++) {
            mX[i] -= dt * 0.012f * mDepth[i];
            if (mX[i] < 0f) mX[i] += 1f;
            float px = mX[i] * w, py = mY[i] * h;
            float twinkle = 0.5f + 0.5f * (float) Math.sin(mTime * (1.2f + mDepth[i]) + mPhase[i]);
            int alpha = (int) (110 + 145 * twinkle * mDepth[i] + 30 * mWarp);
            mStarPaint.setColor(STAR_TINTS[i % STAR_TINTS.length]);
            mStarPaint.setAlpha(Math.min(255, alpha));
            float size = (0.6f + mDepth[i] * 1.5f) * mDensity;
            if (streak > 0.01f) {
                mStarPaint.setStrokeWidth(size);
                float k = streak * mDepth[i];
                canvas.drawLine(px, py, px + (px - cx) * k, py + (py - cy) * k, mStarPaint);
            } else {
                canvas.drawCircle(px, py, size, mStarPaint);
            }
        }

        updateMeteor(canvas, w, h, dt);
        if (mActive && mAttached) postInvalidateOnAnimation();
    }

    private void updateMeteor(Canvas canvas, int w, int h, float dt) {
        if (mMeteorProgress < 0f) {
            mNextMeteorAt -= dt;
            if (mNextMeteorAt <= 0f) {
                mMeteorProgress = 0f;
                mMeteorX = w * (0.3f + mRandom.nextFloat() * 0.7f);
                mMeteorY = h * mRandom.nextFloat() * 0.5f;
                mMeteorDx = -w * 0.35f;
                mMeteorDy = h * 0.3f;
                mNextMeteorAt = 4f + mRandom.nextFloat() * 6f;
            }
            return;
        }
        mMeteorProgress += dt / 0.9f;
        if (mMeteorProgress >= 1f) {
            mMeteorProgress = -1f;
            return;
        }
        float headX = mMeteorX + mMeteorDx * mMeteorProgress;
        float headY = mMeteorY + mMeteorDy * mMeteorProgress;
        float fade = 1f - mMeteorProgress;
        mMeteorPaint.setStrokeWidth(1.6f * mDensity);
        for (int s = 0; s < 6; s++) {
            float t0 = s / 6f, t1 = (s + 1) / 6f;
            float tail = 0.22f;
            mMeteorPaint.setColor(0xFFFFFFFF);
            mMeteorPaint.setAlpha((int) (230 * fade * (1f - t0)));
            canvas.drawLine(headX - mMeteorDx * tail * t0, headY - mMeteorDy * tail * t0,
                    headX - mMeteorDx * tail * t1, headY - mMeteorDy * tail * t1, mMeteorPaint);
        }
    }
}
