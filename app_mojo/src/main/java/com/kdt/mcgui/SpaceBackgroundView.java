package com.kdt.mcgui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.os.SystemClock;
import java.util.Random;

/** Lightweight animated space backdrop for the launcher home screen. */
public final class SpaceBackgroundView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] xs = new float[105], ys = new float[105], sizes = new float[105], phases = new float[105];
    private long start = SystemClock.uptimeMillis();
    private float density;
    public SpaceBackgroundView(Context c, AttributeSet a) { super(c,a); init(); }
    public SpaceBackgroundView(Context c) { super(c); init(); }
    private void init() {
        density = getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        Random r = new Random(78231);
        for (int i=0;i<xs.length;i++) {
            xs[i]=r.nextFloat(); ys[i]=r.nextFloat();
            sizes[i]=(0.7f+r.nextFloat()*1.8f)*density;
            phases[i]=r.nextFloat()*(float)(Math.PI*2);
        }
        setWillNotDraw(false);
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w=getWidth(), h=getHeight();
        c.drawColor(0xff050817);
        paint.setShader(new RadialGradient(w*.18f,h*.22f,w*.58f,
                new int[]{0x442d4eaa,0x18233b83,0x00050817},null,Shader.TileMode.CLAMP));
        c.drawRect(0,0,w,h,paint); paint.setShader(null);
        paint.setShader(new RadialGradient(w*.86f,h*.68f,w*.48f,
                new int[]{0x332c176e,0x14201752,0x00050817},null,Shader.TileMode.CLAMP));
        c.drawRect(0,0,w,h,paint); paint.setShader(null);
        float t=(SystemClock.uptimeMillis()-start)/1000f;
        for(int i=0;i<xs.length;i++) {
            float alpha=105f+105f*(.5f+.5f*(float)Math.sin(t*1.25f+phases[i]));
            paint.setColor((Math.min(255,(int)alpha)<<24)|0x00dceaff);
            c.drawCircle(xs[i]*w,ys[i]*h,sizes[i],paint);
        }
        // A subtle moving comet
        float p=(t*.105f)%1f;
        float x=(p*1.35f-.15f)*w, y=(.13f+p*.38f)*h;
        paint.setShader(new android.graphics.LinearGradient(x-65*density,y-28*density,x,y,
                new int[]{0x00000000,0x9958cfff,0xffe5f7ff},null,Shader.TileMode.CLAMP));
        paint.setStrokeWidth(1.7f*density);
        c.drawLine(x-65*density,y-28*density,x,y,paint);
        paint.setShader(null);
        postInvalidateOnAnimation();
    }
}