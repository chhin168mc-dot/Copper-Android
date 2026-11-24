package git.artdeell.mojo.skins;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.R;

/** Skins that are drawn in code, used when an account has no skin of its own */
public final class DefaultSkins {
    private static final int SKIN = 0xFFB88563;
    private static final int SKIN_DARK = 0xFFA27150;
    private static final int HAIR = 0xFF4A3220;
    private static final int SHIRT = 0xFF1FA5B8;
    private static final int PANTS = 0xFF3B3F94;
    private static final int SHOE = 0xFF6B6B6F;
    private static final int EYE_WHITE = 0xFFFFFFFF;
    private static final int EYE_BLUE = 0xFF3A3A9A;
    private static final int MOUTH = 0xFF7A4636;

    @Nullable
    private static Bitmap sBundled;

    private DefaultSkins() {}

    /** Loads the skin that comes with the app (res/drawable-nodpi/default_skin.png), call it once before {@link #steve()} */
    public static synchronized void init(@NonNull Context context) {
        if (sBundled != null) return;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        sBundled = BitmapFactory.decodeResource(context.getResources(), R.drawable.default_skin, options);
    }

    /** @return a new 64x64 default skin: the bundled one, or Steve drawn in code when the bundled one is not loaded */
    @NonNull
    public static synchronized Bitmap steve() {
        if (sBundled != null) return sBundled.copy(Bitmap.Config.ARGB_8888, true);
        return drawSteve();
    }

    /** PNG of the default skin, for the local skin server that gives it to the game */
    @NonNull
    public static synchronized byte[] steveBytes() {
        Bitmap bitmap = steve();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    @NonNull
    private static Bitmap drawSteve() {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(false);
        paint.setStyle(Paint.Style.FILL);

        // Head: u=0 v=0, 8x8x8
        head(canvas, paint);
        // Body: u=16 v=16, 8x12x4
        box(canvas, paint, 16, 16, 8, 12, 4, SHIRT, SHOE, SHIRT, SHIRT, SHIRT, SHIRT);
        // Arms: u=40 v=16 and u=32 v=48, 4x12x4, sleeves on the upper four rows
        arm(canvas, paint, 40, 16);
        arm(canvas, paint, 32, 48);
        // Legs: u=0 v=16 and u=16 v=48, 4x12x4, shoes on the lower two rows
        leg(canvas, paint, 0, 16);
        leg(canvas, paint, 16, 48);
        return bitmap;
    }

    private static void head(Canvas c, Paint p) {
        // top, bottom, right, front, left, back
        box(c, p, 0, 0, 8, 8, 8, HAIR, SKIN, SKIN, SKIN, SKIN, SKIN);
        // hair on the sides and the back
        rect(c, p, HAIR, 0, 8, 8, 3);
        rect(c, p, HAIR, 16, 8, 8, 3);
        rect(c, p, HAIR, 24, 8, 8, 6);
        // front: hair fringe, eyes, nose, mouth
        rect(c, p, HAIR, 8, 8, 8, 2);
        rect(c, p, HAIR, 8, 10, 1, 1);
        rect(c, p, HAIR, 15, 10, 1, 1);
        rect(c, p, EYE_WHITE, 9, 12, 2, 1);
        rect(c, p, EYE_WHITE, 13, 12, 2, 1);
        rect(c, p, EYE_BLUE, 10, 12, 1, 1);
        rect(c, p, EYE_BLUE, 13, 12, 1, 1);
        rect(c, p, SKIN_DARK, 11, 13, 2, 1);
        rect(c, p, MOUTH, 11, 14, 2, 1);
    }

    private static void arm(Canvas c, Paint p, int u, int v) {
        box(c, p, u, v, 4, 12, 4, SHIRT, SKIN, SKIN, SKIN, SKIN, SKIN);
        // sleeve rows on the four side faces
        rect(c, p, SHIRT, u, v + 4, 4, 4);
        rect(c, p, SHIRT, u + 4, v + 4, 4, 4);
        rect(c, p, SHIRT, u + 8, v + 4, 4, 4);
        rect(c, p, SHIRT, u + 12, v + 4, 4, 4);
    }

    private static void leg(Canvas c, Paint p, int u, int v) {
        box(c, p, u, v, 4, 12, 4, PANTS, SHOE, PANTS, PANTS, PANTS, PANTS);
        rect(c, p, SHOE, u, v + 14, 4, 2);
        rect(c, p, SHOE, u + 4, v + 14, 4, 2);
        rect(c, p, SHOE, u + 8, v + 14, 4, 2);
        rect(c, p, SHOE, u + 12, v + 14, 4, 2);
    }

    /** Fills the six faces of a box in the standard skin layout, the colors are top, bottom, right, front, left, back */
    private static void box(Canvas c, Paint p, int u, int v, int w, int h, int d,
                            int top, int bottom, int right, int front, int left, int back) {
        rect(c, p, top, u + d, v, w, d);
        rect(c, p, bottom, u + d + w, v, w, d);
        rect(c, p, right, u, v + d, d, h);
        rect(c, p, front, u + d, v + d, w, h);
        rect(c, p, left, u + d + w, v + d, d, h);
        rect(c, p, back, u + d + w + d, v + d, w, h);
    }

    private static void rect(Canvas c, Paint p, int color, int x, int y, int w, int h) {
        p.setColor(color);
        c.drawRect(x, y, x + w, y + h, p);
    }
}
