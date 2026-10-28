package my.rafhan.app.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Daily ayat card under the left cards on Home: one short verse a day (Malaysian time) with its
 * Uthmani text and Abdullah Muhammad Basmeih's Malay translation from Quran.com, fetched once a day
 * and kept. The verse list and the Quran.com approach follow Glass TV Home (MIT, (c) 2026 Gathin).
 */
final class DailyAyah {
    private static final String TAG = "TvHome.Ayah";
    private static final String API = "https://api.quran.com/api/v4/";
    private static final int MALAY = 39;
    private static final String[] VERSES = {
            "2:152", "94:5", "13:28", "2:153", "20:25", "2:45", "3:8", "94:6", "3:134", "7:23",
            "2:201", "93:5", "3:31", "40:60", "20:114", "2:156", "14:7", "4:28", "18:10", "17:24",
            "6:162", "10:62", "11:115", "15:99", "16:128", "19:96", "20:46", "23:118", "33:41", "47:7",
            "49:10", "50:16", "51:56", "55:13", "73:9", "76:9", "87:14", "93:3", "94:8", "96:1",
            "103:2", "108:1", "110:3", "112:1", "2:155", "3:92", "28:24", "31:18", "35:34", "42:19",
            "67:2", "9:129"};

    private Context ctx;
    private LinearLayout card;
    private TextView head, arabic, malay;
    private boolean fetching;

    static void attach(Activity a) {
        try {
            new DailyAyah(a);
        } catch (RuntimeException e) {
            Log.w(TAG, "ayat card not added", e);
        }
    }

    private DailyAyah(Activity a) {
        ctx = a;
        View panel = a.findViewById(R.id.prayerPanel);
        if (panel == null || !(panel.getParent() instanceof LinearLayout)) throw new IllegalStateException("no left column");
        final float d = a.getResources().getDisplayMetrics().density;

        card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(a.getDrawable(R.drawable.sys_bg));
        card.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (11 * d));

        head = new TextView(a);
        head.setTextSize(10);
        head.setTextColor(0x99FFFFFF);
        head.setLetterSpacing(0.08f);
        head.setTypeface(Fonts.outfit(a, 400));

        arabic = new TextView(a);
        arabic.setTextSize(17);
        arabic.setTextColor(Color.WHITE);
        arabic.setMaxLines(2);
        arabic.setEllipsize(android.text.TextUtils.TruncateAt.END);
        arabic.setTextDirection(View.TEXT_DIRECTION_RTL);
        arabic.setGravity(Gravity.END);

        malay = new TextView(a);
        malay.setTextSize(11);
        malay.setTextColor(0xCCFFFFFF);
        malay.setMaxLines(3);
        malay.setEllipsize(android.text.TextUtils.TruncateAt.END);
        malay.setTypeface(Fonts.outfit(a, 400));

        LinearLayout.LayoutParams lpA = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpA.topMargin = (int) (4 * d);
        LinearLayout.LayoutParams lpM = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpM.topMargin = (int) (3 * d);
        card.addView(head);
        card.addView(arabic, lpA);
        card.addView(malay, lpM);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (330 * d), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * d);
        ((LinearLayout) panel.getParent()).addView(card, lp);

        showKept();
        card.post(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!card.isAttachedToWindow()) return;
            if (!keyFor(day()).equals(prefs().getString("key", null)) && !fetching) fetch();
            card.postDelayed(this, 30 * 60_000L);
        }
    };

    private static int day() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kuala_Lumpur"));
        return (int) ((c.getTimeInMillis() + c.getTimeZone().getOffset(c.getTimeInMillis())) / 86_400_000L);
    }

    private static String keyFor(int day) {
        return VERSES[Math.floorMod(day, VERSES.length)];
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("daily_ayah", Context.MODE_PRIVATE);
    }

    /** Whatever verse is kept (today's, or the last one, or a first default) shows at once. */
    private void showKept() {
        SharedPreferences p = prefs();
        String ar = p.getString("arabic", null);
        if (ar == null) {
            show("Al-Baqarah 2:152",
                    "فَاذْكُرُونِي أَذْكُرْكُمْ وَاشْكُرُوا لِي وَلَا تَكْفُرُونِ",
                    "Oleh itu ingatlah kamu kepada-Ku, supaya Aku membalas ingat kepada kamu; dan bersyukurlah kamu kepada-Ku dan janganlah kamu kufur.");
        } else {
            show(p.getString("reference", ""), ar, p.getString("malay", ""));
        }
    }

    private void show(String reference, String ar, String ms) {
        head.setText("AYAT HARIAN" + (reference.isEmpty() ? "" : "  ·  " + reference.toUpperCase(java.util.Locale.ROOT)));
        arabic.setText(ar);
        malay.setText(ms);
    }

    private void fetch() {
        fetching = true;
        final String key = keyFor(day());
        new Thread(() -> {
            String reference = null, ar = null, ms = null;
            try {
                JSONObject v = new JSONObject(get(API + "verses/by_key/" + key
                        + "?fields=text_uthmani&translations=" + MALAY)).getJSONObject("verse");
                ar = v.optString("text_uthmani");
                JSONArray trs = v.optJSONArray("translations");
                ms = "";
                for (int i = 0; trs != null && i < trs.length(); i++) {
                    JSONObject t = trs.getJSONObject(i);
                    if (t.optInt("resource_id") == MALAY) {
                        ms = t.optString("text").replaceAll("<sup[^>]*>.*?</sup>", "").replaceAll("<[^>]+>", "").trim();
                    }
                }
                String chapter = key.substring(0, key.indexOf(':'));
                String name = new JSONObject(get(API + "chapters/" + chapter + "?language=ms"))
                        .getJSONObject("chapter").optString("name_simple");
                reference = name + " " + key;
            } catch (Exception e) {
                Log.w(TAG, "verse " + key + ": " + e.getMessage());
                ar = null;
            }
            final String fr = reference, far = ar, fms = ms;
            card.post(() -> {
                fetching = false;
                if (far == null || far.isEmpty() || fr == null) return;
                prefs().edit().putString("key", key).putString("arabic", far).putString("malay", fms)
                        .putString("reference", fr).apply();
                show(fr, far, fms);
            });
        }, "ayah-fetch").start();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "TvHome/1.0 (Android TV home launcher)");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > 256 * 1024) throw new IllegalStateException("response too large");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
