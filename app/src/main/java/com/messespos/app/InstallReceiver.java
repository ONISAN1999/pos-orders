package com.messespos.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

/** รับผลจาก PackageInstaller: ต้องให้ผู้ใช้กดยืนยัน → เปิดหน้าต่างยืนยัน (หรือแจ้งเตือนให้แตะ) */
public class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            boolean opened = false;
            try { c.startActivity(confirm); opened = true; } catch (Exception ignored) {}
            try {
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel("app_update", "อัปเดตแอป", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
                int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
                PendingIntent pi = PendingIntent.getActivity(c, 7003, confirm, fl);
                Notification n = new Notification.Builder(c, "app_update")
                        .setContentTitle("ยืนยันการอัปเดต v" + Updater.latestName)
                        .setContentText(opened ? "ถ้าหน้าต่างยืนยันไม่เด้ง แตะที่นี่" : "แตะเพื่อเปิดหน้าต่างยืนยันการติดตั้ง")
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build();
                nm.notify(7001, n);
            } catch (Exception ignored) {}
        } else if (st == PackageInstaller.STATUS_SUCCESS) {
            try { Toast.makeText(c, "✅ อัปเดตแอปเรียบร้อย", Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
        } else {
            try { Toast.makeText(c, "อัปเดตไม่สำเร็จ (" + st + ") " + (msg == null ? "" : msg), Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
        }
    }
}
