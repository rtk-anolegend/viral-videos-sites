package com.callmonitor.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if(intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
            boolean wasRunning = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .getBoolean("running", false);

            if(wasRunning) {
                Intent service = new Intent(context, MonitorService.class);
                context.startService(service);
            }
        }
    }
}
