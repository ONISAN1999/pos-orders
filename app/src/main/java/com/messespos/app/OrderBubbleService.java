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

    private static final int MAIN_DP = 70;
    private static final int CHILD_DP = 64;
    private static final int PAD_DP = 8;                       // ขอบว่างรอบฟองย่อย ให้พื้นที่ตอนเต้น
    private static final int CHILD_WIN = CHILD_DP + PAD_DP * 2; // ขนาดหน้าต่างจริงของฟองย่อย
    private static final int RADIUS_DP = 104;
    private TextView mainIcon;

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

        mainIcon = text(this, "🧾", 20, false, WHITE);
        mainIcon.setGravity(Gravity.CENTER);
        b.addView(mainIcon);

        mainCount = text(this, "0 ออเดอร์", 10.5f, true, WHITE);
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
        for (View v : childViews) { stopPulse(v); try { wm.removeView(v); } catch (Exception ignored) {} }
        childViews.clear();
        childParams.clear();

        int win = dp(this, CHILD_WIN);
        int s = dp(this, CHILD_DP);
        for (Order o : orders) {
            if (o.status.equals(Order.ST_DONE)) continue;

            // หน้าต่างนอก (โปร่ง) → วงกลมข้างใน (เต้นได้โดยไม่โดนตัดขอบ)
            android.widget.FrameLayout outer = new android.widget.FrameLayout(this);
            LinearLayout c = col(this);
            c.setGravity(Gravity.CENTER);
            c.setElevation(dp(this, 9));
            android.widget.FrameLayout.LayoutParams cl = new android.widget.FrameLayout.LayoutParams(s, s);
            cl.gravity = Gravity.CENTER;
            outer.addView(c, cl);

            TextView tag = text(this, "", 11, true, 0xFF11141C);
            tag.setGravity(Gravity.CENTER);
            tag.setSingleLine(true);
            c.addView(tag);

            TextView clk = text(this, "", 12.5f, true, 0xFF11141C);
            clk.setGravity(Gravity.CENTER);
            clk.setSingleLine(true);
            c.addView(clk);

            final String oid = o.id;
            Fx.onTap(c, () -> openDetail(oid));

            WindowManager.LayoutParams p = new WindowManager.LayoutParams(win, win, wtype(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.START;

            childViews.add(outer);
            childParams.add(p);
            wm.addView(outer, p);
            if (!expanded) outer.setVisibility(View.GONE);
            bindChild(outer, o);
        }
        layoutChildren();
        refreshMain();
    }

    /** ใส่ข้อความ/สี/สถานะอ่านแล้วให้ฟองย่อย 1 ฟอง */
    private void bindChild(View outer, Order o) {
        LinearLayout c = (LinearLayout) ((android.widget.FrameLayout) outer).getChildAt(0);
        TextView tag = (TextView) c.getChildAt(0);
        TextView clk = (TextView) c.getChildAt(1);
        boolean unread = unreadIds.contains(o.id);

        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(o.tierColor());
        g.setStroke(dp(this, unread ? 4 : 2), unread ? 0xFFFF2D55 : 0xB3FFFFFF);
        c.setBackground(g);

        tag.setText(unread ? "ใหม่!" : o.tag());
        tag.setTextColor(unread ? 0xFFB3001B : 0xFF11141C);
        clk.setText(o.clock());

        if (unread) startPulse(c); else stopPulse(c);
    }

    /** ฟองแม่: ไอคอน + จำนวน; มีออเดอร์ยังไม่อ่าน → กระดิ่ง + ขอบแดง + เต้น */
    private void refreshMain() {
        int n = activeOrders().size();
        int unread = unreadIds.size();
        if (mainCount != null) mainCount.setText(unread > 0 ? "ใหม่ " + unread : n + " ออเดอร์");
        if (mainIcon != null) mainIcon.setText(unread > 0 ? "🔔" : "🧾");
        if (mainBubble != null) {
            if (unread > 0) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColors(new int[]{0xFFFF3B5C, 0xFFFF7A45});
                g.setStroke(dp(this, 3), 0xFFFFFFFF);
                mainBubble.setBackground(g);
                startPulse(mainBubble);
            } else {
                mainBubble.setBackground(bubbleBg(this));
                stopPulse(mainBubble);
            }
        }
    }

    private void startPulse(View v) {
        if (v.getTag() instanceof android.animation.Animator) return;
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofPropertyValuesHolder(v,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.13f, 1f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.13f, 1f));
        a.setDuration(900);
        a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        a.start();
        v.setTag(a);
    }

    private void stopPulse(View v) {
        if (v == null) return;
        View target = v;
        if (v instanceof android.widget.FrameLayout && ((android.widget.FrameLayout) v).getChildCount() > 0)
            target = ((android.widget.FrameLayout) v).getChildAt(0);
        if (target.getTag() instanceof android.animation.Animator) {
            ((android.animation.Animator) target.getTag()).cancel();
            target.setTag(null);
        }
        target.setScaleX(1f); target.setScaleY(1f);
    }

    /** วางฟองย่อยเป็นวงรอบฟองแม่ */
    private void layoutChildren() {
        int n = childViews.size();
        if (n == 0) return;
        int mainS = dp(this, MAIN_DP), childS = dp(this, CHILD_WIN);
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
        List<Order> act = activeOrders();
        for (int i = 0; i < childViews.size() && i < act.size(); i++) bindChild(childViews.get(i), act.get(i));
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

    /** อัปเดตสถานะอ่านแล้ว/ยังไม่อ่าน ทั้งฟองย่อยและฟองแม่ */
    private void refreshBadges() {
        refreshClocks();
        refreshMain();
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
        panel.setPadding(dp(this, 20), dp(this, 16), dp(this, 20), dp(this, 16));
        panel.setElevation(dp(this, 18));

        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean wide = dm.widthPixels > dm.heightPixels;   // จอ POS แนวนอน → 2 คอลัมน์

        // ---------- หัว ----------
        LinearLayout head = row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleCol = col(this);
        titleCol.addView(text(this, (o.no.isEmpty() ? "ออเดอร์" : "ออเดอร์ #" + o.no)
                + (o.place.isEmpty() ? "" : "  •  " + o.place), 22, true, WHITE));
        TextView sub = text(this, "รอมาแล้ว " + o.waitedMin() + " นาที", 13, false, WHITE_DIM);
        sub.setPadding(0, dp(this, 3), 0, 0);
        titleCol.addView(sub);
        head.addView(titleCol, lpw(1));

        TextView stChip = chip(this, o.status, false);
        stChip.setBackground(glass(this, o.status.equals(Order.ST_DOING) ? 0x59F97316 : 0x33FFFFFF, 20,
                o.status.equals(Order.ST_DOING) ? 0x99F97316 : 0x55FFFFFF));
        head.addView(stChip);
        TextView close = chip(this, "✕", false);
        LinearLayout.LayoutParams clp = lp(WRAP, WRAP); clp.leftMargin = dp(this, 8);
        close.setLayoutParams(clp);
        Fx.onTap(close, this::closePanel);
        head.addView(close);
        panel.addView(head, lp(MATCH, WRAP));

        // ---------- รายการ (ซ้าย) ----------
        ScrollView sv = new ScrollView(this);
        LinearLayout list = col(this);
        java.util.regex.Pattern pricePat = java.util.regex.Pattern.compile("^(.*\\S)\\s+(\\d[\\d,]*)\\s*บาท$");
        for (String line : o.lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("ช่องทางชำระ") || t.startsWith("สถานะ")) continue;   // ไปอยู่การ์ดขวาแล้ว
            final String s = t;
            LinearLayout rowv = row(this);
            rowv.setGravity(Gravity.CENTER_VERTICAL);
            rowv.setBackground(glass(this, 0x1FFFFFFF, 12, 0x33FFFFFF));
            rowv.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
            java.util.regex.Matcher m = pricePat.matcher(t);
            if (m.find()) {
                rowv.addView(text(this, m.group(1), 17, true, WHITE), lpw(1));
                TextView pr = text(this, m.group(2) + " บาท", 16, true, OK_GREEN);
                pr.setPadding(dp(this, 12), 0, 0, 0);
                rowv.addView(pr);
            } else {
                rowv.addView(text(this, t, 16, false, WHITE), lpw(1));
            }
            LinearLayout.LayoutParams lp2 = lp(MATCH, WRAP);
            lp2.bottomMargin = dp(this, 8);
            Fx.onCopyTap(rowv, () -> copy(s, "คัดลอก: " + s));
            list.addView(rowv, lp2);
        }
        TextView hint = text(this, "แตะรายการเพื่อคัดลอกทีละบรรทัด", 11, false, 0xFF8FA0BD);
        hint.setPadding(dp(this, 4), dp(this, 2), 0, 0);
        list.addView(hint);
        sv.addView(list);

        // ---------- สรุป + ปุ่ม (ขวา) ----------
        LinearLayout side = col(this);
        LinearLayout info = col(this);
        info.setBackground(glass(this, 0x14FFFFFF, 16, 0x2EFFFFFF));
        info.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        info.addView(text(this, "ยอดรวม", 12, false, WHITE_DIM));
        TextView tot = text(this, (o.total > 0 ? o.total : 0) + " บาท", 30, true, OK_GREEN);
        info.addView(tot);
        if (!o.pay.isEmpty()) {
            View div = new View(this);
            div.setBackgroundColor(0x33FFFFFF);
            LinearLayout.LayoutParams dl = lp(MATCH, dp(this, 1));
            dl.topMargin = dp(this, 10); dl.bottomMargin = dp(this, 10);
            info.addView(div, dl);
            for (String pl : o.pay.split("\\n")) {
                String q = pl.trim();
                if (q.isEmpty()) continue;
                int i = q.indexOf(":");
                LinearLayout pr = row(this);
                if (i > 0) {
                    pr.addView(text(this, q.substring(0, i).trim(), 13, false, WHITE_DIM), lpw(1));
                    pr.addView(text(this, q.substring(i + 1).trim(), 14, true, 0xFFFFE6B8));
                } else {
                    pr.addView(text(this, q, 13, false, 0xFFFFE6B8), lpw(1));
                }
                LinearLayout.LayoutParams prl = lp(MATCH, WRAP); prl.topMargin = dp(this, 4);
                info.addView(pr, prl);
            }
        }
        side.addView(info, lp(MATCH, WRAP));

        View spacer = new View(this);
        side.addView(spacer, lpw(1));

        if (!o.status.equals(Order.ST_DOING)) {
            TextView doing = button(this, "🔥  รับออเดอร์ / กำลังทำ", glass(this, 0x59F97316, 14, 0x99F97316), 15);
            Fx.onTap(doing, () -> updateStatus(o, Order.ST_DOING));
            LinearLayout.LayoutParams dl = lp(MATCH, WRAP); dl.topMargin = dp(this, 10);
            side.addView(doing, dl);
        }
        TextView done = button(this, "✓  เสร็จแล้ว", green(this, 14), 16);
        Fx.onTap(done, () -> updateStatus(o, Order.ST_DONE));
        LinearLayout.LayoutParams dnl = lp(MATCH, WRAP); dnl.topMargin = dp(this, 8);
        side.addView(done, dnl);
        TextView copyAll = button(this, "📋  คัดลอกทั้งออเดอร์", glass(this, GLASS, 14, STROKE), 13.5f);
        Fx.onCopyTap(copyAll, () -> copy(o.text.isEmpty() ? joinLines(o) : o.text, "คัดลอกออเดอร์แล้ว"));
        LinearLayout.LayoutParams cal = lp(MATCH, WRAP); cal.topMargin = dp(this, 8);
        side.addView(copyAll, cal);

        // ---------- ประกอบ ----------
        int bodyH = Math.min(dp(this, 360), (int) (dm.heightPixels * 0.62f));
        if (wide) {
            LinearLayout body = row(this);
            LinearLayout.LayoutParams ll = lpw(3); ll.rightMargin = dp(this, 16);
            body.addView(sv, ll);
            body.addView(side, lpw(2));
            LinearLayout.LayoutParams bl = lp(MATCH, bodyH); bl.topMargin = dp(this, 14);
            panel.addView(body, bl);
        } else {
            LinearLayout.LayoutParams sl = lp(MATCH, Math.min(dp(this, 240), bodyH)); sl.topMargin = dp(this, 12);
            panel.addView(sv, sl);
            LinearLayout.LayoutParams sdl = lp(MATCH, WRAP); sdl.topMargin = dp(this, 10);
            panel.addView(side, sdl);
        }

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
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.min(dm.widthPixels, dp(this, 1000));   // จอกว้างมาก → จำกัดความกว้างให้อ่านง่าย
        panelParams = new WindowManager.LayoutParams(w, WRAP, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;

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
