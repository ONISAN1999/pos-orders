package com.messespos.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * เสียงแจ้งเตือนออเดอร์ใหม่ — ดังซ้ำจนกว่าจะกดอ่าน แล้วเงียบทันที
 */
public class Alert {

    private static Ringtone tone;
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static boolean ringing = false;
    private static final long REPEAT_MS = 12000;   // ย้ำทุก 12 วิ

    /** เริ่มเตือน (ถ้ากำลังเตือนอยู่แล้วจะไม่ซ้อน) */
    public static void start(Context c) {
        if (ringing) return;
        ringing = true;
        h.post(loop(c.getApplicationContext()));
    }

    private static Runnable loop(final Context c) {
        return new Runnable() {
            @Override public void run() {
                if (!ringing) return;
                ping(c);
                h.postDelayed(this, REPEAT_MS);
            }
        };
    }

    /** ดัง 1 ครั้ง */
    public static void ping(Context c) {
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (tone != null && tone.isPlaying()) tone.stop();
            tone = RingtoneManager.getRingtone(c.getApplicationContext(), u);
            if (tone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tone.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                }
                tone.play();
            }
        } catch (Exception ignored) {}

        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                long[] pattern = {0, 220, 140, 220};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                else v.vibrate(pattern, -1);
            }
        } catch (Exception ignored) {}
    }

    /** เงียบทันที (เรียกตอนกดอ่านออเดอร์) */
    public static void stop() {
        ringing = false;
        h.removeCallbacksAndMessages(null);
        try { if (tone != null && tone.isPlaying()) tone.stop(); } catch (Exception ignored) {}
    }

    public static boolean isRinging() { return ringing; }
}
