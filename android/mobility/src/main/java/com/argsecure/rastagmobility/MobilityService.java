package com.argsecure.rastagmobility;

import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import android.util.AtomicFile;
import com.argsecure.mobility.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Own process, own lifecycle and keys. UI/camera/voice crashes cannot stop its ESP heartbeat. */
public final class MobilityService extends Service {
    private static final String CHANNEL = "rastag_mobility";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String instance = UUID.randomUUID().toString().replace("-", "");
    private final MobilityCommandGate commands = new MobilityCommandGate();
    private final ExecutorService diagnostics = Executors.newSingleThreadExecutor();
    private final AtomicBoolean writing = new AtomicBoolean();
    private EspLink link;
    private PowerManager.WakeLock wake;
    private WifiManager.MulticastLock multicast;
    private long serial, nextWake;
    private boolean alive;
    private LinkHealth.State notificationState;
    private LidarReading lastScanSent;
    public final class LocalBinder extends Binder {
        EspLink controls(){return link;}
    }
    private final LocalBinder binder=new LocalBinder();
    private final Runnable motionPulse=new Runnable(){
        @Override public void run(){if(!alive)return;
            String raw=link.motionSnapshot(SystemClock.elapsedRealtime()).json();
            for(String target:new String[]{getPackageName(),"com.argsecure.scada"})
                sendBroadcast(new Intent(MotionContract.STATUS).setPackage(target).putExtra(MobilityContract.PAYLOAD,raw),MobilityContract.PERMISSION);
            main.postDelayed(this,250);
        }
    };
    private final Runnable scanPulse=new Runnable(){
        @Override public void run(){
            if(!alive)return;
            LidarReading reading=link.scan();
            if(reading!=lastScanSent){
                lastScanSent=reading;sendScan(getPackageName(),reading);sendScan("com.argsecure.scada",reading);
            }
            main.postDelayed(this,100);
        }
    };
    private final Runnable pulse = new Runnable() {
        @Override public void run() {
            if (!alive) return;
            long now = SystemClock.elapsedRealtime();
            if (now >= nextWake) { wake.acquire(30000); nextWake = now + 10000; }
            MobilitySnapshot status = snapshot();
            sendStatus("com.argsecure.scada", status);
            sendStatus(getPackageName(), status);
            sendBroadcast(new Intent(EspSensorStatus.ACTION).setPackage(getPackageName())
                    .putExtra(MobilityContract.PAYLOAD,link.sensors().json(now)),MobilityContract.PERMISSION);
            JsonObject diagnostic=JsonParser.parseString(status.json()).getAsJsonObject();
            diagnostic.add("lidar",link.scanDiagnostics());
            diagnostic.add("motion",JsonParser.parseString(link.motionSnapshot(now).json()));persist(diagnostic.toString());
            if (notificationState != status.link) {
                notificationState = status.link;
                ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(904, notification(status.link));
            }
            main.postDelayed(this, 2000);
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(
                CHANNEL,"ارتباط و حرکت رستاگ",NotificationManager.IMPORTANCE_LOW));
        if (Build.VERSION.SDK_INT >= 29) startForeground(904, notification(LinkHealth.State.CONNECTING), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(904, notification(LinkHealth.State.CONNECTING));
        wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"RastagMobility:Link");
        wake.setReferenceCounted(false);
        multicast = ((WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE)).createMulticastLock("rastag-mdns");
        multicast.setReferenceCounted(false); multicast.acquire();
        link = new EspLink(this);
        alive = true;
        main.post(pulse);
        main.post(scanPulse);
        main.post(motionPulse);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String reply = intent.getStringExtra(MobilityContract.REPLY_PACKAGE);
            if (trustedReply(reply)) {
                if(MotionContract.REQUEST.equals(intent.getAction())){
                    String raw=intent.getStringExtra(MobilityContract.PAYLOAD);
                    String code=link.externalMotion(raw);JsonObject result=new JsonObject();
                    result.addProperty("version",1);result.addProperty("code",code);result.addProperty("queued",code.equals("queued"));
                    result.addProperty("physical_motion_verified",false);
                    try{String id=JsonParser.parseString(raw).getAsJsonObject().get("request_id").getAsString();
                        if(id.matches("[a-f0-9]{32}"))result.addProperty("request_id",id);
                    }catch(RuntimeException ignored){}
                    sendBroadcast(new Intent(MotionContract.RESULT).setPackage(reply).putExtra(MobilityContract.PAYLOAD,result.toString()),MobilityContract.PERMISSION);
                }
                if (MobilityContract.COMMAND.equals(intent.getAction())) {
                    String result = commands.evaluate(intent.getStringExtra(MobilityContract.PAYLOAD), SystemClock.elapsedRealtime());
                    sendBroadcast(new Intent(MobilityContract.RESULT).setPackage(reply).putExtra(MobilityContract.PAYLOAD,result), MobilityContract.PERMISSION);
                }
                sendStatus(reply, snapshot());
                sendScan(reply,link.scan());
            }
        }
        return START_STICKY;
    }
    private boolean trustedReply(String name) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) return false;
        return getPackageManager().checkSignatures(getPackageName(),name) == PackageManager.SIGNATURE_MATCH
                && getPackageManager().checkPermission(MobilityContract.PERMISSION,name) == PackageManager.PERMISSION_GRANTED;
    }
    private MobilitySnapshot snapshot() {
        long acknowledged = link.acknowledgedElapsedMs();
        long now=SystemClock.elapsedRealtime();
        return new MobilitySnapshot(instance,serial++,now,acknowledged,link.state(),
                link.motionSnapshot(now).driving,link.scan()!=null?"live":"unavailable");
    }
    private void sendStatus(String target, MobilitySnapshot value) {
        sendBroadcast(new Intent(MobilityContract.STATUS).setPackage(target).putExtra(MobilityContract.PAYLOAD,value.json()), MobilityContract.PERMISSION);
    }
    private void sendScan(String target,LidarReading value){
        Intent intent=new Intent(MobilityContract.LIDAR).setPackage(target);
        if(value!=null){intent.putExtra(MobilityContract.FRAME,value.frame.bytes());
            intent.putExtra(MobilityContract.RECEIVED_ELAPSED,value.receivedElapsed);
            intent.putExtra(MobilityContract.ROUND_TRIP,value.roundTripMs);}
        sendBroadcast(intent,MobilityContract.PERMISSION);
    }
    private void persist(String json) {
        if (!writing.compareAndSet(false,true)) return;
        diagnostics.execute(() -> {
            AtomicFile file = new AtomicFile(new File(getNoBackupFilesDir(),"mobility_status.json"));
            FileOutputStream stream = null;
            try { stream = file.startWrite(); stream.write(json.getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream); }
            catch (IOException ignored) { if (stream != null) file.failWrite(stream); }
            finally { writing.set(false); }
        });
    }
    private Notification notification(LinkHealth.State state) {
        Intent intent = new Intent(this,MobilityActivity.class);
        PendingIntent open = PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this,CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_mobility).setContentTitle("حرکت و لیدار رستاگ")
                .setContentText("ارتباط ESP: " + MobilityActivity.label(state)).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build();
    }
    @Override public void onDestroy() {
        alive = false; main.removeCallbacksAndMessages(null);
        if (link != null) link.close();
        if (wake != null && wake.isHeld()) wake.release();
        if (multicast != null && multicast.isHeld()) multicast.release();
        diagnostics.shutdown();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public boolean onUnbind(Intent intent) { if(link!=null)link.stopMotion();return false; }
}
