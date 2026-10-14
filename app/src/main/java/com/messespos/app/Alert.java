package com.messespos.app;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * เสียงแจ้งเตือน — แยกเสียงตามเหตุการณ์ให้พนักงานฟังออกทันทีว่าต้องทำอะไร
 *
 *  NEW      ออเดอร์ใหม่       ปี๊บสั้น 3 ครั้ง ถี่ (ตื๊ด-ตื๊ด-ตื๊ด)        → ไปกดรับออเดอร์
 *  EDIT     แก้ไขออเดอร์      โทนขึ้น-ลง 2 รอบ (ตี๊-ต่อ ตี๊-ต่อ)           → เปิดดูว่าแก้ตรงไหน แล้วกดรับทราบ
 *  HARVEST  เก็บกุ้งจากบ่อ    ไซเรนยาว ขึ้นลง (วี้-หว่อ)                 → ไปบ่อ แล้วกดรับทราบ
 *  READY    ครบ 40 นาที       ติ๊ง 2 ครั้ง ช้าๆ (ติ๊ง … ติ๊ง)              → เตรียมส่ง ไม่ต้องกด
 *  NET      เน็ตหลุด          เสียงต่ำสั้น 1 ครั้ง                       → เช็ค WiFi
 *
 * NEW / EDIT / HARVEST จะดังซ้ำจนกว่าจะกดรับ — READY / NET ดังครั้งเดียว
 */
public class Alert {

    public static final int NEW = 0, EDIT = 1, HARVEST = 2, READY = 3, NET = 4;

    private static ToneGenerator beeper;
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static boolean ringing = false;
    private static int ringKind = NEW;
    private static final long REPEAT_MS = 8000;

    /** เริ่มเตือนแบบวนซ้ำ (ถ้ากำลังเตือนอยู่แล้ว จะเปลี่ยนเป็นเสียงของเหตุการณ์ล่าสุดที่สำคัญกว่า) */
    public static void start(Context c, int kind) {
        if (ringing) {
            // ลำดับความสำคัญ: HARVEST > EDIT > NEW
            if (kind > ringKind) ringKind = kind;
            return;
        }
        ringing = true;
        ringKind = kind;
        h.post(loop(c.getApplicationContext()));
    }

    /** รองรับโค้ดเดิม */
    public static void start(Context c) { start(c, NEW); }

    private static Runnable loop(final Context c) {
        return new Runnable() {
            @Override public void run() {
                if (!ringing) return;
                ping(c, ringKind);
                h.postDelayed(this, REPEAT_MS);
            }
        };
    }

    /** ดัง 1 รอบตามชนิด */
    public static void ping(Context c, int kind) {
        try {
            if (beeper == null) beeper = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            final ToneGenerator b = beeper;
            switch (kind) {
                case NEW:
                    tone(b, ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 220, 0);
                    tone(b, ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 220, 320);
                    tone(b, ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 420, 640);
                    vibrate(c, new long[]{0, 200, 120, 200, 120, 350});
                    break;
                case EDIT:
                    tone(b, ToneGenerator.TONE_CDMA_HIGH_L, 350, 0);
                    tone(b, ToneGenerator.TONE_CDMA_LOW_L, 350, 400);
                    tone(b, ToneGenerator.TONE_CDMA_HIGH_L, 350, 900);
                    tone(b, ToneGenerator.TONE_CDMA_LOW_L, 350, 1300);
                    vibrate(c, new long[]{0, 400, 150, 400});
                    break;
                case HARVEST:
                    tone(b, ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 2500, 0);
                    vibrate(c, new long[]{0, 800, 200, 800, 200, 800});
                    break;
                case READY:
                    tone(b, ToneGenerator.TONE_PROP_BEEP2, 300, 0);
                    tone(b, ToneGenerator.TONE_PROP_BEEP2, 300, 900);
                    vibrate(c, new long[]{0, 150, 700, 150});
                    break;
                case NET:
                    tone(b, ToneGenerator.TONE_CDMA_LOW_SS, 500, 0);
                    vibrate(c, new long[]{0, 300});
                    break;
            }
        } catch (Exception e) {
            beeper = null;
        }
    }

    /** รองรับโค้ดเดิม */
    public static void ping(Context c) { ping(c, NEW); }

    private static void tone(final ToneGenerator b, final int t, final int ms, long delay) {
        if (delay == 0) { try { b.startTone(t, ms); } catch (Exception ignored) {} }
        else h.postDelayed(() -> { try { b.startTone(t, ms); } catch (Exception ignored) {} }, delay);
    }

    private static void vibrate(Context c, long[] pattern) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                else v.vibrate(pattern, -1);
            }
        } catch (Exception ignored) {}
    }

    /** เงียบทันที */
    public static void stop() {
        ringing = false;
        h.removeCallbacksAndMessages(null);
        try { if (beeper != null) beeper.stopTone(); } catch (Exception ignored) {}
    }

    public static boolean isRinging() { return ringing; }
}
