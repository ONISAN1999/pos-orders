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
    private boolean harvestPending = false; // มีสัญญาณเก็บกุ้งที่ยังไม่กดรับทราบ (ไซเรนต้องดังต่อ)
    private boolean loaded = false;         // ดึงออเดอร์จากคลาวด์สำเร็จแล้วอย่างน้อย 1 ครั้ง
    private final java.util.Map<String, String> pendingStatus = new java.util.HashMap<>();   // สถานะที่กดไว้แต่ยังส่งขึ้นคลาวด์ไม่สำเร็จ
    private String posBanner = ""; private long posBannerAt = 0;   // แถบแจ้งข้อผิดพลาดค้างบนจอ

    /** หยุดเสียงเฉพาะเมื่อไม่มีทั้งออเดอร์ค้างและสัญญาณเก็บกุ้งค้าง */
    private void stopAlertIfIdle() { if (unreadIds.isEmpty() && !harvestPending) Alert.stop(); }

    private boolean expanded = true;
    private long downAt = 0;
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
        ui.postDelayed(updateCheck, 20000);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "update".equals(intent.getStringExtra("act"))) startUpdate();
        return START_STICKY;
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int win = dp(this, MAIN_WIN);
        if (mainParams != null) {
            mainParams.x = Math.max(0, Math.min(mainParams.x, dm.widthPixels - win));
            mainParams.y = Math.max(0, Math.min(mainParams.y, dm.heightPixels - win));
            try { wm.updateViewLayout(mainBubble, mainParams); } catch (Exception ignored) {}
            layoutChildren(); placeListBubble();
        }
        if (panel != null && !harvestPanel) openWorkspace(tab);
    }

    /* ================= อัปเดตแอปอัตโนมัติจาก GitHub ================= */
    private int updateNotified = 0;
    private final Runnable updateCheck = new Runnable() {
        @Override public void run() {
            Updater.check(OrderBubbleService.this, (ok, msg) -> {
                if (ok && Updater.latestCode != updateNotified) {
                    updateNotified = Updater.latestCode;
                    Updater.notify(OrderBubbleService.this, OrderBubbleService.class);
                    Toast.makeText(OrderBubbleService.this, "🆕 " + msg + " — เปิด ตั้งค่า เพื่ออัปเดต", Toast.LENGTH_LONG).show();
                    if (panel != null) openWorkspace(tab);
                }
            });
            ui.postDelayed(this, 6 * 3600000L);   // ตรวจทุก 6 ชม.
        }
    };

    private void startUpdate() {
        Toast.makeText(this, "⬇️ กำลังโหลดเวอร์ชันใหม่…", Toast.LENGTH_SHORT).show();
        Runnable go = () -> Updater.install(this, (ok, msg) -> Toast.makeText(this, (ok ? "✅ " : "⚠️ ") + msg, Toast.LENGTH_LONG).show());
        if (Updater.latestApk.isEmpty()) Updater.check(this, (ok, msg) -> { if (ok) go.run(); else Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); });
        else go.run();
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT);   // จอไม่ดับ → เสียงเตือนทำงานตลอด
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
                        downAt = System.currentTimeMillis();
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
                            if (System.currentTimeMillis() - downAt >= 600) { Fx.buzz(OrderBubbleService.this); openList(); return true; }
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
        mainBubble.setContentDescription("ฟองออเดอร์ POS แตะเพื่อเปิดรายการออเดอร์");
        mainBubble.setOnClickListener(v -> { if (panel != null) closePanel(); else openList(); });

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
    private String childSig = "";
    private void rebuildChildren() {
        StringBuilder sig = new StringBuilder();
        for (Order o : orders) if (!o.isClosed() && !o.isPre()) sig.append(o.id).append('|');
        if (sig.toString().equals(childSig) && childViews.size() == activeOrders().size()) { refreshClocks(); refreshMain(); return; }
        childSig = sig.toString();
        for (View v : childViews) { stopPulse(v); try { wm.removeView(v); } catch (Exception ignored) {} }
        childViews.clear();
        childParams.clear();

        int win = dp(this, CHILD_WIN);
        int s = dp(this, CHILD_DP);
        for (Order o : orders) {
            if (o.isClosed() || o.isPre()) continue;

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
            checkPreActivation();
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
            if (o.isClosed() || o.isPre()) continue;
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
        for (Order o : orders) if (!o.isClosed() && !o.isPre()) a.add(o);
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
                        if (o.isClosed() || o.isPre()) { unreadIds.remove(o.id); if (o.isPre()) { seenIds.add(o.id); seenEdit.put(o.id, o.editedAt); } continue; }
                        if (!seenIds.contains(o.id)) {
                            seenIds.add(o.id);
                            if (o.status.equals(Order.ST_NEW) || o.needsAck()) { unreadIds.add(o.id); isNew = true; }
                        }
                        // รับไปแล้วจากที่อื่น / ไม่มีการแก้ไขค้าง → เลิกเตือน
                        if (unreadIds.contains(o.id) && !o.status.equals(Order.ST_NEW) && !o.needsAck()) unreadIds.remove(o.id);
                    }
                    for (Order o : got) { String ps = pendingStatus.get(o.id); if (ps != null) o.status = ps; }
                    // กันออเดอร์ที่ POS เพิ่งย้ายออกจากพรี แล้วคลาวด์ยังไม่ทันอัปเดต: เก็บเวลาเริ่มเตรียมจากในเครื่อง
                    for (Order o : got) for (Order old : orders) if (old.id.equals(o.id) && !old.isPre() && o.isPre() && pendingStatus.containsKey(o.id)) { o.status = old.status; o.createdAt = old.createdAt; }
                    orders = got;
                    loaded = true;
                    rebuildChildren();
                    checkPreActivation();
                    if (isNew) {
                        Fx.bounce(mainDisc);
                        boolean anyEdit = false;
                        for (Order o : got) if (unreadIds.contains(o.id) && o.needsAck()) anyEdit = true;
                        Alert.start(OrderBubbleService.this, anyEdit ? Alert.EDIT : Alert.NEW);   // ดังจนกว่าจะกดรับ
                    }
                    stopAlertIfIdle();
                });
            } catch (Exception ignored) {}
        }).start();
    }

    /** ทำเครื่องหมายว่าอ่านแล้ว — เงียบเสียงเตือนถ้าอ่านครบ */
    private void markRead(String id) {
        if (id == null) unreadIds.clear();
        else unreadIds.remove(id);
        stopAlertIfIdle();
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

    private static final int T_ORDERS = 0, T_ADD = 1, T_HIST = 2, T_HARV = 3, T_SET = 4, T_PRE = 5;
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
        boolean done = o.isClosed();
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
        if (o.status.equals(Order.ST_CANCEL)) return lchip("ยกเลิก", RED_SOFT, RED_C);
        if (o.preLate()) return lchip("เลยเวลานัด", RED_SOFT, RED_C);
        if (o.preAt > 0) return lchip("📅 นัด " + o.preClock(), PURPLE_SOFT, PURPLE);
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
            case T_PRE:  buildPreTab(main); break;
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
        String[][] items = {{"🧾", "ออเดอร์"}, {"📅", "พรีออเดอร์"}, {"➕", "คีย์เอง"}, {"📋", "ประวัติ"}, {"🦐", "เก็บกุ้ง"}, {"⚙️", "ตั้งค่า"}};
        int[] ids = {T_ORDERS, T_PRE, T_ADD, T_HIST, T_HARV, T_SET};
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
            int badge = id == T_ORDERS ? unreadIds.size() : id == T_PRE ? preTodayCount() : id == T_HARV && Cloud.harvestAt > 0 && Cloud.harvestAck < Cloud.harvestAt ? 1 : id == T_SET && Updater.available(this) ? 1 : 0;
            if (badge > 0) {
                TextView bd = text(this, id == T_ORDERS || id == T_PRE ? String.valueOf(badge) : id == T_SET ? "🆕" : "!", 10, true, WHITE);
                bd.setGravity(Gravity.CENTER);
                bd.setBackground(fill(id == T_PRE ? PURPLE : RED_C, 999));
                bd.setPadding(dp(this, 6), dp(this, 1), dp(this, 6), dp(this, 1));
                LinearLayout.LayoutParams bl = lp(WRAP, WRAP); bl.topMargin = dp(this, 2);
                it.addView(bd, bl);
            }
            LinearLayout.LayoutParams il = lp(dp(this, 66), dp(this, badge > 0 ? 72 : 58)); il.bottomMargin = dp(this, 4);
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
        if (Updater.available(this) && tab != T_SET) {
            TextView up = lchip("🆕 v" + Updater.latestName, ORANGE_SOFT, ORANGE);
            LinearLayout.LayoutParams ul = lp(WRAP, WRAP); ul.rightMargin = dp(this, 10);
            Fx.onTap(up, () -> openWorkspace(T_SET));
            top.addView(up, ul);
        }
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

    /** แสดงข้อผิดพลาดแบบค้างบนจอ (Toast อย่างเดียวอาจไม่เด้ง) */
    private void showError(String msg) {
        posBanner = "⚠️ " + msg; posBannerAt = System.currentTimeMillis();
        try { Toast.makeText(this, posBanner, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
        if (panel != null) openWorkspace(tab);
    }

    private LinearLayout bodyOf(LinearLayout main) {
        LinearLayout body = col(this);
        body.setPadding(dp(this, 18), dp(this, 16), dp(this, 18), dp(this, 16));
        main.addView(body, new LinearLayout.LayoutParams(MATCH, bodyH()));
        if (!posBanner.isEmpty() && System.currentTimeMillis() - posBannerAt < 90000L) {
            LinearLayout bn = row(this);
            bn.setBackground(glass(this, RED_SOFT, 12, RED_C));
            bn.setPadding(dp(this, 12), dp(this, 8), dp(this, 8), dp(this, 8));
            TextView bt = text(this, posBanner, 13, true, RED_C);
            bn.addView(bt, lpw(1));
            TextView x = lchip("✕", 0xFFFEE2E2, RED_C);
            Fx.onTap(x, () -> { posBanner = ""; openWorkspace(tab); });
            bn.addView(x);
            LinearLayout.LayoutParams bl = lp(MATCH, WRAP); bl.bottomMargin = dp(this, 10);
            body.addView(bn, bl);
        }
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
        for (Order o : orders) { if (o.isPre()) continue; if (o.isClosed()) done.add(o); else act.add(o); }
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
            TextView em = text(this, !loaded ? "⏳ กำลังโหลดออเดอร์จากคลาวด์…" : filter == 0 ? "ไม่มีออเดอร์ค้าง 🎉" : "ยังไม่มีออเดอร์", 14, false, MUTED);
            em.setPadding(dp(this, 4), dp(this, 14), 0, 0);
            list.addView(em);
        }
        for (Order o : show) {
            final boolean isSel = sel != null && o.id.equals(sel.id);
            LinearLayout rowv = row(this);
            rowv.setBackground(glass(this, CARD, 14, isSel ? ORANGE : LINE_C));
            rowv.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            if (o.isClosed() && filter != 1) rowv.setAlpha(0.6f);
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
            Fx.onTap(rowv, () -> {
                selId = oid;
                if (unreadIds.contains(oid)) {   // แตะแถวออเดอร์ที่ยังไม่รับ = รับทันที (เสียงหยุด)
                    Order x = null; for (Order q : orders) if (q.id.equals(oid)) x = q;
                    if (x != null) { if (x.needsAck()) ackEditNow(x); if (x.status.equals(Order.ST_NEW)) { updateStatus(x, Order.ST_DOING); return; } markRead(oid); }
                }
                openWorkspace(tab);
            });
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
        } else buildDetail(right, sel, innerH);

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

    /** ทุกบรรทัดชำระ (ช่องทาง · สถานะ) รวมเป็นข้อความเดียว */
    private String payAllOf(Order o) {
        List<String> parts = new ArrayList<>();
        for (String pl : o.pay.split("\\n")) {
            String q = pl.trim();
            if (q.isEmpty() || q.startsWith("หมายเหตุ")) continue;
            int i = q.indexOf(":");
            String v = i > 0 ? q.substring(i + 1).trim() : q;
            if (!v.isEmpty() && !parts.contains(v)) parts.add(v);
        }
        return String.join(" · ", parts);
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

    /** รายละเอียดออเดอร์ (ด้านขวา) — แบบ "ใบสั่งครัว": หัวดำ · จำนวนกล่องส้ม · เส้นประ · รวม/ชำระ 2 กล่องใหญ่ */
    private void buildDetail(LinearLayout right, final Order o, int paneH) {
        right.setPadding(0, 0, 0, 0);
        right.setClipToOutline(true);

        // ---- หัวดำ ----
        LinearLayout h = row(this);
        h.setBackground(glass(this, 0xFF1F2430, 0, 0));
        h.setPadding(dp(this, 18), dp(this, 12), dp(this, 18), dp(this, 12));
        LinearLayout hc = col(this);
        TextView big = text(this, "📍 " + (o.place.isEmpty() ? (o.by.equals("pos") ? "คีย์ที่เครื่อง" : "ออเดอร์") : o.place), 22, true, WHITE);
        big.setSingleLine(true); big.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hc.addView(big);
        String meta = (o.no.isEmpty() ? "" : "#" + o.no + " · ") + (o.preAt > 0 ? "📅 นัด" + ("ส่ง".equals(o.preType) ? "ส่ง " : "รับ ") + o.preClock() + " น. (" + untilLabel(o.preAt) + ") · เริ่มเตรียม " : "สั่ง ") + clockOf(o.createdAt) + " น. · " + (o.by.equals("pos") ? "คีย์ที่เครื่อง" : "จากมือถือ")
                + (o.status.equals(Order.ST_DONE) ? " · เสร็จแล้ว" : o.status.equals(Order.ST_CANCEL) ? " · ยกเลิกแล้ว" : "");
        TextView mt = text(this, meta, 13, false, 0xBFFFFFFF);
        mt.setPadding(0, dp(this, 3), 0, 0);
        hc.addView(mt);
        h.addView(hc, lpw(1));
        if (o.needsAck()) {
            TextView ed = lchip("✏️ แก้ไข " + clockOf(o.editedAt), PURPLE, WHITE);
            LinearLayout.LayoutParams el = lp(WRAP, WRAP); el.rightMargin = dp(this, 12);
            h.addView(ed, el);
        } else if (o.status.equals(Order.ST_NEW)) {
            TextView nw = lchip("ใหม่ — ยังไม่รับ", ORANGE, WHITE);
            LinearLayout.LayoutParams el = lp(WRAP, WRAP); el.rightMargin = dp(this, 12);
            h.addView(nw, el);
        }
        LinearLayout tb = timeBox(o, 56);
        GradientDrawable tbg = (GradientDrawable) tb.getBackground();
        tbg.setStroke(dp(this, 3), WHITE);
        LinearLayout.LayoutParams tl = lp(dp(this, 56), dp(this, 56));
        h.addView(tb, tl);
        right.addView(h, lp(MATCH, dp(this, 76)));

        // ---- รายการ ----
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = col(this);
        list.setPadding(dp(this, 18), dp(this, 4), dp(this, 18), dp(this, 4));
        sv.addView(list);
        int listH = Math.max(dp(this, 70), paneH - dp(this, 76 + 66 + 46 + 58 + 6));
        right.addView(sv, new LinearLayout.LayoutParams(MATCH, listH));

        java.util.regex.Pattern linePat = java.util.regex.Pattern.compile("^(.*?)(?:\\s+[xX×]\\s*(\\d+))?\\s+(\\d[\\d,]*)\\s*บาท(?:\\s*\\((.*)\\))?\\s*$");
        final boolean showDiff = o.needsAck() && !o.prevLines.isEmpty();
        java.util.Set<String> prevSet = new java.util.HashSet<>(o.prevLines);
        java.util.Set<String> curSet = new java.util.HashSet<>();
        for (String line : o.lines) curSet.add(line.trim());
        String noteTxt = null;
        int count = 0;

        for (String line : o.lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("ช่องทางชำระ") || t.startsWith("สถานะ") || t.startsWith("ยอดรวม")) continue;
            if (t.startsWith("หมายเหตุ")) { int i = t.indexOf(":"); noteTxt = i > 0 ? t.substring(i + 1).trim() : t; continue; }
            final boolean changed = showDiff && !prevSet.contains(t);
            boolean ship = t.startsWith("ค่าส่ง") || t.startsWith("ส่งฟรี");
            String name = t, qty = "1", price = "", note = "";
            java.util.regex.Matcher m = linePat.matcher(t);
            if (m.find()) { name = m.group(1); if (m.group(2) != null) qty = m.group(2); price = m.group(3); if (m.group(4) != null) note = m.group(4); }
            else if (ship) price = "0";
            if (!ship) count += Integer.parseInt(qty);
            list.addView(ticketRow(name, qty, price, note, ship, changed ? PURPLE_SOFT : 0, changed ? "แก้" : null, t, false), lp(MATCH, WRAP));
            list.addView(dashed(), lp(MATCH, dp(this, 2)));
        }
        if (showDiff) {
            for (String pv : o.prevLines) {
                String t = pv.trim();
                if (t.isEmpty() || curSet.contains(t)) continue;
                if (t.startsWith("ช่องทางชำระ") || t.startsWith("สถานะ") || t.startsWith("ยอดรวม") || t.startsWith("หมายเหตุ")) continue;
                String name = t, qty = "1", price = "", note = "";
                java.util.regex.Matcher m = linePat.matcher(t);
                if (m.find()) { name = m.group(1); if (m.group(2) != null) qty = m.group(2); price = m.group(3); if (m.group(4) != null) note = m.group(4); }
                list.addView(ticketRow(name, qty, price, note, false, RED_SOFT, "ตัดออก", t, true), lp(MATCH, WRAP));
                list.addView(dashed(), lp(MATCH, dp(this, 2)));
            }
        }
        if (noteTxt != null && !noteTxt.isEmpty()) {
            TextView nb = text(this, "📝 หมายเหตุ: " + noteTxt, 17, true, 0xFF92400E);
            nb.setBackground(glass(this, YEL_SOFT, 12, YEL_LINE));
            nb.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
            LinearLayout.LayoutParams nl = lp(MATCH, WRAP); nl.topMargin = dp(this, 10);
            final String ntx = noteTxt;
            Fx.onCopyTap(nb, () -> copy(ntx, "คัดลอกหมายเหตุแล้ว"));
            list.addView(nb, nl);
        }
        TextView hint = text(this, "แตะรายการเพื่อคัดลอกทีละบรรทัด", 11, false, MUTED);
        hint.setPadding(dp(this, 2), dp(this, 8), 0, 0);
        list.addView(hint);

        // ---- รวม / ชำระ: 2 กล่องใหญ่ ----
        LinearLayout ft = row(this);
        ft.setPadding(dp(this, 18), dp(this, 8), dp(this, 18), dp(this, 6));
        LinearLayout b1 = row(this);
        b1.setBackground(fill(0xFFF1F2F5, 12));
        b1.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        b1.addView(text(this, "รวม " + count + " รายการ", 14, true, 0xFF5B6270), lpw(1));
        b1.addView(text(this, o.total > 0 ? o.total + " บาท" : "—", 24, true, TXT));
        ft.addView(b1, lpw(1));
        String payAll = payAllOf(o);
        boolean paid = payAll.contains("ชำระแล้ว") || payAll.contains("โอนแล้ว");
        LinearLayout b2 = row(this);
        b2.setBackground(glass(this, paid ? GREEN_SOFT : YEL_SOFT, 12, paid ? GREEN_C : YEL_LINE));
        b2.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        b2.addView(text(this, "ชำระ", 14, true, paid ? GREEN_C : 0xFF92400E), lpw(1));
        TextView pv = text(this, payAll.isEmpty() ? "—" : (paid ? "✅ " : "💰 ") + payAll, 20, true, paid ? GREEN_C : 0xFF92400E);
        pv.setSingleLine(true); pv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b2.addView(pv);
        LinearLayout.LayoutParams b2l = lpw(1.2f); b2l.leftMargin = dp(this, 10);
        if (!payAll.isEmpty()) { final String pa = payAll; Fx.onCopyTap(b2, () -> copy(pa, "คัดลอก: " + pa)); }
        ft.addView(b2, b2l);
        right.addView(ft, lp(MATCH, dp(this, 66)));

        // ---- ปุ่ม: แถวบน = คัดลอก (รอง) · แถวล่าง = ปุ่มหลักใหญ่ ----
        LinearLayout r1 = row(this);
        r1.setPadding(dp(this, 18), dp(this, 2), dp(this, 18), 0);
        TextView cpP = lbtn("📋 คัดลอกที่อยู่", false);
        cpP.setTextSize(13.5f); cpP.setPadding(dp(this, 10), dp(this, 9), dp(this, 10), dp(this, 9));
        if (!o.place.isEmpty()) { final String plc = o.place; Fx.onCopyTap(cpP, () -> { copy(plc, "คัดลอกที่อยู่แล้ว — วางในแอปส่งได้เลย"); ui.postDelayed(this::closePanel, 150); }); }   // คัดลอกแล้วหุบเป็นฟองทันที
        else cpP.setAlpha(0.4f);
        r1.addView(cpP, lpw(1));
        TextView cpA = lbtn("📄 คัดลอกทั้งออเดอร์", false);
        cpA.setTextSize(13.5f); cpA.setPadding(dp(this, 10), dp(this, 9), dp(this, 10), dp(this, 9));
        LinearLayout.LayoutParams cal = lpw(1); cal.leftMargin = dp(this, 8);
        Fx.onCopyTap(cpA, () -> copy(o.text.isEmpty() ? joinLines(o) : o.text, "คัดลอกออเดอร์แล้ว"));
        r1.addView(cpA, cal);
        right.addView(r1, lp(MATCH, dp(this, 46)));

        LinearLayout r2 = row(this);
        r2.setPadding(dp(this, 18), dp(this, 6), dp(this, 18), dp(this, 6));
        boolean urgent = unreadIds.contains(o.id);
        int nBtn = 0;
        if (o.needsAck()) {
            TextView ack = lbtnColor("✓ รับทราบการแก้ไข", PURPLE, WHITE, 0);
            ack.setTextSize(16); ack.setPadding(dp(this, 12), dp(this, 11), dp(this, 12), dp(this, 11));
            if (urgent) startPulse(ack);
            Fx.onTap(ack, () -> { ackEditNow(o); openWorkspace(tab); });
            r2.addView(ack, lpw(1)); nBtn++;
        }
        if (o.status.equals(Order.ST_NEW)) {
            TextView doing = lbtn(urgent ? "🔥 รับออเดอร์ (หยุดเสียง)" : "🔥 รับออเดอร์", true);
            doing.setTextSize(16); doing.setPadding(dp(this, 12), dp(this, 11), dp(this, 12), dp(this, 11));
            if (urgent) startPulse(doing);
            Fx.onTap(doing, () -> updateStatus(o, Order.ST_DOING));
            LinearLayout.LayoutParams dl = lpw(1); if (nBtn > 0) dl.leftMargin = dp(this, 8);
            r2.addView(doing, dl); nBtn++;
        }
        if (!o.isClosed()) {
            boolean primaryDone = nBtn == 0;
            TextView done = primaryDone ? lbtnColor("✅ เสร็จแล้ว — ส่งออก", GREEN_C, WHITE, 0) : lbtnColor("✅ เสร็จแล้ว", CARD, GREEN_C, GREEN_C);
            done.setTextSize(16); done.setPadding(dp(this, 12), dp(this, 11), dp(this, 12), dp(this, 11));
            Fx.onTap(done, () -> updateStatus(o, Order.ST_DONE));
            LinearLayout.LayoutParams dnl = lpw(1); if (nBtn > 0) dnl.leftMargin = dp(this, 8);
            r2.addView(done, dnl); nBtn++;
        } else {
            TextView re = lbtnColor("↩ เปิดใหม่ (กำลังทำ)", CARD, ORANGE, ORANGE);
            re.setTextSize(16); re.setPadding(dp(this, 12), dp(this, 11), dp(this, 12), dp(this, 11));
            Fx.onTap(re, () -> updateStatus(o, Order.ST_DOING));
            r2.addView(re, lpw(1));
        }
        right.addView(r2, lp(MATCH, dp(this, 58)));
    }

    /** 1 แถวในใบสั่งครัว: [จำนวน] [รูป] ชื่อ+โน้ต ............ ราคา */
    private LinearLayout ticketRow(String name, String qty, String price, String note, boolean ship, int bg, String tag, final String copyTxt, boolean removed) {
        LinearLayout rowv = row(this);
        rowv.setPadding(dp(this, bg != 0 ? 8 : 0), dp(this, 10), dp(this, bg != 0 ? 8 : 0), dp(this, 10));
        if (bg != 0) rowv.setBackground(fill(bg, 10));

        TextView q = text(this, ship ? "🛵" : qty, ship ? 20 : 26, true, WHITE);
        q.setGravity(Gravity.CENTER);
        q.setBackground(fill(removed ? RED_C : ship ? 0xFF9AA1AE : ORANGE, 12));
        q.setIncludeFontPadding(false);
        LinearLayout.LayoutParams ql = lp(dp(this, 52), dp(this, 52)); ql.rightMargin = dp(this, 14);
        rowv.addView(q, ql);

        int img = name.contains("กุ้งแกะ") ? R.drawable.menu_kae : name.contains("กุ้งผ่า") ? R.drawable.menu_pha : 0;
        if (img != 0) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageResource(img);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            iv.setBackground(fill(WHITE, 8));
            LinearLayout.LayoutParams il = lp(dp(this, 64), dp(this, 48)); il.rightMargin = dp(this, 12);
            rowv.addView(iv, il);
        }

        LinearLayout nc = col(this);
        LinearLayout nr = row(this);
        if (tag != null) {
            TextView tg = lchip(tag, removed ? RED_C : PURPLE, WHITE);
            tg.setTextSize(11); tg.setPadding(dp(this, 8), dp(this, 2), dp(this, 8), dp(this, 2));
            LinearLayout.LayoutParams tgl = lp(WRAP, WRAP); tgl.rightMargin = dp(this, 8);
            nr.addView(tg, tgl);
        }
        TextView nm = text(this, name, ship ? 17 : 22, !ship, removed ? RED_C : ship ? 0xFF5B6270 : TXT);
        if (removed) nm.setPaintFlags(nm.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        nr.addView(nm, lpw(1));
        nc.addView(nr, lp(MATCH, WRAP));
        if (!note.isEmpty()) {
            TextView nt = text(this, "📝 " + note, 13.5f, true, 0xFF92400E);
            nt.setBackground(fill(YEL_SOFT, 6));
            nt.setPadding(dp(this, 8), dp(this, 2), dp(this, 8), dp(this, 2));
            LinearLayout.LayoutParams ntl = lp(WRAP, WRAP); ntl.topMargin = dp(this, 4);
            nc.addView(nt, ntl);
        }
        rowv.addView(nc, lpw(1));

        TextView pr = text(this, price.isEmpty() ? "" : price, 20, true, 0xFF5B6270);
        pr.setPadding(dp(this, 12), 0, 0, 0);
        rowv.addView(pr);
        Fx.onCopyTap(rowv, () -> copy(copyTxt, "คัดลอก: " + copyTxt));
        return rowv;
    }

    /** เส้นประแบบใบเสร็จ */
    private View dashed() {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.LINE);
        g.setStroke(dp(this, 2), 0xFFDFE2E8, dp(this, 6), dp(this, 5));
        v.setBackground(g);
        v.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        return v;
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
        final String prev = o.status;
        o.status = status;
        if (!o.needsAck()) markRead(o.id);   // กดรับออเดอร์ = รับทราบ → เงียบ
        if (Cloud.ready(this)) {
            final String id = o.id;
            pendingStatus.put(id, status);   // ทับค่าที่ดึงจากคลาวด์จนกว่าจะส่งสำเร็จ
            new Thread(() -> {
                try { Cloud.setStatus(OrderBubbleService.this, id, status); ui.post(() -> pendingStatus.remove(id)); }
                catch (Exception e) {
                    ui.post(() -> {
                        pendingStatus.remove(id);
                        for (Order x : orders) if (x.id.equals(id)) x.status = prev;
                        showError("บันทึกสถานะ \"" + status + "\" ไม่สำเร็จ — เช็คเน็ตแล้วกดอีกครั้ง");
                        rebuildChildren();
                        if (panel != null) openWorkspace(tab);
                    });
                }
            }).start();
        }
        Toast.makeText(this, "ออเดอร์ " + (o.no.isEmpty() ? o.place : "#" + o.no) + " → " + status, Toast.LENGTH_SHORT).show();
        rebuildChildren();
        if (panel != null) {
            if (status.equals(Order.ST_DONE) && listFilter == 0) selId = null;   // ไปเลือกออเดอร์ถัดไปให้
            openWorkspace(tab);
        }
    }

    /** กดรับทราบการแก้ไข (ส่งขึ้นคลาวด์ + เงียบเสียง) */
    private void ackEditNow(final Order o) {
        final long prevAck = o.ackEditAt;
        o.ackEditAt = Math.max(o.editedAt, Cloud.now());
        if (!o.status.equals(Order.ST_NEW)) markRead(o.id);
        new Thread(() -> {
            try { Cloud.ackEdit(OrderBubbleService.this, o.id); }
            catch (Exception e) { ui.post(() -> { o.ackEditAt = prevAck; unreadIds.add(o.id); showError("ส่งรับทราบการแก้ไขไม่สำเร็จ — เช็คเน็ตแล้วกดอีกครั้ง"); rebuildChildren(); if (panel != null) openWorkspace(tab); }); }
        }).start();
        Toast.makeText(this, "รับทราบการแก้ไขแล้ว", Toast.LENGTH_SHORT).show();
        rebuildChildren();
    }

    /** เปิดดูรายละเอียด (จากฟองย่อย) — ถ้าเป็นออเดอร์ใหม่/แก้ไขที่ยังไม่รับ ให้ถือว่า "รับ" ทันทีในแตะเดียว (เสียงหยุด) */
    private void openDetail(String id) {
        Order found = null;
        for (Order o : orders) if (o.id.equals(id)) found = o;
        if (found == null) return;
        selId = id;
        if (unreadIds.contains(id)) {
            if (found.needsAck()) ackEditNow(found);
            if (found.status.equals(Order.ST_NEW)) updateStatus(found, Order.ST_DOING);   // แตะฟอง = รับออเดอร์
            markRead(id);
        }
        if (found.isPre()) { openWorkspace(T_PRE); return; }
        if (found.isClosed()) { listFilter = 1; openWorkspace(T_HIST); }
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
            Toast.makeText(this, "⏳ กำลังบันทึก " + place + "…", Toast.LENGTH_SHORT).show();
            listFilter = 0;
            openWorkspace(T_ORDERS);
            new Thread(() -> {
                String id = "", err = "";
                try { id = Cloud.push(OrderBubbleService.this, o); } catch (Exception e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
                final String fid = id, ferr = err;
                ui.post(() -> {
                    if (!fid.isEmpty()) {
                        seenIds.add(fid); seenEdit.put(fid, 0L); selId = fid;
                        Toast.makeText(this, "⏱ เริ่มจับเวลา " + place, Toast.LENGTH_SHORT).show();
                        fetchOrders();
                        ui.postDelayed(() -> { if (panel != null && tab == T_ORDERS) openWorkspace(T_ORDERS); }, 1200);
                    } else {
                        // บันทึกไม่สำเร็จ → กลับไปหน้าโน้ตพร้อมข้อมูลเดิม ให้กดยืนยันใหม่
                        addPlace = place; addStep = 1;
                        showError("บันทึกออเดอร์ \"" + place + "\" ไม่สำเร็จ — เช็คเน็ตแล้วกดยืนยันอีกครั้ง (" + ferr + ")");
                        openWorkspace(T_ADD);
                    }
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
                harvestPanel = false; harvestPending = false;
                stopAlertIfIdle();
                new Thread(() -> {
                    try { Cloud.ackHarvest(OrderBubbleService.this); }
                    catch (Exception e) { ui.post(() -> { Cloud.harvestAck = 0; harvestPending = true; showError("ส่งรับทราบเก็บกุ้งไม่สำเร็จ (เน็ต?) — จะเด้งเตือนอีกครั้ง"); }); }
                }).start();
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
        TextView chk = lchip("🔄 ตรวจอัปเดต", 0xFFF1F2F5, TXT);
        LinearLayout.LayoutParams ckl = lp(WRAP, WRAP); ckl.leftMargin = dp(this, 14);
        Fx.onTap(chk, this::checkUpdateNow);
        top.addView(chk, ckl);
        topBarEnd(top);
        LinearLayout outerBody = bodyOf(main);
        outerBody.setPadding(0, 0, 0, 0);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = col(this);
        body.setPadding(dp(this, 18), dp(this, 16), dp(this, 18), dp(this, 16));
        sv.addView(body);
        outerBody.addView(sv, lp(MATCH, MATCH));

        boolean upd = Updater.available(this);
        int done = 0; for (Order o : orders) if (o.isClosed()) done++;
        long age = Cloud.lastOk == 0 ? -1 : (System.currentTimeMillis() - Cloud.lastOk) / 1000;

        // ---- พรีออเดอร์: เริ่มเตรียมล่วงหน้ากี่นาที ----
        LinearLayout pm = row(this);
        pm.setBackground(card(14));
        pm.setPadding(dp(this, 16), dp(this, 12), dp(this, 12), dp(this, 12));
        LinearLayout pmc = col(this);
        pmc.addView(text(this, "📅 พรีออเดอร์ — เริ่มเตรียมก่อนนัด", 14.5f, true, TXT));
        pmc.addView(text(this, "ถึงเวลานี้ POS จะย้ายเข้าคิวปกติ + เสียงเตือน + เริ่มจับเวลา", 12, false, MUTED));
        pm.addView(pmc, lpw(1));
        for (int v : new int[]{30, 45, 60, 90}) {
            final int vv = v;
            TextView c = lchip(v + " นาที", prepMinDefault() == v ? PURPLE : 0xFFF1F2F5, prepMinDefault() == v ? WHITE : TXT);
            LinearLayout.LayoutParams cl = lp(WRAP, WRAP); cl.leftMargin = dp(this, 6);
            Fx.onTap(c, () -> { Cloud.prefs(this).edit().putInt("pre_prep_min", vv).apply(); openWorkspace(T_SET); });
            pm.addView(c, cl);
        }
        LinearLayout.LayoutParams pml = lp(MATCH, WRAP); pml.bottomMargin = dp(this, 14);
        body.addView(pm, pml);

        // ---- อัปเดตแอป (บนสุด) ----
        LinearLayout ub = row(this);
        ub.setBackground(upd ? glass(this, ORANGE_SOFT, 14, ORANGE) : card(14));
        ub.setPadding(dp(this, 16), dp(this, 12), dp(this, 12), dp(this, 12));
        LinearLayout uc = col(this);
        uc.addView(text(this, upd ? "🆕 มีเวอร์ชันใหม่ v" + Updater.latestName + " พร้อมติดตั้ง" : "เวอร์ชัน " + ver(this) + (Updater.checkedAt > 0 ? " — ล่าสุดแล้ว" : ""), 14.5f, true, upd ? ORANGE : TXT));
        uc.addView(text(this, upd ? (Updater.latestNotes.isEmpty() ? "ติดตั้งทับได้เลย ข้อมูล/ที่อยู่ในเครื่องไม่หาย ฟองจะกลับมาเอง" : Updater.latestNotes)
                : "แอปตรวจจาก GitHub ทุก 6 ชม. — มีเวอร์ชันใหม่จะแจ้งเตือน + ป้าย 🆕 ที่เมนูตั้งค่า"
                + (Updater.checkedAt > 0 ? " · ตรวจล่าสุด " + clockOf(Updater.checkedAt + Cloud.offset) : ""), 12, false, MUTED));
        ub.addView(uc, lpw(1));
        TextView ubtn = lbtn(upd ? "⬇️ อัปเดตตอนนี้" : "🔄 ตรวจอัปเดต", upd);
        Fx.onTap(ubtn, () -> { if (Updater.available(this)) startUpdate(); else checkUpdateNow(); });
        LinearLayout.LayoutParams ubl = lp(WRAP, WRAP); ubl.leftMargin = dp(this, 10);
        ub.addView(ubtn, ubl);
        LinearLayout.LayoutParams ubb = lp(MATCH, WRAP); ubb.bottomMargin = dp(this, 14);
        body.addView(ub, ubb);

        LinearLayout cardV = col(this);
        cardV.setBackground(card(16));
        cardV.setPadding(dp(this, 22), dp(this, 6), dp(this, 22), dp(this, 6));
        String[][] rows = {
                {"เวอร์ชันแอป", ver(this) + (upd ? "  →  ใหม่ v" + Updater.latestName : "")},
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

    /** ตรวจอัปเดตทันที (กดเอง) */
    private void checkUpdateNow() {
        Toast.makeText(this, "🔄 กำลังตรวจเวอร์ชันล่าสุด…", Toast.LENGTH_SHORT).show();
        Updater.check(this, (ok, msg) -> {
            Toast.makeText(this, (ok ? "🆕 " : "") + msg, Toast.LENGTH_LONG).show();
            if (panel != null) openWorkspace(tab);
        });
    }

    private void clearDone() {
        final List<Order> done = new ArrayList<>();
        for (Order o : orders) if (o.isClosed()) done.add(o);
        if (done.isEmpty()) { Toast.makeText(this, "ไม่มีออเดอร์ที่เสร็จแล้ว", Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(this, "⏳ กำลังเก็บเข้าคลัง " + done.size() + " ออเดอร์…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int okN = 0;
            for (Order o : done) { try { Cloud.archive(OrderBubbleService.this, o); okN++; } catch (Exception ignored) {} }
            final int n = okN;
            ui.post(() -> {
                if (n == done.size()) Toast.makeText(this, "✅ เก็บเข้าคลัง " + n + " ออเดอร์แล้ว (เก็บไว้ 30 วัน)", Toast.LENGTH_SHORT).show();
                else showError("เก็บเข้าคลังได้ " + n + "/" + done.size() + " — เช็คเน็ตแล้วลองอีกครั้ง");
                fetchOrders(); ui.postDelayed(() -> { if (panel != null) openWorkspace(tab); }, 900);
            });
        }).start();
    }

    /** งานดูแลอัตโนมัติ (ทุก 30 นาที): เก็บออเดอร์เสร็จเกิน 12 ชม. เข้า archive + ลบ archive เกิน 30 วัน */
    private final Runnable housekeeping = new Runnable() {
        @Override public void run() {
            if (Cloud.ready(OrderBubbleService.this)) {
                final List<Order> old = new ArrayList<>();
                long cutoff = Cloud.now() - 12L * 3600000L;
                for (Order o : orders) if (o.isClosed() && Math.max(o.createdAt, o.editedAt) < cutoff) old.add(o);
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

    /* ================= พรีออเดอร์ (สั่งล่วงหน้า) ================= */

    private final java.util.Map<String, Long> preSnooze = new java.util.HashMap<>();   // เลื่อนเวลาเตรียม (id → เวลาที่ให้เช็คใหม่)
    private boolean prePopup = false;
    private String prePopupId = null;

    private int prepMinDefault() { return Cloud.prefs(this).getInt("pre_prep_min", 45); }

    private static final String[] TH_DAY = {"อา.", "จ.", "อ.", "พ.", "พฤ.", "ศ.", "ส."};
    private static final String[] TH_MON = {"ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."};

    private String dayLabel(long serverMs) {
        java.util.Calendar c = java.util.Calendar.getInstance(); c.setTimeInMillis(serverMs - Cloud.offset);
        java.util.Calendar t = java.util.Calendar.getInstance();
        int diff = (int) Math.round((startOfDay(c) - startOfDay(t)) / 86400000.0);
        String d = TH_DAY[c.get(java.util.Calendar.DAY_OF_WEEK) - 1] + " " + c.get(java.util.Calendar.DAY_OF_MONTH) + " " + TH_MON[c.get(java.util.Calendar.MONTH)];
        if (diff == 0) return "วันนี้ · " + d;
        if (diff == 1) return "พรุ่งนี้ · " + d;
        if (diff < 0) return "เลยมาแล้ว · " + d;
        return d;
    }
    private static long startOfDay(java.util.Calendar c) {
        java.util.Calendar x = (java.util.Calendar) c.clone();
        x.set(java.util.Calendar.HOUR_OF_DAY, 0); x.set(java.util.Calendar.MINUTE, 0); x.set(java.util.Calendar.SECOND, 0); x.set(java.util.Calendar.MILLISECOND, 0);
        return x.getTimeInMillis();
    }
    private String untilLabel(long serverMs) {
        long d = serverMs - Cloud.now();
        boolean late = d < 0; d = Math.abs(d) / 60000L;
        String t = d >= 60 ? (d / 60) + " ชม. " + (d % 60 > 0 ? (d % 60) + " น." : "") : d + " น.";
        return (late ? "เลยเวลา " : "อีก ") + t.trim();
    }

    private List<Order> preOrders() {
        List<Order> out = new ArrayList<>();
        for (Order o : orders) if (o.isPre()) out.add(o);
        java.util.Collections.sort(out, (a, b) -> Long.compare(a.preAt, b.preAt));
        return out;
    }
    private int preTodayCount() {
        int n = 0;
        for (Order o : preOrders()) if (dayLabel(o.preAt).startsWith("วันนี้") || dayLabel(o.preAt).startsWith("เลย")) n++;
        return n;
    }

    /** ตรวจทุกรอบ: พรีออเดอร์ไหนถึงเวลาเตรียม (นัด − prepMin) → ย้ายเข้าคิวปกติ + เสียง + ป๊อปอัป */
    private void checkPreActivation() {
        if (!Cloud.ready(this)) return;
        long now = Cloud.now();
        for (Order o : preOrders()) {
            long startAt = o.preAt - (o.prepMin > 0 ? o.prepMin : prepMinDefault()) * 60000L;
            Long sn = preSnooze.get(o.id);
            if (sn != null && now < sn) continue;
            if (now < startAt) continue;
            activatePre(o, false);
        }
    }

    /** เริ่มเตรียมพรีออเดอร์ (อัตโนมัติ หรือกด "เริ่มทำตอนนี้") */
    private void activatePre(final Order o, boolean manual) {
        preSnooze.remove(o.id);
        final String st = manual ? Order.ST_DOING : Order.ST_NEW;
        o.status = st;
        o.createdAt = Cloud.now();          // นับเวลาตั้งแต่เริ่มเตรียม
        seenIds.add(o.id);
        seenEdit.put(o.id, o.editedAt);
        if (!manual) unreadIds.add(o.id);
        pendingStatus.put(o.id, st);
        new Thread(() -> {
            try { Cloud.activatePre(OrderBubbleService.this, o.id, st); ui.post(() -> pendingStatus.remove(o.id)); }
            catch (Exception e) { ui.post(() -> { pendingStatus.remove(o.id); showError("ย้ายพรีออเดอร์เข้าคิวไม่สำเร็จ — เช็คเน็ต (จะลองใหม่อัตโนมัติ)"); o.status = Order.ST_PRE; unreadIds.remove(o.id); rebuildChildren(); }); }
        }).start();
        rebuildChildren();
        if (mainDisc != null) Fx.bounce(mainDisc);
        if (!manual) { Alert.start(this, Alert.PRE); openPrePopup(o); }
        else { Toast.makeText(this, "เริ่มทำ " + o.place + " แล้ว", Toast.LENGTH_SHORT).show(); selId = o.id; listFilter = 0; openWorkspace(T_ORDERS); }
    }

    /** ป๊อปอัปม่วง "ถึงเวลาเตรียมพรีออเดอร์" */
    private void openPrePopup(final Order o) {
        if (panel != null && tab == T_ADD) {   // กำลังคีย์อยู่ → ไม่ปิดหน้า แค่เตือน
            Toast.makeText(this, "📅 ถึงเวลาเตรียมพรีออเดอร์ " + o.place + " นัด " + o.preClock() + " — แตะฟองเพื่อรับ", Toast.LENGTH_LONG).show();
            return;
        }
        closePanel();
        prePopup = true; prePopupId = o.id;
        panel = col(this);
        panel.setBackground(glass(this, 0xFF7C3AED, 24, WHITE));
        panel.setPadding(dp(this, 26), dp(this, 22), dp(this, 26), dp(this, 22));
        panel.setElevation(dp(this, 20));

        TextView big = text(this, "📅 ถึงเวลาเตรียมพรีออเดอร์!", 28, true, WHITE);
        big.setGravity(Gravity.CENTER);
        panel.addView(big, lp(MATCH, WRAP));
        TextView who = text(this, (o.no.isEmpty() ? "" : "#" + o.no + " ") + o.place + " · นัด" + ("ส่ง".equals(o.preType) ? "ส่ง " : "รับ ") + o.preClock(), 20, true, WHITE);
        who.setGravity(Gravity.CENTER); who.setPadding(0, dp(this, 8), 0, 0);
        panel.addView(who, lp(MATCH, WRAP));
        StringBuilder sb = new StringBuilder();
        for (String l : o.lines) { String t = l.trim(); if (t.isEmpty() || t.startsWith("ช่องทาง") || t.startsWith("สถานะ") || t.startsWith("นัด") || t.startsWith("มัดจำ")) continue; if (sb.length() > 0) sb.append(" · "); sb.append(t); }
        if (o.total > 0) sb.append(" · ").append(o.total).append(" บาท");
        if (o.paidFull) sb.append(" · ชำระครบ"); else if (o.deposit > 0) sb.append(" · คงเหลือ ").append(Math.max(0, o.total - o.deposit));
        TextView sub = text(this, sb.toString(), 13.5f, false, 0xFFEDE7FF);
        sub.setGravity(Gravity.CENTER); sub.setPadding(0, dp(this, 4), 0, 0); sub.setMaxLines(3);
        panel.addView(sub, lp(MATCH, WRAP));

        LinearLayout btns = row(this);
        TextView snooze = button(this, "⏰ เลื่อนอีก 15 นาที", glass(this, 0x33FFFFFF, 14, WHITE), 15);
        snooze.setTextColor(WHITE);
        Fx.onTap(snooze, () -> {
            // ยังไม่เริ่ม: คืนเป็นพรี แล้วเช็คใหม่ใน 15 นาที
            o.status = Order.ST_PRE; unreadIds.remove(o.id); pendingStatus.remove(o.id);
            preSnooze.put(o.id, Cloud.now() + 15 * 60000L);
            new Thread(() -> { try { Cloud.setStatus(OrderBubbleService.this, o.id, Order.ST_PRE); } catch (Exception ignored) {} }).start();
            prePopup = false; stopAlertIfIdle(); closePanel(); rebuildChildren();
            Toast.makeText(this, "เลื่อนไป 15 นาที", Toast.LENGTH_SHORT).show();
        });
        btns.addView(snooze, lpw(1));
        TextView go = button(this, "🔥 รับ — เริ่มทำเลย (หยุดเสียง)", glass(this, WHITE, 14, 0), 17);
        go.setTextColor(0xFF7C3AED);
        LinearLayout.LayoutParams gl = lpw(2); gl.leftMargin = dp(this, 10);
        Fx.onTap(go, () -> { prePopup = false; closePanel(); updateStatus(o, Order.ST_DOING); selId = o.id; listFilter = 0; openWorkspace(T_ORDERS); });
        btns.addView(go, gl);
        LinearLayout.LayoutParams bl = lp(MATCH, WRAP); bl.topMargin = dp(this, 18);
        panel.addView(btns, bl);
        showPanel();
    }

    /* ---------- แท็บ พรีออเดอร์ ---------- */

    private void buildPreTab(LinearLayout main) {
        List<Order> pre = preOrders();
        int today = preTodayCount();
        LinearLayout top = topBar(main, "พรีออเดอร์");
        TextView p1 = lchip("วันนี้ " + today, PURPLE, WHITE);
        LinearLayout.LayoutParams l1 = lp(WRAP, WRAP); l1.leftMargin = dp(this, 14);
        top.addView(p1, l1);
        TextView p2 = lchip("ทั้งหมด " + pre.size(), 0xFFF1F2F5, TXT);
        LinearLayout.LayoutParams l2 = lp(WRAP, WRAP); l2.leftMargin = dp(this, 8);
        top.addView(p2, l2);
        TextView p3 = lchip("เตรียมล่วงหน้า " + prepMinDefault() + " นาที", 0xFFF1F2F5, 0xFF6B7280);
        LinearLayout.LayoutParams l3 = lp(WRAP, WRAP); l3.leftMargin = dp(this, 8);
        Fx.onTap(p3, () -> openWorkspace(T_SET));
        top.addView(p3, l3);
        topBarEnd(top);

        LinearLayout body = bodyOf(main);
        final int innerH = bodyH() - dp(this, 32);
        LinearLayout cols = row(this);
        cols.setGravity(Gravity.TOP);
        body.addView(cols, new LinearLayout.LayoutParams(MATCH, innerH));

        LinearLayout left = col(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int leftW = Math.min(dp(this, 360), (int) (dm.widthPixels * 0.36f));
        cols.addView(left, new LinearLayout.LayoutParams(leftW, innerH));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = col(this);
        sv.addView(list);
        left.addView(sv, new LinearLayout.LayoutParams(MATCH, innerH));

        Order sel = null;
        for (Order o : pre) if (o.id.equals(selId)) sel = o;
        if (sel == null && !pre.isEmpty()) { sel = pre.get(0); selId = sel.id; }

        if (pre.isEmpty()) {
            TextView em = text(this, "ยังไม่มีพรีออเดอร์ — มือถือเปิด \"สั่งล่วงหน้า\" ตอนส่งออเดอร์ จะมาโผล่ที่นี่", 13.5f, false, MUTED);
            em.setPadding(dp(this, 4), dp(this, 10), 0, 0);
            list.addView(em);
        }
        String lastDay = "";
        for (Order o : pre) {
            String dl = dayLabel(o.preAt);
            if (!dl.equals(lastDay)) {
                lastDay = dl;
                TextView h = text(this, "📍 " + dl, 12.5f, true, 0xFF5B6270);
                h.setPadding(dp(this, 2), dp(this, 6), 0, dp(this, 6));
                list.addView(h);
            }
            final boolean isSel = sel != null && o.id.equals(sel.id);
            long startAt = o.preAt - (o.prepMin > 0 ? o.prepMin : prepMinDefault()) * 60000L;
            boolean soon = Cloud.now() >= startAt - 30 * 60000L;
            boolean noDep = o.deposit <= 0 && !o.paidFull;
            LinearLayout rowv = row(this);
            rowv.setBackground(glass(this, soon ? 0xFFFFF8F4 : CARD, 14, isSel ? PURPLE : soon ? ORANGE : LINE_C));
            rowv.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            LinearLayout ck = col(this); ck.setGravity(Gravity.CENTER);
            TextView c1 = text(this, o.preClock(), 19, true, soon ? ORANGE : PURPLE); c1.setGravity(Gravity.CENTER); ck.addView(c1);
            TextView c2 = text(this, untilLabel(o.preAt), 10, false, MUTED); c2.setGravity(Gravity.CENTER); c2.setSingleLine(true); ck.addView(c2);
            LinearLayout.LayoutParams ckl = lp(dp(this, 72), WRAP); ckl.rightMargin = dp(this, 10);
            rowv.addView(ck, ckl);
            LinearLayout info = col(this);
            TextView nm = text(this, o.place.isEmpty() ? "ออเดอร์" : o.place, 15.5f, true, TXT);
            nm.setSingleLine(true); nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(nm);
            String dep = o.paidFull ? "ชำระครบ" : o.deposit > 0 ? "มัดจำ " + o.deposit : "ยังไม่มัดจำ";
            TextView mt = text(this, (o.no.isEmpty() ? "" : "#" + o.no + " · ") + (o.total > 0 ? o.total + " บาท · " : "") + dep + " · " + ("ส่ง".equals(o.preType) ? "ส่ง" : "รับเอง"), 11.5f, false, MUTED);
            mt.setSingleLine(true); mt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mt.setPadding(0, dp(this, 2), 0, 0);
            info.addView(mt);
            rowv.addView(info, lpw(1));
            TextView tg = lchip(soon ? "ใกล้ถึงเวลา" : noDep ? "ยังไม่มัดจำ" : "รอ", soon ? ORANGE_SOFT : noDep ? YEL_SOFT : PURPLE_SOFT, soon ? ORANGE : noDep ? 0xFF92400E : PURPLE);
            LinearLayout.LayoutParams tgl = lp(WRAP, WRAP); tgl.leftMargin = dp(this, 6);
            rowv.addView(tg, tgl);
            final String oid = o.id;
            Fx.onTap(rowv, () -> { selId = oid; openWorkspace(T_PRE); });
            LinearLayout.LayoutParams rl = lp(MATCH, WRAP); rl.bottomMargin = dp(this, 8);
            list.addView(rowv, rl);
        }

        LinearLayout right = col(this);
        right.setBackground(card(16));
        right.setPadding(dp(this, 20), dp(this, 16), dp(this, 20), dp(this, 16));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, innerH, 1); rl.leftMargin = dp(this, 16);
        cols.addView(right, rl);
        if (sel == null) {
            TextView em = text(this, "เลือกพรีออเดอร์จากรายการด้านซ้าย", 15, false, MUTED);
            em.setGravity(Gravity.CENTER); right.setGravity(Gravity.CENTER); right.addView(em);
        } else buildPreDetail(right, sel, innerH);
    }

    /** รายละเอียดพรีออเดอร์ (หัวม่วง + นับถอยหลัง + แผนเตรียม) */
    private void buildPreDetail(LinearLayout right, final Order o, int paneH) {
        right.setPadding(0, 0, 0, 0);
        right.setClipToOutline(true);
        LinearLayout h = row(this);
        h.setBackground(glass(this, PURPLE, 0, 0));
        h.setPadding(dp(this, 18), dp(this, 12), dp(this, 18), dp(this, 12));
        LinearLayout hc = col(this);
        TextView big = text(this, "📍 " + (o.place.isEmpty() ? "ออเดอร์" : o.place), 22, true, WHITE);
        big.setSingleLine(true); big.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hc.addView(big);
        TextView mt = text(this, (o.no.isEmpty() ? "" : "#" + o.no + " · ") + "สั่งเมื่อ " + clockOf(o.createdAt) + " · " + ("ส่ง".equals(o.preType) ? "🛵 ส่ง" : "🏠 รับเอง"), 13, false, 0xBFFFFFFF);
        mt.setPadding(0, dp(this, 3), 0, 0);
        hc.addView(mt);
        h.addView(hc, lpw(1));
        LinearLayout cd = col(this); cd.setGravity(Gravity.CENTER);
        cd.setBackground(glass(this, 0x00000000, 12, WHITE));
        cd.setPadding(dp(this, 12), dp(this, 4), dp(this, 12), dp(this, 4));
        TextView c1 = text(this, o.preClock(), 22, true, WHITE); c1.setGravity(Gravity.CENTER); cd.addView(c1);
        TextView c2 = text(this, dayLabel(o.preAt) + " · " + untilLabel(o.preAt), 10, false, WHITE); c2.setGravity(Gravity.CENTER); cd.addView(c2);
        h.addView(cd, lp(WRAP, WRAP));
        right.addView(h, lp(MATCH, dp(this, 76)));

        int prep = o.prepMin > 0 ? o.prepMin : prepMinDefault();
        TextView plan = text(this, "⏱ แผนเตรียม: POS จะย้ายเข้าคิวปกติและเตือนเสียงตอน " + clockOf(o.preAt - prep * 60000L) + " (ก่อนนัด " + prep + " นาที) · กด \"เริ่มทำตอนนี้\" ได้ถ้าอยากทำก่อน", 12.5f, false, 0xFF92400E);
        plan.setBackground(glass(this, YEL_SOFT, 12, YEL_LINE));
        plan.setPadding(dp(this, 14), dp(this, 8), dp(this, 14), dp(this, 8));
        LinearLayout.LayoutParams pl = lp(MATCH, WRAP); pl.leftMargin = dp(this, 18); pl.rightMargin = dp(this, 18); pl.topMargin = dp(this, 8);
        right.addView(plan, pl);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = col(this);
        list.setPadding(dp(this, 18), dp(this, 4), dp(this, 18), dp(this, 4));
        sv.addView(list);
        int listH = Math.max(dp(this, 70), paneH - dp(this, 76 + 60 + 66 + 58 + 10));
        right.addView(sv, new LinearLayout.LayoutParams(MATCH, listH));
        java.util.regex.Pattern linePat = java.util.regex.Pattern.compile("^(.*?)(?:\\s+[xX×]\\s*(\\d+))?\\s+(\\d[\\d,]*)\\s*บาท(?:\\s*\\((.*)\\))?\\s*$");
        String noteTxt = null; int count = 0;
        for (String line : o.lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("ช่องทางชำระ") || t.startsWith("สถานะ") || t.startsWith("ยอดรวม") || t.startsWith("นัด") || t.startsWith("มัดจำ")) continue;
            if (t.startsWith("หมายเหตุ")) { int i = t.indexOf(":"); noteTxt = i > 0 ? t.substring(i + 1).trim() : t; continue; }
            boolean ship = t.startsWith("ค่าส่ง") || t.startsWith("ส่งฟรี");
            String name = t, qty = "1", price = "", note = "";
            java.util.regex.Matcher m = linePat.matcher(t);
            if (m.find()) { name = m.group(1); if (m.group(2) != null) qty = m.group(2); price = m.group(3); if (m.group(4) != null) note = m.group(4); }
            else if (ship) price = "0";
            if (!ship) { try { count += Integer.parseInt(qty); } catch (Exception ignored) {} }
            list.addView(ticketRow(name, qty, price, note, ship, 0, null, t, false), lp(MATCH, WRAP));
            list.addView(dashed(), lp(MATCH, dp(this, 2)));
        }
        if (noteTxt != null && !noteTxt.isEmpty()) {
            TextView nb = text(this, "📝 หมายเหตุ: " + noteTxt, 16, true, 0xFF92400E);
            nb.setBackground(glass(this, YEL_SOFT, 12, YEL_LINE));
            nb.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
            LinearLayout.LayoutParams nl = lp(MATCH, WRAP); nl.topMargin = dp(this, 10);
            list.addView(nb, nl);
        }

        LinearLayout ft = row(this);
        ft.setPadding(dp(this, 18), dp(this, 6), dp(this, 18), dp(this, 6));
        LinearLayout b1 = row(this);
        b1.setBackground(fill(0xFFF1F2F5, 12));
        b1.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        b1.addView(text(this, "รวม " + count + " รายการ", 14, true, 0xFF5B6270), lpw(1));
        b1.addView(text(this, o.total > 0 ? o.total + " บาท" : "—", 24, true, TXT));
        ft.addView(b1, lpw(1));
        boolean paid = o.paidFull;
        LinearLayout b2 = row(this);
        b2.setBackground(glass(this, paid ? GREEN_SOFT : o.deposit > 0 ? YEL_SOFT : RED_SOFT, 12, paid ? GREEN_C : o.deposit > 0 ? YEL_LINE : RED_C));
        b2.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        b2.addView(text(this, paid ? "ชำระครบแล้ว" : o.deposit > 0 ? "มัดจำ " + o.deposit + " · คงเหลือ" : "ยังไม่มัดจำ", 14, true, paid ? GREEN_C : o.deposit > 0 ? 0xFF92400E : RED_C), lpw(1));
        b2.addView(text(this, paid ? "✅" : o.deposit > 0 ? Math.max(0, o.total - o.deposit) + " บาท" : "⚠️", 20, true, paid ? GREEN_C : o.deposit > 0 ? 0xFF92400E : RED_C));
        LinearLayout.LayoutParams b2l = lpw(1.2f); b2l.leftMargin = dp(this, 10);
        ft.addView(b2, b2l);
        right.addView(ft, lp(MATCH, dp(this, 66)));

        LinearLayout r2 = row(this);
        r2.setPadding(dp(this, 18), dp(this, 6), dp(this, 18), dp(this, 6));
        TextView cp = lbtn("📋 คัดลอกที่อยู่", false);
        cp.setTextSize(13.5f); cp.setPadding(dp(this, 10), dp(this, 9), dp(this, 10), dp(this, 9));
        final String plc = o.place;
        Fx.onCopyTap(cp, () -> { copy(plc, "คัดลอกที่อยู่แล้ว — วางในแอปส่งได้เลย"); ui.postDelayed(this::closePanel, 150); });
        r2.addView(cp, lpw(1));
        TextView dl = lbtn("⏰ เลื่อน +30 นาที", false);
        dl.setTextSize(13.5f); dl.setPadding(dp(this, 10), dp(this, 9), dp(this, 10), dp(this, 9));
        LinearLayout.LayoutParams dll = lpw(1); dll.leftMargin = dp(this, 8);
        Fx.onTap(dl, () -> {
            final long newAt = o.preAt + 30 * 60000L;
            o.preAt = newAt;
            new Thread(() -> { try { Cloud.setPreAt(OrderBubbleService.this, o.id, newAt); } catch (Exception e) { ui.post(() -> showError("เลื่อนเวลาไม่สำเร็จ — เช็คเน็ต")); } }).start();
            Toast.makeText(this, "เลื่อนนัดเป็น " + o.preClock(), Toast.LENGTH_SHORT).show();
            openWorkspace(T_PRE);
        });
        r2.addView(dl, dll);
        TextView go = lbtnColor("▶ เริ่มทำตอนนี้ (เข้าคิวปกติ)", PURPLE, WHITE, 0);
        go.setTextSize(15); go.setPadding(dp(this, 12), dp(this, 11), dp(this, 12), dp(this, 11));
        LinearLayout.LayoutParams gl = lpw(2); gl.leftMargin = dp(this, 8);
        Fx.onTap(go, () -> { closePanel(); activatePre(o, true); });
        r2.addView(go, gl);
        right.addView(r2, lp(MATCH, dp(this, 58)));
    }

    /* ================= แจ้งเตือนเก็บกุ้งจากบ่อ ================= */

    private void checkHarvest() {
        long at = Cloud.harvestAt, ack = Cloud.harvestAck;
        if (at <= 0) return;
        if (ack >= at) {                       // รับทราบแล้ว
            if (harvestPending) { harvestPending = false; stopAlertIfIdle(); }
            if (harvestPanel) { closePanel(); harvestPanel = false; }
            return;
        }
        harvestPending = true;
        if (at > harvestShownAt) {              // สัญญาณใหม่ → เสียง + popup
            harvestShownAt = at;
            Alert.start(this, Alert.HARVEST);
            if (mainDisc != null) Fx.bounce(mainDisc);
            if (panel != null && tab == T_ADD) {   // กำลังคีย์ออเดอร์อยู่ → ไม่ปิดหน้า แค่เตือน
                Toast.makeText(this, "🦐 มีสัญญาณเก็บกุ้งจากบ่อ — คีย์เสร็จแล้วไปกดรับทราบที่เมนู เก็บกุ้ง", Toast.LENGTH_LONG).show();
            } else openHarvest();
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
            harvestPanel = false; harvestPending = false;
            stopAlertIfIdle();
            new Thread(() -> {
                try { Cloud.ackHarvest(OrderBubbleService.this); }
                catch (Exception e) { ui.post(() -> { Cloud.harvestAck = 0; harvestPending = true; showError("ส่งรับทราบเก็บกุ้งไม่สำเร็จ (เน็ต?) — จะเด้งเตือนอีกครั้ง"); }); }
            }).start();
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
        prePopup = false;
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
        ui.removeCallbacks(updateCheck);
    }
}
