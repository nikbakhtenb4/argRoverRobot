package com.argsecure.rastagmobility;

import com.argsecure.mobility.api.LinkHealth;
import com.argsecure.mobility.api.MotionContract;
import com.argsecure.mobility.api.MotionSnapshot;
import com.argsecure.mobility.api.LidarFrame;
import com.argsecure.mobility.api.LidarReading;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.SystemClock;
import android.util.AtomicFile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.atomic.AtomicReference;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Sole ESP session owner, hosted by the independent foreground service. */
final class EspLink {
    private final Context context;
    private final LinkHealth status = new LinkHealth();
    private volatile String address = "";
    private volatile Call call;
    private volatile EspPairing pair;
    private EspProtocol.Ack last;
    private long acknowledged, discoveryAt;
    private NsdManager.DiscoveryListener discovery;
    private volatile boolean resolving;
    private int failures;
    private final ScheduledExecutorService executor;
    private volatile JsonObject latest = new JsonObject();
    final MotionAuthority motionAuthority=new MotionAuthority();
    private volatile String rawMotion="{}",motionSource="none",motionRequested="stop",motionError="unavailable";
    private volatile long motionReceived;
    private long motionSeq,motionClock,motionPollAt,motionSendAt;
    private final AtomicReference<JsonObject> pendingProfile=new AtomicReference<>();
    String motionRaw(){return rawMotion;}
    long beginManual(String operation,int a,int b){
        motionSource="manual";motionRequested=operation.equals("drive")?MotionContract.direction(a):"stop";motionError="queued";
        return motionAuthority.beginManual(operation,a,b,SystemClock.elapsedRealtime());
    }
    void renewManual(long run){motionAuthority.renew(run,SystemClock.elapsedRealtime());}
    void releaseManual(long run){motionAuthority.release(run);}
    void stopMotion(){motionAuthority.stop();motionRequested="stop";motionError="";}
    void allowRemote(boolean enabled){motionAuthority.allowRemote(enabled,SystemClock.elapsedRealtime());}
    boolean remoteAllowed(){return motionAuthority.remoteAllowed(SystemClock.elapsedRealtime());}
    String externalMotion(String raw){
        String result=motionAuthority.external(raw,SystemClock.elapsedRealtime());
        if(result.equals("queued")){
            JsonObject o=JsonParser.parseString(raw).getAsJsonObject();motionSource=o.get("source").getAsString();
            motionRequested=o.get("direction").getAsString();motionError="queued";
        }return result;
    }
    void saveMotionProfile(JsonObject profile){
        motionAuthority.stop();pendingProfile.set(profile.deepCopy());
    }
    MotionSnapshot motionSnapshot(long now){
        JsonObject o=JsonParser.parseString(rawMotion).getAsJsonObject();
        boolean fresh=state()==LinkHealth.State.CONNECTED&&motionReceived>0&&now>=motionReceived&&now-motionReceived<4000;
        // Never leave a stale active arrow on screen beyond the controller's deadman window.
        if(fresh&&o.get("relay").getAsBoolean()&&now-motionReceived>=250)fresh=false;
        String reason=!fresh?"unavailable":!motionError.isEmpty()?motionError:o.get("reason").getAsString();
        if(fresh&&reason.equals("stopped")&&!o.get("configured").getAsBoolean())reason="calibration_required";
        String decision=fresh&&motionError.isEmpty()?o.get("decision").getAsString():"stop";
        return new MotionSnapshot(motionRequested,decision,reason,motionSource,now,
                fresh&&o.get("configured").getAsBoolean(),fresh&&motionError.isEmpty()&&o.get("relay").getAsBoolean());
    }

    private volatile EspSensorStatus sensors = EspSensorStatus.UNKNOWN;
    private long sensorPollAt;
    private long heartbeatAt, scanPollAt;
    private volatile LidarReading scan;
    private LidarFrame acceptedScan;
    private volatile long framesReceived, frameErrors, scanUnavailable, duplicateScans;
    EspLink(Context context) {
        this.context = context.getApplicationContext();
        status.setVisible(true);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r,"rastag-hardware-link"); t.setDaemon(true); return t;
        });
        // One serialized owner, no queued scans. Heartbeat always has priority.
        executor.scheduleWithFixedDelay(this::tick,0,50,TimeUnit.MILLISECONDS);
    }
    void setVisible(boolean value) {
        status.setVisible(value);
        if (!value) { Call pending = call; if (pending != null) pending.cancel(); }
    }
    LinkHealth.State state() { return status.state(SystemClock.elapsedRealtime()); }
    EspSensorStatus sensors() { return state() == LinkHealth.State.CONNECTED ? sensors : EspSensorStatus.UNKNOWN; }
    LidarReading scan() {
        LidarReading value=scan;
        return state()==LinkHealth.State.CONNECTED&&value!=null&&value.live(SystemClock.elapsedRealtime())?value:null;
    }
    JsonObject scanDiagnostics() {
        LidarReading value=scan();JsonObject o=new JsonObject();
        o.addProperty("live",value!=null);o.addProperty("frames_received",framesReceived);
        o.addProperty("frame_errors",frameErrors);o.addProperty("unavailable",scanUnavailable);o.addProperty("duplicates",duplicateScans);
        if(value!=null){o.addProperty("sequence",value.frame.sequence);o.addProperty("points",value.frame.count);
            o.addProperty("valid_points",value.frame.validCount);o.addProperty("nearest_mm",value.frame.nearestMm);
            o.addProperty("age_ms",value.ageMs(SystemClock.elapsedRealtime()));o.addProperty("round_trip_ms",value.roundTripMs);}
        return o;
    }
    long acknowledgedElapsedMs() {
        JsonObject value = latest;
        return value.has("last_ack_elapsed_ms") ? value.get("last_ack_elapsed_ms").getAsLong() : 0;
    }
    void close() {
        motionAuthority.stop();
        setVisible(false);
        // Serialize NSD cleanup after an in-flight tick so it cannot recreate a listener after close.
        executor.execute(() -> {
            stopDiscovery();
            EspPairing p = pair;
            if (p != null) { p.client.connectionPool().evictAll(); p.client.dispatcher().cancelAll(); }
        });
        executor.shutdown();
    }
    private void tick() {
        long generation = status.generation();
        long now=SystemClock.elapsedRealtime();
        MotionAuthority.Demand demand=motionAuthority.current(now);
        if(last!=null&&motionAuthority.takeStop()){sendMotion(null,null);return;}
        if(now<heartbeatAt){
            if(last!=null){
                if(demand!=null&&now>=motionSendAt){motionSendAt=now+80;sendMotion(demand,null);}
                else if(demand==null&&pendingProfile.get()!=null){JsonObject p=pendingProfile.getAndSet(null);sendMotion(null,p);}
                else if(demand==null&&now>=motionPollAt){motionPollAt=now+1000;readMotion();}
                else if(demand==null&&now>=scanPollAt&&heartbeatAt-now>50){scanPollAt=now+250;readScan();}
            }
            return;
        }
        heartbeatAt=now+2000;
        try {
            if (!status.isVisible()) {
                if (pair != null) { last = null; pair.client.connectionPool().evictAll(); stopDiscovery(); writeStatus("inactive", generation); }
                return;
            }
            if (pair == null) {
                pair = EspPairing.load(context,() -> address);
                if (pair == null) { writeStatus("unpaired", generation); return; }
                address = pair.hint;
            }
            if (discovery == null || ((address.isEmpty() || failures >= 3) && SystemClock.elapsedRealtime()-discoveryAt > 15000)) {
                stopDiscovery(); startDiscovery();
            }
            if (address.isEmpty()) { writeStatus("discovering", generation); return; }
            if (last != null && SystemClock.elapsedRealtime()-acknowledged >= EspProtocol.LEASE_MS) last = null;
            boolean fresh = last == null || last.sequence >= 0xffffffffL;
            long seq = fresh ? 0 : last.sequence+1;
            String body = fresh ? "{\"protocol\":1}" : EspProtocol.heartbeat(last.session,seq);
            Request request = new Request.Builder().url("https://"+pair.hostname+":8443/v1/"+(fresh?"session":"heartbeat"))
                    .post(RequestBody.create(body,MediaType.get("application/json"))).build();
            if (!status.isVisible()) return;
            call = pair.client.newCall(request);
            try (Response response = call.execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("Controller response");
                String raw = new String(EspPairing.read(response.body().byteStream(),2048),StandardCharsets.UTF_8);
                EspProtocol.Ack ack = EspProtocol.parse(raw,pair.device,fresh?null:last.boot,fresh?null:last.session,seq);
                if (!status.isVisible()) { last = null; writeStatus("inactive", generation); return; }
                if(fresh){motionAuthority.stop();motionSeq=0;motionReceived=0;motionError="unavailable";}
                last = ack; acknowledged = SystemClock.elapsedRealtime(); failures = 0;
                writeStatus("connected", generation);
            } finally { call = null; }
            if (motionAuthority.current(SystemClock.elapsedRealtime())==null && SystemClock.elapsedRealtime() >= sensorPollAt) {
                sensorPollAt = SystemClock.elapsedRealtime()+3000;
                readSensors();
            }
        } catch (Exception e) {
            motionAuthority.stop();motionError="transport_error";last = null; failures++;
            // Never persist exception text; providers may include private network or certificate information.
            writeStatus(pair == null ? "pairing_error" : "disconnected", generation);
        }
    }
    private void observeMotion(String raw) throws IOException {
        try{
            JsonObject o=JsonParser.parseString(raw).getAsJsonObject();
            if(o.get("protocol").getAsInt()!=3||last==null||!last.boot.equals(o.get("boot").getAsString()))throw new IllegalArgumentException();
            motionClock=o.get("uptime_ms").getAsBigDecimal().longValueExact();
            motionSeq=Math.max(motionSeq,o.get("seq").getAsBigDecimal().longValueExact());
            motionReceived=SystemClock.elapsedRealtime();rawMotion=o.toString();
        }catch(RuntimeException e){throw new IOException("Invalid motion observation");}
    }
    private void readMotion(){
        if(pair==null||last==null)return;
        try{
            call=pair.client.newBuilder().callTimeout(700,TimeUnit.MILLISECONDS).build().newCall(
                new Request.Builder().url("https://"+pair.hostname+":8443/v3/motion").get().build());
            try(Response response=call.execute()){
                if(!response.isSuccessful()||response.body()==null)throw new IOException();
                observeMotion(new String(EspPairing.read(response.body().byteStream(),4096),StandardCharsets.UTF_8));
                if(motionError.equals("unavailable")||motionError.equals("transport_error"))motionError="";
            }
        }catch(Exception e){motionError="unavailable";motionAuthority.stop();}
        finally{call=null;}
    }
    private void sendMotion(MotionAuthority.Demand demand,JsonObject profile){
        boolean stop=demand==null&&profile==null;
        if(pair==null||last==null){if(demand!=null)motionAuthority.failed(demand.run);return;}
        long now=SystemClock.elapsedRealtime();
        if(!stop&&(motionReceived<=0||now-motionReceived>4000)){if(demand!=null)motionAuthority.failed(demand.run);motionError="unavailable";return;}
        JsonObject request=profile==null?new JsonObject():profile.deepCopy();
        request.addProperty("boot",last.boot);request.addProperty("session",last.session);request.addProperty("seq",++motionSeq);
        request.addProperty("operation",stop?"stop":profile!=null?"configure":demand.operation);
        if(!stop)request.addProperty("expires_at_ms",motionClock+now-motionReceived+(profile!=null?1500:200));
        if(demand!=null){
            request.addProperty("run",demand.run);
            request.addProperty(demand.operation.equals("drive")?"direction":"left_cdeg",demand.a);
            request.addProperty(demand.operation.equals("drive")?"intensity":"right_cdeg",demand.b);
        }
        try{
            call=pair.client.newBuilder().callTimeout(stop||profile!=null?1500:220,TimeUnit.MILLISECONDS).build().newCall(
                new Request.Builder().url("https://"+pair.hostname+":8443/v3/motion")
                .post(RequestBody.create(request.toString(),MediaType.get("application/json"))).build());
            try(Response response=call.execute()){
                if(response.body()==null)throw new IOException();
                String raw=new String(EspPairing.read(response.body().byteStream(),4096),StandardCharsets.UTF_8);
                JsonObject o=JsonParser.parseString(raw).getAsJsonObject();
                if(!o.has("protocol"))throw new IOException();
                observeMotion(raw);
                if(response.isSuccessful()&&o.get("accepted").getAsBoolean()&&(demand!=null||profile!=null||motionError.equals("queued")))motionError="";
                if(!response.isSuccessful()||!o.get("accepted").getAsBoolean()){
                    if(demand!=null)motionAuthority.failed(demand.run);
                    motionError=o.get("reason").getAsString();
                }
            }
        }catch(Exception e){if(demand!=null)motionAuthority.failed(demand.run);motionError="transport_error";}
        finally{call=null;}
    }
    private void readScan() {
        if(!status.isVisible()||last==null)return;
        long started=SystemClock.elapsedRealtime();
        try{
            Request request=new Request.Builder().url("https://"+pair.hostname+":8443/v2/lidar/frame").get().build();
            call=pair.client.newBuilder().callTimeout(850,TimeUnit.MILLISECONDS).build().newCall(request);
            try(Response response=call.execute()){
                if(response.code()==503){scanUnavailable++;scan=null;return;}
                if(!response.isSuccessful()||response.body()==null)throw new IOException("Scan unavailable");
                byte[] bytes=EspPairing.read(response.body().byteStream(),LidarFrame.MAX_BYTES);
                long received=SystemClock.elapsedRealtime();
                if(!status.isVisible())return;
                LidarReading reading=LidarReading.receive(bytes,last.boot,started,received,acceptedScan);
                if(reading==null){duplicateScans++;return;}
                acceptedScan=reading.frame;scan=reading;framesReceived++;
            }
        }catch(Exception ignored){frameErrors++;scan=null;}
        finally{call=null;}
    }
    private void readSensors() {
        if (!status.isVisible() || last == null) return;
        try {
            Request request = new Request.Builder().url("https://"+pair.hostname+":8443/v2/state").get().build();
            // Bounded read-only probe; it cannot renew/take ownership of a session or delay a heartbeat indefinitely.
            call = pair.client.newBuilder().callTimeout(1500,TimeUnit.MILLISECONDS).build().newCall(request);
            try(Response response=call.execute()) {
                if(!response.isSuccessful()||response.body()==null)throw new IOException("Sensor status");
                sensors=EspSensorStatus.parse(new String(EspPairing.read(response.body().byteStream(),2048),StandardCharsets.UTF_8),
                        last.boot,SystemClock.elapsedRealtime());
            }
        }catch(Exception ignored){sensors=EspSensorStatus.UNKNOWN;}
        finally {call=null;}
    }
    @SuppressWarnings("deprecation")
    private void startDiscovery() {
        discoveryAt = SystemClock.elapsedRealtime();
        NsdManager nsd = (NsdManager)context.getSystemService(Context.NSD_SERVICE);
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String t) { }
            public void onDiscoveryStopped(String t) { }
            public void onStartDiscoveryFailed(String t,int code) { }
            public void onStopDiscoveryFailed(String t,int code) { }
            public void onServiceLost(NsdServiceInfo i) { }
            public void onServiceFound(NsdServiceInfo i) {
                if (!status.isVisible() || pair == null || resolving || !i.getServiceName().equals(pair.device)) return;
                resolving = true;
                try { nsd.resolveService(i,new NsdManager.ResolveListener() {
                    public void onResolveFailed(NsdServiceInfo s,int code) { resolving = false; }
                    public void onServiceResolved(NsdServiceInfo s) {
                        if (status.isVisible() && s.getPort() == 8443 && s.getHost() != null) address = s.getHost().getHostAddress();
                        resolving = false;
                    }
                }); } catch (RuntimeException e) { resolving = false; }
            }
        };
        nsd.discoverServices("_rastaglink._tcp.",NsdManager.PROTOCOL_DNS_SD,listener);
        discovery = listener;
    }
    private void stopDiscovery() {
        if (discovery == null) return;
        try { ((NsdManager)context.getSystemService(Context.NSD_SERVICE)).stopServiceDiscovery(discovery); }
        catch (RuntimeException ignored) { }
        discovery = null; resolving = false;
    }
    private void writeStatus(String state, long generation) {
        if (!status.update(state, acknowledged, generation)) return;
        if (!status.isVisible()) state = "inactive";
        try {
            JsonObject o = new JsonObject();
            o.addProperty("state",state); o.addProperty("updated_at",System.currentTimeMillis());
            o.addProperty("motion_enabled",false); o.addProperty("last_ack_elapsed_ms",acknowledged);
            o.addProperty("seq",last==null?0:last.sequence); o.addProperty("failures",failures);
            latest = o;
            AtomicFile f = new AtomicFile(new File(context.getNoBackupFilesDir(),"hardware_link_status.json"));
            FileOutputStream out = f.startWrite();
            try { out.write(o.toString().getBytes(StandardCharsets.UTF_8)); f.finishWrite(out); }
            catch (IOException e) { f.failWrite(out); }
        } catch (IOException ignored) { }
    }
}
