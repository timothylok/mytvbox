package com.setupbox.launcher;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Xml;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.StringReader;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Ad-free TV home screen: clocks, weather, storage, server status,
 * app grid, stock/currency/news strips and a photo screensaver. Settings live in
 * /sdcard/Launcher/config.json (see Config).
 */
public class MainActivity extends Activity {

    private static final long WX_MS = 15 * 60000L, STOCK_MS = 5 * 60000L, FX_MS = 60 * 60000L,
            NEWS_MS = 15 * 60000L, JF_MS = 60000L;
    private static final int COL_OK = Color.parseColor("#4C8DFF");
    private static final int COL_LOW = Color.parseColor("#E5484D");
    private static final int COL_UP = Color.parseColor("#3DDC84");
    private static final int COL_DOWN = Color.parseColor("#FF6B6B");
    private static final int COL_DIM = Color.parseColor("#7C89B0");
    private static final int COL_SUB = Color.parseColor("#9AA6C8");
    private static final int COL_CARD = Color.parseColor("#1F2A4A");

    private Config cfg;
    private String city, tz, jfUrl, fxBase, rssLabel;
    private double lat, lon;
    private List<String> symbols, hidden, pinned, fxList;
    private float lowFraction;
    private long idleMs;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---- views ----
    private GridView grid;
    private Adapter adapter;
    private final List<AppItem> items = new ArrayList<>();
    private TextView wxTemp, wxDesc, wxRange, wxSun, storageView, jfView, fxView, newsView;
    private StorageBar storageBar;
    private WeatherIcon wxIcon;
    private final WeatherIcon[] fcIcon = new WeatherIcon[3];
    private final TextView[] fcDay = new TextView[3];
    private final TextView[] fcRange = new TextView[3];
    private TextView[] stockViews;

    // ---- state ----
    private JSONObject lastWx;
    private long wxLast, stocksLast, fxLast, newsLast, jfLast;
    private volatile boolean wxBusy, stocksBusy, fxBusy, newsBusy, jfBusy;
    private final List<String> headlines = new ArrayList<>();
    private int newsIdx = 0;

    // ---- screensaver ----
    private FrameLayout saver;
    private ImageView saverA, saverB;
    private TextView saverCaption;
    private boolean saverActive = false, frontIsA = true;
    private int saverGen = 0, saverIndex = 0;
    private final Handler saverH = new Handler(Looper.getMainLooper());
    private final Handler idleH = new Handler(Looper.getMainLooper());
    private List<String[]> bing = null;

    private static class AppItem {
        String label;
        Drawable icon;
        Intent intent;
        boolean power; // the built-in Power tile
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("wx", MODE_PRIVATE);
    }

    private TextView text(int sizeSp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout strip() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setBackground(rounded(Color.parseColor("#161F3A"), 12));
        l.setPadding(dp(8), 0, dp(8), 0);
        return l;
    }

    private void addStrip(LinearLayout root, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32));
        lp.topMargin = dp(topDp);
        root.addView(v, lp);
    }

    // ===================================================================== lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        cfg = Config.load();
        city = cfg.str("city");
        lat = cfg.dbl("lat");
        lon = cfg.dbl("lon");
        tz = cfg.str("tz");
        symbols = cfg.list("stocks");
        fxBase = cfg.str("currency_base");
        fxList = cfg.list("currencies");
        rssLabel = cfg.str("rss_label");
        hidden = cfg.list("hidden");
        pinned = cfg.list("pinned");
        jfUrl = cfg.str("jellyfin_url").replaceAll("/+$", "");
        lowFraction = (float) (cfg.dbl("low_storage_percent") / 100.0);
        idleMs = (long) (cfg.dbl("screensaver_minutes") * 60000.0);

        FrameLayout frame = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#0B1020"), Color.parseColor("#1B2440")}));
        root.setPadding(dp(48), dp(22), dp(48), dp(10));

        buildHeader(root);
        buildGrid(root);
        buildStrips(root);
        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildSaver(frame);
        setContentView(frame);

        // Show last good data immediately, refresh in the background.
        String c = prefs().getString("json", null);
        if (c != null) try { applyWeather(new JSONObject(c)); } catch (Exception ignored) { }
        c = prefs().getString("stocks", null);
        if (c != null) applyStocks(c);
        c = prefs().getString("fx", null);
        if (c != null) try { applyFx(new JSONObject(c)); } catch (Exception ignored) { }
        c = prefs().getString("news", null);
        if (c != null) { for (String s : c.split("\n")) if (!s.isEmpty()) headlines.add(s); showHeadline(false); }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshAll();
            ui.postDelayed(this, 30000);
        }
    };
    private final Runnable newsRot = new Runnable() {
        @Override
        public void run() {
            newsIdx++;
            showHeadline(true);
            ui.postDelayed(this, 9000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
        grid.requestFocus();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(newsRot);
        ui.post(tick);
        ui.postDelayed(newsRot, 9000);
        resetIdle();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(newsRot);
        idleH.removeCallbacksAndMessages(null);
        stopSaver(false);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (grid != null) { // Home press: back to the first page, first tile
            if (page != 0) { page = 0; applyPage(0); }
            else if (grid.getCount() > 0) grid.setSelection(0);
        }
    }

    @Override
    public void onBackPressed() {
        // a launcher stays on home
    }

    private void refreshAll() {
        long now = System.currentTimeMillis();
        updateStorage();
        if (lastWx != null) try { applyWeather(lastWx); } catch (Exception ignored) { }
        if (now - wxLast > WX_MS) fetchWeather();
        if (now - stocksLast > STOCK_MS) fetchStocks();
        if (now - fxLast > FX_MS) fetchFx();
        if (now - newsLast > NEWS_MS) fetchNews();
        if (now - jfLast > JF_MS) fetchJfStatus();
    }

    // ===================================================================== UI building

    private void buildHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        // Clock + second timezone
        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setGravity(Gravity.BOTTOM);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(56);
        LinearLayout second = new LinearLayout(this);
        second.setOrientation(LinearLayout.VERTICAL);
        second.setPadding(dp(22), 0, 0, dp(10));
        TextView secLabel = text(12, COL_DIM);
        secLabel.setText(cfg.str("second_label"));
        secLabel.setLetterSpacing(0.12f);
        TextClock secClock = new TextClock(this);
        secClock.setTimeZone(cfg.str("second_tz"));
        secClock.setFormat12Hour("h:mm a");
        secClock.setFormat24Hour("HH:mm");
        secClock.setTextColor(Color.parseColor("#C9D3EE"));
        secClock.setTextSize(24);
        second.addView(secLabel);
        second.addView(secClock);
        clockRow.addView(clock);
        clockRow.addView(second);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM yyyy");
        date.setFormat24Hour("EEEE, d MMMM yyyy");
        date.setTextColor(COL_SUB);
        date.setTextSize(20);

        storageBar = new StorageBar(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(360), dp(8));
        blp.topMargin = dp(8);
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setPadding(0, dp(4), 0, 0);
        storageView = text(15, COL_DIM);
        jfView = text(15, COL_DIM);
        jfView.setPadding(dp(22), 0, 0, 0);
        jfView.setText("● Jellyfin …");
        statusRow.addView(storageView);
        statusRow.addView(jfView);

        header.addView(clockRow);
        header.addView(date);
        header.addView(storageBar, blp);
        header.addView(statusRow);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(header, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 3-day forecast cards
        LinearLayout fc = new LinearLayout(this);
        fc.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(14), dp(10), dp(14), dp(10));
            card.setBackground(rounded(COL_CARD, 12));
            fcDay[i] = text(18, Color.WHITE);
            fcDay[i].setGravity(Gravity.CENTER);
            fcIcon[i] = new WeatherIcon(this);
            fcRange[i] = text(16, Color.WHITE);
            fcRange[i].setGravity(Gravity.CENTER);
            card.addView(fcDay[i]);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(36), dp(36));
            ilp.topMargin = dp(2);
            ilp.bottomMargin = dp(2);
            card.addView(fcIcon[i], ilp);
            card.addView(fcRange[i]);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    dp(120), ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = dp(10);
            fc.addView(card, clp);
        }
        LinearLayout.LayoutParams fclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fclp.rightMargin = dp(28);
        row.addView(fc, fclp);

        // Current weather
        LinearLayout wx = new LinearLayout(this);
        wx.setOrientation(LinearLayout.VERTICAL);
        wx.setGravity(Gravity.END);
        wxTemp = text(48, Color.WHITE);
        wxTemp.setGravity(Gravity.END);
        wxTemp.setText("--°");
        wxDesc = text(20, COL_SUB);
        wxDesc.setGravity(Gravity.END);
        wxDesc.setText(city);
        wxRange = text(16, COL_SUB);
        wxRange.setGravity(Gravity.END);
        wxSun = text(15, Color.parseColor("#FFC83D"));
        wxSun.setGravity(Gravity.END);
        wxSun.setPadding(0, dp(4), 0, 0);
        for (TextView t : new TextView[]{wxDesc, wxRange, wxSun}) t.setSingleLine(true);
        wxIcon = new WeatherIcon(this);
        LinearLayout tempRow = new LinearLayout(this);
        tempRow.setOrientation(LinearLayout.HORIZONTAL);
        tempRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams wilp = new LinearLayout.LayoutParams(dp(60), dp(60));
        wilp.rightMargin = dp(14);
        tempRow.addView(wxIcon, wilp);
        tempRow.addView(wxTemp);
        wx.addView(tempRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wx.addView(wxDesc);
        wx.addView(wxRange);
        wx.addView(wxSun);
        row.addView(wx, new LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void buildGrid(LinearLayout root) {
        grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setVerticalSpacing(dp(12));
        grid.setHorizontalSpacing(dp(12));
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(14), 0, dp(4));
        GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.parseColor("#33FFFFFF"));
        ring.setCornerRadius(dp(12));
        ring.setStroke(dp(3), Color.WHITE);
        grid.setSelector(ring);
        grid.setDrawSelectorOnTop(true);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            AppItem it = items.get(pos);
            if (it.power) { showPowerDialog(); return; }
            try { startActivity(it.intent); } catch (Exception ignored) { }
        });
        grid.setOnKeyListener((v, keyCode, ev) -> ev.getAction() == KeyEvent.ACTION_DOWN && handlePagerKey(keyCode));
        grid.setOnGenericMotionListener((v, ev) -> {
            if (ev.getAction() != MotionEvent.ACTION_SCROLL) return false;
            long now = System.currentTimeMillis();
            if (now - lastWheelPage > 350) {
                float vs = ev.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (vs < 0) gotoPage(page + 1, 0);
                else if (vs > 0) gotoPage(page - 1, 0);
                lastWheelPage = now;
            }
            return true;
        });
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        root.addView(dots, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
    }

    private void buildStrips(LinearLayout root) {
        // Stocks
        LinearLayout stocks = strip();
        stockViews = new TextView[symbols.size()];
        for (int i = 0; i < symbols.size(); i++) {
            TextView tv = text(14, COL_DIM);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setText(symbols.get(i) + " --");
            stockViews[i] = tv;
            stocks.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        addStrip(root, stocks, 6);

        // Currency
        LinearLayout fx = strip();
        fxView = text(14, COL_DIM);
        fxView.setGravity(Gravity.CENTER);
        fxView.setSingleLine(true);
        fxView.setText(fxBase + " rates …");
        fx.addView(fxView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        addStrip(root, fx, 6);

        // News
        LinearLayout news = strip();
        newsView = text(14, COL_DIM);
        newsView.setGravity(Gravity.CENTER_VERTICAL);
        newsView.setSingleLine(true);
        newsView.setEllipsize(TextUtils.TruncateAt.END);
        newsView.setPadding(dp(8), 0, dp(8), 0);
        newsView.setText(rssLabel + "  loading headlines…");
        news.addView(newsView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        addStrip(root, news, 6);
    }

    private void buildSaver(FrameLayout frame) {
        saver = new FrameLayout(this);
        saver.setBackgroundColor(Color.BLACK);
        saver.setVisibility(View.GONE);
        FrameLayout images = new FrameLayout(this);
        saverA = new ImageView(this);
        saverB = new ImageView(this);
        for (ImageView v : new ImageView[]{saverA, saverB}) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            images.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        saver.addView(images, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(48), 0, dp(48), dp(36));
        TextClock sClock = new TextClock(this);
        sClock.setFormat12Hour("h:mm a");
        sClock.setFormat24Hour("HH:mm");
        sClock.setTextColor(Color.WHITE);
        sClock.setTextSize(54);
        sClock.setShadowLayer(8, 0, 2, Color.BLACK);
        TextClock sDate = new TextClock(this);
        sDate.setFormat12Hour("EEEE, d MMMM");
        sDate.setFormat24Hour("EEEE, d MMMM");
        sDate.setTextColor(Color.WHITE);
        sDate.setTextSize(20);
        sDate.setShadowLayer(6, 0, 2, Color.BLACK);
        saverCaption = text(14, Color.parseColor("#DDDDDD"));
        saverCaption.setShadowLayer(6, 0, 2, Color.BLACK);
        saverCaption.setSingleLine(true);
        saverCaption.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(sClock);
        info.addView(sDate);
        info.addView(saverCaption);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        saver.addView(info, ilp);
        frame.addView(saver, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ===================================================================== apps grid

    private void loadApps() {
        final PackageManager pm = getPackageManager();
        List<ResolveInfo> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> hide = new HashSet<>(hidden);
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || hide.contains(pkg) || !seen.add(pkg)) continue;
                all.add(ri);
            }
        }
        final Collator col = Collator.getInstance();
        Collections.sort(all, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                int pa = pinned.indexOf(a.activityInfo.packageName);
                int pb = pinned.indexOf(b.activityInfo.packageName);
                if (pa >= 0 || pb >= 0) {
                    if (pa < 0) return 1;
                    if (pb < 0) return -1;
                    return pa - pb;
                }
                return col.compare(a.loadLabel(pm).toString(), b.loadLabel(pm).toString());
            }
        });
        allItems.clear();
        for (ResolveInfo ri : all) {
            AppItem it = new AppItem();
            it.label = ri.loadLabel(pm).toString();
            it.icon = ri.loadIcon(pm);
            it.intent = new Intent(Intent.ACTION_MAIN)
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            allItems.add(it);
        }
        AppItem pw = new AppItem();
        pw.label = "Power";
        pw.icon = new PowerIcon();
        pw.power = true;
        allItems.add(pw);
        applyPage(-1);
    }

    // ---------------------------------------------------------------- paging

    private static final int COLS = 5, ROWS = 3, PAGE_SIZE = COLS * ROWS;
    private final List<AppItem> allItems = new ArrayList<>();
    private int page = 0;
    private LinearLayout dots;
    private long lastWheelPage = 0;

    private int pageCount() {
        return Math.max(1, (allItems.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** Shows the current page. selectIdx >= 0 also selects that tile (index within the page). */
    private void applyPage(final int selectIdx) {
        int pc = pageCount();
        if (page >= pc) page = pc - 1;
        if (page < 0) page = 0;
        items.clear();
        int from = page * PAGE_SIZE;
        items.addAll(allItems.subList(from, Math.min(allItems.size(), from + PAGE_SIZE)));
        adapter.notifyDataSetChanged();
        updateDots();
        if (selectIdx >= 0) {
            grid.post(() -> {
                grid.requestFocus();
                grid.setSelection(Math.max(0, Math.min(selectIdx, items.size() - 1)));
            });
        }
    }

    private void gotoPage(int p, int selectIdx) {
        if (p < 0 || p >= pageCount() || p == page) return;
        page = p;
        applyPage(selectIdx);
    }

    private void updateDots() {
        if (dots == null) return;
        dots.removeAllViews();
        int pc = pageCount();
        dots.setVisibility(pc > 1 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < pc; i++) {
            final int target = i;
            TextView d = text(18, i == page ? Color.WHITE : COL_DIM);
            d.setText(i == page ? "●" : "○");
            d.setPadding(dp(8), 0, dp(8), 0);
            d.setOnClickListener(v -> gotoPage(target, 0));
            dots.addView(d);
        }
    }

    /** Turns the page when the D-pad runs off an edge of the grid. Returns true if handled. */
    private boolean handlePagerKey(int keyCode) {
        int pos = Math.max(0, grid.getSelectedItemPosition());
        int col = pos % COLS, row = pos / COLS;
        int lastRow = (items.size() - 1) / COLS;
        boolean hasNext = page < pageCount() - 1, hasPrev = page > 0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (hasNext) { gotoPage(page + 1, 0); return true; }
                return false;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (hasPrev) { gotoPage(page - 1, 0); return true; }
                return false;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if ((col == COLS - 1 || pos == items.size() - 1) && hasNext) { gotoPage(page + 1, row * COLS); return true; }
                return false;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (col == 0 && hasPrev) { gotoPage(page - 1, row * COLS + COLS - 1); return true; }
                return false;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row == lastRow && hasNext) { gotoPage(page + 1, col); return true; }
                return false;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row == 0 && hasPrev) { gotoPage(page - 1, (ROWS - 1) * COLS + col); return true; }
                return false;
            default:
                return false;
        }
    }

    private void showPowerDialog() {
        final String[] choices = {"Shut down", "Restart", "Cancel"};
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Power")
                .setItems(choices, (d, which) -> {
                    if (which == 0) runPower("reboot -p", "Shutting down…");
                    else if (which == 1) runPower("reboot", "Restarting…");
                })
                .show();
    }

    /** Runs a power command through the box's own ADB service (see AdbShell). */
    private void runPower(final String cmd, String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                AdbShell.exec(cmd, 4000);
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(this,
                        "Power control needs network ADB (port 5555) turned on: " + e.getMessage(),
                        android.widget.Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() { return items.size(); }

        @Override
        public Object getItem(int i) { return items.get(i); }

        @Override
        public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout tile = new LinearLayout(MainActivity.this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(6), dp(8), dp(6), dp(8));
            tile.setLayoutParams(new GridView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(114)));
            tile.setBackground(rounded(COL_CARD, 12));
            AppItem it = items.get(pos);
            ImageView iv = new ImageView(MainActivity.this);
            iv.setImageDrawable(it.icon);
            tile.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
            TextView tv = text(16, Color.WHITE);
            tv.setText(it.label);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setPadding(0, dp(6), 0, 0);
            tile.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return tile;
        }
    }

    // ===================================================================== storage

    private void updateStorage() {
        try {
            android.os.StatFs st = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
            double free = st.getAvailableBytes() / 1073741824.0;
            double total = st.getTotalBytes() / 1073741824.0;
            boolean low = total > 0 && (free / total) < lowFraction;
            storageBar.usedFrac = total > 0 ? (float) (1.0 - free / total) : 0f;
            storageBar.fillColor = low ? COL_LOW : COL_OK;
            storageBar.invalidate();
            storageView.setTextColor(low ? COL_LOW : COL_DIM);
            storageView.setText(String.format(Locale.US, "%.1f GB free of %.0f GB%s",
                    free, total, low ? "  • low space" : ""));
        } catch (Exception e) {
            storageView.setText("");
        }
    }

    // ===================================================================== weather

    private void fetchWeather() {
        if (wxBusy) return;
        wxBusy = true;
        new Thread(() -> {
            try {
                String u = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset"
                        + "&forecast_days=4&timezone=" + tz;
                final JSONObject j = new JSONObject(Http.get(u, 10000));
                j.getJSONObject("current");
                prefs().edit().putString("json", j.toString()).apply();
                wxLast = System.currentTimeMillis();
                runOnUiThread(() -> { try { applyWeather(j); } catch (Exception ignored) { } });
            } catch (Exception ignored) {
                // keep last reading
            } finally {
                wxBusy = false;
            }
        }).start();
    }

    private void applyWeather(JSONObject j) throws Exception {
        lastWx = j;
        JSONObject cur = j.getJSONObject("current");
        wxTemp.setText(Math.round(cur.getDouble("temperature_2m")) + "°");
        wxIcon.setCode(cur.getInt("weather_code"), isNight(j.optJSONObject("daily")));
        wxDesc.setText(city + " · " + describe(cur.getInt("weather_code")));
        JSONObject d = j.optJSONObject("daily");
        if (d == null) return;
        wxRange.setText("H " + Math.round(d.getJSONArray("temperature_2m_max").getDouble(0))
                + "°  L " + Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)) + "°");

        JSONArray rise = d.optJSONArray("sunrise");
        JSONArray set = d.optJSONArray("sunset");
        java.text.SimpleDateFormat sin = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        java.text.SimpleDateFormat sout = new java.text.SimpleDateFormat("h:mm a", Locale.getDefault());
        if (rise != null && set != null && rise.length() > 0 && set.length() > 0) {
            wxSun.setText("☀ Rise " + sout.format(sin.parse(rise.getString(0)))
                    + "   Set " + sout.format(sin.parse(set.getString(0))));
        }
        JSONArray dates = d.optJSONArray("time");
        JSONArray codes = d.optJSONArray("weather_code");
        JSONArray hiA = d.optJSONArray("temperature_2m_max");
        JSONArray loA = d.optJSONArray("temperature_2m_min");
        java.text.SimpleDateFormat din = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        java.text.SimpleDateFormat dout = new java.text.SimpleDateFormat("EEE", Locale.getDefault());
        for (int i = 0; i < 3; i++) {
            int k = i + 1;
            if (dates != null && codes != null && hiA != null && loA != null && k < dates.length()) {
                fcDay[i].setText(dout.format(din.parse(dates.getString(k))));
                fcIcon[i].setCode(codes.getInt(k));
                fcRange[i].setText(Math.round(hiA.getDouble(k)) + "° / " + Math.round(loA.getDouble(k)) + "°");
            }
        }
    }

    /** True when now is outside today's sunrise..sunset window (in the configured timezone). */
    private boolean isNight(JSONObject d) {
        try {
            if (d == null) return false;
            JSONArray rise = d.optJSONArray("sunrise");
            JSONArray set = d.optJSONArray("sunset");
            if (rise == null || set == null || rise.length() == 0 || set.length() == 0) return false;
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone(tz));
            long now = System.currentTimeMillis();
            return now < f.parse(rise.getString(0)).getTime() || now > f.parse(set.getString(0)).getTime();
        } catch (Exception e) {
            return false;
        }
    }

    private static String describe(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: case 56: case 57: return "Drizzle";
            case 61: case 63: case 65: case 66: case 67: return "Rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: case 81: case 82: return "Showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm, hail";
            default: return "";
        }
    }

    // ===================================================================== stocks

    private void fetchStocks() {
        if (stocksBusy) return;
        stocksBusy = true;
        new Thread(() -> {
            try {
                Map<String, String> m = parseStocks(prefs().getString("stocks", ""));
                boolean any = false;
                for (String sym : symbols) {
                    try {
                        JSONObject meta = new JSONObject(Http.get(
                                "https://query1.finance.yahoo.com/v8/finance/chart/" + sym + "?range=1d&interval=1d", 8000))
                                .getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta");
                        m.put(sym, meta.getDouble("regularMarketPrice") + "|" + meta.getDouble("chartPreviousClose"));
                        any = true;
                    } catch (Exception ignored) {
                        // keep previous value for this symbol
                    }
                }
                if (any) {
                    StringBuilder out = new StringBuilder();
                    for (Map.Entry<String, String> e : m.entrySet()) {
                        out.append(e.getKey()).append('|').append(e.getValue()).append(';');
                    }
                    final String data = out.toString();
                    prefs().edit().putString("stocks", data).apply();
                    stocksLast = System.currentTimeMillis();
                    runOnUiThread(() -> applyStocks(data));
                }
            } finally {
                stocksBusy = false;
            }
        }).start();
    }

    private static Map<String, String> parseStocks(String data) {
        Map<String, String> m = new LinkedHashMap<>();
        if (data == null) return m;
        for (String part : data.split(";")) {
            String[] f = part.split("\\|");
            if (f.length == 3) m.put(f[0], f[1] + "|" + f[2]);
        }
        return m;
    }

    private void applyStocks(String data) {
        Map<String, String> m = parseStocks(data);
        for (int i = 0; i < symbols.size(); i++) {
            String v = m.get(symbols.get(i));
            if (v == null) continue;
            try {
                String[] f = v.split("\\|");
                double price = Double.parseDouble(f[0]);
                double prev = Double.parseDouble(f[1]);
                double pct = prev != 0 ? (price - prev) / prev * 100.0 : 0;
                SpannableStringBuilder sb = new SpannableStringBuilder(symbols.get(i));
                sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), 0);
                sb.setSpan(new ForegroundColorSpan(Color.WHITE), 0, sb.length(), 0);
                int start = sb.length();
                sb.append(String.format(Locale.US, " %.2f %+.1f%%", price, pct));
                sb.setSpan(new ForegroundColorSpan(pct >= 0 ? COL_UP : COL_DOWN), start, sb.length(), 0);
                stockViews[i].setText(sb);
            } catch (Exception ignored) {
            }
        }
    }

    // ===================================================================== currency

    private void fetchFx() {
        if (fxBusy) return;
        fxBusy = true;
        new Thread(() -> {
            try {
                String u = "https://api.frankfurter.app/latest?from=" + fxBase + "&to=" + TextUtils.join(",", fxList);
                final JSONObject j = new JSONObject(Http.get(u, 10000));
                j.getJSONObject("rates");
                prefs().edit().putString("fx", j.toString()).apply();
                fxLast = System.currentTimeMillis();
                runOnUiThread(() -> { try { applyFx(j); } catch (Exception ignored) { } });
            } catch (Exception ignored) {
            } finally {
                fxBusy = false;
            }
        }).start();
    }

    private void applyFx(JSONObject j) throws Exception {
        JSONObject r = j.getJSONObject("rates");
        SpannableStringBuilder sb = new SpannableStringBuilder("1 " + fxBase + " =");
        sb.setSpan(new ForegroundColorSpan(COL_DIM), 0, sb.length(), 0);
        for (String c : fxList) {
            if (!r.has(c)) continue;
            sb.append("    ");
            int s = sb.length();
            sb.append(c);
            sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), 0);
            sb.setSpan(new ForegroundColorSpan(Color.WHITE), s, sb.length(), 0);
            int v = sb.length();
            sb.append(String.format(Locale.US, " %.3f", r.getDouble(c)));
            sb.setSpan(new ForegroundColorSpan(Color.parseColor("#C9D3EE")), v, sb.length(), 0);
        }
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            java.text.SimpleDateFormat out = new java.text.SimpleDateFormat("d MMM", Locale.getDefault());
            int s = sb.length();
            sb.append("    ECB " + out.format(in.parse(j.getString("date"))));
            sb.setSpan(new ForegroundColorSpan(COL_DIM), s, sb.length(), 0);
        } catch (Exception ignored) {
        }
        fxView.setText(sb);
    }

    // ===================================================================== news

    private void fetchNews() {
        if (newsBusy) return;
        newsBusy = true;
        new Thread(() -> {
            try {
                String xml = Http.get(cfg.str("rss_url"), 10000);
                XmlPullParser p = Xml.newPullParser();
                p.setInput(new StringReader(xml));
                final List<String> t = new ArrayList<>();
                boolean inItem = false;
                int ev;
                while ((ev = p.next()) != XmlPullParser.END_DOCUMENT && t.size() < 15) {
                    if (ev == XmlPullParser.START_TAG) {
                        if ("item".equals(p.getName())) inItem = true;
                        else if (inItem && "title".equals(p.getName())) {
                            String s = p.nextText().trim();
                            if (!s.isEmpty()) t.add(s);
                        }
                    } else if (ev == XmlPullParser.END_TAG && "item".equals(p.getName())) {
                        inItem = false;
                    }
                }
                if (!t.isEmpty()) {
                    prefs().edit().putString("news", TextUtils.join("\n", t)).apply();
                    newsLast = System.currentTimeMillis();
                    runOnUiThread(() -> {
                        headlines.clear();
                        headlines.addAll(t);
                        newsIdx = 0;
                        showHeadline(false);
                    });
                }
            } catch (Exception ignored) {
            } finally {
                newsBusy = false;
            }
        }).start();
    }

    private void showHeadline(boolean fade) {
        if (headlines.isEmpty()) return;
        final String h = headlines.get(newsIdx % headlines.size());
        final SpannableStringBuilder sb = new SpannableStringBuilder(rssLabel + "   ");
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, rssLabel.length(), 0);
        sb.setSpan(new ForegroundColorSpan(COL_OK), 0, rssLabel.length(), 0);
        int s = sb.length();
        sb.append(h);
        sb.setSpan(new ForegroundColorSpan(Color.WHITE), s, sb.length(), 0);
        if (!fade) {
            newsView.setText(sb);
            return;
        }
        newsView.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            newsView.setText(sb);
            newsView.animate().alpha(1f).setDuration(250).start();
        }).start();
    }

    // ===================================================================== jellyfin

    private void fetchJfStatus() {
        if (jfBusy) return;
        jfBusy = true;
        new Thread(() -> {
            boolean up = false;
            String name = "";
            try {
                JSONObject j = new JSONObject(Http.get(jfUrl + "/System/Info/Public", 4000));
                name = j.optString("ServerName", "");
                up = true;
            } catch (Exception ignored) {
            }
            final boolean fUp = up;
            final String fName = name;
            jfLast = System.currentTimeMillis();
            runOnUiThread(() -> {
                SpannableStringBuilder sb = new SpannableStringBuilder("● ");
                sb.setSpan(new ForegroundColorSpan(fUp ? COL_UP : COL_LOW), 0, 1, 0);
                int s = sb.length();
                sb.append(fUp ? "Jellyfin online" : "Jellyfin offline");
                sb.setSpan(new ForegroundColorSpan(fUp ? COL_SUB : COL_LOW), s, sb.length(), 0);
                jfView.setText(sb);
            });
            jfBusy = false;
        }).start();
    }

    // ===================================================================== screensaver

    private void resetIdle() {
        idleH.removeCallbacksAndMessages(null);
        if (idleMs > 0 && !saverActive) idleH.postDelayed(this::startSaver, idleMs);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (saverActive) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                stopSaver(true);
                resetIdle();
            }
            return true; // the key that wakes the screen is not passed on
        }
        resetIdle();
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (saverActive) {
            stopSaver(true);
            resetIdle();
            return true;
        }
        resetIdle();
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (saverActive) {
            stopSaver(true);
            resetIdle();
            return true;
        }
        resetIdle();
        return super.dispatchGenericMotionEvent(e);
    }

    private List<String> localPhotos() {
        List<String> out = new ArrayList<>();
        File[] fs = new File(cfg.str("photos_dir")).listFiles();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName().toLowerCase(Locale.US);
                if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) {
                    out.add(f.getAbsolutePath());
                }
            }
        }
        Collections.sort(out);
        Collections.shuffle(out, new Random(7));
        return out;
    }

    private void startSaver() {
        if (saverActive) return;
        saverActive = true;
        saverGen++;
        saverIndex = 0;
        saver.setAlpha(0f);
        saver.setVisibility(View.VISIBLE);
        saver.animate().alpha(1f).setDuration(800).start();
        nextSlide();
    }

    private final Runnable nextSlideRun = this::nextSlide;

    private void nextSlide() {
        if (!saverActive) return;
        final int gen = saverGen;
        new Thread(() -> {
            Bitmap bm = null;
            String cap = "";
            try {
                List<String> locals = localPhotos();
                if (!locals.isEmpty()) {
                    bm = Http.decodeFile(locals.get(saverIndex % locals.size()), 1920);
                } else {
                    if (bing == null) {
                        List<String[]> l = new ArrayList<>();
                        JSONArray imgs = new JSONObject(Http.get(
                                "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt="
                                        + cfg.str("bing_market"), 10000)).getJSONArray("images");
                        for (int i = 0; i < imgs.length(); i++) {
                            JSONObject o = imgs.getJSONObject(i);
                            l.add(new String[]{"https://www.bing.com" + o.getString("url"), o.optString("copyright", "")});
                        }
                        bing = l;
                    }
                    if (!bing.isEmpty()) {
                        String[] e = bing.get(saverIndex % bing.size());
                        bm = Http.bitmap(e[0], 1920);
                        cap = e[1];
                    }
                }
            } catch (Throwable ignored) {
                // offline: show the clock on black
            }
            saverIndex++;
            final Bitmap fb = bm;
            final String fcap = cap;
            runOnUiThread(() -> {
                if (!saverActive || gen != saverGen) {
                    if (fb != null) fb.recycle();
                    return;
                }
                showSlide(fb, fcap);
                saverH.postDelayed(nextSlideRun, 14000);
            });
        }).start();
    }

    private void showSlide(Bitmap bm, String caption) {
        saverCaption.setText(caption);
        if (bm == null) return;
        final ImageView front = frontIsA ? saverA : saverB;
        final ImageView back = frontIsA ? saverB : saverA;
        frontIsA = !frontIsA;
        final Bitmap prev = (Bitmap) front.getTag();
        back.setImageBitmap(bm);
        back.setTag(bm);
        back.setAlpha(0f);
        back.setScaleX(1f);
        back.setScaleY(1f);
        back.bringToFront();
        back.animate().alpha(1f).setDuration(1500).start();
        ObjectAnimator.ofFloat(back, "scaleX", 1f, 1.08f).setDuration(15000).start();
        ObjectAnimator.ofFloat(back, "scaleY", 1f, 1.08f).setDuration(15000).start();
        front.animate().alpha(0f).setDuration(1500).withEndAction(() -> {
            front.setImageDrawable(null);
            front.setTag(null);
            if (prev != null && !prev.isRecycled()) prev.recycle();
        }).start();
    }

    private void stopSaver(boolean animate) {
        if (!saverActive) return;
        saverActive = false;
        saverGen++;
        saverH.removeCallbacksAndMessages(null);
        Runnable done = () -> {
            saver.setVisibility(View.GONE);
            for (ImageView v : new ImageView[]{saverA, saverB}) {
                Bitmap b = (Bitmap) v.getTag();
                v.setImageDrawable(null);
                v.setTag(null);
                v.setAlpha(0f);
                if (b != null && !b.isRecycled()) b.recycle();
            }
        };
        if (animate) saver.animate().alpha(0f).setDuration(400).withEndAction(done).start();
        else done.run();
    }
}
