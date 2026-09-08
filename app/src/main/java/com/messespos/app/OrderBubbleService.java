package com.messespos.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import static com.messespos.app.UI.*;

/**
 * Bubble แม่ (ตรงกลาง) + Bubble ย่อยรายออเดอร์วงรอบ
 * ลากตัวแม่ → ตัวย่อยตามมาทั้งพวง
 */
public class OrderBubbleService extends Service {

    private WindowManager wm;
    private View mainBubble;
    private TextView mainCount;
    private WindowManager.LayoutParams mainParams;

    private final List<View> childViews = new ArrayList<>();
    private final List<WindowManager.LayoutParams> childParams = new ArrayList<>();
    private List<Order> orders = new ArrayList<>();

    private LinearLayout panel;
    private WindowManager.LayoutParams panelParams;
    private String openOrderId = null;

    private final java.util.Set<String> seenIds = new java.util.HashSet<>();
    private final java.util.Set<String> unreadIds = new java.util.HashSet<>();

    private boolean expanded = true;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final String CH = "pos_orders";

    private static final int MAIN_DP = 66;
    private static final int CHILD_DP = 58;
    private static final int RADIUS_DP = 96;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForegroundNotif();
        addMainBubble();
        ui.post(tick);
        ui.post(poll);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void startForegroundNotif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CH, "POS ออเดอร์", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("POS ออเดอร์ กำลังทำงาน")
                .setContentText("แตะฟองลอยเพื่อดูออเดอร์")
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }

    private int wtype() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /* ================= Bubble แม่ ================= */

    private void addMainBubble() {
        LinearLayout b = col(this);
        b.setGravity(Gravity.CENTER);
        b.setBackground(bubbleBg(this));
        b.setElevation(dp(this, 12));

        TextView icon = text(this, "🧾", 20, false, WHITE);
        icon.setGravity(Gravity.CENTER);
        b.addView(icon);

        mainCount = text(this, "0", 13, true, WHITE);
        mainCount.setGravity(Gravity.CENTER);
        b.addView(mainCount);

        mainBubble = b;

        int s = dp(this, MAIN_DP);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        mainParams = new WindowManager.LayoutParams(s, s, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        mainParams.gravity = Gravity.TOP | Gravity.START;
        mainParams.x = (dm.widthPixels - s) / 2;      // เริ่มตรงกลางจอ
        mainParams.y = (dm.heightPixels - s) / 2;

        mainBubble.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy; float tx, ty; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        ix = mainParams.x; iy = mainParams.y;
                        tx = e.getRawX(); ty = e.getRawY(); moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - tx), dy = (int) (e.getRawY() - ty);
                        if (Math.abs(dx) > dp(OrderBubbleService.this, 6)
                                || Math.abs(dy) > dp(OrderBubbleService.this, 6)) moved = true;
                        mainParams.x = ix + dx;
                        mainParams.y = iy + dy;
                        wm.updateViewLayout(mainBubble, mainParams);
                        layoutChildren();   // ลูกตามพ่อ
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            Fx.sound(OrderBubbleService.this);
                            Fx.bounce(mainBubble);
                            if (panel != null) closePanel();
                            else if (childViews.isEmpty()) openList();
                            else toggleExpand();
                        }
                        return true;
                }
                return false;
            }
        });
        mainBubble.setOnLongClickListener(v -> { Fx.buzz(this); openList(); return true; });

        wm.addView(mainBubble, mainParams);
    }

    private void toggleExpand() {
        expanded = !expanded;
        for (View v : childViews) v.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    /* ================= Bubble ย่อย ================= */

    /** สร้าง/รีเฟรชฟองย่อยให้ตรงกับรายการออเดอร์ */
    private void rebuildChildren() {
        for (View v : childViews) { try { wm.removeView(v); } catch (Exception ignored) {} }
        childViews.clear();
        childParams.clear();

        int s = dp(this, CHILD_DP);
        for (Order o : orders) {
            if (o.status.equals(Order.ST_DONE)) continue;

            LinearLayout c = col(this);
            c.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(o.tierColor());
            g.setStroke(dp(this, 2), 0x99FFFFFF);
            c.setBackground(g);
            c.setElevation(dp(this, 9));

            TextView no = text(this, o.no.isEmpty() ? "•" : "#" + o.no, 13, true, 0xFF11141C);
            no.setGravity(Gravity.CENTER);
            c.addView(no);

            TextView clk = text(this, o.clock(), 11.5f, true, 0xFF11141C);
            clk.setGravity(Gravity.CENTER);
            c.addView(clk);

            final String oid = o.id;
            Fx.onTap(c, () -> openDetail(oid));

            WindowManager.LayoutParams p = new WindowManager.LayoutParams(s, s, wtype(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.START;

            childViews.add(c);
            childParams.add(p);
            wm.addView(c, p);
            if (!expanded) c.setVisibility(View.GONE);
        }
        layoutChildren();
        mainCount.setText(String.valueOf(childViews.size()));
        refreshBadges();
    }

    /** วางฟองย่อยเป็นวงรอบฟองแม่ */
    private void layoutChildren() {
        int n = childViews.size();
        if (n == 0) return;
        int mainS = dp(this, MAIN_DP), childS = dp(this, CHILD_DP);
        int cx = mainParams.x + mainS / 2;
        int cy = mainParams.y + mainS / 2;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int perRing = 8;

        for (int i = 0; i < n; i++) {
            int ring = i / perRing;
            int idxInRing = i % perRing;
            int inThisRing = Math.min(perRing, n - ring * perRing);
            double step = 2 * Math.PI / Math.max(inThisRing, 3);
            double ang = -Math.PI / 2 + idxInRing * step;
            int r = dp(this, RADIUS_DP + ring * (CHILD_DP + 12));

            int x = cx + (int) (Math.cos(ang) * r) - childS / 2;
            int y = cy + (int) (Math.sin(ang) * r) - childS / 2;

            // กันหลุดขอบจอ
            x = Math.max(0, Math.min(x, dm.widthPixels - childS));
            y = Math.max(0, Math.min(y, dm.heightPixels - childS));

            WindowManager.LayoutParams p = childParams.get(i);
            p.x = x; p.y = y;
            try { wm.updateViewLayout(childViews.get(i), p); } catch (Exception ignored) {}
        }
    }

    /* ================= นาฬิกา + ดึงข้อมูล ================= */

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshClocks();
            ui.postDelayed(this, 20000);   // อัปเดตเวลาแต่ละ 20 วิ
        }
    };

    private void refreshClocks() {
        for (int i = 0; i < childViews.size() && i < activeOrders().size(); i++) {
            Order o = activeOrders().get(i);
            LinearLayout c = (LinearLayout) childViews.get(i);
            if (c.getChildCount() >= 2) ((TextView) c.getChildAt(1)).setText(o.clock());
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(o.tierColor());
            g.setStroke(dp(this, 2), 0x99FFFFFF);
            c.setBackground(g);
        }
    }

    private List<Order> activeOrders() {
        List<Order> a = new ArrayList<>();
        for (Order o : orders) if (!o.status.equals(Order.ST_DONE)) a.add(o);
        return a;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            fetchOrders();
            ui.postDelayed(this, 6000);    // ดึงออเดอร์ใหม่ทุก 6 วิ
        }
    };

    private void fetchOrders() {
        if (!Cloud.ready(this)) return;
        new Thread(() -> {
            try {
                final List<Order> got = Cloud.fetch(OrderBubbleService.this);
                ui.post(() -> {
                    boolean isNew = false;
                    for (Order o : got) {
                        if (o.status.equals(Order.ST_DONE)) continue;
                        if (!seenIds.contains(o.id)) {
                            seenIds.add(o.id);
                            unreadIds.add(o.id);
                            isNew = true;
                        }
                    }
                    orders = got;
                    rebuildChildren();
                    if (isNew) {
                        Fx.bounce(mainBubble);
                        Alert.start(OrderBubbleService.this);   // ดังจนกว่าจะกดอ่าน
                    }
                    if (unreadIds.isEmpty()) Alert.stop();
                });
            } catch (Exception ignored) {}
        }).start();
    }

    /** ทำเครื่องหมายว่าอ่านแล้ว — เงียบเสียงเตือนถ้าอ่านครบ */
    private void markRead(String id) {
        if (id == null) unreadIds.clear();
        else unreadIds.remove(id);
        if (unreadIds.isEmpty()) Alert.stop();
        refreshBadges();
    }

    /** จุดแดงเล็กบนฟองที่ยังไม่ได้อ่าน */
    private void refreshBadges() {
        List<Order> act = activeOrders();
        for (int i = 0; i < childViews.size() && i < act.size(); i++) {
            LinearLayout c = (LinearLayout) childViews.get(i);
            if (c.getChildCount() >= 1 && c.getChildAt(0) instanceof TextView) {
                Order o = act.get(i);
                TextView no = (TextView) c.getChildAt(0);
                String base = o.no.isEmpty() ? "•" : "#" + o.no;
                no.setText(unreadIds.contains(o.id) ? "🔔" + base : base);
            }
        }
    }

    /* ================= Popup รายละเอียด ================= */

    private void openDetail(String id) {
        Order found = null;
        for (Order o : orders) if (o.id.equals(id)) found = o;
        if (found == null) return;
        final Order o = found;
        openOrderId = id;
        markRead(id);          // กดอ่านแล้ว → เงียบ

        closePanel();
        panel = col(this);
        panel.setBackground(glass(this, 0xF51B1F2E, 22, STROKE));
        panel.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16));
        panel.setElevation(dp(this, 18));

        // หัว
        LinearLayout head = row(this);
        LinearLayout titleCol = col(this);
        titleCol.addView(text(this, "ออเดอร์ " + (o.no.isEmpty() ? "" : "ที่ " + o.no)
                + (o.place.isEmpty() ? "" : "  •  " + o.place), 16, true, WHITE));
        TextView sub = text(this, "รอมาแล้ว " + o.waitedMin() + " นาที   •   " + o.status, 12, false, WHITE_DIM);
        sub.setPadding(0, dp(this, 3), 0, 0);
        titleCol.addView(sub);
        head.addView(titleCol, lpw(1));

        TextView close = chip(this, "✕", false);
        Fx.onTap(close, this::closePanel);
        head.addView(close);
        panel.addView(head, lp(MATCH, WRAP));

        // รายการ — แตะบรรทัดเพื่อคัดลอกทีละรายการ
        ScrollView sv = new ScrollView(this);
        LinearLayout list = col(this);
        for (String line : o.lines) {
            final String s = line;
            TextView t = text(this, s, 14, false, WHITE);
            t.setBackground(glass(this, 0x1FFFFFFF, 11, 0x33FFFFFF));
            t.setPadding(dp(this, 12), dp(this, 11), dp(this, 12), dp(this, 11));
            LinearLayout.LayoutParams lp2 = lp(MATCH, WRAP);
            lp2.bottomMargin = dp(this, 7);
            Fx.onCopyTap(t, () -> copy(s, "คัดลอก: " + s));
            list.addView(t, lp2);
        }
        if (o.total > 0) {
            TextView tt = text(this, "ยอดรวม " + o.total + " บาท", 15, true, OK_GREEN);
            tt.setPadding(dp(this, 2), dp(this, 6), 0, 0);
            list.addView(tt);
        }
        if (!o.pay.isEmpty()) {
            TextView pt = text(this, o.pay, 12.5f, false, 0xFFFFE6B8);
            pt.setPadding(dp(this, 2), dp(this, 5), 0, 0);
            list.addView(pt);
        }
        sv.addView(list);
        LinearLayout.LayoutParams svLp = lp(MATCH, dp(this, 260));
        svLp.topMargin = dp(this, 13);
        panel.addView(sv, svLp);

        // ปุ่มคัดลอกทั้งหมด
        TextView copyAll = button(this, "📋   คัดลอกทั้งออเดอร์", glass(this, GLASS, 14, STROKE), 14);
        LinearLayout.LayoutParams caLp = lp(MATCH, WRAP);
        caLp.topMargin = dp(this, 12);
        Fx.onCopyTap(copyAll, () -> copy(o.text.isEmpty() ? joinLines(o) : o.text, "คัดลอกออเดอร์แล้ว"));
        panel.addView(copyAll, caLp);

        // ปุ่มสถานะ
        LinearLayout acts = row(this);
        if (!o.status.equals(Order.ST_DOING)) {
            TextView doing = button(this, "🔥  กำลังทำ", glass(this, 0x59F97316, 14, 0x99F97316), 14);
            Fx.onTap(doing, () -> updateStatus(o, Order.ST_DOING));
            acts.addView(doing, lpw(1));
        }
        TextView done = button(this, "✓  เสร็จ", green(this, 14), 14);
        LinearLayout.LayoutParams dLp = lpw(1);
        dLp.leftMargin = acts.getChildCount() > 0 ? dp(this, 8) : 0;
        Fx.onTap(done, () -> updateStatus(o, Order.ST_DONE));
        acts.addView(done, dLp);
        LinearLayout.LayoutParams acLp = lp(MATCH, WRAP);
        acLp.topMargin = dp(this, 9);
        panel.addView(acts, acLp);

        showPanel();
    }

    private String joinLines(Order o) {
        StringBuilder sb = new StringBuilder();
        for (String s : o.lines) sb.append(s).append("\n");
        if (o.total > 0) sb.append("ยอดรวมทั้งหมด ").append(o.total).append(" บาท");
        return sb.toString().trim();
    }

    private void updateStatus(Order o, String status) {
        o.status = status;
        if (Cloud.ready(this)) {
            final String id = o.id;
            new Thread(() -> {
                try {
                    if (status.equals(Order.ST_DONE)) Cloud.setStatus(OrderBubbleService.this, id, status);
                    else Cloud.setStatus(OrderBubbleService.this, id, status);
                } catch (Exception ignored) {}
            }).start();
        }
        Toast.makeText(this, "ออเดอร์ " + (o.no.isEmpty() ? "" : "#" + o.no) + " → " + status,
                Toast.LENGTH_SHORT).show();
        closePanel();
        rebuildChildren();
    }

    /* ================= รายการออเดอร์ทั้งหมด ================= */

    private void openList() {
        markRead(null);        // เปิดรายการ = อ่านทั้งหมด → เงียบ
        closePanel();
        panel = col(this);
        panel.setBackground(glass(this, 0xF51B1F2E, 22, STROKE));
        panel.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16));
        panel.setElevation(dp(this, 18));

        LinearLayout head = row(this);
        head.addView(text(this, "🧾 ออเดอร์ทั้งหมด  " + ver(this), 16, true, WHITE), lpw(1));
        TextView refresh = chip(this, "⟳", false);
        Fx.onTap(refresh, () -> { fetchOrders(); Toast.makeText(this, "กำลังดึงออเดอร์…", Toast.LENGTH_SHORT).show(); });
        head.addView(refresh);
        TextView close = chip(this, "✕", false);
        LinearLayout.LayoutParams clp = lp(WRAP, WRAP);
        clp.leftMargin = dp(this, 5);
        close.setLayoutParams(clp);
        Fx.onTap(close, this::closePanel);
        head.addView(close);
        panel.addView(head, lp(MATCH, WRAP));

        ScrollView sv = new ScrollView(this);
        LinearLayout list = col(this);

        if (!Cloud.ready(this)) {
            TextView warn = text(this, "ยังไม่ได้ตั้งค่าคลาวด์ — เปิดแอป POS ออเดอร์ แล้วใส่ลิงก์ฐานข้อมูลก่อน",
                    13, false, 0xFFFFE6B8);
            warn.setPadding(0, dp(this, 12), 0, 0);
            list.addView(warn);
        } else if (orders.isEmpty()) {
            TextView em = text(this, "ยังไม่มีออเดอร์เข้ามา", 13.5f, false, WHITE_DIM);
            em.setPadding(0, dp(this, 14), 0, 0);
            list.addView(em);
        }

        List<Order> act = new ArrayList<>(), hist = new ArrayList<>();
        for (Order o : orders) {
            if (o.status.equals(Order.ST_DONE)) hist.add(o); else act.add(o);
        }

        if (!act.isEmpty()) {
            TextView h1 = text(this, "กำลังดำเนินการ (" + act.size() + ")", 12.5f, true, 0xFFFFD18F);
            h1.setPadding(dp(this, 2), 0, 0, dp(this, 7));
            list.addView(h1);
            for (Order o : act) addOrderRow(list, o, false);
        }

        if (!hist.isEmpty()) {
            TextView h2 = text(this, "ประวัติออเดอร์ที่เสร็จแล้ว (" + hist.size() + ")", 12.5f, true, 0xFF9FE1CB);
            h2.setPadding(dp(this, 2), dp(this, 10), 0, dp(this, 7));
            list.addView(h2);
            for (Order o : hist) addOrderRow(list, o, true);
        }

        sv.addView(list);
        LinearLayout.LayoutParams svLp = lp(MATCH, dp(this, 320));
        svLp.topMargin = dp(this, 12);
        panel.addView(sv, svLp);

        TextView clearDone = button(this, "🧹   ล้างออเดอร์ที่เสร็จแล้ว", glass(this, GLASS, 14, STROKE), 13.5f);
        LinearLayout.LayoutParams cdLp = lp(MATCH, WRAP);
        cdLp.topMargin = dp(this, 10);
        Fx.onTap(clearDone, this::clearDone);
        panel.addView(clearDone, cdLp);

        showPanel();
    }

    /** 1 แถวในรายการออเดอร์ */
    private void addOrderRow(LinearLayout list, Order o, boolean done) {
        LinearLayout rowv = row(this);
        rowv.setBackground(glass(this, done ? 0x12FFFFFF : 0x1FFFFFFF, 12, done ? 0x22FFFFFF : 0x33FFFFFF));
        rowv.setPadding(dp(this, 12), dp(this, 11), dp(this, 12), dp(this, 11));

        TextView dot = text(this, "●", 15, true, done ? 0xFF22C55E : o.tierColor());
        dot.setPadding(0, 0, dp(this, 9), 0);
        rowv.addView(dot);

        LinearLayout info = col(this);
        info.addView(text(this, (o.no.isEmpty() ? "ออเดอร์" : "ออเดอร์ที่ " + o.no)
                + (o.place.isEmpty() ? "" : "  •  " + o.place), 13.5f, true, done ? WHITE_DIM : WHITE));
        TextView meta = text(this, (done ? "ใช้เวลา " : "รอมา ") + o.waitedMin() + " นาที  •  " + o.status
                + (o.total > 0 ? "  •  " + o.total + " บาท" : ""), 11.5f, false, WHITE_DIM);
        meta.setPadding(0, dp(this, 3), 0, 0);
        info.addView(meta);
        rowv.addView(info, lpw(1));

        final String oid = o.id;
        Fx.onTap(rowv, () -> openDetail(oid));

        LinearLayout.LayoutParams rl = lp(MATCH, WRAP);
        rl.bottomMargin = dp(this, 8);
        list.addView(rowv, rl);
    }

    private void clearDone() {
        final List<String> ids = new ArrayList<>();
        for (Order o : orders) if (o.status.equals(Order.ST_DONE)) ids.add(o.id);
        if (ids.isEmpty()) { Toast.makeText(this, "ไม่มีออเดอร์ที่เสร็จแล้ว", Toast.LENGTH_SHORT).show(); return; }
        new Thread(() -> {
            for (String id : ids) {
                try { Cloud.remove(OrderBubbleService.this, id); } catch (Exception ignored) {}
            }
            ui.post(() -> { fetchOrders(); closePanel(); });
        }).start();
        Toast.makeText(this, "ล้าง " + ids.size() + " ออเดอร์แล้ว", Toast.LENGTH_SHORT).show();
    }

    /* ================= panel helper ================= */

    private void showPanel() {
        panelParams = new WindowManager.LayoutParams(MATCH, WRAP, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.BOTTOM;

        panel.setFocusableInTouchMode(true);
        panel.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) {
                closePanel();
                return true;
            }
            return false;
        });

        wm.addView(panel, panelParams);
        panel.requestFocus();
    }

    private void closePanel() {
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) {}
            panel = null;
        }
        openOrderId = null;
    }

    private void copy(String txt, String msg) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("pos", txt));
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override public void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(poll);
        Alert.stop();
        closePanel();
        for (View v : childViews) { try { wm.removeView(v); } catch (Exception ignored) {} }
        childViews.clear();
        if (mainBubble != null) { try { wm.removeView(mainBubble); } catch (Exception ignored) {} }
    }
}
