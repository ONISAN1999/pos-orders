package com.messespos.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import static com.messespos.app.UI.*;

public class MainActivity extends Activity {

    private EditText dbUrl, shopCode;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(INK);
        sv.setFillViewport(true);

        LinearLayout root = col(this);
        root.setPadding(dp(this, 20), dp(this, 30), dp(this, 20), dp(this, 30));

        TextView h1 = text(this, "🧾 POS ออเดอร์", 24, true, WHITE);
        h1.setGravity(Gravity.CENTER);
        root.addView(h1);

        TextView sub = text(this, "ออเดอร์เด้งเป็นฟองลอยบนเครื่อง POS พร้อมจับเวลา", 13, false, WHITE_DIM);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(this, 4), 0, dp(this, 20));
        root.addView(sub);

        TextView start = button(this, "▶   เปิดฟองออเดอร์", primary(this, 15), 16);
        Fx.onTap(start, this::startBubble);
        root.addView(start, lp(MATCH, WRAP));

        TextView stop = button(this, "■   ปิดฟองออเดอร์", glass(this, GLASS, 15, STROKE), 15);
        Fx.onTap(stop, () -> {
            stopService(new Intent(this, OrderBubbleService.class));
            Toast.makeText(this, "ปิดแล้ว", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams stLp = lp(MATCH, WRAP);
        stLp.topMargin = dp(this, 10);
        root.addView(stop, stLp);

        TextView note = text(this,
                "ครั้งแรกต้องกดอนุญาด \"แสดงทับแอปอื่น\" (Display over other apps) แล้วกดเปิดอีกครั้ง",
                12, false, 0xFFFFE6B8);
        note.setPadding(0, dp(this, 12), 0, dp(this, 22));
        root.addView(note);

        root.addView(text(this, "เชื่อมกับมือถือ", 16, true, WHITE));
        TextView cnote = text(this,
                "ใส่ลิงก์ Realtime Database ของ Firebase (ลงท้าย .firebasedatabase.app หรือ .firebaseio.com) "
                        + "แล้วใส่รหัสร้านให้ตรงกับที่ตั้งไว้ในแอปมือถือ",
                12, false, WHITE_DIM);
        cnote.setPadding(0, dp(this, 5), 0, dp(this, 4));
        root.addView(cnote);

        dbUrl = addField(root, "ลิงก์ฐานข้อมูล", Cloud.dbUrl(this));
        dbUrl.setHint("https://xxxx-default-rtdb.asia-southeast1.firebasedatabase.app");
        shopCode = addField(root, "รหัสร้าน", Cloud.shop(this));

        TextView save = button(this, "💾   บันทึกการเชื่อมต่อ", green(this, 14), 16);
        LinearLayout.LayoutParams svLp = lp(MATCH, WRAP);
        svLp.topMargin = dp(this, 16);
        Fx.onTap(save, () -> {
            Cloud.save(this, dbUrl.getText().toString(), shopCode.getText().toString());
            Toast.makeText(this, "บันทึกแล้ว", Toast.LENGTH_SHORT).show();
        });
        root.addView(save, svLp);

        TextView test = button(this, "🔌   ทดสอบการเชื่อมต่อ", glass(this, GLASS, 14, STROKE), 15);
        LinearLayout.LayoutParams tLp = lp(MATCH, WRAP);
        tLp.topMargin = dp(this, 10);
        Fx.onTap(test, this::testConnection);
        root.addView(test, tLp);

        TextView demo = button(this, "🧪   สร้างออเดอร์ทดสอบ", glass(this, GLASS, 14, STROKE), 15);
        LinearLayout.LayoutParams dLp = lp(MATCH, WRAP);
        dLp.topMargin = dp(this, 10);
        Fx.onTap(demo, this::pushDemo);
        root.addView(demo, dLp);

        TextView guide = text(this,
                "วิธีตั้ง Firebase (ทำครั้งเดียว)\n"
                        + "1. เข้า console.firebase.google.com → สร้างโปรเจกต์\n"
                        + "2. เมนู Build → Realtime Database → Create Database\n"
                        + "3. เลือก Singapore (asia-southeast1) → Start in test mode\n"
                        + "4. ก๊อปลิงก์ที่ขึ้นต้น https:// มาใส่ช่องด้านบน ทั้งเครื่อง POS และมือถือ",
                12, false, 0xFFBFD4FF);
        guide.setPadding(0, dp(this, 24), 0, 0);
        guide.setLineSpacing(dp(this, 4), 1f);
        root.addView(guide);

        sv.addView(root);
        setContentView(sv);
    }

    private EditText addField(LinearLayout parent, String label, String value) {
        TextView l = text(this, label, 12.5f, false, WHITE_DIM);
        l.setPadding(0, dp(this, 12), 0, dp(this, 5));
        parent.addView(l);
        EditText e = input(this, label);
        e.setText(value);
        parent.addView(e, lp(MATCH, WRAP));
        return e;
    }

    private void testConnection() {
        Cloud.save(this, dbUrl.getText().toString(), shopCode.getText().toString());
        if (!Cloud.ready(this)) {
            Toast.makeText(this, "ใส่ลิงก์ฐานข้อมูลก่อน", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            String msg;
            try {
                int n = Cloud.fetch(MainActivity.this).size();
                msg = "เชื่อมต่อสำเร็จ — มี " + n + " ออเดอร์ในระบบ";
            } catch (Exception e) {
                msg = "เชื่อมต่อไม่ได้: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void pushDemo() {
        Cloud.save(this, dbUrl.getText().toString(), shopCode.getText().toString());
        if (!Cloud.ready(this)) {
            Toast.makeText(this, "ใส่ลิงก์ฐานข้อมูลก่อน", Toast.LENGTH_SHORT).show();
            return;
        }
        Order o = new Order();
        o.no = String.valueOf((System.currentTimeMillis() / 1000) % 100);
        o.place = "ทดสอบ";
        o.lines.add("กุ้งเผา 1 กิโล 350 บาท");
        o.lines.add("ข้าว 1 ถุง 10 บาท");
        o.total = 360;
        o.pay = "ช่องทางชำระ : โอนปกติ / สถานะ : ชำระแล้ว";
        o.text = "กุ้งเผา 1 กิโล 350 บาท\nข้าว 1 ถุง 10 บาท\n\nยอดรวมทั้งหมด 360 บาท";
        new Thread(() -> {
            String msg;
            try { Cloud.push(MainActivity.this, o); msg = "ส่งออเดอร์ทดสอบแล้ว"; }
            catch (Exception e) { msg = "ส่งไม่สำเร็จ: " + e.getMessage(); }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void startBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "กรุณาอนุญาต \"แสดงทับแอปอื่น\" แล้วกดเปิดอีกครั้ง", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Cloud.save(this, dbUrl.getText().toString(), shopCode.getText().toString());
        startForegroundService(new Intent(this, OrderBubbleService.class));
        Toast.makeText(this, "เปิดฟองออเดอร์แล้ว", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }
}
