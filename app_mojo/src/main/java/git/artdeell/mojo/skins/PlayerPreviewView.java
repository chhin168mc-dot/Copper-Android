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

    /** Like in the game the cape hangs close to the back, only its lower edge swings a little away */
    private static final float CAPE_TILT_DEGREES = 4f;
    /** The frame buffer only covers the player. Above this many pixels it is drawn at a lower resolution */
    private static final float MAX_BUFFER_PIXELS = 360_000f;
    private static final float EDGE_EPSILON = 1e-3f;
    /** Pixels this close in depth count as the same depth, the face drawn last wins (no flickering) */
    private static final float DEPTH_BIAS = 1e-3f;
    /** Soft light that comes from the camera side, so the sides of the player and the cape are told apart */
    private static final float LIGHT_X = 0.25f, LIGHT_Y = 0.45f, LIGHT_Z = 0.86f;
    private static final float LIGHT_AMBIENT = 0.58f, LIGHT_DIFFUSE = 0.49f;
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
    private float mLastCapeYaw = Float.NaN;
    private float mLastCapePitch = Float.NaN;
    private float mCapeSwingDegrees = CAPE_TILT_DEGREES;

    private final Paint mPaint = new Paint();
    private final RectF mDst = new RectF();
    private final float[] mView = new float[12];
    private final float[][] mAnimatedCapeFaces = new float[6][12];

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
        // Same size as the cape of the game: 10 wide, 16 tall and 1 thick.
        // The outer layer of the torso, arms and legs ends at z=-2.25 (their back is z=-2, plus 0.25).
        // The cape starts 0.03 behind it, so it touches the back without being inside the skin.
        // Its top is at y=23.5, the bottom of the outer layer of the head, for the same reason.
        float x = -5, y = 7.5f, z = -3.28f, w = 10, h = 16, d = 1f;
        fillFaces(part.baseFaces, x, y, z, w, h, d, 0);
        fillRects(part.baseRects, s, 0, 0, 10, 16, 1, true);
        // Keep a neutral pose; the cape is animated around its top hinge every frame.
        mParts.add(part);
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
        float dt = mLastFrameTime == 0 ? 1f / 60f : Math.min(now - mLastFrameTime, 50) / 1000f;
        if (!mDragging) {
            mYaw = (mYaw + AUTO_ROTATE_DEGREES_PER_SECOND * dt) % 360f;
        }

        // The cape has inertia: it trails behind turns and gently flutters while idle.
        float yawDelta = 0f, pitchDelta = 0f;
        if (!Float.isNaN(mLastCapeYaw)) {
            yawDelta = mYaw - mLastCapeYaw;
            if (yawDelta > 180f) yawDelta -= 360f;
            if (yawDelta < -180f) yawDelta += 360f;
            pitchDelta = mPitch - mLastCapePitch;
        }
        mLastCapeYaw = mYaw;
        mLastCapePitch = mPitch;
        if (mCape != null) {
            float turnLag = Math.max(-18f, Math.min(18f, -yawDelta * 0.75f));
            float pitchLag = Math.max(-7f, Math.min(7f, -pitchDelta * 0.55f));
            float flutter = (float) Math.sin(now * 0.0032f) * 2.2f;
            float target = CAPE_TILT_DEGREES + turnLag + pitchLag + flutter;
            mCapeSwingDegrees += (target - mCapeSwingDegrees) * Math.min(1f, dt * 9f);
        } else {
            mCapeSwingDegrees = CAPE_TILT_DEGREES;
        }
        mLastFrameTime = now;

        float yawRad = (float) Math.toRadians(mYaw);
        float pitchRad = (float) Math.toRadians(mPitch);
        float cy = (float) Math.cos(yawRad), sy = (float) Math.sin(yawRad);
        float cp = (float) Math.cos(pitchRad), sp = (float) Math.sin(pitchRad);

        float scale = Math.min(getWidth() / MODEL_FIT_WIDTH, getHeight() / MODEL_FIT_HEIGHT);
        // One buffer pixel is one screen pixel, so the edges stay as sharp as they can be.
        // Only a very big view is drawn at a lower resolution (a whole number of screen pixels per
        // buffer pixel, so the texture pixels stay crisp) to keep the work per frame small.
        int factor = 1;
        while ((MODEL_FIT_WIDTH * scale / factor) * (MODEL_FIT_HEIGHT * scale / factor) > MAX_BUFFER_PIXELS) factor++;
        int width = (int) Math.ceil(MODEL_FIT_WIDTH * scale / factor) + 2;
        int height = (int) Math.ceil(MODEL_FIT_HEIGHT * scale / factor) + 2;
        prepareFrame(width, height);
        Arrays.fill(mColors, 0);
        Arrays.fill(mDepths, -Float.MAX_VALUE);

        float bufferScale = scale / factor;
        float originX = width / 2f;
        float originY = height / 2f;

        for (Part part : mParts) {
            int[] pixels = part.cape ? mCapePixels : mSkinPixels;
            int textureWidth = part.cape ? mCapeWidth : mSkinWidth;
            if (pixels == null) continue;
            if (part.cape) {
                drawAnimatedCape(part, cy, sy, cp, sp, bufferScale, originX, originY);
            } else {
                drawFaces(pixels, textureWidth, part.baseFaces, part.baseRects, cy, sy, cp, sp, bufferScale, originX, originY);
            }
            if (part.hasOverlay) {
                drawFaces(pixels, textureWidth, part.overlayFaces, part.overlayRects, cy, sy, cp, sp, bufferScale, originX, originY);
            }
        }

        mFrame.setPixels(mColors, 0, width, 0, 0, width, height);
        // The buffer covers the player only, it is centered on the view
        int left = Math.round((getWidth() - width * factor) / 2f);
        int top = Math.round((getHeight() - height * factor) / 2f);
        mDst.set(left, top, left + width * factor, top + height * factor);
        canvas.drawBitmap(mFrame, null, mDst, mPaint);
        postInvalidateOnAnimation();
    }

    /** Draws the cape after rotating it around the shoulder-level hinge. */
    private void drawAnimatedCape(Part part, float cy, float sy, float cp, float sp,
                                  float scale, float originX, float originY) {
        float[][] animated = mAnimatedCapeFaces;
        float pivotY = 23.5f;
        float pivotZ = -2.28f;
        float angle = (float) Math.toRadians(mCapeSwingDegrees);
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        for (int face = 0; face < 6; face++) {
            System.arraycopy(part.baseFaces[face], 0, animated[face], 0, 12);
            float[] vertices = animated[face];
            for (int i = 0; i < 12; i += 3) {
                float dy = vertices[i + 1] - pivotY;
                float dz = vertices[i + 2] - pivotZ;
                vertices[i + 1] = pivotY + dy * cos - dz * sin;
                vertices[i + 2] = pivotZ + dy * sin + dz * cos;
            }
        }
        drawFaces(mCapePixels, mCapeWidth, animated, part.baseRects,
                cy, sy, cp, sp, scale, originX, originY);
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
            rasterizeFace(texture, textureWidth, rects[f], shadeOf(c, cy, sy, cp, sp));
        }
    }

    /** How bright a face is, 0..256 (256 = the texture as it is). The light comes from the camera side. */
    private static int shadeOf(float[] c, float cy, float sy, float cp, float sp) {
        // Outward normal of the face = (bottom-left - top-left) x (top-right - top-left)
        float e1x = c[3] - c[0], e1y = c[4] - c[1], e1z = c[5] - c[2];
        float e2x = c[6] - c[0], e2y = c[7] - c[1], e2z = c[8] - c[2];
        float nx = e2y * e1z - e2z * e1y;
        float ny = e2z * e1x - e2x * e1z;
        float nz = e2x * e1y - e2y * e1x;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-6f) return 256;
        nx /= length; ny /= length; nz /= length;
        // Into view space, the same rotation as project()
        float x1 = nx * cy + nz * sy;
        float z1 = -nx * sy + nz * cy;
        float y2 = ny * cp - z1 * sp;
        float z2 = ny * sp + z1 * cp;
        float dot = Math.max(0f, x1 * LIGHT_X + y2 * LIGHT_Y + z2 * LIGHT_Z);
        float brightness = Math.min(1f, LIGHT_AMBIENT + LIGHT_DIFFUSE * dot);
        return (int) (brightness * 256f);
    }

    /**
     * Draws the face whose projected corners (top-left, top-right, bottom-left, bottom-right) are in mView.
     * With an orthographic camera a rectangle is always a parallelogram on the screen, so the texture
     * position and the depth of a pixel follow from two coordinates (a, b) along its two edges.
     */
    private void rasterizeFace(int[] texture, int textureWidth, Rect src, int shade) {
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
                // Faces on the same plane (the outer layers of the arm and the torso) get the same depth
                // up to rounding: the bias lets the one drawn last win everywhere, not a mix of both
                if (depth < mDepths[index] - DEPTH_BIAS) continue;

                if (a < 0f) a = 0f; else if (a > maxFraction) a = maxFraction;
                if (b < 0f) b = 0f; else if (b > maxFraction) b = maxFraction;
                int color = texture[(src.top + (int) (b * sh)) * textureWidth + src.left + (int) (a * sw)];
                int alpha = color >>> 24;
                if (alpha == 0) continue; // transparent texels (outer layers) show what is behind them
                if (shade < 256) {
                    int r = (((color >> 16) & 0xFF) * shade) >> 8;
                    int g = (((color >> 8) & 0xFF) * shade) >> 8;
                    int bl = ((color & 0xFF) * shade) >> 8;
                    color = (alpha << 24) | (r << 16) | (g << 8) | bl;
                }
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
