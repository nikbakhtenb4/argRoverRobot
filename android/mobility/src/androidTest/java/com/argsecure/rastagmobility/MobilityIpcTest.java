package com.argsecure.rastagmobility;

import android.content.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.core.content.ContextCompat;
import com.argsecure.mobility.api.*;
import com.google.gson.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MobilityIpcTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private String exchange(String action,String payload,String replyAction) throws Exception {
        ArrayBlockingQueue<String> queue=new ArrayBlockingQueue<>(1);
        BroadcastReceiver receiver=new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent i){queue.offer(i.getStringExtra(MobilityContract.PAYLOAD));}
        };
        ContextCompat.registerReceiver(context,receiver,new IntentFilter(replyAction),MobilityContract.PERMISSION,null,ContextCompat.RECEIVER_EXPORTED);
        try {
            Intent request=new Intent(action).setComponent(new ComponentName(MobilityContract.PACKAGE,MobilityContract.RECEIVER))
                    .putExtra(MobilityContract.REPLY_PACKAGE,context.getPackageName());
            if(payload!=null)request.putExtra(MobilityContract.PAYLOAD,payload);
            context.sendBroadcast(request,MobilityContract.PERMISSION);
            String result=queue.poll(15,TimeUnit.SECONDS);assertNotNull("IPC timed out",result);return result;
        } finally {context.unregisterReceiver(receiver);}
    }
    private String request(String op,long age) {
        JsonObject o=new JsonObject();o.addProperty("version",1);o.addProperty("request_id",UUID.randomUUID().toString().replace("-",""));
        o.addProperty("issued_elapsed_ms",SystemClock.elapsedRealtime()-age);o.addProperty("ttl_ms",2000);o.addProperty("operation",op);return o.toString();
    }
    @Test public void protectedQueryReportsOnlyRealCapabilities() throws Exception {
        String raw=exchange(MobilityContract.QUERY,null,MobilityContract.STATUS);
        MobilitySnapshot.parse(raw,SystemClock.elapsedRealtime());
        JsonObject o=JsonParser.parseString(raw).getAsJsonObject();
        assertFalse(o.get("motion_enabled").getAsBoolean());assertTrue(o.get("lidar_state").getAsString().matches("live|unavailable"));
        for(String secret:new String[]{"client_key","server_pin","ip_hint","hostname","session"})assertFalse(o.has(secret));
    }
    @Test public void stopRequestNeverClaimsPhysicalStop() throws Exception {
        String command=request("stop",0);
        JsonObject result=JsonParser.parseString(exchange(MobilityContract.COMMAND,command,MobilityContract.RESULT)).getAsJsonObject();
        assertEquals("motion_unavailable",result.get("code").getAsString());assertFalse(result.get("stop_confirmed").getAsBoolean());
        result=JsonParser.parseString(exchange(MobilityContract.COMMAND,command,MobilityContract.RESULT)).getAsJsonObject();
        assertEquals("duplicate_request",result.get("code").getAsString());
    }
    @Test public void expiredAndMalformedCommandsCannotBeAccepted() throws Exception {
        JsonObject result=JsonParser.parseString(exchange(MobilityContract.COMMAND,request("move",10000),MobilityContract.RESULT)).getAsJsonObject();
        assertEquals("expired_request",result.get("code").getAsString());
        result=JsonParser.parseString(exchange(MobilityContract.COMMAND,"{}",MobilityContract.RESULT)).getAsJsonObject();
        assertEquals("invalid_request",result.get("code").getAsString());
    }
    @Test public void motionStopIsDeduplicatedAndDoesNotClaimPhysicalCompletion() throws Exception {
        String command=MotionRequest.json(UUID.randomUUID().toString().replace("-",""),"ai","stop",0,0,SystemClock.elapsedRealtime());
        JsonObject result=JsonParser.parseString(exchange(MotionContract.REQUEST,command,MotionContract.RESULT)).getAsJsonObject();
        assertEquals("queued",result.get("code").getAsString());assertFalse(result.get("physical_motion_verified").getAsBoolean());
        result=JsonParser.parseString(exchange(MotionContract.REQUEST,command,MotionContract.RESULT)).getAsJsonObject();
        assertEquals("duplicate_request",result.get("code").getAsString());
    }
    @Test public void expiredAiMotionCannotActuate() throws Exception {
        String command=MotionRequest.json(UUID.randomUUID().toString().replace("-",""),"ai","forward",20,3000,SystemClock.elapsedRealtime()-10000);
        JsonObject result=JsonParser.parseString(exchange(MotionContract.REQUEST,command,MotionContract.RESULT)).getAsJsonObject();
        assertEquals("expired_request",result.get("code").getAsString());assertFalse(result.get("queued").getAsBoolean());
    }
    @Test public void motionPanelOpensWithoutEnablingDrive() throws Exception {
        android.app.Instrumentation test=InstrumentationRegistry.getInstrumentation();
        android.app.Activity activity=test.startActivitySync(new Intent(context,MotionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{test.runOnMainSync(()->{assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag("motion_stop"));
            assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag("motion_decision"));});}
        finally{test.runOnMainSync(activity::finish);}
    }
}
