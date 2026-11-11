package com.argsecure.rastagmobility;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.Locale;

/** Metric plan preview. Empty/unknown space is never drawn as observed free space. */
final class MapPlanView extends View {
    interface Editor { void origin(double x,double y);void zone(MapDefinition.Zone zone); }
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF area=new RectF();
    private MapDefinition map;
    private Editor editor;
    private int mode;
    private float downX,downY,dragX,dragY;
    private boolean dragging;
    MapPlanView(Context context){super(context);setMinimumHeight(dp(180));setBackgroundColor(0xFF091923);setFocusable(true);setClickable(true);}
    void display(MapDefinition value,int mode,Editor editor){map=value;this.mode=mode;this.editor=editor;dragging=false;
        setContentDescription(value==null?"پیش‌نمایش محدوده؛ نقشه‌ای انتخاب نشده":"محدودهٔ نقشهٔ "+value.name+"؛ بدون اسکن ثبت‌شده");invalidate();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void text(Canvas c,String value,float x,float y,int color,int size,Paint.Align align){p.setColor(color);p.setTextSize(dp(size));p.setTextAlign(align);p.setStyle(Paint.Style.FILL);c.drawText(value,x,y,p);}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        if(map==null){text(c,"برای شروع، یک نقشه تعریف کنید",getWidth()/2f,getHeight()/2f,0xFFABC3D2,17,Paint.Align.CENTER);return;}
        float scale=(float)Math.min((getWidth()-dp(64))/map.width,(getHeight()-dp(64))/map.height);
        if(scale<=0)return;
        float w=(float)map.width*scale,h=(float)map.height*scale;
        area.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);
        p.setStyle(Paint.Style.FILL);p.setColor(0xFF102A36);c.drawRoundRect(area,dp(4),dp(4),p);
        double step=Math.max(map.width,map.height)>30?5:1;
        p.setColor(0xFF1D414F);p.setStrokeWidth(dp(1));
        for(double x=0;x<=map.width;x+=step){float px=x(x);c.drawLine(px,area.top,px,area.bottom,p);}
        for(double y=0;y<=map.height;y+=step){float py=y(y);c.drawLine(area.left,py,area.right,py,p);}
        for(MapDefinition.Zone z:map.zones)zone(c,x(z.x),y(z.y+z.height),x(z.x+z.width),y(z.y));
        if(dragging&&mode==2)zone(c,Math.min(downX,dragX),Math.min(downY,dragY),Math.max(downX,dragX),Math.max(downY,dragY));
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(0xFF45919D);c.drawRect(area,p);p.setStyle(Paint.Style.FILL);
        float sx=x(map.startX),sy=y(map.startY);
        p.setColor(0x334AE0B8);c.drawCircle(sx,sy,dp(18),p);p.setColor(0xFF63E0AD);c.drawCircle(sx,sy,dp(7),p);
        c.save();c.rotate(map.heading,sx,sy);Path arrow=new Path();arrow.moveTo(sx,sy-dp(23));arrow.lineTo(sx-dp(5),sy-dp(14));arrow.lineTo(sx+dp(5),sy-dp(14));arrow.close();c.drawPath(arrow,p);c.restore();
        text(c,String.format(Locale.US,"%.1f m",map.width),area.centerX(),area.bottom+dp(19),0xFFC1D6DF,12,Paint.Align.CENTER);
        text(c,String.format(Locale.US,"%.1f m",map.height),area.left+dp(3),area.top-dp(10),0xFFC1D6DF,12,Paint.Align.LEFT);
        text(c,"بدون اسکن ثبت‌شده",getWidth()-dp(12),getHeight()-dp(10),0xFF7695A5,11,Paint.Align.RIGHT);
    }
    private void zone(Canvas c,float left,float top,float right,float bottom){p.setStyle(Paint.Style.FILL);p.setColor(0x55F3757B);c.drawRect(left,top,right,bottom,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(0xFFF3757B);c.drawRect(left,top,right,bottom,p);p.setStyle(Paint.Style.FILL);}
    private float x(double x){return area.left+(float)(x/map.width)*area.width();}
    private float y(double y){return area.bottom-(float)(y/map.height)*area.height();}
    private double mx(float x){return Math.max(0,Math.min(map.width,(x-area.left)/area.width()*map.width));}
    private double my(float y){return Math.max(0,Math.min(map.height,(area.bottom-y)/area.height()*map.height));}
    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        if(map==null||mode==0||editor==null||area.isEmpty())return super.onTouchEvent(e);
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                if(!area.contains(e.getX(),e.getY()))return false;
                downX=dragX=e.getX();downY=dragY=e.getY();dragging=true;getParent().requestDisallowInterceptTouchEvent(true);return true;
            case MotionEvent.ACTION_MOVE:
                if(!dragging)return false;dragX=Math.max(area.left,Math.min(area.right,e.getX()));dragY=Math.max(area.top,Math.min(area.bottom,e.getY()));invalidate();return true;
            case MotionEvent.ACTION_UP:
                if(!dragging)return false;dragging=false;getParent().requestDisallowInterceptTouchEvent(false);performClick();
                if(mode==1)editor.origin(mx(e.getX()),my(e.getY()));
                else{double x1=mx(downX),y1=my(downY),x2=mx(e.getX()),y2=my(e.getY());editor.zone(new MapDefinition.Zone(Math.min(x1,x2),Math.min(y1,y2),Math.abs(x2-x1),Math.abs(y2-y1)));}
                invalidate();return true;
            case MotionEvent.ACTION_CANCEL:dragging=false;getParent().requestDisallowInterceptTouchEvent(false);invalidate();return true;
            default:return true;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
