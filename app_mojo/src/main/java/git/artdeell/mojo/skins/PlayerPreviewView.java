package git.artdeell.mojo.skins;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A small software 3D renderer that shows a Minecraft player with a skin and (optionally) a cape.
 * Every body part is a textured box, drawn with an orthographic camera that can be rotated
 * by dragging. It auto-rotates while idle.
 *
 * Every face is rasterized into a frame buffer with a depth buffer, so what is in front is decided
 * per pixel. Sorting whole parts by their center (what this view did before) made the cape show
 * through the skin, and the skin through the cape, from some angles.
 */
public class PlayerPreviewView extends View {
    // Face order used everywhere in this class
    private static final int F_FRONT = 0;  // +z
    private static final int F_BACK = 1;   // -z
    private static final int F_RIGHT = 2;  // -x, the right side of the player
    private static final int F_LEFT = 3;   // +x, the left side of the player
    private static final int F_TOP = 4;    // +y
    private static final int F_BOTTOM = 5; // -y

    /** The cape hangs almost straight down, only the lower edge swings a little away from the back */
    private static final float CAPE_TILT_DEGREES = 3f;
    private static final float RENDER_PIXELS_PER_UNIT = 8f;
    private static final float EDGE_EPSILON = 1e-3f;
    private static final float AUTO_ROTATE_DEGREES_PER_SECOND = 28f;
    private static final float MODEL_CENTER_Y = 16f;
    private static final float MODEL_FIT_HEIGHT = 36f;
    private static final float MODEL_FIT_WIDTH = 24f;

    /** One textured box (plus, for body parts, its outer layer) */
    private static final class Part {
        final boolean cape;
        final float[][] baseFaces = new float[6][12];
        final float[][] overlayFaces = new float[6][12];
        final Rect[] baseRects = new Rect[6];
        final Rect[] overlayRects = new Rect[6];
        boolean hasOverlay;
        Part(boolean cape) { this.cape = cape; }
    }

    private final List<Part> mParts = new ArrayList<>();

    private Bitmap mSkin;
    private boolean mSlim;
    private Bitmap mCape;
    private boolean mModelDirty = true;

    private float mYaw = 180f; // Show the back by default so the selected cape is immediately visible
    private float mPitch = 8f;
    private float mLastTouchX, mLastTouchY;
    private float mDownX, mDownY;
    private boolean mDragging;
    private long mLastFrameTime;

    private final Paint mPaint = new Paint();
    private final RectF mDst = new RectF();
    private final float[] mView = new float[12];

    // Textures as plain pixels, read once per model rebuild
    private int[] mSkinPixels;
    private int mSkinWidth;
    private int[] mCapePixels;
    private int mCapeWidth;

    // Frame buffer: the colors, the depth of every pixel (bigger = closer to the camera) and the bitmap shown
    private Bitmap mFrame;
    private int[] mColors;
    private float[] mDepths;
    private int mFrameWidth, mFrameHeight;

    public PlayerPreviewView(Context context) {
        super(context);
        init();
    }

    public PlayerPreviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PlayerPreviewView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mPaint.setAntiAlias(false);
        mPaint.setFilterBitmap(false); // keep the pixels crisp
        mPaint.setDither(false);
    }

    /** @param skin any skin bitmap, it gets normalized to the 64x64 layout; null clears the player */
    public void setSkin(@Nullable Bitmap skin, boolean slim) {
        mSkin = skin == null ? null : SkinTextures.normalizeSkin(skin);
        mSlim = slim;
        mModelDirty = true;
        invalidate();
    }

    /** Changes only the arm model of the current skin */
    public void setSlim(boolean slim) {
        if (mSlim == slim) return;
        mSlim = slim;
        mModelDirty = true;
        invalidate();
    }

    public void setCape(@Nullable Bitmap cape) {
        mCape = cape == null ? null : SkinTextures.normalizeCape(cape);
        mModelDirty = true;
        invalidate();
    }

    // ------------------------------------------------------------------ model

    private void rebuildModel() {
        mModelDirty = false;
        mParts.clear();
        mSkinPixels = null;
        mCapePixels = null;
        if (mSkin == null) return;
        mSkinWidth = mSkin.getWidth();
        mSkinPixels = new int[mSkinWidth * mSkin.getHeight()];
        mSkin.getPixels(mSkinPixels, 0, mSkinWidth, 0, 0, mSkinWidth, mSkin.getHeight());
        if (mCape != null) {
            mCapeWidth = mCape.getWidth();
            mCapePixels = new int[mCapeWidth * mCape.getHeight()];
            mCape.getPixels(mCapePixels, 0, mCapeWidth, 0, 0, mCapeWidth, mCape.getHeight());
        }
        int s = mSkin.getWidth() / 64;
        float armWidth = mSlim ? 3 : 4;

        //                x               y   z   w         h   d  u   v   ou  ov  inflate
        addBody(s, -4,            24, -4, 8,        8,  8,  0,  0, 32,  0, 0.5f);  // head
        addBody(s, -4,            12, -2, 8,        12, 4, 16, 16, 16, 32, 0.25f); // body
        addBody(s, -4 - armWidth, 12, -2, armWidth, 12, 4, 40, 16, 40, 32, 0.25f); // right arm
        addBody(s,  4,            12, -2, armWidth, 12, 4, 32, 48, 48, 48, 0.25f); // left arm
        addBody(s, -4,            0,  -2, 4,        12, 4,  0, 16,  0, 32, 0.25f); // right leg
        addBody(s,  0,            0,  -2, 4,        12, 4, 16, 48,  0, 48, 0.25f); // left leg

        if (mCape != null) addCape(mCape.getWidth() / 64);
    }

    private void addBody(int s, float x, float y, float z, float w, float h, float d,
                         int u, int v, int ou, int ov, float inflate) {
        Part part = new Part(false);
        fillFaces(part.baseFaces, x, y, z, w, h, d, 0);
        fillRects(part.baseRects, s, u, v, (int) w, (int) h, (int) d, false);
        part.hasOverlay = true;
        fillFaces(part.overlayFaces, x, y, z, w, h, d, inflate);
        fillRects(part.overlayRects, s, ou, ov, (int) w, (int) h, (int) d, false);
        mParts.add(part);
    }

    private void addCape(int s) {
        Part part = new Part(true);
        // The outer layer of the torso, arms and legs ends at z=-2.25 (their back is z=-2, plus 0.25).
        // The cape starts 0.03 behind it, so it is close to the skin and still not inside it.
        // Its top stays at y=23.4, just under the outer layer of the head (y=23.5), for the same reason.
        // The lower edge only swings backwards, away from the body, so the two never cross.
        float x = -5, y = 7.4f, z = -2.78f, w = 10, h = 16, d = 0.5f;
        fillFaces(part.baseFaces, x, y, z, w, h, d, 0);
        fillRects(part.baseRects, s, 0, 0, 10, 16, 1, true);
        // The cape hangs from the top of its inner surface (the surface that touches the back)
        tilt(part.baseFaces, y + h, z + d, (float) Math.toRadians(CAPE_TILT_DEGREES));
        mParts.add(part);
    }

    private static void tilt(float[][] faces, float pivotY, float pivotZ, float angle) {
        for (float[] face : faces) {
            for (int i = 0; i < 12; i += 3) {
                float[] p = {face[i], face[i + 1], face[i + 2]};
                tiltPoint(p, pivotY, pivotZ, angle);
                face[i] = p[0]; face[i + 1] = p[1]; face[i + 2] = p[2];
            }
        }
    }

    /** Rotates around the X axis so that points below the pivot swing backwards (-z) */
    private static void tiltPoint(float[] p, float pivotY, float pivotZ, float angle) {
        float dy = p[1] - pivotY, dz = p[2] - pivotZ;
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        p[1] = pivotY + dy * cos - dz * sin;
        p[2] = pivotZ + dy * sin + dz * cos;
    }

    /**
     * Writes the 4 corners (top-left, top-right, bottom-left, bottom-right, as seen from outside
     * the box) of each face. Each corner is x,y,z.
     */
    private static void fillFaces(float[][] out, float x, float y, float z, float w, float h, float d, float e) {
        float x0 = x - e, x1 = x + w + e;
        float y0 = y - e, y1 = y + h + e;
        float z0 = z - e, z1 = z + d + e;
        set(out[F_FRONT],  x0, y1, z1,  x1, y1, z1,  x0, y0, z1,  x1, y0, z1);
        set(out[F_BACK],   x1, y1, z0,  x0, y1, z0,  x1, y0, z0,  x0, y0, z0);
        set(out[F_RIGHT],  x0, y1, z0,  x0, y1, z1,  x0, y0, z0,  x0, y0, z1);
        set(out[F_LEFT],   x1, y1, z1,  x1, y1, z0,  x1, y0, z1,  x1, y0, z0);
        set(out[F_TOP],    x0, y1, z0,  x1, y1, z0,  x0, y1, z1,  x1, y1, z1);
        set(out[F_BOTTOM], x0, y0, z1,  x1, y0, z1,  x0, y0, z0,  x1, y0, z0);
    }

    private static void set(float[] dst, float... values) {
        System.arraycopy(values, 0, dst, 0, 12);
    }

    /** Texture rectangle of each face of a box whose layout starts at (u, v) */
    private static void fillRects(Rect[] out, int s, int u, int v, int w, int h, int d, boolean cape) {
        Rect front = rect(s, u + d, v + d, w, h);
        Rect back = rect(s, u + d + w + d, v + d, w, h);
        Rect right = rect(s, u, v + d, d, h);
        Rect left = rect(s, u + d + w, v + d, d, h);
        if (cape) {
            // The outside of a cape faces backwards, so front/back and left/right trade places
            out[F_FRONT] = back; out[F_BACK] = front;
            out[F_RIGHT] = left; out[F_LEFT] = right;
        } else {
            out[F_FRONT] = front; out[F_BACK] = back;
            out[F_RIGHT] = right; out[F_LEFT] = left;
        }
        out[F_TOP] = rect(s, u + d, v, w, d);
        out[F_BOTTOM] = rect(s, u + d + w, v, w, d);
    }

    private static Rect rect(int s, int x, int y, int w, int h) {
        return new Rect(x * s, y * s, (x + w) * s, (y + h) * s);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (mModelDirty) rebuildModel();
        if (mSkin == null || mSkinPixels == null || mParts.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return;

        long now = SystemClock.uptimeMillis();
        if (mLastFrameTime != 0 && !mDragging) {
            float dt = Math.min(now - mLastFrameTime, 100) / 1000f;
            mYaw = (mYaw + AUTO_ROTATE_DEGREES_PER_SECOND * dt) % 360f;
        }
        mLastFrameTime = now;

        float yawRad = (float) Math.toRadians(mYaw);
        float pitchRad = (float) Math.toRadians(mPitch);
        float cy = (float) Math.cos(yawRad), sy = (float) Math.sin(yawRad);
        float cp = (float) Math.cos(pitchRad), sp = (float) Math.sin(pitchRad);

        float scale = Math.min(getWidth() / MODEL_FIT_WIDTH, getHeight() / MODEL_FIT_HEIGHT);
        // The frame buffer is drawn at a whole number of screen pixels per buffer pixel,
        // so the texture pixels stay crisp and the work per frame stays small
        int factor = Math.max(1, Math.round(scale / RENDER_PIXELS_PER_UNIT));
        prepareFrame((getWidth() + factor - 1) / factor, (getHeight() + factor - 1) / factor);
        Arrays.fill(mColors, 0);
        Arrays.fill(mDepths, -Float.MAX_VALUE);

        float bufferScale = scale / factor;
        // The frame buffer can be a little larger than the view (when the size is not a multiple of
        // the factor): the player is centered on the view, not on the buffer
        float originX = getWidth() / 2f / factor;
        float originY = getHeight() / 2f / factor;

        for (Part part : mParts) {
            int[] pixels = part.cape ? mCapePixels : mSkinPixels;
            int textureWidth = part.cape ? mCapeWidth : mSkinWidth;
            if (pixels == null) continue;
            drawFaces(pixels, textureWidth, part.baseFaces, part.baseRects, cy, sy, cp, sp, bufferScale, originX, originY);
            if (part.hasOverlay) {
                drawFaces(pixels, textureWidth, part.overlayFaces, part.overlayRects, cy, sy, cp, sp, bufferScale, originX, originY);
            }
        }

        mFrame.setPixels(mColors, 0, mFrameWidth, 0, 0, mFrameWidth, mFrameHeight);
        mDst.set(0, 0, mFrameWidth * factor, mFrameHeight * factor);
        canvas.drawBitmap(mFrame, null, mDst, mPaint);
        postInvalidateOnAnimation();
    }

    /** Makes the frame buffer the wanted size, it is reused from frame to frame */
    private void prepareFrame(int width, int height) {
        if (mFrame != null && mFrameWidth == width && mFrameHeight == height) return;
        if (mFrame != null) mFrame.recycle();
        mFrameWidth = width;
        mFrameHeight = height;
        mFrame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        mColors = new int[width * height];
        mDepths = new float[width * height];
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mFrame != null) {
            mFrame.recycle();
            mFrame = null;
            mFrameWidth = 0;
            mFrameHeight = 0;
        }
    }

    /** Projects model space to screen space; result in out[offset..offset+2] = screenX, screenY, depth (bigger = closer) */
    private static void project(float x, float y, float z, float cy, float sy, float cp, float sp,
                                float scale, float originX, float originY, float[] out, int offset) {
        float y0 = y - MODEL_CENTER_Y;
        float x1 = x * cy + z * sy;
        float z1 = -x * sy + z * cy;
        float y2 = y0 * cp - z1 * sp;
        float z2 = y0 * sp + z1 * cp;
        out[offset] = originX + x1 * scale;
        out[offset + 1] = originY - y2 * scale;
        out[offset + 2] = z2;
    }

    private void drawFaces(int[] texture, int textureWidth, float[][] faces, Rect[] rects,
                           float cy, float sy, float cp, float sp,
                           float scale, float originX, float originY) {
        for (int f = 0; f < 6; f++) {
            float[] c = faces[f];
            for (int i = 0; i < 4; i++) {
                project(c[i * 3], c[i * 3 + 1], c[i * 3 + 2], cy, sy, cp, sp,
                        scale, originX, originY, mView, i * 3);
            }
            rasterizeFace(texture, textureWidth, rects[f]);
        }
    }

    /**
     * Draws the face whose projected corners (top-left, top-right, bottom-left, bottom-right) are in mView.
     * With an orthographic camera a rectangle is always a parallelogram on the screen, so the texture
     * position and the depth of a pixel follow from two coordinates (a, b) along its two edges.
     */
    private void rasterizeFace(int[] texture, int textureWidth, Rect src) {
        final float[] v = mView;
        float p0x = v[0], p0y = v[1];
        float e1x = v[3] - p0x, e1y = v[4] - p0y; // top-left -> top-right
        float e2x = v[6] - p0x, e2y = v[7] - p0y; // top-left -> bottom-left
        float det = e1x * e2y - e1y * e2x;
        // Back-face culling: the sign tells whether the outside of the face points to the camera
        if (det <= 0.05f) return;
        float invDet = 1f / det;

        float minX = Math.min(Math.min(v[0], v[3]), Math.min(v[6], v[9]));
        float maxX = Math.max(Math.max(v[0], v[3]), Math.max(v[6], v[9]));
        float minY = Math.min(Math.min(v[1], v[4]), Math.min(v[7], v[10]));
        float maxY = Math.max(Math.max(v[1], v[4]), Math.max(v[7], v[10]));
        int x0 = Math.max(0, (int) Math.floor(minX));
        int x1 = Math.min(mFrameWidth - 1, (int) Math.ceil(maxX));
        int y0 = Math.max(0, (int) Math.floor(minY));
        int y1 = Math.min(mFrameHeight - 1, (int) Math.ceil(maxY));

        float z0 = v[2];
        float dz1 = v[5] - z0; // depth change along the first edge
        float dz2 = v[8] - z0; // depth change along the second edge
        int sw = src.width(), sh = src.height();
        final float maxFraction = 0.999999f;

        for (int y = y0; y <= y1; y++) {
            float py = y + 0.5f - p0y;
            int row = y * mFrameWidth;
            for (int x = x0; x <= x1; x++) {
                float px = x + 0.5f - p0x;
                float a = (px * e2y - py * e2x) * invDet;
                float b = (e1x * py - e1y * px) * invDet;
                if (a < -EDGE_EPSILON || a >= 1f + EDGE_EPSILON || b < -EDGE_EPSILON || b >= 1f + EDGE_EPSILON) continue;
                int index = row + x;
                float depth = z0 + a * dz1 + b * dz2;
                if (depth <= mDepths[index]) continue;

                if (a < 0f) a = 0f; else if (a > maxFraction) a = maxFraction;
                if (b < 0f) b = 0f; else if (b > maxFraction) b = maxFraction;
                int color = texture[(src.top + (int) (b * sh)) * textureWidth + src.left + (int) (a * sw)];
                int alpha = color >>> 24;
                if (alpha == 0) continue; // transparent texels (outer layers) show what is behind them
                if (alpha == 255) {
                    mColors[index] = color;
                    mDepths[index] = depth;
                } else {
                    int behind = mColors[index];
                    if ((behind >>> 24) == 0) {
                        mColors[index] = color;
                    } else {
                        // A semi-transparent texel is mixed with what is already there (it writes no depth)
                        int inv = 255 - alpha;
                        int r = (((color >> 16) & 0xFF) * alpha + ((behind >> 16) & 0xFF) * inv) / 255;
                        int g = (((color >> 8) & 0xFF) * alpha + ((behind >> 8) & 0xFF) * inv) / 255;
                        int bl = ((color & 0xFF) * alpha + (behind & 0xFF) * inv) / 255;
                        mColors[index] = 0xFF000000 | (r << 16) | (g << 8) | bl;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                mDragging = true;
                mLastTouchX = event.getX();
                mLastTouchY = event.getY();
                mDownX = mLastTouchX;
                mDownY = mLastTouchY;
                return true;
            case MotionEvent.ACTION_MOVE:
                mYaw += (event.getX() - mLastTouchX) * 0.6f;
                mPitch = Math.max(-30f, Math.min(45f, mPitch + (event.getY() - mLastTouchY) * 0.3f));
                mLastTouchX = event.getX();
                mLastTouchY = event.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // A touch that stayed in place is a tap, a drag only turns the player
                if (event.getActionMasked() == MotionEvent.ACTION_UP && hasOnClickListeners()) {
                    float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.abs(event.getX() - mDownX) < slop && Math.abs(event.getY() - mDownY) < slop) performClick();
                }
                mDragging = false;
                mLastFrameTime = SystemClock.uptimeMillis();
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }
}
