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

    public String id = "";
    public String no = "";          // เลขออเดอร์ เช่น "3"
    public String place = "";       // จุดส่ง
    public String text = "";        // ข้อความเต็มจากมือถือ
    public List<String> lines = new ArrayList<>();  // รายการทีละบรรทัด
    public int total = 0;
    public String pay = "";         // ช่องทาง/สถานะชำระ
    public String status = ST_NEW;
    public long createdAt = System.currentTimeMillis();

    /** นาทีที่รอแล้ว */
    public int waitedMin() {
        return (int) ((System.currentTimeMillis() - createdAt) / 60000L);
    }

    /** สีตามเวลา: <30 เขียว, 30-39 เหลือง, 40-49 ส้ม, 50-59 แดง, 60+ แดงเข้ม */
    public int tierColor() {
        int m = waitedMin();
        if (status.equals(ST_DONE)) return 0xFF6B7280;
        if (m >= 60) return 0xFFB91C1C;
        if (m >= 50) return 0xFFEF4444;
        if (m >= 40) return 0xFFF97316;
        if (m >= 30) return 0xFFFACC15;
        return 0xFF22C55E;
    }

    public String clock() {
        int m = waitedMin();
        if (m < 60) return m + "′";
        return (m / 60) + "ชม" + (m % 60);
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
        r.createdAt = o.optLong("createdAt", System.currentTimeMillis());
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
            o.put("createdAt", createdAt);
            JSONArray a = new JSONArray();
            for (String s : lines) a.put(s);
            o.put("lines", a);
        } catch (Exception ignored) {}
        return o;
    }
}
