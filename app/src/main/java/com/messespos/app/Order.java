package com.messespos.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** ออเดอร์ 1 ใบที่ส่งมาจากมือถือ */
public class Order {

    public static final String ST_NEW  = "ใหม่";
    public static final String ST_DOING = "กำลังทำ";
    public static final String ST_DONE  = "เสร็จ";
    public static final String ST_PRE   = "พรี";       // สั่งล่วงหน้า ยังไม่ถึงเวลาเตรียม
    public static final String ST_CANCEL = "ยกเลิก";

    public String id = "";
    public String no = "";          // เลขออเดอร์ เช่น "3"
    public String place = "";       // จุดส่ง
    public String text = "";        // ข้อความเต็มจากมือถือ
    public List<String> lines = new ArrayList<>();  // รายการทีละบรรทัด
    public int total = 0;
    public String pay = "";         // ช่องทาง/สถานะชำระ
    public String status = ST_NEW;
    public String by = "";          // "mobile" / "pos"
    public long createdAt = System.currentTimeMillis();
    public long editedAt = 0, ackEditAt = 0;   // แก้ไขจากมือถือ / POS กดรับทราบแล้ว
    public List<String> prevLines = new ArrayList<>();   // รายการก่อนแก้ (ไว้ไฮไลต์ส่วนที่เปลี่ยน)
    public long preAt = 0;          // เวลานัด (พรีออเดอร์) 0 = ไม่ใช่
    public String preType = "";     // รับเอง / ส่ง
    public int deposit = 0;
    public boolean paidFull = false;
    public int prepMin = 45;        // เริ่มเตรียมก่อนนัดกี่นาที

    public boolean isPre() { return status.equals(ST_PRE); }
    public boolean isClosed() { return status.equals(ST_DONE) || status.equals(ST_CANCEL); }
    /** เลยเวลานัดแล้วยังไม่เสร็จ */
    public boolean preLate() { return preAt > 0 && !isClosed() && Cloud.now() > preAt; }
    public String preClock() {
        if (preAt <= 0) return "";
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(preAt - Cloud.offset));
    }

    /** ถูกแก้ไขจากมือถือ และยังไม่ได้กดรับทราบ */
    public boolean needsAck() { return editedAt > 0 && ackEditAt < editedAt; }

    /** นาทีที่รอแล้ว */
    public int waitedMin() {
        long m = (Cloud.now() - createdAt) / 60000L;
        return (int) Math.max(0, m);
    }

    /** สีตามเวลา: <30 เขียว, 30-39 เหลือง, 40-49 ส้ม, 50-59 แดง, 60+ แดงเข้ม */
    public int tierColor() {
        int m = waitedMin();
        if (isClosed()) return 0xFF6B7280;
        if (isPre()) return 0xFF7C3AED;
        if (preLate()) return 0xFFEF4444;
        if (m >= 60) return 0xFFB91C1C;
        if (m >= 50) return 0xFFEF4444;
        if (m >= 40) return 0xFFF97316;
        if (m >= 30) return 0xFFFACC15;
        return 0xFF22C55E;
    }

    /** ข้อความเวลาแบบสั้นสำหรับฟองลอย เช่น "12 นาที" / "1 ชม 5" */
    public String clock() {
        int m = waitedMin();
        if (m < 60) return m + " นาที";
        return (m / 60) + " ชม " + (m % 60);
    }

    /** ป้ายชื่อสั้นบนฟอง: #เลขออเดอร์ ถ้าไม่มีใช้จุดส่ง (ตัดสั้น) */
    public String tag() {
        if (!no.isEmpty()) return "#" + no;
        String p = place.trim();
        if (p.isEmpty()) return "ออเดอร์";
        return p.length() > 6 ? p.substring(0, 6) : p;
    }

    public static Order fromJson(String id, JSONObject o) {
        Order r = new Order();
        r.id = id;
        r.no = o.optString("no", "");
        r.place = o.optString("place", "");
        r.text = o.optString("text", "");
        r.total = o.optInt("total", 0);
        r.pay = o.optString("pay", "");
        r.status = o.optString("status", ST_NEW);
        r.by = o.optString("by", "");
        r.createdAt = o.optLong("createdAt", Cloud.now());
        r.editedAt = o.optLong("editedAt", 0);
        r.ackEditAt = o.optLong("ackEditAt", 0);
        r.preAt = o.optLong("preAt", 0);
        r.preType = o.optString("preType", "");
        r.deposit = o.optInt("deposit", 0);
        r.paidFull = o.optBoolean("paidFull", false);
        r.prepMin = o.optInt("prepMin", 45);
        JSONArray pv = o.optJSONArray("prevLines");
        if (pv != null) for (int i = 0; i < pv.length(); i++) r.prevLines.add(pv.optString(i));
        JSONArray a = o.optJSONArray("lines");
        if (a != null) for (int i = 0; i < a.length(); i++) r.lines.add(a.optString(i));
        if (r.lines.isEmpty() && !r.text.isEmpty())
            for (String s : r.text.split("\n")) if (!s.trim().isEmpty()) r.lines.add(s.trim());
        return r;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("no", no);
            o.put("place", place);
            o.put("text", text);
            o.put("total", total);
            o.put("pay", pay);
            o.put("status", status);
            if (!by.isEmpty()) o.put("by", by);
            o.put("createdAt", createdAt);
            if (preAt > 0) { o.put("preAt", preAt); o.put("preType", preType); o.put("deposit", deposit); o.put("paidFull", paidFull); o.put("prepMin", prepMin); }
            JSONArray a = new JSONArray();
            for (String s : lines) a.put(s);
            o.put("lines", a);
        } catch (Exception ignored) {}
        return o;
    }
}
