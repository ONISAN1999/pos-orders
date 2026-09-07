package com.messespos.app;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** เอฟเฟกต์ตอนกด: เด้ง + เสียงคลิก + สั่นสั้นๆ */
public class Fx {

    private static AudioManager am;

    private static AudioManager audio(Context c) {
        if (am == null) am = (AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        return am;
    }

    /** เสียงคลิกของระบบ */
    public static void sound(Context c) {
        try { audio(c).playSoundEffect(AudioManager.FX_KEY_CLICK, 0.55f); } catch (Exception ignored) {}
    }

    /** สั่นสั้นมาก */
    public static void haptic(View v) {
        try {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Exception ignored) {}
    }

    /** สั่นแรงขึ้นนิด (ใช้ตอนคัดลอกสำเร็จ) */
    public static void buzz(Context c) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createOneShot(28, 90));
            else v.vibrate(28);
        } catch (Exception ignored) {}
    }

    /** เด้งย่อ-ขยาย */
    public static void bounce(View v) {
        v.animate().cancel();
        v.setScaleX(1f); v.setScaleY(1f);
        v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(150).setInterpolator(new OvershootInterpolator(2.4f)).start())
                .start();
    }

    /** กระพริบสว่างวาบ (ใช้ตอนคัดลอก) */
    public static void flash(View v) {
        v.animate().cancel();
        v.setAlpha(1f);
        v.animate().alpha(0.35f).setDuration(70)
                .withEndAction(() -> v.animate().alpha(1f).setDuration(200).start())
                .start();
    }

    /** ผูก onClick พร้อมเอฟเฟกต์ครบชุด */
    public static void onTap(View v, Runnable action) {
        v.setOnClickListener(x -> {
            sound(v.getContext());
            haptic(v);
            bounce(v);
            v.postDelayed(action, 60);
        });
    }

    /** ผูก onClick แบบ "คัดลอก" — เด้ง + วาบ + สั่น */
    public static void onCopyTap(View v, Runnable action) {
        v.setOnClickListener(x -> {
            sound(v.getContext());
            buzz(v.getContext());
            bounce(v);
            flash(v);
            v.postDelayed(action, 60);
        });
    }

    /** ผูก long-press พร้อมสั่นเตือน */
    public static void onHold(View v, Runnable action) {
        v.setOnLongClickListener(x -> {
            buzz(v.getContext());
            flash(v);
            v.postDelayed(action, 50);
            return true;
        });
    }
}
