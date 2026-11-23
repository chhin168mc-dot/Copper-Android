package git.artdeell.mojo.skins;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
/** Builds the standard Steve fallback skin when no account skin is available. */
public final class DefaultSteveSkin {
    private DefaultSteveSkin() {}
    public static Bitmap create() {
        Bitmap b=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(b); Paint p=new Paint();
        int skin=0xffbd8b62, shadow=0xffa97550, hair=0xff49352b, shirt=0xff2875a8, shirtDark=0xff1d527b, pants=0xff263b79, shoes=0xff51463e;
        // Head base + face
        p.setColor(skin); c.drawRect(8,8,16,16,p);
        p.setColor(hair); c.drawRect(8,8,16,11,p); c.drawRect(8,8,10,14,p); c.drawRect(14,8,16,14,p);
        p.setColor(0xfff1f0e8); c.drawRect(9,11,11,12,p); c.drawRect(13,11,15,12,p);
        p.setColor(0xff3d8fb1); c.drawRect(10,11,11,12,p); c.drawRect(13,11,14,12,p);
        p.setColor(0xff694332); c.drawRect(11,13,13,14,p);
        p.setColor(shadow); c.drawRect(8,15,16,16,p);
        // Head outer layer
        p.setColor(0x2249352b); c.drawRect(40,8,48,16,p);
        // Torso
        p.setColor(shirt); c.drawRect(20,20,28,32,p); p.setColor(shirtDark); c.drawRect(20,30,28,32,p);
        // Arms
        p.setColor(skin); c.drawRect(44,20,48,32,p); c.drawRect(36,52,40,64,p);
        p.setColor(shirt); c.drawRect(44,20,48,28,p); c.drawRect(36,52,40,58,p);
        // Legs
        p.setColor(pants); c.drawRect(4,20,8,32,p); c.drawRect(20,52,24,64,p);
        p.setColor(shoes); c.drawRect(4,30,8,32,p); c.drawRect(20,62,24,64,p);
        // Left/right arm texture and second leg texture
        p.setColor(shirt); c.drawRect(44,20,48,28,p); c.drawRect(52,20,56,28,p);
        p.setColor(skin); c.drawRect(52,28,56,32,p);
        p.setColor(pants); c.drawRect(4,36,8,48,p); c.drawRect(4,52,8,64,p);
        p.setColor(shoes); c.drawRect(4,62,8,64,p);
        return b;
    }
}