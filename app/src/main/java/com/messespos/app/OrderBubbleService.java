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
    private View mainBubble;              // หน้าต่างฟองแม่ (โปร่ง) = วงกลม + ป้ายแดง + จุดคลาวด์
    private LinearLayout mainDisc;        // วงกลมขาวขอบส้ม
    private android.widget.ImageView mainIconV;
    private TextView mainBadge;
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
    private final java.util.Map<String, Long> seenEdit = new java.util.HashMap<>();   // editedAt ล่าสุดที่เคยเห็น
    private View listBubble;              // ปุ่ม 📋 เล็กติดฟองแม่ → เปิดรายการ/ประวัติได้ตลอด
    private TextView netDot;              // จุดสัญญาณคลาวด์ (เขียว/เหลือง/แดง) มุมบนขวาของฟองแม่
    private WindowManager.LayoutParams netParams;
    private boolean netLost = false;
    private WindowManager.LayoutParams listParams;
    private final java.util.Map<String, Integer> readyPinged = new java.util.HashMap<>();   // เตือน "ครบ 40 นาที" ไปแล้วกี่รอบ
    private static final int READY_MIN = 40;
    private long harvestShownAt = 0;      // สัญญาณเก็บกุ้งที่เปิด popup ไปแล้ว
    private boolean harvestPanel = false; // popup เก็บกุ้งเปิดอยู่

    private boolean expanded = true;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final String CH = "pos_orders";

    private static final int MAIN_DP = 70;
    private static final int MAIN_PAD = 12;                     // ขอบว่างรอบวงกลมแม่ (ที่วางป้าย + เงา)
    private static final int MAIN_WIN = MAIN_DP + MAIN_PAD * 2;
    private static final int CHILD_DP = 66;
    private static final int PAD_DP = 8;                       // ขอบว่างรอบฟองย่อย ให้พื้นที่ตอนเต้น
    private static final int CHILD_WIN = CHILD_DP + PAD_DP * 2; // ขนาดหน้าต่างจริงของฟองย่อย
    private static final int RADIUS_DP = 104;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForegroundNotif();
        addMainBubble();
        ui.post(tick);
        ui.postDelayed(housekeeping, 60000);
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
        android.widget.FrameLayout outer = new android.widget.FrameLayout(this);
        int s = dp(this, MAIN_DP), win = dp(this, MAIN_WIN);

        LinearLayout b = col(this);
        b.setGravity(Gravity.CENTER);
        b.setBackground(bubbleBg(this));
        b.setElevation(dp(this, 10));
        android.widget.FrameLayout.LayoutParams bl = new android.widget.FrameLayout.LayoutParams(s, s);
        bl.gravity = Gravity.CENTER;
        outer.addView(b, bl);
        mainDisc = b;

        mainIconV = new android.widget.ImageView(this);
        mainIconV.setImageResource(R.drawable.ic_receipt);
        mainIconV.setColorFilter(0xFFF05A28);
        b.addView(mainIconV, lp(dp(this, 30), dp(this, 30)));

        mainCount = text(this, "0 ออเดอร์", 10, true, 0xFF1F2430);
        mainCount.setGravity(Gravity.CENTER);
        mainCount.setPadding(0, dp(this, 1), 0, 0);
        b.addView(mainCount);

        // ป้ายแดง: จำนวนออเดอร์ที่ยังไม่รับ
        mainBadge = text(this, "", 11.5f, true, WHITE);
        mainBadge.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFEF4444); bg.setCornerRadius(dp(this, 20)); bg.setStroke(dp(this, 2), WHITE);
        mainBadge.setBackground(bg);
        mainBadge.setMinWidth(dp(this, 24));
        mainBadge.setPadding(dp(this, 6), dp(this, 1), dp(this, 6), dp(this, 1));
        mainBadge.setElevation(dp(this, 14));
        mainBadge.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams bdl = new android.widget.FrameLayout.LayoutParams(WRAP, dp(this, 24));
        bdl.gravity = Gravity.TOP | Gravity.END;
        bdl.topMargin = dp(this, MAIN_PAD - 6); bdl.rightMargin = dp(this, MAIN_PAD - 6);
        outer.addView(mainBadge, bdl);

        // จุดสถานะคลาวด์ มุมล่างขวาในฟอง
        netDot = text(this, "", 1, false, 0);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL); g.setColor(0xFF94A3B8); g.setStroke(dp(this, 2), WHITE);
        netDot.setBackground(g);
        netDot.setElevation(dp(this, 14));
        int ds = dp(this, 14);
        android.widget.FrameLayout.LayoutParams ndl = new android.widget.FrameLayout.LayoutParams(ds, ds);
        ndl.gravity = Gravity.BOTTOM | Gravity.END;
        ndl.bottomMargin = dp(this, MAIN_PAD + 2); ndl.rightMargin = dp(this, MAIN_PAD + 4);
        outer.addView(netDot, ndl);

        mainBubble = outer;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        mainParams = new WindowManager.LayoutParams(win, win, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        mainParams.gravity = Gravity.TOP | Gravity.START;
        mainParams.x = (dm.widthPixels - win) / 2;      // เริ่มตรงกลางจอ
        mainParams.y = (dm.heightPixels - win) / 2;

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
                        placeListBubble();
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            Fx.sound(OrderBubbleService.this);
                            Fx.bounce(mainDisc);
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
        addListBubble();
    }

    private void refreshNetDot() {
        if (netDot == null) return;
        long age = Cloud.lastOk == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - Cloud.lastOk;
        int color = age < 20000 ? 0xFF22C55E : age < 60000 ? 0xFFFACC15 : 0xFFEF4444;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(this, 2), 0xFFFFFFFF);
        netDot.setBackground(g);
        boolean lost = age >= 60000 && Cloud.ready(this);
        if (lost != netLost) {
            netLost = lost;
            if (lost) {
                if (mainDisc != null) {
                    GradientDrawable gb = new GradientDrawable();
                    gb.setShape(GradientDrawable.OVAL);
                    gb.setColor(0xFFF1F2F5);
                    gb.setStroke(dp(this, 3), 0xFFEF4444);
                    mainDisc.setBackground(gb);
                    stopPulse(mainDisc);
                }
                if (mainIconV != null) mainIconV.setColorFilter(0xFF9AA1AE);
                if (mainCount != null) { mainCount.setText("ไม่มีเน็ต"); mainCount.setTextColor(0xFFEF4444); }
                Alert.ping(this, Alert.NET);
                if (mainBadge != null) mainBadge.setVisibility(View.GONE);
                Toast.makeText(this, "⚠️ ต่อคลาวด์ไม่ได้เกิน 1 นาที — เช็ค WiFi ของเครื่อง POS", Toast.LENGTH_LONG).show();
            } else {
                refreshMain();
                Toast.makeText(this, "✅ ต่อคลาวด์ได้แล้ว", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** ปุ่ม 📋 เล็กๆ เกาะมุมล่างขวาของฟองแม่ — กดเปิดรายการ + ประวัติได้ตลอด แม้มีออเดอร์อยู่ */
    private void addListBubble() {
        TextView b = text(this, "📋", 15, false, WHITE);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFFFFFFFF);
        g.setStroke(dp(this, 2), 0xFFDFE2E8);
        b.setBackground(g);
        b.setElevation(dp(this, 13));
        listBubble = b;
        int s = dp(this, 38);
        listParams = new WindowManager.LayoutParams(s, s, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        listParams.gravity = Gravity.TOP | Gravity.START;
        Fx.onTap(b, () -> { if (panel != null) closePanel(); openList(); });
        wm.addView(listBubble, listParams);
        placeListBubble();
    }

    private void placeListBubble() {
        if (listBubble == null) return;
        int pad = dp(this, MAIN_PAD), mainS = dp(this, MAIN_DP), s = dp(this, 38);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int x = mainParams.x + pad - s / 2 + dp(this, 6), y = mainParams.y + pad + mainS - s / 2 - dp(this, 4);
        listParams.x = Math.max(0, Math.min(x, dm.widthPixels - s));
        listParams.y = Math.max(0, Math.min(y, dm.heightPixels - s));
        try { wm.updateViewLayout(listBubble, listParams); } catch (Exception ignored) {}
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
            android.widget.FrameLayout disc = new android.widget.FrameLayout(this);
            disc.setElevation(dp(this, 8));
            android.widget.FrameLayout.LayoutParams cl = new android.widget.FrameLayout.LayoutParams(s, s);
            cl.gravity = Gravity.CENTER;
            outer.addView(disc, cl);

            RingView ring = new RingView(this);
            disc.addView(ring, new android.widget.FrameLayout.LayoutParams(MATCH, MATCH));

            LinearLayout c = col(this);
            c.setGravity(Gravity.CENTER);
            disc.addView(c, new android.widget.FrameLayout.LayoutParams(MATCH, MATCH));

            TextView num = text(this, "", 19, true, 0xFF1F2430);
            num.setGravity(Gravity.CENTER);
            num.setSingleLine(true);
            num.setIncludeFontPadding(false);
            c.addView(num);

            TextView sub = text(this, "", 9.5f, true, 0xFF8A91A0);
            sub.setGravity(Gravity.CENTER);
            sub.setSingleLine(true);
            sub.setPadding(dp(this, 6), dp(this, 1), dp(this, 6), 0);
            c.addView(sub);

            final String oid = o.id;
            Fx.onTap(disc, () -> openDetail(oid));

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
        android.widget.FrameLayout disc = (android.widget.FrameLayout) ((android.widget.FrameLayout) outer).getChildAt(0);
        RingView ring = (RingView) disc.getChildAt(0);
        LinearLayout c = (LinearLayout) disc.getChildAt(1);
        TextView num = (TextView) c.getChildAt(0);
        TextView sub = (TextView) c.getChildAt(1);
        boolean unread = unreadIds.contains(o.id);
        boolean edited = o.needsAck();
        int m = o.waitedMin();
        String who = !o.no.isEmpty() ? "#" + o.no : o.place.isEmpty() ? "" : (o.place.length() > 9 ? o.place.substring(0, 9) + "…" : o.place);

        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (unread) {
            g.setColors(new int[]{edited ? 0xFF7C3AED : 0xFFF05A28, edited ? 0xFFA78BFA : 0xFFFF8A3D});
            g.setStroke(dp(this, 3), WHITE);
            ring.setVisibility(View.INVISIBLE);
            num.setText(edited ? "แก้ไข!" : "ใหม่!");
            num.setTextSize(15);
            num.setTextColor(WHITE);
            sub.setText((who.isEmpty() ? "" : who + " · ") + o.clock());
            sub.setTextColor(0xFFFFFFFF);
        } else {
            g.setColor(WHITE);
            ring.setVisibility(View.VISIBLE);
            boolean late = m >= 50;
            ring.set(m / 60f, edited ? 0xFF7C3AED : o.tierColor(), late ? 0xFFFEE2E2 : 0xFFEEF0F3);
            num.setText(String.valueOf(m));
            num.setTextSize(19);
            num.setTextColor(late ? 0xFFEF4444 : edited ? 0xFF7C3AED : 0xFF1F2430);
            sub.setText("นาที" + (who.isEmpty() ? "" : " · " + who));
            sub.setTextColor(late ? 0xFFEF4444 : 0xFF8A91A0);
        }
        disc.setBackground(g);

        if (unread) startPulse(disc); else stopPulse(disc);
    }

    /** วงแหวนความคืบหน้ารอบฟองย่อย (เต็มวง = 60 นาที) */
    static class RingView extends View {
        float progress = 0f; int color = 0xFF22C55E, track = 0xFFEEF0F3;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        RingView(android.content.Context c) {
            super(c);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }
        void set(float pr, int col, int tr) { progress = pr; color = col; track = tr; invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float sw = dp(getContext(), 5);
            p.setStrokeWidth(sw);
            android.graphics.RectF r = new android.graphics.RectF(sw / 2 + 1, sw / 2 + 1, getWidth() - sw / 2 - 1, getHeight() - sw / 2 - 1);
            p.setColor(track);
            cv.drawOval(r, p);
            p.setColor(color);
            cv.drawArc(r, -90, 360f * Math.max(0.03f, Math.min(1f, progress)), false, p);
        }
    }

    /** ฟองแม่: ไอคอน + จำนวน; มีออเดอร์ยังไม่อ่าน → ส้มเต็ม + ป้ายแดง + เต้น */
    private void refreshMain() {
        if (netLost) return;
        int n = activeOrders().size();
        int unread = unreadIds.size();
        if (mainCount != null) mainCount.setText(unread > 0 ? "ใหม่!" : n + " ออเดอร์");
        if (mainBadge != null) { mainBadge.setText(String.valueOf(unread)); mainBadge.setVisibility(unread > 0 ? View.VISIBLE : View.GONE); }
        if (mainDisc != null) {
            if (unread > 0) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColors(new int[]{0xFFF05A28, 0xFFFF8A3D});
                g.setStroke(dp(this, 3), 0xFFFFFFFF);
                mainDisc.setBackground(g);
                mainIconV.setColorFilter(WHITE); mainCount.setTextColor(WHITE);
                startPulse(mainDisc);
            } else {
                mainDisc.setBackground(bubbleBg(this));
                mainIconV.setColorFilter(0xFFF05A28); mainCount.setTextColor(0xFF1F2430);
                stopPulse(mainDisc);
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

    /** วางฟองย่อยเป็นวงรอบฟองแม่ — เลือกเฉพาะมุมที่ยังอยู่ในจอ ไม่ให้ฟองทับกันแม้ฟองแม่อยู่ชิดขอบ */
    private void layoutChildren() {
        int n = childViews.size();
        if (n == 0) return;
        int mainS = dp(this, MAIN_WIN), childS = dp(this, CHILD_WIN);
        int cx = mainParams.x + mainS / 2;
        int cy = mainParams.y + mainS / 2;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int W = dm.widthPixels, H = dm.heightPixels;
        int maxX = W - childS, maxY = H - childS;

        int placed = 0;
        for (int ring = 0; ring < 6 && placed < n; ring++) {
            int r = dp(this, RADIUS_DP + ring * (CHILD_DP + 10));
            // มุมที่วางแล้วฟองยังอยู่ในจอทั้งใบ
            java.util.List<Double> ok = new java.util.ArrayList<>();
            int samples = 72;
            for (int i = 0; i < samples; i++) {
                double ang = -Math.PI / 2 + i * (2 * Math.PI / samples);
                int x = cx + (int) (Math.cos(ang) * r) - childS / 2;
                int y = cy + (int) (Math.sin(ang) * r) - childS / 2;
                if (x >= 0 && x <= maxX && y >= 0 && y <= maxY) ok.add(ang);
            }
            if (ok.isEmpty()) continue;
            // ระยะห่างเชิงมุมขั้นต่ำ ไม่ให้ฟองชนกัน
            double minStep = (childS * 1.02) / r;
            java.util.List<Double> picks = new java.util.ArrayList<>();
            double last = -99;
            for (double ang : ok) {
                if (picks.isEmpty() || ang - last >= minStep) { picks.add(ang); last = ang; }
            }
            // กันฟองแรกกับฟองสุดท้ายชนกันตอนครบวง
            if (picks.size() > 1 && (2 * Math.PI - (picks.get(picks.size() - 1) - picks.get(0))) < minStep)
                picks.remove(picks.size() - 1);

            int take = Math.min(picks.size(), n - placed);
            for (int k = 0; k < take; k++) {
                int idx = take == 1 ? picks.size() / 2
                        : (int) Math.round((double) k * (picks.size() - 1) / (take - 1));
                double ang = picks.get(idx);
                int x = cx + (int) (Math.cos(ang) * r) - childS / 2;
                int y = cy + (int) (Math.sin(ang) * r) - childS / 2;
                WindowManager.LayoutParams p = childParams.get(placed);
                p.x = Math.max(0, Math.min(x, maxX));
                p.y = Math.max(0, Math.min(y, maxY));
                try { wm.updateViewLayout(childViews.get(placed), p); } catch (Exception ignored) {}
                placed++;
            }
        }
        // เผื่อกรณีสุดโต่ง (จอเล็กมาก) วางที่เหลือเรียงแนวตั้งข้างฟองแม่
        int col = 0;
        while (placed < n) {
            WindowManager.LayoutParams p = childParams.get(placed);
            p.x = Math.max(0, Math.min(cx - childS / 2, maxX));
            p.y = Math.max(0, Math.min(cy + mainS / 2 + (col + 1) * (childS + dp(this, 4)), maxY));
            try { wm.updateViewLayout(childViews.get(placed), p); } catch (Exception ignored) {}
            placed++; col++;
        }
    }

    /* ================= นาฬิกา + ดึงข้อมูล ================= */

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshClocks();
            checkReady();
            refreshNetDot();
            ui.postDelayed(this, 20000);   // อัปเดตเวลาแต่ละ 20 วิ
        }
    };

    private void refreshClocks() {
        List<Order> act = activeOrders();
        for (int i = 0; i < childViews.size() && i < act.size(); i++) bindChild(childViews.get(i), act.get(i));
    }

    /** ออเดอร์ไหนรอครบ 40 นาที → เตือน 2 รอบ (ห่าง 1 นาที) ให้พนักงานเตรียมส่ง ไม่ต้องกดอะไร */
    private void checkReady() {
        for (Order o : orders) {
            if (o.status.equals(Order.ST_DONE)) continue;
            int m = o.waitedMin();
            int done = readyPinged.containsKey(o.id) ? readyPinged.get(o.id) : 0;
            if (m < READY_MIN || done >= 2) continue;
            if (done == 1 && m < READY_MIN + 1) continue;   // รอบ 2 ห่างจากรอบแรก 1 นาที
            readyPinged.put(o.id, done + 1);
            Alert.ping(this, Alert.READY);
            if (mainDisc != null) Fx.bounce(mainDisc);
            String who = (o.no.isEmpty() ? "" : "#" + o.no + " ") + o.place;
            Toast.makeText(this, "⏰ ออเดอร์ " + who.trim() + " ครบ " + READY_MIN + " นาทีแล้ว — เตรียมส่ง" + (done == 0 ? "" : " (เตือนรอบ 2)"), Toast.LENGTH_LONG).show();
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel("ready_ch", "ออเดอร์ครบ 40 นาที", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
                Notification n = new Notification.Builder(this, "ready_ch")
                        .setContentTitle("⏰ ครบ " + READY_MIN + " นาที — เตรียมส่ง " + who.trim())
                        .setContentText("รอมาแล้ว " + m + " นาที")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setAutoCancel(true)
                        .build();
                nm.notify(5000 + Math.abs(o.id.hashCode() % 1000), n);
            } catch (Exception ignored) {}
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
            refreshNetDot();
            ui.postDelayed(this, 6000);    // ดึงออเดอร์ใหม่ทุก 6 วิ
        }
    };

    private void fetchOrders() {
        if (!Cloud.ready(this)) return;
        new Thread(() -> {
            try {
                final List<Order> got = Cloud.fetch(OrderBubbleService.this);
                try { Cloud.fetchHarvest(OrderBubbleService.this); } catch (Exception ignored) {}
                ui.post(() -> {
                    checkHarvest();
                    boolean isNew = false;
                    for (Order o : got) {
                        // แก้ไขจากมือถือ → เตือนเหมือนออเดอร์ใหม่ (แม้เคยเสร็จแล้ว)
                        Long prev = seenEdit.get(o.id);
                        if (o.editedAt > 0 && (prev == null || o.editedAt > prev)) {
                            boolean firstSight = prev == null && !seenIds.contains(o.id);
                            seenEdit.put(o.id, o.editedAt);
                            if (!firstSight && o.needsAck()) { unreadIds.add(o.id); isNew = true; }
                        }
                        if (o.status.equals(Order.ST_DONE)) { unreadIds.remove(o.id); continue; }
                        if (!seenIds.contains(o.id)) {
                            seenIds.add(o.id);
                            if (o.status.equals(Order.ST_NEW) || o.needsAck()) { unreadIds.add(o.id); isNew = true; }
                        }
                        // รับไปแล้วจากที่อื่น / ไม่มีการแก้ไขค้าง → เลิกเตือน
                        if (unreadIds.contains(o.id) && !o.status.equals(Order.ST_NEW) && !o.needsAck()) unreadIds.remove(o.id);
                    }
                    orders = got;
                    rebuildChildren();
                    if (isNew) {
                        Fx.bounce(mainDisc);
                        boolean anyEdit = false;
                        for (Order o : got) if (unreadIds.contains(o.id) && o.needsAck()) anyEdit = true;
                        Alert.start(OrderBubbleService.this, anyEdit ? Alert.EDIT : Alert.NEW);   // ดังจนกว่าจะกดรับ
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

    /* ================= หน้าจอทำงาน (ธีมสว่าง: แถบเมนูซ้าย + รายการ/รายละเอียด) ================= */

    // จานสีธีมสว่าง
    private static final int BG = 0xFFF4F5F7, CARD = 0xFFFFFFFF, LINE_C = 0xFFE3E5EA, TXT = 0xFF1F2430, MUTED = 0xFF8A91A0;
    private static final int ORANGE = 0xFFF05A28, ORANGE_SOFT = 0xFFFFF1E8, GREEN_C = 0xFF139A4B, GREEN_SOFT = 0xFFE6F8EE;
    private static final int PURPLE = 0xFF7C3AED, PURPLE_SOFT = 0xFFEFE9FF, RED_C = 0xFFEF4444, RED_SOFT = 0xFFFEE2E2, YEL_SOFT = 0xFFFFFBE6, YEL_LINE = 0xFFFDE68A;

    private static final int T_ORDERS = 0, T_ADD = 1, T_HIST = 2, T_HARV = 3, T_SET = 4;
    private int tab = T_ORDERS;
    private String selId = null;      // ออเดอร์ที่เลือกดูรายละเอียด
    private int listFilter = 0;       // 0 กำลังทำ / 1 เสร็จแล้ว / 2 ทั้งหมด
    private int addStep = 0;          // 0 เลือกที่อยู่ / 1 โน้ต
    private String addQuery = "";
    private String addPlace = "";
    private int wsH = 0;              // ความสูงหน้าจอทำงาน (px) — ใช้คำนวณส่วนเนื้อหาแบบตายตัว กัน weight เพี้ยน

    private GradientDrawable card(float r) { return glass(this, CARD, r, LINE_C); }
    private GradientDrawable fill(int color, float r) { return glass(this, color, r, 0); }

    private TextView lchip(String s, int bg, int fg) {
        TextView t = text(this, s, 12.5f, true, fg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(this, 13), dp(this, 7), dp(this, 13), dp(this, 7));
        t.setBackground(fill(bg, 999));
        return t;
    }

    /** ปุ่มใหญ่: primary = พื้นส้ม / ไม่ใช่ = ขอบขาว */
    private TextView lbtn(String s, boolean primary) {
        TextView t = text(this, s, 15, true, primary ? WHITE : TXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(this, 22), dp(this, 13), dp(this, 22), dp(this, 13));
        t.setBackground(primary ? fill(ORANGE, 12) : glass(this, CARD, 12, 0xFFDFE2E8));
        return t;
    }

    private TextView lbtnColor(String s, int bg, int fg, int stroke) {
        TextView t = text(this, s, 15, true, fg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(this, 22), dp(this, 13), dp(this, 22), dp(this, 13));
        t.setBackground(glass(this, bg, 12, stroke));
        return t;
    }

    private android.widget.EditText linput(String hint) {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFF9AA1AE);
        e.setTextColor(TXT);
        e.setTextSize(16);
        e.setBackground(glass(this, CARD, 12, 0xFFDFE2E8));
        e.setPadding(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 12));
        return e;
    }

    /** กล่องเวลา (สี่เหลี่ยมมน) สีตามระดับเวลา */
    private LinearLayout timeBox(Order o, int sizeDp) {
        LinearLayout b = col(this);
        b.setGravity(Gravity.CENTER);
        boolean done = o.status.equals(Order.ST_DONE);
        b.setBackground(fill(done ? 0xFF9AA1AE : o.tierColor(), 12));
        TextView n = text(this, done ? "✓" : String.valueOf(o.waitedMin()), sizeDp >= 64 ? 20 : 17, true, WHITE);
        n.setGravity(Gravity.CENTER);
        b.addView(n);
        if (!done) {
            TextView u = text(this, "นาที", 10, false, WHITE);
            u.setGravity(Gravity.CENTER);
            b.addView(u);
        }
        return b;
    }

    private TextView statusTag(Order o) {
        boolean unread = unreadIds.contains(o.id);
        if (o.needsAck()) return lchip("แก้ไข", PURPLE_SOFT, PURPLE);
        if (o.status.equals(Order.ST_DONE)) return lchip("เสร็จ", 0xFFEEF0F3, MUTED);
        if (o.status.equals(Order.ST_NEW) || unread) return lchip("ใหม่", ORANGE_SOFT, ORANGE);
        return lchip("กำลังทำ", GREEN_SOFT, GREEN_C);
    }

    private String clockOf(long t) {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(t - Cloud.offset));
    }

    /** เปิดหน้าจอทำงานที่แท็บที่ระบุ */
    private void openWorkspace(int t) {
        closePanel();
        tab = t;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int W = Math.min(dm.widthPixels - dp(this, 12), dp(this, 1180));
        int H = Math.min(dm.heightPixels - dp(this, 12), dp(this, 760));
        wsH = H;

        LinearLayout root = row(this);
        root.setGravity(Gravity.TOP);
        root.setBackground(glass(this, BG, 18, LINE_C));
        root.setClipToOutline(true);
        root.setElevation(dp(this, 18));

        root.addView(sidebar(), new LinearLayout.LayoutParams(dp(this, 84), MATCH));
        View div = new View(this); div.setBackgroundColor(LINE_C);
        root.addView(div, new LinearLayout.LayoutParams(dp(this, 1), MATCH));

        LinearLayout main = col(this);
        root.addView(main, new LinearLayout.LayoutParams(0, H, 1));

        switch (tab) {
            case T_ADD:  buildAddTab(main); break;
            case T_HARV: buildHarvestTab(main); break;
            case T_SET:  buildSettingsTab(main); break;
            default:     buildOrdersTab(main); break;
        }

        panel = root;
        showPanel(W, H);
    }

    private View sidebar() {
        LinearLayout s = col(this);
        s.setBackgroundColor(CARD);
        s.setGravity(Gravity.CENTER_HORIZONTAL);
        s.setPadding(0, dp(this, 12), 0, dp(this, 12));
        String[][] items = {{"🧾", "ออเดอร์"}, {"➕", "คีย์เอง"}, {"📋", "ประวัติ"}, {"🦐", "เก็บกุ้ง"}, {"⚙️", "ตั้งค่า"}};
        int[] ids = {T_ORDERS, T_ADD, T_HIST, T_HARV, T_SET};
        for (int i = 0; i < items.length; i++) {
            final int id = ids[i];
            boolean on = tab == id;
            LinearLayout it = col(this);
            it.setGravity(Gravity.CENTER);
            it.setBackground(on ? fill(ORANGE_SOFT, 12) : null);
            TextView ic = text(this, items[i][0], 22, false, TXT);
            ic.setGravity(Gravity.CENTER);
            it.addView(ic);
            TextView lb = text(this, items[i][1], 11, on, on ? ORANGE : 0xFF6B7280);
            lb.setGravity(Gravity.CENTER);
            lb.setPadding(0, dp(this, 3), 0, 0);
            it.addView(lb);
            // ป้ายจำนวนยังไม่อ่าน / สัญญาณเก็บกุ้งค้าง
            int badge = id == T_ORDERS ? unreadIds.size() : id == T_HARV && Cloud.harvestAt > 0 && Cloud.harvestAck < Cloud.harvestAt ? 1 : 0;
            if (badge > 0) {
                TextView bd = text(this, id == T_HARV ? "!" : String.valueOf(badge), 10, true, WHITE);
                bd.setGravity(Gravity.CENTER);
                bd.setBackground(fill(RED_C, 999));
                bd.setPadding(dp(this, 6), dp(this, 1), dp(this, 6), dp(this, 1));
                LinearLayout.LayoutParams bl = lp(WRAP, WRAP); bl.topMargin = dp(this, 2);
                it.addView(bd, bl);
            }
            LinearLayout.LayoutParams il = lp(dp(this, 66), dp(this, badge > 0 ? 78 : 62)); il.bottomMargin = dp(this, 6);
            Fx.onTap(it, () -> {
                if (id == T_ADD) { addStep = 0; addQuery = ""; }
                if (id == T_HIST) listFilter = 1; else if (id == T_ORDERS && listFilter == 1) listFilter = 0;
                openWorkspace(id);
            });
            s.addView(it, il);
        }
        View sp = new View(this);
        s.addView(sp, new LinearLayout.LayoutParams(MATCH, 0, 1));
        TextView v = text(this, ver(this), 10, false, MUTED);
        v.setGravity(Gravity.CENTER);
        s.addView(v);
        return s;
    }

    /** แถบบน: ชื่อหน้า + ป้าย + สถานะคลาวด์ + ปุ่มปิด */
    private LinearLayout topBar(LinearLayout main, String title) {
        LinearLayout top = row(this);
        top.setBackgroundColor(CARD);
        top.setPadding(dp(this, 22), 0, dp(this, 14), 0);
        top.addView(text(this, title, 18, true, TXT));
        main.addView(top, lp(MATCH, dp(this, 60)));
        View div = new View(this); div.setBackgroundColor(LINE_C);
        main.addView(div, lp(MATCH, dp(this, 1)));
        return top;
    }

    private void topBarEnd(LinearLayout top) {
        View sp = new View(this);
        top.addView(sp, lpw(1));
        long age = Cloud.lastOk == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - Cloud.lastOk;
        int dotc = age < 20000 ? 0xFF22C55E : age < 60000 ? 0xFFFACC15 : RED_C;
        TextView dot = text(this, "", 1, false, 0);
        dot.setBackground(fill(dotc, 999));
        LinearLayout.LayoutParams dl = lp(dp(this, 9), dp(this, 9)); dl.rightMargin = dp(this, 6);
        top.addView(dot, dl);
        String when = Cloud.synced() ? clockOf(Cloud.now()) : new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date());
        TextView st = text(this, (age < 60000 ? "คลาวด์ปกติ" : "คลาวด์หลุด") + " · " + when, 12, false, 0xFF6B7280);
        top.addView(st);
        TextView refresh = lchip("⟳", 0xFFF1F2F5, TXT);
        LinearLayout.LayoutParams rl = lp(WRAP, WRAP); rl.leftMargin = dp(this, 10);
        Fx.onTap(refresh, () -> { fetchOrders(); ui.postDelayed(() -> { if (panel != null) openWorkspace(tab); }, 900); });
        top.addView(refresh, rl);
        TextView close = lchip("✕", 0xFFF1F2F5, TXT);
        LinearLayout.LayoutParams cl = lp(WRAP, WRAP); cl.leftMargin = dp(this, 6);
        Fx.onTap(close, this::closePanel);
        top.addView(close, cl);
    }

    private static final int FOOT_DP = 68;

    /** ความสูงส่วนเนื้อหา = ทั้งหมด − แถบบน − แถบล่าง (คำนวณเป็น px ตายตัว) */
    private int bodyH() { return Math.max(dp(this, 120), wsH - dp(this, 61) - dp(this, FOOT_DP + 1)); }

    private LinearLayout bodyOf(LinearLayout main) {
        LinearLayout body = col(this);
        body.setPadding(dp(this, 18), dp(this, 16), dp(this, 18), dp(this, 16));
        main.addView(body, new LinearLayout.LayoutParams(MATCH, bodyH()));
        return body;
    }

    private LinearLayout footerOf(LinearLayout main) {
        View div = new View(this); div.setBackgroundColor(LINE_C);
        main.addView(div, lp(MATCH, dp(this, 1)));
        LinearLayout f = row(this);
        f.setBackgroundColor(CARD);
        f.setPadding(dp(this, 18), dp(this, 8), dp(this, 18), dp(this, 8));
        main.addView(f, lp(MATCH, dp(this, FOOT_DP)));
        return f;
    }

    /* ---------- แท็บ ออเดอร์ / ประวัติ ---------- */

    private void buildOrdersTab(LinearLayout main) {
        boolean hist = tab == T_HIST;
        List<Order> act = new ArrayList<>(), done = new ArrayList<>();
        for (Order o : orders) { if (o.status.equals(Order.ST_DONE)) done.add(o); else act.add(o); }
        java.util.Collections.sort(act, (a, b) -> Long.compare(a.createdAt, b.createdAt));            // รอนานสุดอยู่บน
        java.util.Collections.sort(done, (a, b) -> Long.compare(Math.max(b.editedAt, b.createdAt), Math.max(a.editedAt, a.createdAt)));

        LinearLayout top = topBar(main, hist ? "ประวัติออเดอร์" : "ออเดอร์วันนี้");
        TextView p1 = lchip("กำลังทำ " + act.size(), ORANGE, WHITE);
        LinearLayout.LayoutParams l1 = lp(WRAP, WRAP); l1.leftMargin = dp(this, 14);
        top.addView(p1, l1);
        TextView p2 = lchip("เสร็จ " + done.size(), GREEN_SOFT, GREEN_C);
        LinearLayout.LayoutParams l2 = lp(WRAP, WRAP); l2.leftMargin = dp(this, 8);
        top.addView(p2, l2);
        topBarEnd(top);

        LinearLayout body = bodyOf(main);
        final int innerH = bodyH() - dp(this, 32);
        LinearLayout cols = row(this);
        cols.setGravity(Gravity.TOP);
        body.addView(cols, new LinearLayout.LayoutParams(MATCH, innerH));

        // ----- ซ้าย: รายการ -----
        LinearLayout left = col(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int leftW = Math.min(dp(this, 400), (int) (dm.widthPixels * 0.38f));
        cols.addView(left, new LinearLayout.LayoutParams(leftW, innerH));

        int filter = hist ? 1 : listFilter;
        if (!hist) {
            LinearLayout seg = row(this);
            seg.setBackground(card(12));
            seg.setPadding(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 4));
            String[] names = {"กำลังทำ", "เสร็จแล้ว", "ทั้งหมด"};
            for (int i = 0; i < 3; i++) {
                final int fi = i;
                TextView sgt = text(this, names[i], 13.5f, true, filter == i ? WHITE : 0xFF6B7280);
                sgt.setGravity(Gravity.CENTER);
                sgt.setPadding(0, dp(this, 9), 0, dp(this, 9));
                sgt.setBackground(filter == i ? fill(ORANGE, 9) : null);
                Fx.onTap(sgt, () -> { listFilter = fi; openWorkspace(T_ORDERS); });
                seg.addView(sgt, lpw(1));
            }
            LinearLayout.LayoutParams sl = lp(MATCH, WRAP); sl.bottomMargin = dp(this, 10);
            left.addView(seg, sl);
        }

        List<Order> show = new ArrayList<>();
        if (filter == 0) show.addAll(act);
        else if (filter == 1) show.addAll(done);
        else { show.addAll(act); show.addAll(done); }

        // ออเดอร์ที่เลือก: ถ้าไม่มี/ไม่อยู่ในรายการ → เลือกตัวแรก
        Order sel = null;
        for (Order o : show) if (o.id.equals(selId)) sel = o;
        if (sel == null && !show.isEmpty()) { sel = show.get(0); selId = sel.id; }

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = col(this);
        sv.addView(list);
        left.addView(sv, new LinearLayout.LayoutParams(MATCH, innerH - (hist ? 0 : dp(this, 52))));

        if (!Cloud.ready(this)) {
            TextView warn = text(this, "ยังไม่ได้ตั้งค่าคลาวด์ — เปิดแอป POS ออเดอร์ แล้วใส่ลิงก์ฐานข้อมูลก่อน", 13, false, ORANGE);
            warn.setPadding(dp(this, 4), dp(this, 10), 0, 0);
            list.addView(warn);
        } else if (show.isEmpty()) {
            TextView em = text(this, filter == 0 ? "ไม่มีออเดอร์ค้าง 🎉" : "ยังไม่มีออเดอร์", 14, false, MUTED);
            em.setPadding(dp(this, 4), dp(this, 14), 0, 0);
            list.addView(em);
        }
        for (Order o : show) {
            final boolean isSel = sel != null && o.id.equals(sel.id);
            LinearLayout rowv = row(this);
            rowv.setBackground(glass(this, CARD, 14, isSel ? ORANGE : LINE_C));
            rowv.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            if (o.status.equals(Order.ST_DONE) && filter != 1) rowv.setAlpha(0.6f);
            LinearLayout.LayoutParams tl = lp(dp(this, 54), dp(this, 54)); tl.rightMargin = dp(this, 12);
            rowv.addView(timeBox(o, 54), tl);
            LinearLayout info = col(this);
            TextView nm = text(this, o.place.isEmpty() ? (o.by.equals("pos") ? "คีย์ที่เครื่อง" : "ออเดอร์") : o.place, 16, true, TXT);
            nm.setSingleLine(true); nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(nm);
            String meta = (o.no.isEmpty() ? (o.by.equals("pos") ? "คีย์เอง" : "") : "#" + o.no) + " · " + clockOf(o.createdAt)
                    + (o.total > 0 ? " · " + o.total + " บาท" : "") + (o.pay.isEmpty() ? "" : " · " + firstPay(o));
            TextView mt = text(this, meta.startsWith(" · ") ? meta.substring(3) : meta, 12, false, MUTED);
            mt.setSingleLine(true); mt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mt.setPadding(0, dp(this, 3), 0, 0);
            info.addView(mt);
            rowv.addView(info, lpw(1));
            TextView tg = statusTag(o);
            LinearLayout.LayoutParams tgl = lp(WRAP, WRAP); tgl.leftMargin = dp(this, 8);
            rowv.addView(tg, tgl);
            final String oid = o.id;
            Fx.onTap(rowv, () -> { selId = oid; openWorkspace(tab); });
            LinearLayout.LayoutParams rl = lp(MATCH, WRAP); rl.bottomMargin = dp(this, 8);
            list.addView(rowv, rl);
        }

        // ----- ขวา: รายละเอียด -----
        LinearLayout right = col(this);
        right.setBackground(card(16));
        right.setPadding(dp(this, 20), dp(this, 16), dp(this, 20), dp(this, 16));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, innerH, 1); rl.leftMargin = dp(this, 16);
        cols.addView(right, rl);
        if (sel == null) {
            TextView em = text(this, "เลือกออเดอร์จากรายการด้านซ้าย", 15, false, MUTED);
            em.setGravity(Gravity.CENTER);
            right.setGravity(Gravity.CENTER);
            right.addView(em);
        } else buildDetail(right, sel);

        // ----- ท้าย -----
        LinearLayout f = footerOf(main);
        TextView add = lbtn("➕  คีย์ออเดอร์หน้าเครื่อง", true);
        Fx.onTap(add, () -> openAddLocal(""));
        f.addView(add);
        View sp = new View(this); f.addView(sp, lpw(1));
        if (hist || filter != 0) {
            TextView arch = lbtn("🗄  เก็บที่เสร็จแล้วเข้าคลัง (" + done.size() + ")", false);
            Fx.onTap(arch, this::clearDone);
            f.addView(arch);
        }
    }

    private String firstPay(Order o) {
        for (String pl : o.pay.split("\\n")) {
            String q = pl.trim();
            if (q.isEmpty() || q.startsWith("หมายเหตุ")) continue;
            int i = q.indexOf(":");
            return i > 0 ? q.substring(i + 1).trim() : q;
        }
        return "";
    }

    /** รายละเอียดออเดอร์ (ด้านขวา) */
    private void buildDetail(LinearLayout right, final Order o) {
        // ---- หัว ----
        LinearLayout h = row(this);
        TextView big = text(this, o.place.isEmpty() ? "ออเดอร์" : o.place, 22, true, TXT);
        big.setSingleLine(true); big.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.addView(big, new LinearLayout.LayoutParams(0, WRAP, 1));
        if (!o.no.isEmpty()) {
            TextView no = lchip("#" + o.no, 0xFFF1F2F5, TXT);
            LinearLayout.LayoutParams nl = lp(WRAP, WRAP); nl.leftMargin = dp(this, 10);
            h.addView(no, nl);
        }
        TextView st = statusTag(o);
        LinearLayout.LayoutParams stl = lp(WRAP, WRAP); stl.leftMargin = dp(this, 8);
        h.addView(st, stl);
        if (o.needsAck()) {
            TextView ed = lchip("✏️ แก้ไขจากมือถือ " + clockOf(o.editedAt), PURPLE_SOFT, PURPLE);
            LinearLayout.LayoutParams el = lp(WRAP, WRAP); el.leftMargin = dp(this, 8);
            h.addView(ed, el);
        }
        LinearLayout.LayoutParams tl = lp(dp(this, 64), dp(this, 64)); tl.leftMargin = dp(this, 14);
        h.addView(timeBox(o, 64), tl);
        right.addView(h, lp(MATCH, WRAP));
        TextView sub = text(this, "สั่งเมื่อ " + clockOf(o.createdAt) + " น." + (o.status.equals(Order.ST_DONE) ? "" : " · รอมาแล้ว " + o.waitedMin() + " นาที")
                + (o.by.equals("pos") ? " · คีย์ที่เครื่อง" : " · จากมือถือ"), 12.5f, false, MUTED);
        sub.setPadding(0, dp(this, 4), 0, dp(this, 8));
        right.addView(sub);

        // ---- แจ้งแก้ไข ----
        if (o.needsAck()) {
            LinearLayout ab = row(this);
            ab.setBackground(glass(this, PURPLE_SOFT, 12, 0xFFD8CCFA));
            ab.setPadding(dp(this, 14), dp(this, 10), dp(this, 10), dp(this, 10));
            LinearLayout ac = col(this);
            ac.addView(text(this, "ออเดอร์นี้ถูกแก้ไขจากมือถือ", 14.5f, true, PURPLE));
            ac.addView(text(this, "แถวสีม่วง = เปลี่ยน · แถวสีแดงขีดฆ่า = ตัดออก · ตรวจแล้วกดรับทราบ", 12, false, 0xFF6B7280));
            ab.addView(ac, lpw(1));
            LinearLayout.LayoutParams abl = lp(MATCH, WRAP); abl.bottomMargin = dp(this, 8);
            right.addView(ab, abl);
        }

        // ---- รายการ ----
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = col(this);
        sv.addView(list);
        right.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1));

        java.util.regex.Pattern pricePat = java.util.regex.Pattern.compile("^(.*\\S)\\s+(\\d[\\d,]*)\\s*บาท$");
        final boolean showDiff = o.needsAck() && !o.prevLines.isEmpty();
        java.util.Set<String> prevSet = new java.util.HashSet<>(o.prevLines);
        java.util.Set<String> curSet = new java.util.HashSet<>();
        for (String line : o.lines) curSet.add(line.trim());
        String noteTxt = null;

        for (String line : o.lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("ช่องทางชำระ") || t.startsWith("สถานะ")) continue;
            if (t.startsWith("หมายเหตุ")) { int i = t.indexOf(":"); noteTxt = i > 0 ? t.substring(i + 1).trim() : t; continue; }
            final boolean changed = showDiff && !prevSet.contains(t);
            final String s = t;
            LinearLayout rowv = row(this);
            rowv.setPadding(dp(this, changed ? 10 : 2), dp(this, 10), dp(this, changed ? 10 : 2), dp(this, 10));
            if (changed) rowv.setBackground(fill(PURPLE_SOFT, 8));

            int img = t.contains("กุ้งแกะ") ? R.drawable.menu_kae : t.contains("กุ้งผ่า") ? R.drawable.menu_pha : 0;
            if (img != 0) {
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setImageResource(img);
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                iv.setClipToOutline(true);
                iv.setBackground(fill(WHITE, 8));
                LinearLayout.LayoutParams il = lp(dp(this, 72), dp(this, 54)); il.rightMargin = dp(this, 12);
                rowv.addView(iv, il);
            }
            if (changed) {
                TextView tag = lchip("แก้", PURPLE, WHITE);
                tag.setTextSize(11); tag.setPadding(dp(this, 8), dp(this, 2), dp(this, 8), dp(this, 2));
                LinearLayout.LayoutParams tgl = lp(WRAP, WRAP); tgl.rightMargin = dp(this, 10);
                rowv.addView(tag, tgl);
            }
            java.util.regex.Matcher m = pricePat.matcher(t);
            if (m.find()) {
                rowv.addView(itemName(m.group(1)), lpw(1));
                TextView pr = text(this, m.group(2), 16, true, TXT);
                pr.setPadding(dp(this, 12), 0, 0, 0);
                rowv.addView(pr);
            } else {
                rowv.addView(itemName(t), lpw(1));
            }
            Fx.onCopyTap(rowv, () -> copy(s, "คัดลอก: " + s));
            list.addView(rowv, lp(MATCH, WRAP));
            list.addView(hline(), lp(MATCH, dp(this, 1)));
        }
        if (showDiff) {
            for (String pv : o.prevLines) {
                String t = pv.trim();
                if (t.isEmpty() || curSet.contains(t)) continue;
                if (t.startsWith("ช่องทางชำระ") || t.startsWith("สถานะ")) continue;
                LinearLayout rowv = row(this);
                rowv.setBackground(fill(RED_SOFT, 8));
                rowv.setPadding(dp(this, 10), dp(this, 8), dp(this, 10), dp(this, 8));
                TextView tag = lchip("ตัดออก", RED_C, WHITE);
                tag.setTextSize(11); tag.setPadding(dp(this, 8), dp(this, 2), dp(this, 8), dp(this, 2));
                LinearLayout.LayoutParams tgl = lp(WRAP, WRAP); tgl.rightMargin = dp(this, 10);
                rowv.addView(tag, tgl);
                TextView tv = text(this, t, 15, false, RED_C);
                tv.setPaintFlags(tv.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                rowv.addView(tv, lpw(1));
                LinearLayout.LayoutParams rl2 = lp(MATCH, WRAP); rl2.topMargin = dp(this, 6);
                list.addView(rowv, rl2);
            }
        }
        // รวม + ชำระ
        LinearLayout tot = row(this);
        tot.setPadding(dp(this, 2), dp(this, 12), dp(this, 2), dp(this, 4));
        tot.addView(text(this, "รวม", 17, true, TXT), lpw(1));
        String payTxt = firstPay(o);
        tot.addView(text(this, (o.total > 0 ? o.total + " บาท" : "—") + (payTxt.isEmpty() ? "" : " · " + payTxt), 17, true,
                payTxt.contains("ชำระแล้ว") || payTxt.contains("โอน") ? GREEN_C : TXT));
        list.addView(tot, lp(MATCH, WRAP));
        if (noteTxt != null && !noteTxt.isEmpty()) {
            TextView nb = text(this, "📝 หมายเหตุ: " + noteTxt, 14.5f, true, 0xFF92400E);
            nb.setBackground(glass(this, YEL_SOFT, 12, YEL_LINE));
            nb.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            LinearLayout.LayoutParams nl = lp(MATCH, WRAP); nl.topMargin = dp(this, 8);
            final String ntx = noteTxt;
            Fx.onCopyTap(nb, () -> copy(ntx, "คัดลอกหมายเหตุแล้ว"));
            list.addView(nb, nl);
        }
        TextView hint = text(this, "แตะรายการเพื่อคัดลอกทีละบรรทัด", 11, false, MUTED);
        hint.setPadding(dp(this, 2), dp(this, 8), 0, 0);
        list.addView(hint);

        // ---- ปุ่ม ----
        LinearLayout act = row(this);
        act.setPadding(0, dp(this, 12), 0, 0);
        if (!o.place.isEmpty()) {
            TextView cpP = lbtn("📋 คัดลอกที่อยู่", false);
            final String plc = o.place;
            Fx.onCopyTap(cpP, () -> copy(plc, "คัดลอก: " + plc));
            act.addView(cpP);
        }
        TextView cpA = lbtn("📄 ทั้งออเดอร์", false);
        LinearLayout.LayoutParams cal = lp(WRAP, WRAP); cal.leftMargin = dp(this, 8);
        Fx.onCopyTap(cpA, () -> copy(o.text.isEmpty() ? joinLines(o) : o.text, "คัดลอกออเดอร์แล้ว"));
        act.addView(cpA, cal);
        View sp = new View(this); act.addView(sp, lpw(1));

        boolean urgent = unreadIds.contains(o.id);
        if (o.needsAck()) {
            TextView ack = lbtnColor("✓ รับทราบการแก้ไข", PURPLE, WHITE, 0);
            if (urgent) startPulse(ack);
            Fx.onTap(ack, () -> {
                o.ackEditAt = Math.max(o.editedAt, Cloud.now());
                if (!o.status.equals(Order.ST_NEW)) markRead(o.id);
                new Thread(() -> { try { Cloud.ackEdit(OrderBubbleService.this, o.id); } catch (Exception ignored) {} }).start();
                Toast.makeText(this, "รับทราบการแก้ไขแล้ว", Toast.LENGTH_SHORT).show();
                rebuildChildren();
                openWorkspace(tab);
            });
            LinearLayout.LayoutParams al = lp(WRAP, WRAP); al.leftMargin = dp(this, 8);
            act.addView(ack, al);
        }
        if (o.status.equals(Order.ST_NEW)) {
            TextView doing = lbtn(urgent ? "🔥 รับออเดอร์ (หยุดเสียง)" : "🔥 รับออเดอร์", true);
            if (urgent) startPulse(doing);
            Fx.onTap(doing, () -> updateStatus(o, Order.ST_DOING));
            LinearLayout.LayoutParams dl = lp(WRAP, WRAP); dl.leftMargin = dp(this, 8);
            act.addView(doing, dl);
        }
        if (!o.status.equals(Order.ST_DONE)) {
            TextView done = lbtnColor("✅ เสร็จแล้ว", CARD, GREEN_C, GREEN_C);
            Fx.onTap(done, () -> updateStatus(o, Order.ST_DONE));
            LinearLayout.LayoutParams dnl = lp(WRAP, WRAP); dnl.leftMargin = dp(this, 8);
            act.addView(done, dnl);
        } else {
            TextView re = lbtnColor("↩ เปิดใหม่ (กำลังทำ)", CARD, ORANGE, ORANGE);
            Fx.onTap(re, () -> updateStatus(o, Order.ST_DOING));
            LinearLayout.LayoutParams dnl = lp(WRAP, WRAP); dnl.leftMargin = dp(this, 8);
            act.addView(re, dnl);
        }
        right.addView(act, lp(MATCH, WRAP));
    }

    private TextView itemName(String s) {
        // "2 x กุ้งเผา" / "กุ้งเผา x2" → เน้นจำนวนสีส้ม
        TextView t = text(this, s, 16, false, TXT);
        java.util.regex.Matcher q = java.util.regex.Pattern.compile("^(\\d+\\s*[xX×]\\s*)(.*)$").matcher(s);
        if (q.find()) {
            android.text.SpannableString ss = new android.text.SpannableString(s);
            ss.setSpan(new android.text.style.ForegroundColorSpan(ORANGE), 0, q.group(1).length(), 0);
            ss.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, q.group(1).length(), 0);
            t.setText(ss);
        }
        return t;
    }

    private View hline() { View v = new View(this); v.setBackgroundColor(0xFFEEF0F3); return v; }

    private String joinLines(Order o) {
        StringBuilder sb = new StringBuilder();
        for (String s : o.lines) sb.append(s).append("\n");
        if (o.total > 0) sb.append("ยอดรวมทั้งหมด ").append(o.total).append(" บาท");
        return sb.toString().trim();
    }

    private void updateStatus(Order o, String status) {
        o.status = status;
        if (!o.needsAck()) markRead(o.id);   // กดรับออเดอร์ = รับทราบ → เงียบ
        if (Cloud.ready(this)) {
            final String id = o.id;
            new Thread(() -> { try { Cloud.setStatus(OrderBubbleService.this, id, status); } catch (Exception ignored) {} }).start();
        }
        Toast.makeText(this, "ออเดอร์ " + (o.no.isEmpty() ? o.place : "#" + o.no) + " → " + status, Toast.LENGTH_SHORT).show();
        rebuildChildren();
        if (panel != null) {
            if (status.equals(Order.ST_DONE) && listFilter == 0) selId = null;   // ไปเลือกออเดอร์ถัดไปให้
            openWorkspace(tab);
        }
    }

    /** เปิดดูรายละเอียด (จากฟองย่อย) */
    private void openDetail(String id) {
        Order found = null;
        for (Order o : orders) if (o.id.equals(id)) found = o;
        if (found == null) return;
        selId = id;
        if (found.status.equals(Order.ST_DONE)) { listFilter = 1; openWorkspace(T_HIST); }
        else { if (listFilter == 1) listFilter = 0; openWorkspace(T_ORDERS); }
    }

    private void openList() { if (listFilter == 1) listFilter = 0; openWorkspace(T_ORDERS); }

    /* ---------- แท็บ คีย์ออเดอร์หน้าเครื่อง ---------- */

    private static final String K_PLACES = "local_places";

    /** ที่อยู่ที่เคยใช้ (เก็บในเครื่อง + รวมจากออเดอร์บนคลาวด์) ใช้ล่าสุดอยู่บน */
    private List<String> knownPlaces() {
        List<String> out = new ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(Cloud.prefs(this).getString(K_PLACES, "[]"));
            for (int i = arr.length() - 1; i >= 0; i--) { String p = arr.optString(i).trim(); if (!p.isEmpty() && !out.contains(p)) out.add(p); }
        } catch (Exception ignored) {}
        for (int i = orders.size() - 1; i >= 0; i--) {
            String p = orders.get(i).place.trim();
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    private void rememberPlace(String p) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(Cloud.prefs(this).getString(K_PLACES, "[]"));
            org.json.JSONArray keep = new org.json.JSONArray();
            for (int i = 0; i < arr.length(); i++) if (!p.equals(arr.optString(i))) keep.put(arr.optString(i));
            keep.put(p);
            while (keep.length() > 60) keep.remove(0);
            Cloud.prefs(this).edit().putString(K_PLACES, keep.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void openAddLocal() { openAddLocal(""); }
    private void openAddLocal(String query) { addStep = 0; addQuery = query; addPlace = ""; openWorkspace(T_ADD); }
    private void openAddNote(String place) { addStep = 1; addPlace = place; openWorkspace(T_ADD); }

    private void buildAddTab(LinearLayout main) {
        if (addStep == 1) { buildAddNote(main); return; }
        if (addStep == 2) { buildAddNew(main); return; }
        LinearLayout top = topBar(main, "คีย์ออเดอร์หน้าเครื่อง");
        TextView p1 = lchip("ขั้น 1/2 · เลือกที่อยู่", 0xFFF1F2F5, TXT);
        LinearLayout.LayoutParams l1 = lp(WRAP, WRAP); l1.leftMargin = dp(this, 14);
        top.addView(p1, l1);
        topBarEnd(top);

        LinearLayout body = bodyOf(main);
        LinearLayout srow = row(this);
        final android.widget.EditText search = linput("🔍  ค้นหาที่อยู่ที่เคยใช้ หรือพิมพ์ที่อยู่ใหม่ตรงนี้");
        search.setSingleLine(true);
        search.setMinHeight(dp(this, 52));
        search.setText(addQuery);
        search.setSelection(addQuery.length());
        srow.addView(search, lpw(1));
        TextView newBtn = lbtn("➕ พิมพ์ที่อยู่ใหม่", true);
        LinearLayout.LayoutParams nbl = lp(WRAP, WRAP); nbl.leftMargin = dp(this, 10);
        Fx.onTap(newBtn, () -> { addStep = 2; addQuery = search.getText().toString().trim(); openWorkspace(T_ADD); });
        srow.addView(newBtn, nbl);
        body.addView(srow, lp(MATCH, WRAP));

        final LinearLayout listBox = col(this);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(listBox);
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(MATCH, bodyH() - dp(this, 32 + 52 + 12)); svl.topMargin = dp(this, 12);
        body.addView(sv, svl);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int paneW = Math.min(dm.widthPixels - dp(this, 12), dp(this, 1180)) - dp(this, 84 + 36);
        final int cols = Math.max(2, Math.min(6, paneW / dp(this, 190)));

        // สถิติการใช้ที่อยู่จากออเดอร์บนคลาวด์
        final java.util.Map<String, Integer> cnt = new java.util.HashMap<>();
        final java.util.Map<String, Long> last = new java.util.HashMap<>();
        for (Order o : orders) {
            String p = o.place.trim(); if (p.isEmpty()) continue;
            cnt.put(p, (cnt.containsKey(p) ? cnt.get(p) : 0) + 1);
            if (!last.containsKey(p) || last.get(p) < o.createdAt) last.put(p, o.createdAt);
        }

        final Runnable refresh = () -> {
            listBox.removeAllViews();
            String q = search.getText().toString().trim();
            addQuery = q;
            String ql = q.toLowerCase();
            List<String> all = knownPlaces();

            LinearLayout quick = row(this);
            for (String qk : new String[]{"หน้าร้าน", "รับเอง", "ไรเดอร์"}) {
                TextView c = lbtnColor(qk, CARD, qk.equals("หน้าร้าน") ? ORANGE : TXT, qk.equals("หน้าร้าน") ? ORANGE : 0xFFDFE2E8);
                c.setTextSize(14);
                LinearLayout.LayoutParams cl = lp(WRAP, WRAP); cl.rightMargin = dp(this, 8);
                final String v = qk;
                Fx.onTap(c, () -> openAddNote(v));
                quick.addView(c, cl);
            }
            LinearLayout.LayoutParams qll = lp(MATCH, WRAP); qll.bottomMargin = dp(this, 12);
            listBox.addView(quick, qll);

            List<String> items = new ArrayList<>();
            for (String p : all) if (ql.isEmpty() || p.toLowerCase().contains(ql)) items.add(p);
            boolean addNew = !q.isEmpty() && !all.contains(q);

            int total = items.size() + (addNew ? 1 : 0) + (q.isEmpty() ? 1 : 0);
            LinearLayout gridRow = null;
            int k = 0;
            for (int i = 0; i < total; i++) {
                if (k % cols == 0) {
                    gridRow = row(this);
                    gridRow.setGravity(Gravity.TOP);
                    LinearLayout.LayoutParams rl = lp(MATCH, WRAP); rl.bottomMargin = dp(this, 12);
                    listBox.addView(gridRow, rl);
                }
                LinearLayout cardV = col(this);
                cardV.setGravity(Gravity.CENTER_VERTICAL);
                cardV.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
                cardV.setMinimumHeight(dp(this, 92));
                if (addNew && i == 0) {
                    cardV.setBackground(glass(this, 0xFFFFF8F4, 14, ORANGE));
                    cardV.setGravity(Gravity.CENTER);
                    TextView n = text(this, "＋ ใช้ที่อยู่นี้", 16, true, ORANGE); n.setGravity(Gravity.CENTER);
                    cardV.addView(n);
                    TextView s2 = text(this, q, 12.5f, false, ORANGE); s2.setGravity(Gravity.CENTER); s2.setMaxLines(2);
                    s2.setPadding(0, dp(this, 4), 0, 0);
                    cardV.addView(s2);
                    final String v = q;
                    Fx.onTap(cardV, () -> openAddNote(v));
                } else if (i >= items.size() + (addNew ? 1 : 0)) {
                    cardV.setBackground(glass(this, 0xFFFFF8F4, 14, 0xFFF5B79B));
                    cardV.setGravity(Gravity.CENTER);
                    TextView n = text(this, "＋ เพิ่มที่อยู่ใหม่", 16, true, ORANGE); n.setGravity(Gravity.CENTER);
                    cardV.addView(n);
                    TextView s2 = text(this, "แตะเพื่อพิมพ์ที่อยู่", 12, false, MUTED); s2.setGravity(Gravity.CENTER);
                    s2.setPadding(0, dp(this, 4), 0, 0);
                    cardV.addView(s2);
                    Fx.onTap(cardV, () -> { addStep = 2; addQuery = ""; openWorkspace(T_ADD); });
                } else {
                    final String p = items.get(i - (addNew ? 1 : 0));
                    cardV.setBackground(card(14));
                    TextView n = text(this, p, 17, true, TXT);
                    n.setMaxLines(2); n.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    cardV.addView(n);
                    int c2 = cnt.containsKey(p) ? cnt.get(p) : 0;
                    String when = last.containsKey(p) ? "ใช้ล่าสุด " + relDay(last.get(p)) : "บันทึกไว้ในเครื่อง";
                    TextView s2 = text(this, when + (c2 > 0 ? " · " + c2 + " ครั้ง" : ""), 12, false, MUTED);
                    s2.setPadding(0, dp(this, 4), 0, 0);
                    cardV.addView(s2);
                    Fx.onTap(cardV, () -> openAddNote(p));
                }
                LinearLayout.LayoutParams cl = lpw(1);
                if (k % cols != 0) cl.leftMargin = dp(this, 12);
                gridRow.addView(cardV, cl);
                k++;
            }
            if (gridRow != null) for (int e = k % cols; e != 0 && e < cols; e++) {
                View sp = new View(this); LinearLayout.LayoutParams sl2 = lpw(1); sl2.leftMargin = dp(this, 12); gridRow.addView(sp, sl2);
            }
            if (items.isEmpty() && !ql.isEmpty() && !addNew) {
                TextView em = text(this, "ไม่พบที่อยู่ที่ตรงกับ \"" + q + "\"", 13, false, MUTED);
                em.setPadding(dp(this, 4), dp(this, 6), 0, 0);
                listBox.addView(em);
            }
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { refresh.run(); }
            @Override public void afterTextChanged(android.text.Editable e) {}
        });
        try { refresh.run(); } catch (Exception ex) { Toast.makeText(this, "โหลดรายการที่อยู่ไม่ได้: " + ex, Toast.LENGTH_LONG).show(); }

        LinearLayout f = footerOf(main);
        TextView cancel = lbtn("✕ ยกเลิก", false);
        Fx.onTap(cancel, this::closePanel);
        f.addView(cancel);
        View sp = new View(this); f.addView(sp, lpw(1));
        TextView tip = text(this, "แตะการ์ดที่อยู่ → ใส่โน้ต → ยืนยัน ระบบจับเวลาให้ทันที", 12.5f, false, MUTED);
        f.addView(tip);
    }

    /** ขั้นพิเศษ: พิมพ์ที่อยู่ใหม่ (ช่องใหญ่ชัดเจน) → ถัดไปใส่โน้ต */
    private void buildAddNew(LinearLayout main) {
        LinearLayout top = topBar(main, "คีย์ออเดอร์หน้าเครื่อง");
        TextView p1 = lchip("เพิ่มที่อยู่ใหม่", ORANGE_SOFT, ORANGE);
        LinearLayout.LayoutParams l1 = lp(WRAP, WRAP); l1.leftMargin = dp(this, 14);
        top.addView(p1, l1);
        topBarEnd(top);

        LinearLayout body = bodyOf(main);
        LinearLayout cardV = col(this);
        cardV.setBackground(card(16));
        cardV.setPadding(dp(this, 22), dp(this, 20), dp(this, 22), dp(this, 20));
        cardV.addView(text(this, "พิมพ์ชื่อที่อยู่ / จุดส่ง (เช่น ชื่อคอนโด หมู่บ้าน ซอย หรือชื่อลูกค้า)", 13, false, MUTED));
        final android.widget.EditText in = linput("📍 เช่น Gencondo ตึก B / หอพักสีฟ้า ซ.5 / คุณแดง");
        in.setTextSize(22);
        in.setSingleLine(true);
        in.setMinHeight(dp(this, 60));
        in.setText(addQuery);
        in.setSelection(addQuery.length());
        LinearLayout.LayoutParams il = lp(MATCH, WRAP); il.topMargin = dp(this, 8);
        cardV.addView(in, il);
        TextView tip = text(this, "ที่อยู่นี้จะถูกจำไว้ ครั้งต่อไปจะขึ้นเป็นการ์ดให้แตะได้เลย", 12.5f, false, MUTED);
        tip.setPadding(0, dp(this, 10), 0, 0);
        cardV.addView(tip);
        body.addView(cardV, lp(MATCH, WRAP));

        LinearLayout f = footerOf(main);
        TextView back = lbtn("‹ กลับ", false);
        Fx.onTap(back, () -> openAddLocal(""));
        f.addView(back);
        View sp = new View(this); f.addView(sp, lpw(1));
        TextView go = lbtn("ถัดไป › ใส่โน้ต", true);
        go.setTextSize(17);
        Fx.onTap(go, () -> {
            String v = in.getText().toString().trim();
            if (v.isEmpty()) { Toast.makeText(this, "พิมพ์ที่อยู่ก่อน", Toast.LENGTH_SHORT).show(); showIme(in); return; }
            openAddNote(v);
        });
        f.addView(go);
        in.setOnEditorActionListener((tv, a, ev) -> { go.performClick(); return true; });
        showIme(in);
    }

    private String relDay(long t) {
        long d = (Cloud.now() - t) / 86400000L;
        if (d <= 0) return "วันนี้ " + clockOf(t);
        if (d == 1) return "เมื่อวาน";
        if (d < 7) return d + " วันก่อน";
        if (d < 30) return (d / 7) + " สัปดาห์ก่อน";
        return (d / 30) + " เดือนก่อน";
    }

    private void showIme(View v) {
        ui.postDelayed(() -> {
            v.requestFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    /** ขั้น 2: โน้ตเล็กน้อย → ยืนยัน → เริ่มจับเวลา */
    private void buildAddNote(LinearLayout main) {
        final String place = addPlace;
        LinearLayout top = topBar(main, "คีย์ออเดอร์หน้าเครื่อง");
        TextView p1 = lchip("ขั้น 2/2 · โน้ต + ยืนยัน", 0xFFF1F2F5, TXT);
        LinearLayout.LayoutParams l1 = lp(WRAP, WRAP); l1.leftMargin = dp(this, 14);
        top.addView(p1, l1);
        topBarEnd(top);

        LinearLayout body = bodyOf(main);
        LinearLayout cardV = col(this);
        cardV.setBackground(card(16));
        cardV.setPadding(dp(this, 22), dp(this, 20), dp(this, 22), dp(this, 20));
        cardV.addView(text(this, "ที่อยู่ส่ง", 12.5f, false, MUTED));
        TextView pl = text(this, "📍 " + place, 24, true, TXT);
        pl.setPadding(0, dp(this, 4), 0, dp(this, 16));
        cardV.addView(pl);
        cardV.addView(text(this, "โน้ตเล็กน้อย (ไม่ใส่ก็ได้)", 12.5f, false, MUTED));
        final android.widget.EditText note = linput("📝 เช่น กุ้งเผา 1 โล / ไม่ใส่ผัก / โทรก่อนถึง");
        note.setTextSize(18);
        LinearLayout.LayoutParams nl = lp(MATCH, WRAP); nl.topMargin = dp(this, 6);
        cardV.addView(note, nl);
        // โน้ตด่วน
        LinearLayout quick = row(this);
        for (String qk : new String[]{"ไม่เผ็ด", "เผ็ดมาก", "ไม่ใส่ผัก", "โทรก่อนถึง", "รีบ"}) {
            TextView c = lchip(qk, 0xFFF1F2F5, TXT);
            LinearLayout.LayoutParams cl = lp(WRAP, WRAP); cl.rightMargin = dp(this, 8);
            final String v = qk;
            Fx.onTap(c, () -> { String cur = note.getText().toString().trim(); note.setText(cur.isEmpty() ? v : cur + " / " + v); note.setSelection(note.getText().length()); });
            quick.addView(c, cl);
        }
        LinearLayout.LayoutParams ql = lp(MATCH, WRAP); ql.topMargin = dp(this, 10);
        cardV.addView(quick, ql);
        body.addView(cardV, lp(MATCH, WRAP));

        LinearLayout f = footerOf(main);
        TextView back = lbtn("‹ กลับ", false);
        Fx.onTap(back, () -> openAddLocal(""));
        f.addView(back);
        View sp = new View(this); f.addView(sp, lpw(1));
        TextView go = lbtn("✓  ยืนยัน — เริ่มจับเวลา", true);
        go.setTextSize(17);
        Fx.onTap(go, () -> {
            final String n = note.getText().toString().trim();
            final Order o = new Order();
            o.place = place;
            o.lines.add("คีย์ที่เครื่อง POS");
            if (!n.isEmpty()) o.lines.add("หมายเหตุ : " + n);
            o.text = "order " + place + "\n(คีย์ที่เครื่อง POS)" + (n.isEmpty() ? "" : "\nหมายเหตุ : " + n);
            o.pay = n.isEmpty() ? "" : "หมายเหตุ : " + n;
            o.status = Order.ST_DOING;          // คีย์เองแปลว่ารับแล้ว เริ่มทำเลย
            o.by = "pos";
            rememberPlace(place);
            Toast.makeText(this, "⏱ เริ่มจับเวลา " + place, Toast.LENGTH_SHORT).show();
            listFilter = 0;
            openWorkspace(T_ORDERS);
            new Thread(() -> {
                String id = "";
                try { id = Cloud.push(OrderBubbleService.this, o); } catch (Exception ignored) {}
                final String fid = id;
                ui.post(() -> {
                    if (!fid.isEmpty()) { seenIds.add(fid); seenEdit.put(fid, 0L); selId = fid; }
                    fetchOrders();
                    ui.postDelayed(() -> { if (panel != null && tab == T_ORDERS) openWorkspace(T_ORDERS); }, 1200);
                });
            }).start();
        });
        f.addView(go);
        showIme(note);
    }

    /* ---------- แท็บ เก็บกุ้ง ---------- */

    private void buildHarvestTab(LinearLayout main) {
        LinearLayout top = topBar(main, "เก็บกุ้งจากบ่อ");
        topBarEnd(top);
        LinearLayout body = bodyOf(main);
        boolean pending = Cloud.harvestAt > 0 && Cloud.harvestAck < Cloud.harvestAt;
        LinearLayout cardV = col(this);
        cardV.setBackground(pending ? glass(this, ORANGE_SOFT, 16, ORANGE) : card(16));
        cardV.setPadding(dp(this, 24), dp(this, 22), dp(this, 24), dp(this, 22));
        cardV.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView big = text(this, pending ? "🦐 มีสัญญาณให้ไปเก็บกุ้ง!" : "🦐 ไม่มีสัญญาณค้าง", 26, true, pending ? ORANGE : TXT);
        big.setGravity(Gravity.CENTER);
        cardV.addView(big);
        String info;
        if (Cloud.harvestAt > 0) info = "แจ้งล่าสุดจากมือถือ " + clockOf(Cloud.harvestAt) + " น." + (Cloud.harvestAck >= Cloud.harvestAt ? " · รับทราบแล้ว " + clockOf(Cloud.harvestAck) + " น." : "");
        else info = "ยังไม่เคยมีสัญญาณจากมือถือ";
        if (Cloud.harvestEvery > 0) info += "\nตั้งอัตโนมัติทุก " + Cloud.harvestEvery + " นาที";
        TextView sub = text(this, info, 14, false, MUTED);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(this, 8), 0, 0);
        cardV.addView(sub);
        if (pending) {
            TextView ok = lbtn("✓  รับทราบ ไปเก็บกุ้งแล้ว", true);
            ok.setTextSize(18);
            Fx.onTap(ok, () -> {
                Cloud.harvestAck = Math.max(Cloud.harvestAt, Cloud.now());
                harvestPanel = false;
                if (unreadIds.isEmpty()) Alert.stop();
                new Thread(() -> { try { Cloud.ackHarvest(OrderBubbleService.this); } catch (Exception ignored) {} }).start();
                Toast.makeText(this, "รับทราบแล้ว 🦐", Toast.LENGTH_SHORT).show();
                openWorkspace(T_HARV);
            });
            LinearLayout.LayoutParams okl = lp(MATCH, WRAP); okl.topMargin = dp(this, 18);
            cardV.addView(ok, okl);
        }
        body.addView(cardV, lp(MATCH, WRAP));
        TextView tip = text(this, "เมื่อมือถือกด 🦐 แจ้งเก็บกุ้ง เครื่องนี้จะมีเสียงไซเรน + หน้าต่างเด้งขึ้นจนกว่าจะกดรับทราบ", 12.5f, false, MUTED);
        tip.setPadding(dp(this, 4), dp(this, 12), 0, 0);
        body.addView(tip);
    }

    /* ---------- แท็บ ตั้งค่า ---------- */

    private void buildSettingsTab(LinearLayout main) {
        LinearLayout top = topBar(main, "ตั้งค่า");
        topBarEnd(top);
        LinearLayout body = bodyOf(main);
        LinearLayout cardV = col(this);
        cardV.setBackground(card(16));
        cardV.setPadding(dp(this, 22), dp(this, 16), dp(this, 22), dp(this, 16));
        int done = 0; for (Order o : orders) if (o.status.equals(Order.ST_DONE)) done++;
        long age = Cloud.lastOk == 0 ? -1 : (System.currentTimeMillis() - Cloud.lastOk) / 1000;
        String[][] rows = {
                {"เวอร์ชันแอป", ver(this)},
                {"ร้าน", Cloud.shop(this)},
                {"ต่อคลาวด์ล่าสุด", age < 0 ? "ยังไม่เคย" : age + " วินาทีที่แล้ว"},
                {"ออเดอร์บนคลาวด์", orders.size() + " รายการ (เสร็จแล้ว " + done + ")"},
                {"ดึงออเดอร์ใหม่", "ทุก 6 วินาที · เตือนครบ " + READY_MIN + " นาที 2 รอบ"},
                {"เก็บเข้าคลังอัตโนมัติ", "เสร็จแล้วเกิน 12 ชม. · ลบคลังเกิน 30 วัน"},
        };
        for (String[] r : rows) {
            LinearLayout rv = row(this);
            rv.setPadding(0, dp(this, 10), 0, dp(this, 10));
            rv.addView(text(this, r[0], 14, false, MUTED), lpw(1));
            rv.addView(text(this, r[1], 14, true, TXT));
            cardV.addView(rv, lp(MATCH, WRAP));
            cardV.addView(hline(), lp(MATCH, dp(this, 1)));
        }
        body.addView(cardV, lp(MATCH, WRAP));

        LinearLayout acts = row(this);
        acts.setPadding(0, dp(this, 14), 0, 0);
        TextView arch = lbtn("🗄  เก็บที่เสร็จแล้วเข้าคลัง (" + done + ")", false);
        Fx.onTap(arch, this::clearDone);
        acts.addView(arch);
        TextView app = lbtn("🔗 เปิดหน้าตั้งค่าลิงก์คลาวด์", false);
        LinearLayout.LayoutParams al = lp(WRAP, WRAP); al.leftMargin = dp(this, 8);
        Fx.onTap(app, () -> {
            closePanel();
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });
        acts.addView(app, al);
        TextView test = lbtn("🔔 ทดสอบเสียงออเดอร์ใหม่", false);
        LinearLayout.LayoutParams tl = lp(WRAP, WRAP); tl.leftMargin = dp(this, 8);
        Fx.onTap(test, () -> Alert.ping(this, Alert.NEW));
        acts.addView(test, tl);
        body.addView(acts, lp(MATCH, WRAP));
    }

    private void clearDone() {
        final List<Order> done = new ArrayList<>();
        for (Order o : orders) if (o.status.equals(Order.ST_DONE)) done.add(o);
        if (done.isEmpty()) { Toast.makeText(this, "ไม่มีออเดอร์ที่เสร็จแล้ว", Toast.LENGTH_SHORT).show(); return; }
        new Thread(() -> {
            for (Order o : done) { try { Cloud.archive(OrderBubbleService.this, o); } catch (Exception ignored) {} }
            ui.post(() -> { fetchOrders(); ui.postDelayed(() -> { if (panel != null) openWorkspace(tab); }, 900); });
        }).start();
        Toast.makeText(this, "เก็บเข้าคลัง " + done.size() + " ออเดอร์แล้ว (เก็บไว้ 30 วัน)", Toast.LENGTH_SHORT).show();
    }

    /** งานดูแลอัตโนมัติ (ทุก 30 นาที): เก็บออเดอร์เสร็จเกิน 12 ชม. เข้า archive + ลบ archive เกิน 30 วัน */
    private final Runnable housekeeping = new Runnable() {
        @Override public void run() {
            if (Cloud.ready(OrderBubbleService.this)) {
                final List<Order> old = new ArrayList<>();
                long cutoff = Cloud.now() - 12L * 3600000L;
                for (Order o : orders) if (o.status.equals(Order.ST_DONE) && Math.max(o.createdAt, o.editedAt) < cutoff) old.add(o);
                new Thread(() -> {
                    for (Order o : old) { try { Cloud.archive(OrderBubbleService.this, o); } catch (Exception ignored) {} }
                    try { Cloud.purgeArchive(OrderBubbleService.this, 30); } catch (Exception ignored) {}
                    if (!old.isEmpty()) ui.post(OrderBubbleService.this::fetchOrders);
                }).start();
            }
            ui.postDelayed(this, 30 * 60000L);
        }
    };

    /* ================= panel helper ================= */

    private void showPanel() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.min(dm.widthPixels, dp(this, 1000));   // จอกว้างมาก → จำกัดความกว้างให้อ่านง่าย
        showPanel(w, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    }

    private void showPanel(int w, int h) { showPanel(w, h, Gravity.CENTER); }

    private void showPanel(int w, int h, int gravity) {
        panelParams = new WindowManager.LayoutParams(w, h, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        panelParams.gravity = gravity;
        panelParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;

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

    /* ================= แจ้งเตือนเก็บกุ้งจากบ่อ ================= */

    private void checkHarvest() {
        long at = Cloud.harvestAt, ack = Cloud.harvestAck;
        if (at <= 0) return;
        if (ack >= at) {                       // รับทราบแล้ว
            if (harvestPanel) { closePanel(); harvestPanel = false; }
            return;
        }
        if (at > harvestShownAt) {              // สัญญาณใหม่ → เสียง + popup
            harvestShownAt = at;
            Alert.start(this, Alert.HARVEST);
            if (mainDisc != null) Fx.bounce(mainDisc);
            openHarvest();
        } else if (!harvestPanel && panel == null) {
            openHarvest();                      // ยังไม่กดรับทราบ → เปิดเตือนซ้ำ
        }
    }

    private void openHarvest() {
        closePanel();
        harvestPanel = true;
        panel = col(this);
        panel.setBackground(glass(this, 0xF5FF6B4A, 24, 0xFFFFFFFF));
        panel.setPadding(dp(this, 24), dp(this, 22), dp(this, 24), dp(this, 22));
        panel.setElevation(dp(this, 20));

        TextView big = text(this, "🦐 เก็บกุ้งจากบ่อ!", 30, true, WHITE);
        big.setGravity(Gravity.CENTER);
        panel.addView(big, lp(MATCH, WRAP));
        String when = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(new java.util.Date(Cloud.harvestAt - Cloud.offset));
        TextView sub = text(this, "แจ้งจากมือถือเมื่อ " + when + " น."
                + (Cloud.harvestEvery > 0 ? "  •  ตั้งอัตโนมัติทุก " + Cloud.harvestEvery + " นาที" : ""), 14, false, 0xFFFFE6D9);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(this, 6), 0, 0);
        panel.addView(sub, lp(MATCH, WRAP));

        TextView ok = button(this, "✓  รับทราบ ไปเก็บกุ้งแล้ว", glass(this, 0xFFFFFFFF, 16, 0), 18);
        ok.setTextColor(0xFFB3401B);
        Fx.onTap(ok, () -> {
            Cloud.harvestAck = Math.max(Cloud.harvestAt, Cloud.now());
            harvestPanel = false;
            if (unreadIds.isEmpty()) Alert.stop();
            new Thread(() -> { try { Cloud.ackHarvest(OrderBubbleService.this); } catch (Exception ignored) {} }).start();
            closePanel();
            Toast.makeText(this, "รับทราบแล้ว 🦐", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams okl = lp(MATCH, WRAP); okl.topMargin = dp(this, 18);
        panel.addView(ok, okl);

        showPanel();
    }

    private void closePanel() {
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) {}
            panel = null;
        }
        openOrderId = null;
        harvestPanel = false;
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
        if (listBubble != null) { try { wm.removeView(listBubble); } catch (Exception ignored) {} }
        ui.removeCallbacks(housekeeping);
    }
}
