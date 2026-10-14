package com.messespos.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * อัปเดตแอปอัตโนมัติจาก GitHub (dist/version.json + dist/*.apk)
 *  1. check()   อ่าน version.json เทียบ versionCode กับแอปที่ติดตั้ง
 *  2. install() โหลด APK ลง cache แล้วส่งให้ PackageInstaller → ระบบเด้งหน้าต่างให้กด "ติดตั้ง/อัปเดต"
 *  ใช้ลายเซ็นเดิม จึงติดตั้งทับได้ ข้อมูลในเครื่องไม่หาย
 */
public class Updater {

    public static final String REPO = "pos-orders";
    public static final String VERSION_URL = "https://raw.githubusercontent.com/ONISAN1999/" + REPO + "/main/dist/version.json";
    private static final String CH = "app_update";

    public static int latestCode = 0;
    public static String latestName = "", latestApk = "", latestNotes = "";
    public static long checkedAt = 0;
    public static volatile boolean busy = false;
    public static volatile int progress = 0;     // % ดาวน์โหลด

    private static final Handler ui = new Handler(Looper.getMainLooper());

    public interface CB { void done(boolean ok, String msg); }

    public static int myCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    public static String myName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    public static boolean available(Context c) { return latestCode > myCode(c) && !latestApk.isEmpty(); }

    /** ตรวจเวอร์ชันล่าสุดจาก GitHub (พื้นหลัง) */
    public static void check(final Context c, final CB cb) {
        new Thread(() -> {
            try {
                String body = get(VERSION_URL + "?t=" + System.currentTimeMillis(), null);
                JSONObject o = new JSONObject(body);
                latestCode = o.optInt("versionCode", 0);
                latestName = o.optString("versionName", "");
                latestApk = o.optString("apk", "");
                latestNotes = o.optString("notes", "");
                checkedAt = System.currentTimeMillis();
                final boolean av = available(c);
                ui.post(() -> { if (cb != null) cb.done(av, av ? "มีเวอร์ชันใหม่ v" + latestName + " (ตอนนี้ v" + myName(c) + ")" : "เป็นเวอร์ชันล่าสุดแล้ว (v" + myName(c) + ")"); });
            } catch (Exception e) {
                ui.post(() -> { if (cb != null) cb.done(false, "ตรวจอัปเดตไม่ได้: " + e.getMessage()); });
            }
        }).start();
    }

    /** โหลด APK แล้วเริ่มติดตั้ง (ระบบจะเด้งหน้าต่างให้ยืนยัน) */
    public static void install(final Context c, final CB cb) {
        if (busy) { if (cb != null) cb.done(false, "กำลังอัปเดตอยู่แล้ว…"); return; }
        if (latestApk.isEmpty()) { if (cb != null) cb.done(false, "ยังไม่ได้ตรวจเวอร์ชัน"); return; }
        busy = true; progress = 0;
        new Thread(() -> {
            try {
                File f = new File(c.getCacheDir(), "update.apk");
                downloadTo(latestApk + "?t=" + System.currentTimeMillis(), f);
                if (f.length() < 50000) throw new Exception("ไฟล์ APK ไม่สมบูรณ์");

                PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(c.getPackageName());
                int id = pi.createSession(p);
                PackageInstaller.Session s = pi.openSession(id);
                try (OutputStream out = s.openWrite("update.apk", 0, f.length()); InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent i = new Intent(c, InstallReceiver.class);
                i.setAction("com.messespos.app.INSTALL_STATUS");
                int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pend = PendingIntent.getBroadcast(c, 7001, i, fl);
                s.commit(pend.getIntentSender());
                s.close();
                ui.post(() -> { if (cb != null) cb.done(true, "โหลดเสร็จแล้ว — กด \"อัปเดต\" ในหน้าต่างที่เด้งขึ้น"); });
            } catch (Exception e) {
                ui.post(() -> { if (cb != null) cb.done(false, "อัปเดตไม่สำเร็จ: " + e.getMessage()); });
            } finally { busy = false; }
        }).start();
    }

    /** แจ้งเตือนว่ามีเวอร์ชันใหม่ — แตะเพื่อเริ่มอัปเดต */
    public static void notify(Context c, Class<?> service) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH, "อัปเดตแอป", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
            Intent i = new Intent(c, service).putExtra("act", "update");
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getService(c, 7002, i, fl);
            Notification n = new Notification.Builder(c, CH)
                    .setContentTitle("🆕 มีเวอร์ชันใหม่ v" + latestName)
                    .setContentText("ตอนนี้ v" + myName(c) + " — แตะเพื่ออัปเดต (ข้อมูลไม่หาย)")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(7000, n);
        } catch (Exception ignored) {}
    }

    /* ---------------- HTTP ---------------- */

    private static String get(String url, File saveTo) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000); con.setReadTimeout(30000);
        con.setRequestProperty("Cache-Control", "no-cache");
        int code = con.getResponseCode();
        if (code < 200 || code >= 300) { con.disconnect(); throw new Exception("HTTP " + code); }
        InputStream in = con.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close(); con.disconnect();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static void downloadTo(String url, File f) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000); con.setReadTimeout(60000);
        con.setRequestProperty("Cache-Control", "no-cache");
        int code = con.getResponseCode();
        if (code < 200 || code >= 300) { con.disconnect(); throw new Exception("HTTP " + code); }
        long total = con.getContentLengthLong();
        try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[65536]; int n; long got = 0;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); got += n; if (total > 0) progress = (int) (got * 100 / total); }
        }
        con.disconnect();
        progress = 100;
    }
}
