package com.example.callrecorderuploader.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Boot completed, starting MiUiCallRecordMonitorService.");
            Intent serviceIntent = new Intent(context, MiUiCallRecordMonitorService.class);
            // For Android O and above, startForegroundService must be used.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Exception e){
                // This can happen if the app is in a stopped state (e.g., after force-stop)
                // or if background restrictions are very tight.
                Log.e(TAG, "Failed to start MiUiCallRecordMonitorService on boot: " + e.getMessage());
            }

            // Optionally, also start the WebSocket service if phone numbers are saved
            // SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
            // String phone1 = prefs.getString(MainActivity.KEY_PHONE_NUMBER_1, "");
            // if (!phone1.isEmpty()) {
            //     Intent wsIntent = new Intent(context, AppWebSocketClientService.class);
            //     wsIntent.setAction(AppWebSocketClientService.ACTION_CONNECT_ON_BOOT);
            //     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            //         context.startForegroundService(wsIntent);
            //     } else {
            //         context.startService(wsIntent);
            //     }
            // }
        }
    }
}