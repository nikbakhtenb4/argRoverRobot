package com.argsecure.scada.rover;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;
import com.argsecure.mobility.api.*;

/** Controller decisions only: no inferred velocity or physical completion. */
final class RoverMotionBadge extends TextView {
    RoverMotionBadge(Context context){
        super(context);setTag("rover_motion_decision");setGravity(Gravity.CENTER);setTextSize(12);
        setTypeface(Typeface.DEFAULT,Typeface.BOLD);setMaxLines(3);setPadding(6,3,6,3);
        GradientDrawable background=new GradientDrawable();background.setColor(0xFF081B25);background.setCornerRadius(12);
        setBackground(background);setClickable(true);setFocusable(true);display(null);
    }
    void display(MotionSnapshot s){
        boolean live=s!=null;String requested=live?s.requested:"stop",decision=live?s.decision:"stop";
        String symbol=decision.equals("forward")?"↑":decision.equals("back")?"↓":decision.equals("left")?"←":decision.equals("right")?"→":"■";
        String text="درخواست: "+MotionContract.label(requested)+"   تصمیم: "+symbol+" "+MotionContract.label(decision)
                +"\n"+MotionContract.reason(live?s.reason:"unavailable");
        if(!text.contentEquals(getText())){setText(text);setContentDescription(text+"؛ لمس برای کنترل حرکت");}
        setTextColor(live&&s.driving?0xFF64E8CB:0xFFFFCF78);
    }
}
