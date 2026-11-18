package com.setupbox.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Song-location chooser for devices that have NO system folder chooser (some Google TV images). D-pad first: Up/Down, OK, Back.
 * It only shows the app's own folders (getExternalFilesDirs: internal storage and each USB drive / SD card): they need no permission and
 * work on Android 10 and on current Android. Level 1 = the storages, below that = folders; "Use this folder" returns the path (EXTRA_PATH).
 * Disk access (listing, free space) runs on a background thread; the main thread only draws.
 */
public class FolderChooserActivity extends Activity {
    static final String EXTRA_PATH = "path";
    static final String EXTRA_CURRENT = "current";
    private static final String TAG = "Karaoke";

    private static final class Row {
        final String text;
        final Runnable action;
        Row(String text, Runnable action) { this.text = text; this.action = action; }
    }

    private final List<File> roots = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private File dir;                 // null = the list of storages
    private File root;                // the storage `dir` is on
    private TextView title, hint;
    private ListView list;
    private ArrayAdapter<Row> adapter;
    private float px;                 // text size follows the screen height, not the density or the Android version
    private int gen;                  // a listing that finishes after the user moved on is dropped

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        px = Math.max(26f, getResources().getDisplayMetrics().heightPixels * 0.036f);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF101418);
        int pad = Math.round(px * 1.4f);
        box.setPadding(pad, Math.round(px), pad, Math.round(px));
        title = text(px * 1.25f, Color.WHITE);
        hint = text(px * 0.8f, 0xFFB8C2D6);
        hint.setPadding(0, Math.round(px * 0.3f), 0, Math.round(px * 0.5f));
        list = new ListView(this);
        list.setDivider(new ColorDrawable(0x22FFFFFF));
        list.setDividerHeight(1);
        list.setSelector(new ColorDrawable(0x55FFFFFF));
        list.setItemsCanFocus(false);
        adapter = new ArrayAdapter<Row>(this, 0, rows) {
            @Override public View getView(int pos, View v, ViewGroup parent) {
                TextView t = v instanceof TextView ? (TextView) v : text(px, Color.WHITE);
                t.setPadding(Math.round(px * 0.5f), Math.round(px * 0.45f), Math.round(px * 0.5f), Math.round(px * 0.45f));
                t.setText(getItem(pos).text);
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < rows.size() && rows.get(pos).action != null) rows.get(pos).action.run();
            }
        });
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));
        box.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(box);

        File[] dirs = getExternalFilesDirs(null);
        if (dirs != null) for (File f : dirs) if (f != null) roots.add(f);
        if (roots.isEmpty()) roots.add(getFilesDir());
        for (File r : roots) if (!r.isDirectory()) r.mkdirs();

        File cur = null;
        String c = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_CURRENT);
        if (c != null) {
            File f = new File(c);
            if (AppFolders.isAllowed(roots, f)) cur = f;
        }
        show(cur);
    }

    private TextView text(float sizePx, int color) {
        TextView t = new TextView(this);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTextColor(color);
        return t;
    }

    /** Shows the storages (dir == null) or the folders inside dir. Listing happens off the main thread. */
    private void show(final File target) {
        dir = target;
        root = target == null ? null : AppFolders.rootOf(roots, target);
        if (target != null && root == null) { dir = null; }       // never show anything outside the app's folders
        final int mine = ++gen;
        final File d = dir, rt = root;
        title.setText(d == null ? "Where are your songs?" : d.getName().isEmpty() ? d.getPath() : d.getName());
        hint.setText(d == null ? "Choose a storage. Songs are kept in this app's own folder there (no permission needed)."
                : d.getPath() + "\nOK opens a folder. Back goes up.");
        rows.clear();
        rows.add(new Row("Loading...", null));
        adapter.notifyDataSetChanged();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Row> out = new ArrayList<>();
                try {
                    if (d == null) {
                        for (final File r : roots) {
                            boolean removable = false;
                            try { removable = Environment.isExternalStorageRemovable(r); } catch (Exception ignored) { }
                            String free = "";
                            try { free = "  (" + gb(new StatFs(r.getPath()).getAvailableBytes()) + " free)"; } catch (Exception ignored) { }
                            String name = removable ? "USB drive / SD card" : "Internal storage";
                            out.add(new Row(name + free + (removable ? "\n    slower, and can disconnect during a song" : ""), new Runnable() {
                                @Override public void run() { show(r); }
                            }));
                        }
                    } else {
                        final File here = d;
                        out.add(new Row("[ Use this folder ]", new Runnable() { @Override public void run() { choose(here); } }));
                        File up = AppFolders.parentWithin(rt, d);
                        final File upF = up;
                        out.add(new Row(up == null ? "[ Other storage ]" : "..  (up)", new Runnable() {
                            @Override public void run() { show(upF); }     // null = the storage list
                        }));
                        boolean hasKaraoke = false;
                        for (final File sub : AppFolders.subDirs(d)) {
                            if (sub.getName().equals("Karaoke")) hasKaraoke = true;
                            out.add(new Row(sub.getName() + "/", new Runnable() { @Override public void run() { show(sub); } }));
                        }
                        if (!hasKaraoke && up == null) {          // offered only at a storage's top level
                            out.add(new Row("[ + New folder \"Karaoke\" here ]", new Runnable() {
                                @Override public void run() { createKaraoke(here); }
                            }));
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.w(TAG, "folder chooser listing failed: " + e);
                    out.clear();
                    out.add(new Row("Cannot read this folder. Back goes up.", null));
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (mine != gen || isFinishing()) return;
                        rows.clear();
                        rows.addAll(out);
                        adapter.notifyDataSetChanged();
                        list.requestFocus();
                        list.setSelection(0);
                    }
                });
            }
        }, "folder-list").start();
    }

    private void createKaraoke(final File parent) {
        new Thread(new Runnable() {
            @Override public void run() {
                final File f = new File(parent, "Karaoke");
                boolean ok = AppFolders.isAllowed(roots, f) && (f.isDirectory() || f.mkdir());
                if (!ok) android.util.Log.w(TAG, "could not create " + f);
                runOnUiThread(new Runnable() {
                    @Override public void run() { show(ok ? f : parent); }
                });
            }
        }, "folder-create").start();
    }

    private void choose(File f) {
        if (!AppFolders.isAllowed(roots, f)) return;               // defensive: the list only ever offers allowed folders
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_PATH, f.getAbsolutePath()));
        finish();
    }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_BACK) {
            if (dir == null) { setResult(RESULT_CANCELED); finish(); return true; }
            show(AppFolders.parentWithin(root, dir));               // at a storage's top, Back shows the storage list
            return true;
        }
        return super.onKeyDown(k, e);
    }

    private static String gb(long bytes) {
        double g = bytes / 1e9;
        return g >= 10 ? Math.round(g) + " GB" : String.format(java.util.Locale.US, "%.1f GB", g);
    }
}
