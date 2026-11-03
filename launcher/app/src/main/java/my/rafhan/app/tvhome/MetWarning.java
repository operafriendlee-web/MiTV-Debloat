package my.rafhan.app.tvhome;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * MET Malaysia warnings (api.data.gov.my). When the model reads "overcast" (code 3) but an active
 * rain or thunderstorm warning names Johor Bahru, show rain (63) or storm (95) instead.
 */
final class MetWarning {
    private static final String TAG = "TvHome";
    private static final String URL_WARN = "https://api.data.gov.my/weather/warning?limit=30";

    private MetWarning() {}

    static int adjust(int code) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(URL_WARN).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            int rc = c.getResponseCode();
            if (rc != 200) {
                Log.i(TAG, "met warning http " + rc);
                return code;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("Asia/Kuala_Lumpur"));
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject o = arr.getJSONObject(i);
                    Date a = f.parse(o.optString("valid_from")), b = f.parse(o.optString("valid_to"));
                    if (a == null || b == null || now < a.getTime() || now > b.getTime()) continue;
                    String t = (o.optString("heading_bm") + " " + o.optString("text_bm") + " " + o.optString("heading_en")
                            + " " + o.optString("text_en")).toLowerCase(Locale.ROOT);
                    boolean here = t.contains("johor bahru") || t.contains("iskandar puteri") || t.contains("seluruh johor");
                    Log.i(TAG, "met warning: " + o.optString("heading_en") + " here=" + here);
                    if (!here) continue;
                    if (t.contains("ribut petir") || t.contains("thunderstorm")) return 95;
                    if (t.contains("hujan") || t.contains("rain")) return 63;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "met warning failed", e);
        } finally {
            if (c != null) c.disconnect();
        }
        return code;
    }
}
