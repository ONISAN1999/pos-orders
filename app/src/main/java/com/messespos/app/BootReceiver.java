package com.messespos.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/** เปิดฟองออเดอร์อัตโนมัติหลังเครื่อง POS บูตเสร็จ */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (i == null || !Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(c)) return;
            if (!Cloud.ready(c)) return;
            c.startForegroundService(new Intent(c, OrderBubbleService.class));
        } catch (Exception ignored) {}
    }
}
