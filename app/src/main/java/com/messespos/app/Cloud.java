package com.messespos.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * คุยกับ Firebase Realtime Database ตรงๆ ผ่าน REST (ไม่ต้องใช้ SDK)
 * เส้นทางข้อมูล:  <dbUrl>/shops/<shopCode>/orders.json
 */
public class Cloud {

    private static final String PREF = "pos";
    private static final String K_URL = "db_url";
    private static final String K_SHOP = "shop_code";
    private static final String K_LOCAL = "local_orders";

    /** ส่วนต่างเวลา เครื่องนี้ vs เซิร์ฟเวอร์ (มิลลิวินาที) — กันนาฬิกาเครื่อง POS เพี้ยน */
    public static long offset = 0;
    private static boolean offsetKnown = false;

    /** เวลาปัจจุบันตามเซิร์ฟเวอร์ */
    public static long now() { return System.currentTimeMillis() + offset; }
    public static boolean synced() { return offsetKnown; }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** ลิงก์ฐานข้อมูลของร้าน (ฝังมากับแอป — ไม่ต้องตั้งค่า) */
    public static final String DEFAULT_URL = "https://gungpao-bangfan-default-rtdb.asia-southeast1.firebasedatabase.app";

    public static String dbUrl(Context c) {
        String u = prefs(c).getString(K_URL, "").trim();
        return u.isEmpty() ? DEFAULT_URL : u;
    }
    public static String shop(Context c)  { return prefs(c).getString(K_SHOP, "bangfan").trim(); }

    public static void save(Context c, String url, String shopCode) {
        prefs(c).edit()
                .putString(K_URL, url == null ? "" : url.trim())
                .putString(K_SHOP, shopCode == null || shopCode.trim().isEmpty() ? "bangfan" : shopCode.trim())
                .apply();
    }

    public static boolean ready(Context c) { return !dbUrl(c).isEmpty(); }

    private static String base(Context c) {
        String u = dbUrl(c);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u + "/shops/" + shop(c) + "/orders";
    }

    /* ---------------- ดึงออเดอร์ทั้งหมด ---------------- */

    public static List<Order> fetch(Context c) throws Exception {
        List<Order> out = new ArrayList<>();
        String body = http(base(c) + ".json", "GET", null);
        if (body == null || body.equals("null") || body.trim().isEmpty()) return out;
        JSONObject root = new JSONObject(body);
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            JSONObject o = root.optJSONObject(k);
            if (o != null) out.add(Order.fromJson(k, o));
        }
        Collections.sort(out, (a, b) -> Long.compare(a.createdAt, b.createdAt));
        return out;
    }

    /** ส่งออเดอร์ขึ้นคลาวด์ (ใช้ตอนทดสอบ / สร้างเอง) */
    public static String push(Context c, Order o) throws Exception {
        JSONObject j = o.toJson();
        j.put("createdAt", new JSONObject().put(".sv", "timestamp"));   // ให้เซิร์ฟเวอร์ประทับเวลา
        String res = http(base(c) + ".json", "POST", j.toString());
        try { return new JSONObject(res).optString("name", ""); } catch (Exception e) { return ""; }
    }

    /** อัปเดตสถานะออเดอร์ */
    public static void setStatus(Context c, String id, String status) throws Exception {
        JSONObject o = new JSONObject();
        o.put("status", status);
        http(base(c) + "/" + id + ".json", "PATCH", o.toString());
    }

    /** ลบออเดอร์ */
    public static void remove(Context c, String id) throws Exception {
        http(base(c) + "/" + id + ".json", "DELETE", null);
    }

    /* ---------------- HTTP ---------------- */

    private static String http(String urlStr, String method, String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
        con.setConnectTimeout(9000);
        con.setReadTimeout(12000);
        if (method.equals("PATCH")) {
            // HttpURLConnection ไม่รองรับ PATCH ตรงๆ — ใช้ override header
            con.setRequestMethod("POST");
            con.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        } else {
            con.setRequestMethod(method);
        }
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        if (body != null) {
            con.setDoOutput(true);
            OutputStream os = con.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }

        int code = con.getResponseCode();
        try {
            long srv = con.getHeaderFieldDate("Date", 0);
            if (srv > 0) { offset = srv - System.currentTimeMillis(); offsetKnown = true; }
        } catch (Exception ignored) {}
        InputStream in = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String out = "";
        if (in != null) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            out = new String(bos.toByteArray(), "UTF-8");
        }
        con.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " " + out);
        return out;
    }

    /* ---------------- โหมดออฟไลน์ (เก็บในเครื่อง) ---------------- */

    public static String localJson(Context c) { return prefs(c).getString(K_LOCAL, "{}"); }
    public static void saveLocalJson(Context c, String json) {
        prefs(c).edit().putString(K_LOCAL, json).apply();
    }
}
