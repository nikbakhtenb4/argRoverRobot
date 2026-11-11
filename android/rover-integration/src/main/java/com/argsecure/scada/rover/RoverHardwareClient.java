package com.argsecure.scada.rover;

import android.content.*;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import com.argsecure.mobility.api.*;

/** UI subscriber only. The independent mobility app is the sole controller connection owner. */
final class RoverHardwareClient {
    private final Context context;
    private MobilitySnapshot latest;
    private LidarReading lidar;
    private MotionSnapshot motion;
    private long started;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable checkService=new Runnable(){
        @Override public void run(){
            if(!visible)return;
            if(latest==null||latest.state(SystemClock.elapsedRealtime())==LinkHealth.State.SERVICE_UNAVAILABLE)requestService();
            main.postDelayed(this,10000);
        }
    };
    private boolean visible, installed;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            try {
                if(MotionContract.STATUS.equals(intent.getAction())){
                    MotionSnapshot candidate=MotionSnapshot.parse(intent.getStringExtra(MobilityContract.PAYLOAD),SystemClock.elapsedRealtime());
                    if(motion==null||candidate.published>=motion.published)motion=candidate;
                }else if(MobilityContract.LIDAR.equals(intent.getAction())){
                    byte[] bytes=intent.getByteArrayExtra(MobilityContract.FRAME);
                    if(bytes==null)lidar=null;
                    else{
                        LidarReading candidate=new LidarReading(LidarFrame.parse(bytes),
                                intent.getLongExtra(MobilityContract.RECEIVED_ELAPSED,-1),intent.getLongExtra(MobilityContract.ROUND_TRIP,-1));
                        if(candidate.live(SystemClock.elapsedRealtime())&&(lidar==null||candidate.frame.isNewerThan(lidar.frame)))lidar=candidate;
                    }
                }else{
                    MobilitySnapshot candidate = MobilitySnapshot.parse(intent.getStringExtra(MobilityContract.PAYLOAD),SystemClock.elapsedRealtime());
                    if (candidate.isNewerThan(latest)) latest = candidate;
                }
            } catch (RuntimeException ignoredBadStatus) { }
        }
    };
    RoverHardwareClient(Context context) { this.context = context.getApplicationContext(); }
    void setVisible(boolean value) {
        if (visible == value) return;
        visible = value; latest = null; lidar=null;motion=null;
        if (!value) { main.removeCallbacks(checkService);context.unregisterReceiver(receiver); return; }
        started = SystemClock.elapsedRealtime();
        IntentFilter filter=new IntentFilter(MobilityContract.STATUS);filter.addAction(MobilityContract.LIDAR);filter.addAction(MotionContract.STATUS);
        ContextCompat.registerReceiver(context,receiver,filter,MobilityContract.PERMISSION,null,ContextCompat.RECEIVER_EXPORTED);
        requestService();main.postDelayed(checkService,10000);
    }
    private void requestService(){
        try {
            installed = context.getPackageManager().getApplicationInfo(MobilityContract.PACKAGE,0).enabled;
            Intent request = new Intent(MobilityContract.START).setComponent(new ComponentName(MobilityContract.PACKAGE,MobilityContract.RECEIVER))
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(MobilityContract.REPLY_PACKAGE,context.getPackageName());
            context.sendBroadcast(request,MobilityContract.PERMISSION);
        } catch (Exception unavailable) { installed = false; }
    }
    LinkHealth.State state() {
        if (!visible) return LinkHealth.State.INACTIVE;
        if (!installed) return LinkHealth.State.SERVICE_UNAVAILABLE;
        long now = SystemClock.elapsedRealtime();
        if (latest != null) return latest.state(now);
        return now - started < MobilityContract.STATUS_MAX_AGE_MS ? LinkHealth.State.CONNECTING : LinkHealth.State.SERVICE_UNAVAILABLE;
    }
    LidarReading lidar(){
        return state()==LinkHealth.State.CONNECTED&&lidar!=null&&lidar.live(SystemClock.elapsedRealtime())?lidar:null;
    }
    MotionSnapshot motion(){return visible&&motion!=null&&motion.fresh(SystemClock.elapsedRealtime())?motion:null;}
}
