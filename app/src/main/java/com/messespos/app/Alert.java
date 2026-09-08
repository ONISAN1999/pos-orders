package com.messespos.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * เสียงแจ้งเตือนออเดอร์ใหม่ — ดังซ้ำจนกว่าจะกดอ่าน แล้วเงียบทันที
 *
 * ใช้ 2 ทางพร้อมกัน เผื่อเครื่อง POS ไม่มีเสียงแจ้งเตือนตั้งไว้:
 *  1. ToneGenerator ปี๊บ 3 ครั้ง (ช่องเสียง ALARM — ดังแม้เครื่องปิดเสียงแจ้งเตือน)
 *  2. Ringtone ของเครื่อง (ALARM → NOTIFICATION → RINGTONE)
 */
public class Alert {

    private static Ringtone tone;
    private static ToneGenerator beeper;
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static boolean ringing = false;
    private static final long REPEAT_MS = 8000;    // ย้ำทุก 8 วิ

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

    /** ดัง 1 รอบ (ปี๊บ 3 ครั้ง + ริงโทน + สั่น) */
    public static void ping(Context c) {
        beep3();
        ringtone(c);
        vibrate(c);
    }

    private static void beep3() {
        try {
            if (beeper == null) beeper = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            final ToneGenerator b = beeper;
            b.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 260);
            h.postDelayed(() -> { try { b.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 260); } catch (Exception ignored) {} }, 380);
            h.postDelayed(() -> { try { b.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 420); } catch (Exception ignored) {} }, 760);
        } catch (Exception e) {
            beeper = null;
        }
    }

    private static void ringtone(Context c) {
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (u == null) return;
            if (tone != null && tone.isPlaying()) tone.stop();
            tone = RingtoneManager.getRingtone(c.getApplicationContext(), u);
            if (tone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tone.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                }
                tone.play();
                // ริงโทนปลุกบางตัวยาวมาก — ตัดที่ 3 วิ
                final Ringtone t = tone;
                h.postDelayed(() -> { try { if (t.isPlaying()) t.stop(); } catch (Exception ignored) {} }, 3000);
            }
        } catch (Exception ignored) {}
    }

    private static void vibrate(Context c) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                long[] pattern = {0, 220, 140, 220, 140, 360};
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
        try { if (beeper != null) beeper.stopTone(); } catch (Exception ignored) {}
    }

    public static boolean isRinging() { return ringing; }
}
