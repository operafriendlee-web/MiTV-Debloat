package my.rafhan.app.tvhome;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.chrono.HijrahDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.util.Random;

/** Screensaver: Islamic night sky (crescent, twinkling stars, mosque) with clock, dates, next prayer. */
public class IslamicDream extends DreamService {
    private static final String[] DAYS = {"Ahad", "Isnin", "Selasa", "Rabu", "Khamis", "Jumaat", "Sabtu"};
    private static final String[] MON = {"Januari", "Februari", "Mac", "April", "Mei", "Jun", "Julai", "Ogos", "September", "Oktober", "November", "Disember"};
    private static final String[] HM = {"Muharram", "Safar", "Rabiulawal", "Rabiulakhir", "Jamadilawal", "Jamadilakhir", "Rejab", "Syaaban", "Ramadan", "Syawal", "Zulkaedah", "Zulhijjah"};
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private TextView clock, date, hijri, pill;
    private View sky, box;
    private final Runnable tick = new Runnable() {
        @Override public void run() { update(); h.postDelayed(this, 30_000); }
    };
    private final Runnable drift = new Runnable() {
        @Override public void run() {
            if (box != null) { box.setTranslationX((rnd.nextFloat() - .5f) * 60); box.setTranslationY((rnd.nextFloat() - .5f) * 40); }
            h.postDelayed(this, 60_000);
        }
    };

    private TextView text(float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setTextSize(sp); t.setTextColor(color); t.setIncludeFontPadding(false);
        t.setTypeface(Fonts.outfit(this, weight)); t.setShadowLayer(10, 0, 3, 0x80000000);
        return t;
    }

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); setFullscreen(true);
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        sky = new Sky(this);
        root.addView(sky, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL); b.setGravity(Gravity.CENTER_HORIZONTAL);
        clock = text(110, 0xFFFFFFFF, 300); date = text(20, 0xE6FFFFFF, 400);
        hijri = text(17, 0xD9E6E0D2, 400); pill = text(18, 0xFFFFFFFF, 500);
        GradientDrawable g = new GradientDrawable(); g.setColor(0x33FFFFFF); g.setCornerRadius(24 * d);
        pill.setBackground(g); pill.setPadding((int) (22 * d), (int) (8 * d), (int) (22 * d), (int) (8 * d));
        b.addView(clock); b.addView(date); b.addView(hijri);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = (int) (18 * d); b.addView(pill, lp);
        box = b;
        root.addView(b, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);
        update();
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        h.postDelayed(tick, 30_000); h.postDelayed(drift, 60_000);
    }

    @Override public void onDreamingStopped() { h.removeCallbacksAndMessages(null); super.onDreamingStopped(); }

    @Override public void onDetachedFromWindow() { h.removeCallbacksAndMessages(null); super.onDetachedFromWindow(); }

    private void update() {
        LocalDateTime now = LocalDateTime.now();
        clock.setText(now.format(DateTimeFormatter.ofPattern(LauncherSettings.use24h(this) ? "HH:mm" : "h:mm")));
        date.setText(DAYS[now.getDayOfWeek().getValue() % 7] + ", " + now.getDayOfMonth() + " " + MON[now.getMonthValue() - 1] + " " + now.getYear());
        try {
            HijrahDate hd = HijrahDate.from(now);
            hijri.setText("🌙 " + hd.get(ChronoField.DAY_OF_MONTH) + " " + HM[hd.get(ChronoField.MONTH_OF_YEAR) - 1] + " " + hd.get(ChronoField.YEAR));
        } catch (Exception e) { hijri.setText(""); }
        int hr = now.getHour();
        sky.setAlpha(hr >= 22 || hr < 6 ? 0.35f : 1f);
        String z = LauncherSettings.zone(this);
        LocalDate td = now.toLocalDate();
        LocalTime[] t = PrayerPanel.cachedTimes(this, z, td);
        LocalDateTime nx = null; int idx = -1;
        if (t != null) for (int k = 0; k < t.length; k++) if (t[k] != null && td.atTime(t[k]).isAfter(now)) { nx = td.atTime(t[k]); idx = k; break; }
        if (nx == null) {
            LocalTime[] tm = PrayerPanel.cachedTimes(this, z, td.plusDays(1));
            if (tm != null && tm[0] != null) { nx = td.plusDays(1).atTime(tm[0]); idx = 0; }
        }
        if (nx == null) { pill.setVisibility(View.GONE); return; }
        long m = (Duration.between(now, nx).getSeconds() + 59) / 60;
        pill.setVisibility(View.VISIBLE);
        pill.setText(PrayerPanel.NAMES[idx] + " dalam " + (m >= 60 ? (m / 60) + "j " + (m % 60) + "m" : m + "m"));
    }

    /** Night sky: static layer drawn once into a bitmap, stars twinkle on top. */
    static class Sky extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] sx = new float[140], sy = new float[140], sr = new float[140], sp = new float[140];
        private Bitmap bg;
        private float u, w2;
        private final Runnable loop = new Runnable() { @Override public void run() { invalidate(); postDelayed(this, 80); } };

        Sky(Context c) {
            super(c);
            Random r = new Random(7);
            for (int i = 0; i < sx.length; i++) { sx[i] = r.nextFloat(); sy[i] = r.nextFloat() * .6f; sr[i] = .8f + r.nextFloat() * 1.4f; sp[i] = r.nextFloat() * 6.28f; }
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(loop); }
        @Override protected void onDetachedFromWindow() { removeCallbacks(loop); super.onDetachedFromWindow(); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { bg = w > 0 && h > 0 ? render(w, h) : null; }
        @Override protected void onDraw(Canvas c) {
            if (bg == null) return;
            c.drawBitmap(bg, 0, 0, null);
            p.setShader(null); p.setStyle(Paint.Style.FILL);
            long t = System.currentTimeMillis();
            for (int i = 0; i < sx.length; i++) {
                float a = .35f + .65f * (.5f + .5f * (float) Math.sin(t / 1400.0 + sp[i]));
                p.setColor(Color.argb((int) (255 * a), 255, 255, 255));
                c.drawCircle(sx[i] * getWidth(), sy[i] * getHeight(), sr[i] * u, p);
            }
        }
        private float X(float x) { return w2 + (x - 960) * u; }
        private float Y(float y) { return y * u; }
        private void r(Canvas c, float x, float y, float w, float h) { c.drawRect(X(x), Y(y), X(x + w), Y(y + h), p); }
        private void dome(Canvas c, float x, float b, float w, float h) {
            Path q = new Path(); q.moveTo(X(x - w / 2), Y(b));
            q.quadTo(X(x - w / 2), Y(b - h), X(x), Y(b - h * 1.25f)); q.quadTo(X(x + w / 2), Y(b - h), X(x + w / 2), Y(b)); q.close();
            c.drawPath(q, p); r(c, x - 1.5f, b - h * 1.25f - 34, 3, 34); c.drawCircle(X(x), Y(b - h * 1.25f - 38), 5 * u, p);
        }
        private void minaret(Canvas c, float x, float b, float h, float w) {
            r(c, x - w / 2, b - h, w, h); r(c, x - w * .9f, b - h + 60, w * 1.8f, 12);
            Path q = new Path(); q.moveTo(X(x - w / 2), Y(b - h)); q.lineTo(X(x), Y(b - h - 70)); q.lineTo(X(x + w / 2), Y(b - h)); q.close();
            c.drawPath(q, p); c.drawCircle(X(x), Y(b - h - 78), 5 * u, p);
        }
        private Bitmap render(int w, int h) {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b); u = h / 1080f; w2 = w / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xFF050B1F, 0xFF0C1F4A, 0xFF1D3B73}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            float mx = w * .74f, my = h * .21f;
            p.setShader(new RadialGradient(mx, my, 190 * u, 0x59FFE9A8, 0x00FFE9A8, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p); p.setShader(null);
            int sv = c.saveLayer(null, null);
            p.setColor(0xFFF6E3A6); c.drawCircle(mx, my, 72 * u, p);
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR)); c.drawCircle(mx + 34 * u, my - 14 * u, 62 * u, p);
            p.setXfermode(null); c.restoreToCount(sv);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.4f * u); p.setColor(0x229DB8E8);
            for (int gx = 120; gx < 2200; gx += 240) for (int gy = 110; gy < 500; gy += 200) {
                float cx = X(gx + (gy / 200 % 2 == 1 ? 120 : 0)), cy = Y(gy); Path s = new Path();
                for (int k = 0; k < 16; k++) { double a = Math.PI * k / 8, rr = (k % 2 == 0 ? 46 : 28.5) * u; float px = cx + (float) (rr * Math.sin(a)), py = cy - (float) (rr * Math.cos(a)); if (k == 0) s.moveTo(px, py); else s.lineTo(px, py); }
                s.close(); c.drawPath(s, p);
            }
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF050912);
            float g = 860; c.drawRect(0, Y(g), w, h, p);
            r(c, 640, g - 150, 640, 150); dome(c, 960, g - 150, 300, 150); dome(c, 760, g - 150, 130, 70); dome(c, 1160, g - 150, 130, 70);
            minaret(c, 560, g, 430, 26); minaret(c, 1360, g, 430, 26); minaret(c, 470, g, 300, 22); minaret(c, 1450, g, 300, 22);
            r(c, 80, g - 120, 120, 120); r(c, 230, g - 90, 90, 90); r(c, 1600, g - 100, 110, 100); r(c, 1750, g - 140, 130, 140); r(c, 1880, g - 80, 90, 80);
            dome(c, 1660, g - 100, 80, 40); dome(c, 130, g - 120, 90, 45);
            p.setColor(0xCCF0C96A);
            for (int i = 0; i < 7; i++) {
                float x = 700 + i * 85; Path q = new Path(); q.moveTo(X(x - 14), Y(g - 40)); q.lineTo(X(x - 14), Y(g - 80));
                q.quadTo(X(x), Y(g - 105), X(x + 14), Y(g - 80)); q.lineTo(X(x + 14), Y(g - 40)); q.close(); c.drawPath(q, p);
            }
            return b;
        }
    }
}
