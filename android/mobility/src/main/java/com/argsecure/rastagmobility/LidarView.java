package com.argsecure.rastagmobility;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import com.argsecure.mobility.api.*;
import java.util.Locale;

/** One measured revolution only. No interpolation, pose estimate, or invented free space. */
public final class LidarView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private LidarReading reading;
    private int rangeMm=4000;
    public LidarView(Context context){super(context);setTag("lidar_plot");setContentDescription("نمای زندهٔ نقاط لیدار در ۳۶۰ درجه");}
    void display(LidarReading value){reading=value;invalidate();}
    void range(int mm){rangeMm=mm;invalidate();}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float d=getResources().getDisplayMetrics().density;
        float x=getWidth()/2f,y=getHeight()/2f,r=Math.max(1,Math.min(getWidth(),getHeight())/2f-28*d);
        c.drawColor(0xFF0B1B27);p.setStrokeWidth(d);p.setStyle(Paint.Style.STROKE);
        p.setColor(0xFF1B3442);
        for(int i=1;i<=4;i++)c.drawCircle(x,y,r*i/4,p);
        c.drawLine(x-r,y,x+r,y,p);c.drawLine(x,y-r,x,y+r,p);
        p.setColor(0xFF162C39);
        for(int a=45;a<360;a+=90){double angle=Math.toRadians(a);c.drawLine(x,y,x+(float)Math.sin(angle)*r,y-(float)Math.cos(angle)*r,p);}
        p.setStyle(Paint.Style.FILL);p.setTextSize(11*d);p.setTextAlign(Paint.Align.CENTER);p.setColor(0xFF7997AA);
        c.drawText("0°",x,y-r-10*d,p);c.drawText("180°",x,y+r+19*d,p);
        c.drawText("90°",x+r+20*d,y+4*d,p);c.drawText("270°",x-r-22*d,y+4*d,p);
        p.setTextAlign(Paint.Align.LEFT);
        for(int i=1;i<=4;i++)c.drawText(String.format(Locale.US,"%.1f m",rangeMm*i/4000f),x+7*d,y-r*i/4+12*d,p);
        boolean live=reading!=null&&reading.live(SystemClock.elapsedRealtime());
        if(live){
            LidarFrame frame=reading.frame;
            for(int i=0;i<frame.count;i++){
                if(!frame.valid(i)||frame.distanceMm(i)>rangeMm)continue;
                double a=Math.toRadians(frame.angleCdeg(i)/100.0);float rr=r*frame.distanceMm(i)/rangeMm;
                p.setColor(frame.distanceMm(i)==frame.nearestMm?0xFFFFBC6B:0xFF64E8CB);
                c.drawCircle(x+(float)Math.sin(a)*rr,y-(float)Math.cos(a)*rr,2*d,p);
            }
        }
        p.setColor(0xFF6DBFFC);c.drawCircle(x,y,4*d,p);
        p.setColor(0xFFADC4D3);p.setTextSize(13*d);p.setTextAlign(Paint.Align.CENTER);
        if(!live)c.drawText("در انتظار اسکن تازهٔ لیدار",x,y+28*d,p);
    }
}
