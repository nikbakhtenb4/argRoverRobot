package com.argsecure.rastagmobility;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.argsecure.mobility.api.MobilityContract;
import com.argsecure.mobility.api.MotionContract;

/** Manifest signature permission authenticates senders before this receiver executes. */
public final class MobilityReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!MobilityContract.START.equals(action) && !MobilityContract.QUERY.equals(action)
                && !MobilityContract.COMMAND.equals(action) && !MotionContract.REQUEST.equals(action)) return;
        Intent service = new Intent(context, MobilityService.class).setAction(action);
        // Forward only bounded, typed fields; never an arbitrary Intent or Bundle.
        try {
            String reply = intent.getStringExtra(MobilityContract.REPLY_PACKAGE);
            if (reply != null && reply.length() <= 160) service.putExtra(MobilityContract.REPLY_PACKAGE, reply);
            String payload = intent.getStringExtra(MobilityContract.PAYLOAD);
            if (payload != null && payload.length() <= 2048) service.putExtra(MobilityContract.PAYLOAD, payload);
            start(context, service);
        } catch (RuntimeException ignored) { /* Malformed IPC or OS background restriction. */ }
    }
    static void start(Context context, Intent service) {
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
        else context.startService(service);
    }
}
