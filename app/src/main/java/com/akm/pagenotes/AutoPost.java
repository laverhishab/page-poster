package com.akm.pagenotes;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** অ্যাপ বন্ধ থাকলেও চলে: AI দিয়ে পোস্ট লিখে Facebook-এ আগামী ৩ দিনের জন্য শিডিউল করে রাখে। */
public class AutoPost {

    static final String PREF = "auto";
    private static final String GRAPH = "https://graph.facebook.com/v21.0/";

    static synchronized String run(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, 0);
        if (!sp.getBoolean("on", false)) {
            return "বন্ধ";
        }
        String status;
        try {
            JSONObject cfg = new JSONObject(sp.getString("cfg", "{}"));
            String[] hm = cfg.optString("time", "09:00").split(":");
            int h = Integer.parseInt(hm[0]);
            int m = Integer.parseInt(hm[1]);
            JSONArray done = new JSONArray(sp.getString("done", "[]"));
            JSONArray recent = new JSONArray(sp.getString("recent", "[]"));
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            long now = System.currentTimeMillis();
            int made = 0;
            status = "কিছু করার নেই (আগামী ৩ দিনের পোস্ট শিডিউল করা আছে)";
            for (int d = 0; d < 3; d++) {
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, d);
                cal.set(Calendar.HOUR_OF_DAY, h);
                cal.set(Calendar.MINUTE, m);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                long t = cal.getTimeInMillis();
                if (t < now + 15 * 60000L) {
                    continue;
                }
                String key = df.format(cal.getTime());
                if (has(done, key)) {
                    continue;
                }
                String text = generate(cfg, recent);
                schedule(cfg, text, t / 1000);
                done = tail(done.put(key), 10);
                recent = tail(recent.put(text), 5);
                sp.edit().putString("done", done.toString()).putString("recent", recent.toString()).apply();
                made++;
                status = made + "টি পোস্ট শিডিউল হয়েছে, শেষটি " + key + " " + cfg.optString("time");
            }
        } catch (Exception e) {
            status = "এরর: " + e.getMessage();
        }
        status = new SimpleDateFormat("dd MMM HH:mm", Locale.US).format(new Date()) + " — " + status;
        sp.edit().putString("status", status).apply();
        return status;
    }

    private static boolean has(JSONArray a, String k) {
        for (int i = 0; i < a.length(); i++) {
            if (k.equals(a.optString(i))) {
                return true;
            }
        }
        return false;
    }

    private static JSONArray tail(JSONArray a, int n) throws Exception {
        JSONArray r = new JSONArray();
        for (int i = Math.max(0, a.length() - n); i < a.length(); i++) {
            r.put(a.get(i));
        }
        return r;
    }

    private static String generate(JSONObject cfg, JSONArray recent) throws Exception {
        StringBuilder prev = new StringBuilder();
        for (int i = 0; i < recent.length(); i++) {
            prev.append("- ").append(recent.optString(i)).append("\n");
        }
        String prompt = "ফেসবুক পেজের জন্য ১টি নতুন বাংলা পোস্ট লেখো। বিষয়/নির্দেশনা: " + cfg.optString("topic")
                + "। পোস্ট ছোট ও আকর্ষণীয় হবে, দরকারে ইমোজি ও হ্যাশট্যাগ থাকবে।"
                + (prev.length() > 0 ? " আগের পোস্টগুলো পুনরাবৃত্তি করবে না:\n" + prev : "")
                + " শুধু পোস্টের লেখাটুকু দাও, অন্য কিছু নয়।";
        String key = cfg.optString("key");
        String model = cfg.optString("model");
        String res;
        JSONObject j;
        if ("claude".equals(cfg.optString("prov"))) {
            JSONObject body = new JSONObject()
                    .put("model", model.isEmpty() ? "claude-sonnet-5-5" : model)
                    .put("max_tokens", 1000)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)));
            res = http("https://api.anthropic.com/v1/messages", body.toString(), "application/json",
                    "x-api-key", key, "anthropic-version", "2023-06-01");
            j = new JSONObject(res);
            if (j.has("error")) {
                throw new Exception(j.getJSONObject("error").optString("message"));
            }
            return j.getJSONArray("content").getJSONObject(0).getString("text").trim().replace("*", "");
        }
        JSONObject body = new JSONObject().put("contents", new JSONArray().put(new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))));
        res = http("https://generativelanguage.googleapis.com/v1beta/models/"
                        + (model.isEmpty() ? "gemini-2.5-flash" : model) + ":generateContent?key=" + URLEncoder.encode(key, "UTF-8"),
                body.toString(), "application/json", null, null, null, null);
        j = new JSONObject(res);
        if (j.has("error")) {
            throw new Exception(j.getJSONObject("error").optString("message"));
        }
        return j.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).getString("text").trim().replace("*", "");
    }

    private static void schedule(JSONObject cfg, String text, long ts) throws Exception {
        String body = "message=" + URLEncoder.encode(text, "UTF-8")
                + "&published=false&scheduled_publish_time=" + ts
                + "&access_token=" + URLEncoder.encode(cfg.optString("pageToken"), "UTF-8");
        String res = http(GRAPH + cfg.optString("pageId") + "/feed", body,
                "application/x-www-form-urlencoded", null, null, null, null);
        JSONObject j = new JSONObject(res);
        if (j.has("error")) {
            throw new Exception(j.getJSONObject("error").optString("message"));
        }
    }

    private static String http(String url, String body, String type, String h1, String v1, String h2, String v2)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Content-Type", type + "; charset=UTF-8");
        if (h1 != null) {
            c.setRequestProperty(h1, v1);
        }
        if (h2 != null) {
            c.setRequestProperty(h2, v2);
        }
        c.setDoOutput(true);
        OutputStream o = c.getOutputStream();
        o.write(body.getBytes("UTF-8"));
        o.close();
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        in.close();
        return bo.toString("UTF-8");
    }
}
