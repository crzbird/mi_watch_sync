package com.swipesync.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** 开机自启：按用户设置恢复同步服务 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }
        SharedPreferences p = context.getSharedPreferences(GestureService.PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(GestureService.KEY_AUTO_START, true)) {
            SyncService.start(context);
        }
    }
}
