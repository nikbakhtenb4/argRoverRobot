package com.argsecure.rastagmobility;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class MobilityBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) {
            MobilityReceiver.start(context, new Intent(context, MobilityService.class));
        }
    }
}
