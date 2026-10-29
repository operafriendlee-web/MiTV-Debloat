package my.rafhan.app.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;

/**
 * Weather over the wallpaper: drifting clouds and a darker sky when it is overcast, falling rain
 * (light to heavy) and, in a thunderstorm, soft flashes of lightning. It sits between the
 * wallpaper and the rest of Home, never takes focus, and draws only while Home is on screen.
 *
 * The weather comes from Open-Meteo (free, no key) every 30 minutes. To try a look without
 * waiting for the weather, write one word into
 * /sdcard/Android/data/my.rafhan.app.tvhome/files/weather-test.txt :
 * clear, cloud, rain, heavy, storm, or off (switches the effect off). Delete the file to go back.
 */
final class WeatherFx extends View {
    // Skudai. Change these two numbers for another town.
    private static final double LAT = 1.5333, LON = 103.6667;
    private static final String AQI_STATION = "CA30J"; // Larkin, Johor Bahru (DOE APIMS)
    private static final String API = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT
            + "&longitude=" + LON + "&current=temperature_2m,precipitation,cloud_cover,weather_code&timezone=auto";
    private static final long REFRESH_MS = 30 * 60_000L, RETRY_MS = 2 * 60_000L;
    private static final long TICK_MS = 5_000L, FRAME_MS = 40L;
    private static final String TEST_FILE = "weather-test.txt";

    // What is on screen now: cloud 0 none, 1 a few, 2 overcast; rain 0 to 3; storm adds lightning.
    private int cloud, rain;
    private boolean storm, off;
    // What the last forecast said.
    private int liveCloud, liveRain;
    private boolean liveStorm, liveKnown;
    private long lastFetch;
    private boolean fetching, running;
    private String lastOverride = "";

    private final Random rnd = new Random();
    private final float density;
    private int w, h;
    private long lastNs;

    private final Paint tintPaint = new Paint();
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[] blobX = new float[0], blobY = new float[0], blobR = new float[0], blobV = new float[0];
    private Shader[] blobShader = new Shader[0];
    private float[] dropX = new float[0], dropY = new float[0], dropLen = new float[0], dropSpd = new float[0];
    private float[] pts = new float[0];

    private float flash;
    private long nextFlashNs, secondFlashNs;
    private Path bolt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            checkOverride();
            maybeFetch();
            postDelayed(this, TICK_MS);
        }
    };

    private WeatherFx(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        rainPaint.setStrokeWidth(Math.max(1.5f, 1.6f * density));
        rainPaint.setStrokeCap(Paint.Cap.ROUND);
        rainPaint.setColor(Color.argb(115, 205, 222, 255));
        boltPaint.setStyle(Paint.Style.STROKE);
        boltPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Puts the effect just above the wallpaper. Never lets a failure reach Home. */
    static void attach(Activity a, View wallpaper) {
        try {
            ViewGroup parent = (ViewGroup) wallpaper.getParent();
            if (parent == null) return;
            WeatherFx fx = new WeatherFx(a);
            parent.addView(fx, parent.indexOfChild(wallpaper) + 1,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            android.util.Log.i("TvHome", "WeatherFx attached");
            fx.buildWidget(a);
        } catch (RuntimeException ignored) {
        }
    }

    // ---------------------------------------------------------------- widget

    private android.widget.LinearLayout wBox;
    private android.widget.TextView wTemp, wCond;
    private android.widget.LinearLayout aBox;
    private android.widget.TextView aHead, aNum, aCond;

    /** Small city / temperature / condition block to the right of the dates. Hidden until the first forecast. */
    private void buildWidget(Activity a) {
        try {
            View band = a.findViewById(R.id.clockBlock);
            android.util.Log.i("TvHome", "weather widget band=" + band);
            if (!(band instanceof android.widget.LinearLayout)) return;
            wBox = new android.widget.LinearLayout(a);
            wBox.setOrientation(android.widget.LinearLayout.VERTICAL);
            wBox.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            android.widget.TextView city = new android.widget.TextView(a);
            city.setText("SKUDAI");
            city.setTextColor(0x99FFFFFF);
            city.setTextSize(9);
            city.setTypeface(Fonts.outfit(a, 400));
            wTemp = new android.widget.TextView(a);
            wTemp.setTextColor(0xFFFFFFFF);
            wTemp.setTextSize(22);
            wTemp.setIncludeFontPadding(false);
            wTemp.setTypeface(Fonts.outfit(a, 300));
            wCond = new android.widget.TextView(a);
            wCond.setTextColor(0xB3FFFFFF);
            wCond.setTextSize(11);
            wCond.setTypeface(Fonts.outfit(a, 400));
            wBox.addView(city);
            wBox.addView(wTemp);
            wBox.addView(wCond);
            wBox.setVisibility(View.GONE);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart((int) (26 * density));
            lp.gravity = android.view.Gravity.CENTER_VERTICAL;
            ((android.widget.LinearLayout) band).addView(wBox, lp);
            aBox = new android.widget.LinearLayout(a);
            aBox.setOrientation(android.widget.LinearLayout.VERTICAL);
            aBox.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            aHead = new android.widget.TextView(a);
            aHead.setTextColor(0x99FFFFFF);
            aHead.setTextSize(9);
            aHead.setTypeface(Fonts.outfit(a, 400));
            aNum = new android.widget.TextView(a);
            aNum.setTextSize(22);
            aNum.setIncludeFontPadding(false);
            aNum.setTypeface(Fonts.outfit(a, 300));
            aCond = new android.widget.TextView(a);
            aCond.setTextColor(0xB3FFFFFF);
            aCond.setTextSize(11);
            aCond.setTypeface(Fonts.outfit(a, 400));
            aBox.addView(aHead);
            aBox.addView(aNum);
            aBox.addView(aCond);
            aBox.setVisibility(View.GONE);
            android.widget.LinearLayout.LayoutParams lp2 = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp2.setMarginStart((int) (22 * density));
            lp2.gravity = android.view.Gravity.CENTER_VERTICAL;
            ((android.widget.LinearLayout) band).addView(aBox, lp2);
        } catch (RuntimeException e) {
            android.util.Log.w("TvHome", "weather widget build failed", e);
            wBox = null;
        }
    }

    private void showAqi(int aqi, String label) {
        if (aqi < 0) return;
        if (aBox == null && wBox == null && getContext() instanceof Activity) buildWidget((Activity) getContext());
        if (aBox == null) return;
        int color;
        String text;
        if (aqi <= 50) { color = 0xFF34C759; text = "Baik"; }
        else if (aqi <= 100) { color = 0xFFFFD60A; text = "Sederhana"; }
        else if (aqi <= 200) { color = 0xFFFF9F0A; text = "Tidak sihat"; }
        else if (aqi <= 300) { color = 0xFFFF453A; text = "Sangat tidak sihat"; }
        else { color = 0xFFBF5AF2; text = "Berbahaya"; }
        aHead.setText(label);
        aNum.setTextColor(color);
        aNum.setText(String.valueOf(aqi));
        aCond.setText(text);
        aBox.setVisibility(View.VISIBLE);
    }

    private static String httpGet(String u) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(8_000);
            c.setReadTimeout(8_000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Safari/537.36");
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void showWidget(int code, int temp) {
        android.util.Log.i("TvHome", "weather code=" + code + " temp=" + temp);
        if (wBox == null && getContext() instanceof Activity) buildWidget((Activity) getContext());
        if (wBox == null || temp == Integer.MIN_VALUE) return;
        String icon, text;
        if (code <= 1) { icon = "\u2600\uFE0F"; text = "Cerah"; }
        else if (code == 2) { icon = "\u26C5"; text = "Berawan"; }
        else if (code == 3) { icon = "\u2601\uFE0F"; text = "Mendung"; }
        else if (code == 45 || code == 48) { icon = "\uD83C\uDF2B\uFE0F"; text = "Berkabus"; }
        else if (code >= 51 && code <= 57) { icon = "\uD83C\uDF26\uFE0F"; text = "Gerimis"; }
        else if (code >= 95) { icon = "\u26C8\uFE0F"; text = "Ribut petir"; }
        else if (code >= 51) { icon = "\uD83C\uDF27\uFE0F"; text = "Hujan"; }
        else { icon = "\u2601\uFE0F"; text = "Berawan"; }
        wTemp.setText(icon + " " + temp + "\u00B0");
        wCond.setText(text);
        wBox.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- weather

    /** WMO weather code to {cloud, rain, storm (0 or 1)}. */
    static int[] look(int code) {
        switch (code) {
            case 0: case 1: return new int[]{0, 0, 0};
            case 2: return new int[]{1, 0, 0};
            case 3: case 45: case 48: return new int[]{2, 0, 0};
            case 51: case 53: case 55: case 56: case 57: case 61: case 80: return new int[]{2, 1, 0};
            case 63: case 66: case 81: return new int[]{2, 2, 0};
            case 65: case 67: case 82: return new int[]{2, 3, 0};
            case 95: return new int[]{2, 2, 1};
            case 96: case 99: return new int[]{2, 3, 1};
            default: return new int[]{code >= 71 && code <= 86 ? 2 : 0, 0, 0};
        }
    }

    private void maybeFetch() {
        if (fetching || !lastOverride.isEmpty() && parseOverride(lastOverride) != null) return;
        long now = System.currentTimeMillis();
        if (lastFetch != 0 && now - lastFetch < REFRESH_MS) return;
        fetching = true;
        new Thread(() -> {
            int code = -1, temp = Integer.MIN_VALUE;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(15_000);
                c.setReadTimeout(15_000);
                int rc = c.getResponseCode();
                android.util.Log.i("TvHome", "weather http " + rc);
                if (rc == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                        for (String line; (line = r.readLine()) != null; ) sb.append(line);
                    }
                    JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
                    code = cur.getInt("weather_code");
                    double pr = cur.optDouble("precipitation", 1), cc = cur.optDouble("cloud_cover", 100);
                    boolean wet = code >= 51 && code <= 67 || code >= 80 && code <= 82;
                    if (wet && pr < 0.5) code = cc >= 70 ? 3 : cc >= 30 ? 2 : 0;
                    temp = (int) Math.round(cur.getDouble("temperature_2m"));
                }
            } catch (Exception e) {
                android.util.Log.w("TvHome", "weather fetch failed", e);
            } finally {
                if (c != null) c.disconnect();
            }
            int aqiVal = -1;
            String aqiTxt = "";
            try {
                String body = httpGet("https://eqms.doe.gov.my/api3/publicportalapims/currentapicurrentlocation?stationid=" + AQI_STATION);
                if (body != null) {
                    org.json.JSONArray arr = new JSONObject(body).optJSONArray("data");
                    if (arr != null && arr.length() > 0) {
                        aqiVal = arr.getJSONObject(0).optInt("API", -1);
                        aqiTxt = "API · LARKIN";
                    }
                }
                if (aqiVal < 0) {
                    body = httpGet("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + LAT + "&longitude=" + LON + "&current=us_aqi");
                    if (body != null) {
                        aqiVal = new JSONObject(body).getJSONObject("current").optInt("us_aqi", -1);
                        aqiTxt = "AQI · ANGGARAN";
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("TvHome", "air quality failed", e);
            }
            android.util.Log.i("TvHome", "air quality " + aqiVal + " " + aqiTxt);
            final int aqF = aqiVal;
            final String aqL = aqiTxt;
            final int result = code, tmp = temp;
            post(() -> {
                fetching = false;
                if (result >= 0) {
                    showWidget(result, tmp);
                    int[] l = look(result);
                    liveCloud = l[0];
                    liveRain = l[1];
                    liveStorm = l[2] == 1;
                    liveKnown = true;
                    lastFetch = System.currentTimeMillis();
                } else {
                    lastFetch = System.currentTimeMillis() - REFRESH_MS + RETRY_MS; // try again soon
                }
                applyLook();
                showAqi(aqF, aqL);
            });
        }, "weather-fetch").start();
    }

    /** {cloud, rain, storm, off} for a test word, or null when it is not one. */
    private static int[] parseOverride(String v) {
        switch (v) {
            case "off": return new int[]{0, 0, 0, 1};
            case "clear": return new int[]{0, 0, 0, 0};
            case "cloud": case "mendung": return new int[]{2, 0, 0, 0};
            case "rain": case "hujan": return new int[]{2, 2, 0, 0};
            case "heavy": case "lebat": return new int[]{2, 3, 0, 0};
            case "storm": case "ribut": return new int[]{2, 3, 1, 0};
            default: return null;
        }
    }

    private void checkOverride() {
        String v = "";
        try {
            File dir = getContext().getExternalFilesDir(null);
            File f = dir == null ? null : new File(dir, TEST_FILE);
            if (f != null && f.isFile() && f.length() < 64) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line = r.readLine();
                    if (line != null) v = line.trim().toLowerCase(Locale.ROOT);
                }
            }
        } catch (Exception ignored) {
        }
        if (!v.equals(lastOverride)) {
            lastOverride = v;
            applyLook();
        }
    }

    private void applyLook() {
        int[] o = parseOverride(lastOverride);
        if (o != null) {
            cloud = o[0];
            rain = o[1];
            storm = o[2] == 1;
            off = o[3] == 1;
        } else {
            cloud = liveCloud;
            rain = liveRain;
            storm = liveStorm;
            off = false;
        }
        buildScene();
        invalidate();
        if (o != null || liveKnown) publishKind(storm ? "ribut" : rain > 0 ? "hujan" : cloud == 2 ? "mendung" : "cerah");
    }

    /** Tells "Follow the weather" wallpaper what the weather is; reloads the wallpaper when it changed. */
    private void publishKind(String kind) {
        try {
            Context c = getContext();
            android.content.SharedPreferences sp = c.getSharedPreferences("weather_fx", Context.MODE_PRIVATE);
            if (kind.equals(sp.getString("kind", ""))) return;
            sp.edit().putString("kind", kind).apply();
            if (Wallpapers.WEATHER.equals(LauncherSettings.wallpaper(c))) LauncherSettings.setWallpaper(c, Wallpapers.WEATHER);
        } catch (RuntimeException ignored) {
        }
    }

    // ---------------------------------------------------------------- scene

    private void buildScene() {
        if (w <= 0 || h <= 0) return;
        int blobs = off || cloud == 0 ? 0 : cloud == 1 ? 3 : 7;
        blobX = new float[blobs];
        blobY = new float[blobs];
        blobR = new float[blobs];
        blobV = new float[blobs];
        blobShader = new Shader[blobs];
        int centre = cloud == 1 && rain == 0 ? Color.argb(80, 228, 233, 242) : Color.argb(120, 58, 66, 82);
        int edge = centre & 0x00FFFFFF;
        for (int i = 0; i < blobs; i++) {
            blobR[i] = w * (0.16f + rnd.nextFloat() * 0.14f);
            blobX[i] = rnd.nextFloat() * (w + 2 * blobR[i]) - blobR[i];
            blobY[i] = h * (0.04f + rnd.nextFloat() * 0.40f);
            blobV[i] = w * (0.006f + rnd.nextFloat() * 0.012f);
            blobShader[i] = new RadialGradient(0, 0, blobR[i], centre, edge, Shader.TileMode.CLAMP);
        }
        int drops = off ? 0 : rain == 1 ? 14 : rain == 2 ? 110 : rain == 3 ? 180 : 0;
        dropX = new float[drops];
        dropY = new float[drops];
        dropLen = new float[drops];
        dropSpd = new float[drops];
        pts = new float[drops * 4];
        for (int i = 0; i < drops; i++) {
            dropX[i] = rnd.nextFloat() * (w + h * 0.2f);
            dropY[i] = rnd.nextFloat() * h;
            dropLen[i] = h * (0.03f + rnd.nextFloat() * 0.035f);
            dropSpd[i] = h * (0.95f + rnd.nextFloat() * 0.55f);
        }
        int alpha = cloud == 0 ? 0 : cloud == 1 && rain == 0 ? 14 : rain == 0 ? 50
                : rain == 1 ? 70 : rain == 2 ? 90 : 105;
        if (storm) alpha += 20;
        tintPaint.setColor(Color.argb(off ? 0 : alpha, 12, 18, 30));
        nextFlashNs = System.nanoTime() + 1_500_000_000L + (long) (rnd.nextFloat() * 3e9);
        secondFlashNs = 0;
        flash = 0;
        lastNs = System.nanoTime();
    }

    private void startFlash(long now) {
        flash = 1f;
        // A jagged bolt from the top, drawn once for this flash.
        bolt = new Path();
        float x = w * (0.15f + rnd.nextFloat() * 0.7f), y = 0;
        bolt.moveTo(x, y);
        int steps = 9;
        for (int i = 1; i <= steps; i++) {
            x += (rnd.nextFloat() - 0.5f) * w * 0.07f;
            y = h * 0.58f * i / steps;
            bolt.lineTo(x, y);
        }
        secondFlashNs = rnd.nextFloat() < 0.6f ? now + 140_000_000L + (long) (rnd.nextFloat() * 120e6) : 0;
        // Mild and rare on purpose: at least four seconds apart, never a strobe.
        nextFlashNs = now + 4_000_000_000L + (long) (rnd.nextFloat() * 7e9);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        super.onSizeChanged(nw, nh, ow, oh);
        w = nw;
        h = nh;
        buildScene();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!running || off || w <= 0 || (cloud == 0 && rain == 0 && !storm)) return;
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;

        if (Color.alpha(tintPaint.getColor()) > 0) canvas.drawPaint(tintPaint);

        for (int i = 0; i < blobShader.length; i++) {
            blobX[i] += blobV[i] * dt;
            if (blobX[i] - blobR[i] > w) blobX[i] = -blobR[i];
            blobPaint.setShader(blobShader[i]);
            canvas.save();
            canvas.translate(blobX[i], blobY[i]);
            canvas.scale(1f, 0.45f);
            canvas.drawCircle(0, 0, blobR[i], blobPaint);
            canvas.restore();
        }

        if (dropX.length > 0) {
            float slant = 0.18f;
            for (int i = 0; i < dropX.length; i++) {
                dropY[i] += dropSpd[i] * dt;
                dropX[i] -= dropSpd[i] * dt * slant;
                if (dropY[i] > h || dropX[i] < -dropLen[i]) {
                    dropY[i] = -dropLen[i];
                    dropX[i] = rnd.nextFloat() * (w + h * 0.2f);
                }
                pts[i * 4] = dropX[i];
                pts[i * 4 + 1] = dropY[i];
                pts[i * 4 + 2] = dropX[i] + dropLen[i] * slant;
                pts[i * 4 + 3] = dropY[i] - dropLen[i];
            }
            canvas.drawLines(pts, rainPaint);
        }

        if (storm) {
            if (now >= nextFlashNs) startFlash(now);
            if (secondFlashNs != 0 && now >= secondFlashNs) {
                flash = Math.max(flash, 0.65f);
                secondFlashNs = 0;
            }
            if (flash > 0.03f) {
                canvas.drawColor(Color.argb((int) (flash * 105), 232, 238, 255));
                if (bolt != null) {
                    int a = Math.min(255, (int) (flash * 255));
                    boltPaint.setStrokeWidth(9f * density);
                    boltPaint.setColor(Color.argb(a / 4, 190, 205, 255));
                    canvas.drawPath(bolt, boltPaint);
                    boltPaint.setStrokeWidth(2.4f * density);
                    boltPaint.setColor(Color.argb(a, 255, 255, 255));
                    canvas.drawPath(bolt, boltPaint);
                }
                flash *= (float) Math.exp(-dt * 7.0);
            }
        }
        postInvalidateDelayed(FRAME_MS);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) resume();
        else pause();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getWindowVisibility() == VISIBLE) resume();
    }

    @Override
    protected void onDetachedFromWindow() {
        pause();
        super.onDetachedFromWindow();
    }

    private void resume() {
        if (running) return;
        running = true;
        lastNs = System.nanoTime();
        removeCallbacks(tick);
        post(tick);
        invalidate();
    }

    private void pause() {
        running = false;
        removeCallbacks(tick);
    }
}
