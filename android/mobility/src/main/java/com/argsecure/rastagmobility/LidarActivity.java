package com.argsecure.rastagmobility;

import android.app.Activity;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.core.content.ContextCompat;
import com.argsecure.mobility.api.*;
import java.util.Locale;

/** Local live scan display backed by the independent service; no cloud/image calls. */
public final class LidarActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private LidarReading reading;
    private MobilitySnapshot link;
    private LidarView plot;
    private TextView live,details,nearest,connection;
    private boolean resumed;
    private int range=4000;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            try{
                long now=SystemClock.elapsedRealtime();
                if(MobilityContract.STATUS.equals(intent.getAction())){
                    MobilitySnapshot next=MobilitySnapshot.parse(intent.getStringExtra(MobilityContract.PAYLOAD),now);
                    if(next.isNewerThan(link))link=next;
                }else if(MobilityContract.LIDAR.equals(intent.getAction())){
                    byte[] raw=intent.getByteArrayExtra(MobilityContract.FRAME);
                    if(raw==null)reading=null;
                    else{
                        LidarReading next=new LidarReading(LidarFrame.parse(raw),intent.getLongExtra(MobilityContract.RECEIVED_ELAPSED,-1),
                                intent.getLongExtra(MobilityContract.ROUND_TRIP,-1));
                        if(next.live(now)&&(reading==null||next.frame.isNewerThan(reading.frame)))reading=next;
                    }
                }
            }catch(RuntimeException ignored){}
            render();
        }
    };
    private final Runnable refresh=new Runnable(){public void run(){if(resumed){render();main.postDelayed(this,200);}}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF07121B);getWindow().setNavigationBarColor(0xFF07121B);
        LinearLayout root=column();root.setBackgroundColor(0xFF07121B);root.setPadding(dp(16),dp(4),dp(16),dp(6));setContentView(root);
        LinearLayout header=row();root.addView(header,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView title=text("لیدار زنده",22,0xFFE4F4FA);title.setTypeface(null,Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        connection=text("ارتباط: در حال بررسی",13,0xFFABC3D2);header.addView(connection);
        Button back=button("بازگشت","lidar_back");back.setOnClickListener(v->finish());header.addView(back);
        LinearLayout body=row();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout stats=column();stats.setPadding(dp(16),dp(8),dp(16),dp(6));
        GradientDrawable panel=new GradientDrawable();panel.setColor(0xFF102430);panel.setCornerRadius(dp(14));stats.setBackground(panel);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(stats);body.addView(scroll,new LinearLayout.LayoutParams(dp(282),-1));
        live=text("در انتظار اسکن",19,0xFFFFBC6B);live.setTag("lidar_live_status");stats.addView(live);
        stats.addView(text("نزدیک‌ترین بازتاب معتبر",13,0xFFABC3D2));
        nearest=text("—",31,0xFF64E8CB);nearest.setTypeface(Typeface.MONOSPACE);nearest.setTextDirection(View.TEXT_DIRECTION_LTR);nearest.setTag("lidar_nearest");stats.addView(nearest);
        details=text("—",14,0xFFD9ECF5);details.setTag("lidar_metrics");stats.addView(details);
        Button zoom=button("برد نمایش: \u20664 m\u2069","lidar_range");stats.addView(zoom);
        zoom.setOnClickListener(v->{range=range==4000?8000:range==8000?2000:4000;plot.range(range);zoom.setText("برد نمایش: \u2066"+(range/1000)+" m\u2069");});
        stats.addView(text("زاویه‌ها نسبت به سنسور هستند. جهت نصب هنوز کالیبره نشده است.",12,0xFFABC3D2));
        plot=new LidarView(this);LinearLayout.LayoutParams plotParams=new LinearLayout.LayoutParams(0,-1,1);plotParams.setMargins(dp(12),0,0,0);body.addView(plot,plotParams);
        TextView note=text("هر نما یک دور کامل لیدار است · نقاط بدون بازتاب معتبر نمایش داده نمی‌شوند",12,0xFF8CAABC);root.addView(note);
        // A portrait phone also remains usable without changing system size/density.
        if(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density<650){
            body.setOrientation(LinearLayout.VERTICAL);scroll.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(160)));
            plot.setLayoutParams(new LinearLayout.LayoutParams(-1,0,1));
        }
        MobilityReceiver.start(this,new Intent(this,MobilityService.class));
    }
    @Override protected void onResume(){
        super.onResume();resumed=true;
        IntentFilter filter=new IntentFilter(MobilityContract.LIDAR);filter.addAction(MobilityContract.STATUS);
        ContextCompat.registerReceiver(this,receiver,filter,MobilityContract.PERMISSION,null,ContextCompat.RECEIVER_EXPORTED);
        MobilityReceiver.start(this,new Intent(this,MobilityService.class).setAction(MobilityContract.QUERY).putExtra(MobilityContract.REPLY_PACKAGE,getPackageName()));
        main.post(refresh);
    }
    @Override protected void onPause(){resumed=false;main.removeCallbacks(refresh);unregisterReceiver(receiver);super.onPause();}
    private void render(){
        if(plot==null)return;long now=SystemClock.elapsedRealtime();
        LinkHealth.State state=link==null?LinkHealth.State.CONNECTING:link.state(now);
        connection.setText("ارتباط ESP: "+MobilityActivity.label(state));
        boolean fresh=reading!=null&&reading.live(now)&&state==LinkHealth.State.CONNECTED;
        live.setText(fresh?"●  دریافت زنده":"●  در انتظار دادهٔ تازه");live.setTextColor(fresh?0xFF64E8CB:0xFFFFBC6B);
        plot.display(fresh?reading:null);
        if(fresh){
            LidarFrame frame=reading.frame;
            nearest.setText(frame.validCount==0?"—":String.format(Locale.US,"%.2f m",frame.nearestMm/1000.0));
            details.setText(String.format(Locale.US,"دور: \u2066%d\u2069\nنقاط معتبر: \u2066%d / %d\u2069\nسن داده حداکثر: \u2066%d ms\u2069",frame.sequence,frame.validCount,frame.count,reading.ageMs(now)));
        }else{nearest.setText("—");details.setText("اسکن کامل و تازه‌ای برای نمایش در دسترس نیست.");}
    }
    // Used by on-device integration tests to verify real frame delivery, not a screenshot fixture.
    LidarReading displayedReading(){return reading;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);return v;}
    private LinearLayout row(){LinearLayout v=column();v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setGravity(Gravity.CENTER_VERTICAL);v.setPadding(0,dp(3),0,dp(3));return v;}
    private Button button(String value,String tag){Button b=new Button(this);b.setText(value);b.setTextSize(14);b.setAllCaps(false);b.setTag(tag);return b;}
}
