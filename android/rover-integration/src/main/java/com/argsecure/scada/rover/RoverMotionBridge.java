package com.argsecure.scada.rover;
import android.content.*;
import android.os.SystemClock;
import androidx.core.content.ContextCompat;
import com.argsecure.mobility.api.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Signature IPC only. The mobility service and ESP independently enforce authority and obstacles. */
final class RoverMotionBridge {
    static String request(Context context,String turnId,String source,String direction,BooleanSupplier current)throws Exception{
        String id=UUID.nameUUIDFromBytes(("motion:"+turnId).getBytes(StandardCharsets.UTF_8)).toString().replace("-","");
        CountDownLatch reply=new CountDownLatch(1);AtomicReference<String> code=new AtomicReference<>("unavailable");
        BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){try{
            String raw=i.getStringExtra(MobilityContract.PAYLOAD);if(raw==null||raw.length()>2048)return;
            JsonObject o=JsonParser.parseString(raw).getAsJsonObject();
            if(id.equals(o.get("request_id").getAsString())){code.set(o.get("code").getAsString());reply.countDown();}
        }catch(RuntimeException ignored){}}};
        ContextCompat.registerReceiver(context,receiver,new IntentFilter(MotionContract.RESULT),MobilityContract.PERMISSION,null,ContextCompat.RECEIVER_EXPORTED);
        boolean submitted=false;
        try{
            if(!current.getAsBoolean())throw new CancellationException();
            boolean stop=direction.equals("stop");
            send(context,MotionRequest.json(id,source,direction,stop?0:20,stop?0:3000,SystemClock.elapsedRealtime()));submitted=true;
            long end=SystemClock.elapsedRealtime()+1800;
            while(SystemClock.elapsedRealtime()<end){
                if(!current.getAsBoolean())throw new CancellationException();
                if(reply.await(50,TimeUnit.MILLISECONDS))break;
            }
            if(code.get().equals("queued"))return "درخواست "+MotionContract.label(direction)+" به کنترلر ارسال شد. تصمیم نهایی و علت توقف روی صفحه نمایش داده می‌شود؛ حرکت فیزیکی هنوز تأیید نشده است.";
            if(code.get().equals("duplicate_request"))return "این درخواست قبلاً بررسی شده؛ دوباره اجرا نشد.";
            return MotionContract.reason(code.get());
        }catch(Exception failure){
            if(submitted)send(context,MotionRequest.json(UUID.randomUUID().toString().replace("-",""),source,"stop",0,0,SystemClock.elapsedRealtime()));
            throw failure;
        }finally{context.unregisterReceiver(receiver);}
    }
    private static void send(Context context,String raw){
        context.sendBroadcast(new Intent(MotionContract.REQUEST).setComponent(new ComponentName(MobilityContract.PACKAGE,MobilityContract.RECEIVER))
                .putExtra(MobilityContract.REPLY_PACKAGE,context.getPackageName()).putExtra(MobilityContract.PAYLOAD,raw),MobilityContract.PERMISSION);
    }
}
