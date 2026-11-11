package com.argsecure.rastagmobility;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import androidx.core.content.ContextCompat;
import com.argsecure.mobility.api.*;

/** A small local service panel. No camera, microphone, model, or Phoenix-core dependency. */
public final class MobilityActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private MobilitySnapshot latest;
    private boolean resumed;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                MobilitySnapshot s = MobilitySnapshot.parse(intent.getStringExtra(MobilityContract.PAYLOAD),SystemClock.elapsedRealtime());
                if (s.isNewerThan(latest)) latest = s;
            } catch (RuntimeException ignored) { }
            render();
        }
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() { if (resumed) { render(); main.postDelayed(this,1000); } }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(0xFF07121B);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); column.setPadding(32,24,32,24);
        scroll.addView(column); setContentView(scroll);
        add(column,"حرکت و لیدار رستاگ",24,0xFFE0F8FC);
        status = add(column,"ارتباط ESP: در حال اتصال",19,0xFF63E0AD);
        status.setContentDescription("وضعیت ارتباط سرویس مستقل با ESP");
        add(column,"نمایش زندهٔ لیدار و پنل کنترل حرکت در دسترس است. حرکت به کالیبراسیون جهت‌ها و تأیید مسیر نیاز دارد.",17,0xFFFFCF78);
        Button motion=new Button(this);motion.setText("کنترل حرکت و کالیبراسیون");motion.setTag("mobility_open_motion");
        motion.setOnClickListener(v->startActivity(new Intent(this,MotionActivity.class)));column.addView(motion);
        add(column,"سرویس ارتباط مستقل فعال است و با بستن این صفحه ادامه پیدا می‌کند.",15,0xFFBDD0DD);
        Button lidar=new Button(this);lidar.setText("نمایش زندهٔ لیدار");lidar.setTag("mobility_open_lidar");
        lidar.setOnClickListener(v->startActivity(new Intent(this,LidarActivity.class)));column.addView(lidar);
        Button maps = new Button(this); maps.setText("تعریف و مدیریت نقشه‌ها");maps.setTag("mobility_open_mapping");
        maps.setOnClickListener(v -> startActivity(new Intent(this,MappingActivity.class)));column.addView(maps);
        Button close = new Button(this); close.setText("بازگشت"); close.setOnClickListener(v -> finish()); column.addView(close);
        MobilityReceiver.start(this,new Intent(this,MobilityService.class));
    }
    private TextView add(LinearLayout column,String value,int size,int color) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setPadding(0,8,0,8); column.addView(text); return text;
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        ContextCompat.registerReceiver(this,receiver,new IntentFilter(MobilityContract.STATUS),MobilityContract.PERMISSION,null,ContextCompat.RECEIVER_EXPORTED);
        MobilityReceiver.start(this,new Intent(this,MobilityService.class).setAction(MobilityContract.QUERY)
                .putExtra(MobilityContract.REPLY_PACKAGE,getPackageName()));
        main.post(refresh);
    }
    @Override protected void onPause() {
        resumed = false; main.removeCallbacks(refresh); unregisterReceiver(receiver); super.onPause();
    }
    private void render() {
        LinkHealth.State s = latest == null ? LinkHealth.State.CONNECTING : latest.state(SystemClock.elapsedRealtime());
        status.setText("ارتباط ESP: " + label(s));
        status.setTextColor(s == LinkHealth.State.CONNECTED ? 0xFF63E0AD : 0xFFFFCF78);
    }
    static String label(LinkHealth.State state) {
        switch (state) {
            case CONNECTED: return "متصل";
            case CONNECTING: return "در حال اتصال";
            case UNPAIRED: return "تنظیم نشده";
            case PAIRING_ERROR: return "خطای اتصال";
            case INACTIVE: return "غیرفعال";
            case SERVICE_UNAVAILABLE: return "سرویس در دسترس نیست";
            default: return "قطع";
        }
    }
}
