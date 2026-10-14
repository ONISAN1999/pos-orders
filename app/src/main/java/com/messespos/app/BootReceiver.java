package com.messespos.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/** เปิดฟองออเดอร์อัตโนมัติหลังเครื่อง POS บูตเสร็จ หรือหลังอัปเดตแอป */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (i == null) return;
        String a = i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(c)) return;
            if (!Cloud.ready(c)) return;
            c.startForegroundService(new Intent(c, OrderBubbleService.class));
        } catch (Exception ignored) {}
    }
}
