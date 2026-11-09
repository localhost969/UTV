package utv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

public final class Main extends Activity {
    private static final int REQ_OPEN = 1, REQ_SAVE_AS = 2;

    private Doc doc;
    private Ed ed;
    private FrameLayout root, body;
    private LinearLayout viewer, bar, findBar, home;
    private View divider;
    private TextView title, meta, count, openBtn;
    private android.widget.ImageView logo;
    private EditText query;
    private Ic saveIc, editIc, homeTheme, findGlyph;
    private View findSep;
    private TextView credit, devName;
    private android.animation.ValueAnimator shine;
    private final android.graphics.Matrix shineM = new android.graphics.Matrix();

    /** Flowing gradient through the developer name. */
    private void applyShine() {
        float w = devName.getWidth();
        if (w <= 0) return;
        int[] c = col();
        int[] g = {c[1], c[1], c[12], c[1], c[1]}; // theme text colour with a soft muted shine passing through
        devName.getPaint().setShader(new android.graphics.LinearGradient(0, 0, w * 1.5f, 0, g, null,
                android.graphics.Shader.TileMode.REPEAT));
        devName.invalidate();
    }
    private final ArrayList<Ic> icons = new ArrayList<>();
    private boolean dark, fromIntent, editing, dirty, muteWatch;
    private int loadSeq;

    // current document (in memory only, forgotten on exit)
    private Uri uri;
    private String name, src = "";   // src: content with '\n' line endings, used when not editing
    private int enc, bytes;          // 0 utf8, 1 utf8+bom, 2 utf16le, 3 utf16be, 4 windows-1252, 5 iso-8859-1
    private int eol;                 // dominant line ending: 0 LF, 1 CRLF, 2 CR
    private Runnable afterSaveAs;

    // edit-mode search state
    private int[] em = new int[0];
    private int emc, ecur = -1, eqlen;

    // 0 bg, 1 fg, 2 gutterBg, 3 lineNo, 4 barBg, 5 match, 6 currentMatch, 7 thumb,
    // 8 primaryBtn, 9 fieldBg, 10 border, 11 onPrimary, 12 muted, 13 selection
    // GitHub Primer light / dark
    static final int[] LIGHT = {0xFFFFFFFF, 0xFF1F2328, 0xFFFFFFFF, 0xFF6E7781, 0xFFF6F8FA, 0xFFFFF8C5, 0xFFF2CC60, 0x33000000,
            0xFF1F2328, 0xFFFFFFFF, 0xFFD0D7DE, 0xFFFFFFFF, 0xFF656D76, 0x5A8C959F};
    // Neutral graphite dark: no blue cast, soft contrast, amber find highlights
    static final int[] DARK = {0xFF171717, 0xFFE6E6E6, 0xFF171717, 0xFF5C5C5C, 0xFF1E1E1E, 0x45E8B339, 0xB0D9A02B, 0x38FFFFFF,
            0xFFEDEDED, 0xFF242424, 0xFF2B2B2B, 0xFF141414, 0xFF9B9B9B, 0x40FFFFFF};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dark = getSharedPreferences("p", MODE_PRIVATE).getBoolean("dark", false);
        buildUi();
        applyTheme();
        Intent i = getIntent();
        fromIntent = i != null && (i.getData() != null || Intent.ACTION_SEND.equals(i.getAction()));
        handle(i);
        if (!fromIntent) askFileAccess();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private Ic icon(int kind, View.OnClickListener l) {
        Ic v = new Ic(this, kind);
        v.setOnClickListener(l);
        icons.add(v);
        return v;
    }

    private TextView label(float size, boolean medium) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setSingleLine(true);
        return t;
    }

    private void buildUi() {
        int h = dp(56), ib = dp(48);
        root = new FrameLayout(this);

        // ---- home ----
        FrameLayout homeWrap = new FrameLayout(this);
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setGravity(Gravity.CENTER);
        logo = new android.widget.ImageView(this);
        logo.setImageResource(getResources().getIdentifier("logo", "drawable", getPackageName()));
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(190), -2);
        lp.bottomMargin = dp(48);
        home.addView(logo, lp);
        openBtn = label(16, false);
        openBtn.setText("Open file");
        openBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        openBtn.setGravity(Gravity.CENTER);
        openBtn.setOnClickListener(v -> open());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(208), dp(52));
        bp.bottomMargin = dp(64);
        home.addView(openBtn, bp);
        homeWrap.addView(home, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout cr = new LinearLayout(this);
        cr.setGravity(Gravity.CENTER);
        cr.setPadding(dp(16), dp(10), dp(16), dp(10));
        credit = label(14, false);
        credit.setText("Developed by ");
        devName = label(14, true);
        devName.setText("Vardan");
        cr.addView(credit);
        cr.addView(devName);
        cr.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/localhost969"))); }
            catch (Exception e) { toast("No browser found"); }
        });
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cl.bottomMargin = dp(16);
        homeWrap.addView(cr, cl);
        shine = android.animation.ValueAnimator.ofFloat(0f, 1.5f);
        shine.setDuration(3000);
        shine.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        shine.setInterpolator(new android.view.animation.LinearInterpolator());
        shine.addUpdateListener(an -> {
            float w = devName.getWidth();
            if (w <= 0) return;
            shineM.setTranslate((float) an.getAnimatedValue() * w, 0);
            if (devName.getPaint().getShader() != null) devName.getPaint().getShader().setLocalMatrix(shineM);
            devName.invalidate();
        });
        devName.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> applyShine());
        homeTheme = icon(dark ? Ic.SUN : Ic.MOON, v -> toggleTheme(v));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ib, ib, Gravity.TOP | Gravity.END);
        tp.setMargins(0, dp(4), dp(4), 0);
        homeWrap.addView(homeTheme, tp);
        root.addView(homeWrap, new FrameLayout.LayoutParams(-1, -1));

        // ---- viewer ----
        viewer = new LinearLayout(this);
        viewer.setOrientation(LinearLayout.VERTICAL);
        viewer.setVisibility(View.GONE);

        bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), 0, dp(4), 0);
        bar.addView(icon(Ic.CLOSE, v -> confirmClose()), new LinearLayout.LayoutParams(ib, ib));
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.VERTICAL);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        tb.setPadding(dp(8), 0, dp(4), 0);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        tb.setBackgroundResource(tv.resourceId);
        tb.setOnClickListener(v -> rename());
        title = label(16, true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        meta = label(12, false);
        tb.addView(title);
        tb.addView(meta);
        bar.addView(tb, new LinearLayout.LayoutParams(0, ib, 1));
        saveIc = icon(Ic.SAVE, v -> save(null));
        saveIc.setVisibility(View.GONE);
        bar.addView(saveIc, new LinearLayout.LayoutParams(ib, ib));
        editIc = icon(Ic.PEN, v -> setEditing(!editing, true));
        bar.addView(editIc, new LinearLayout.LayoutParams(ib, ib));
        bar.addView(icon(Ic.SEARCH, v -> toggleFind(true)), new LinearLayout.LayoutParams(ib, ib));
        bar.addView(icon(Ic.MORE, this::menu), new LinearLayout.LayoutParams(ib, ib));
        viewer.addView(bar, new LinearLayout.LayoutParams(-1, h));

        // Find bar: [✕] [ 🔍 query ……… 3 of 17 │ ⌃ ⌄ ]  — same height/close position as the main bar
        findBar = new LinearLayout(this);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setPadding(dp(4), 0, dp(12), 0);
        findBar.addView(icon(Ic.CLOSE, v -> toggleFind(false)), new LinearLayout.LayoutParams(ib, ib));
        LinearLayout field = new LinearLayout(this);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setTag("field");
        field.setPadding(dp(4), 0, dp(2), 0);
        findGlyph = new Ic(this, Ic.SEARCH);
        findGlyph.setScale(0.8f);
        field.addView(findGlyph, new LinearLayout.LayoutParams(dp(34), -1));
        query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("Find in file");
        query.setTextSize(15);
        query.setBackground(null);
        query.setPadding(0, 0, dp(4), 0);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        query.setOnEditorActionListener((v, a, e) -> { step(1); return true; });
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { search(s.toString()); }
        });
        field.addView(query, new LinearLayout.LayoutParams(0, -1, 1));
        count = label(12, false);
        count.setPadding(dp(4), 0, dp(8), 0);
        field.addView(count);
        findSep = new View(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(20));
        field.addView(findSep, sp);
        Ic up = icon(Ic.UP, v -> step(-1)), down = icon(Ic.DOWN, v -> step(1));
        up.setScale(0.85f); down.setScale(0.85f);
        field.addView(up, new LinearLayout.LayoutParams(dp(38), -1));
        field.addView(down, new LinearLayout.LayoutParams(dp(38), -1));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, dp(42), 1);
        fp.leftMargin = dp(4);
        findBar.addView(field, fp);
        findBar.setVisibility(View.GONE);
        viewer.addView(findBar, new LinearLayout.LayoutParams(-1, h));

        divider = new View(this);
        viewer.addView(divider, new LinearLayout.LayoutParams(-1, 1));
        body = new FrameLayout(this);
        doc = new Doc(this);
        body.addView(doc, new FrameLayout.LayoutParams(-1, -1));
        viewer.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(viewer, new FrameLayout.LayoutParams(-1, -1));

        root.setOnApplyWindowInsetsListener((v, in) -> {
            v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
            return in.consumeSystemWindowInsets();
        });
        setContentView(root);
    }

    private static GradientDrawable round(int fill, int stroke, int sw, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(r);
        if (sw > 0) g.setStroke(sw, stroke);
        return g;
    }

    private int[] col() { return dark ? DARK : LIGHT; }

    private void toggleTheme(View from) {
        dark = !dark;
        getSharedPreferences("p", MODE_PRIVATE).edit().putBoolean("dark", dark).apply();
        applyTheme();
    }

    private void applyTheme() {
        int[] c = col();
        doc.setColors(c);
        if (ed != null) ed.setColors(c);
        boolean viewing = viewer.getVisibility() == View.VISIBLE;
        int top = viewing ? c[4] : c[0];
        root.setBackgroundColor(top);
        getWindow().getDecorView().setBackgroundColor(top);
        bar.setBackgroundColor(c[4]);
        findBar.setBackgroundColor(c[4]);
        findBar.findViewWithTag("field").setBackground(round(dark ? 0xFF242424 : 0xFFFFFFFF, c[10], Math.max(1, dp(1)), dp(21)));
        findSep.setBackgroundColor(c[10]);
        credit.setTextColor(c[12]);
        devName.setTextColor(c[1]);
        applyShine();
        boolean homeOn = viewer.getVisibility() != View.VISIBLE;
        if (homeOn && !shine.isStarted()) shine.start(); else if (!homeOn) shine.cancel();
        findGlyph.setColor(c[12]);
        if (Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cur = new GradientDrawable();
            cur.setColor(c[1]);
            cur.setSize(Math.max(2, dp(2)), 1);
            query.setTextCursorDrawable(cur);
        }
        divider.setBackgroundColor(c[10]);
        ((View) home.getParent()).setBackgroundColor(c[0]);
        logo.setColorFilter(c[1]);
        openBtn.setTextColor(c[11]);
        openBtn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33808080), round(c[8], 0, 0, dp(10)), null));
        title.setTextColor(c[1]);
        meta.setTextColor(c[12]);
        count.setTextColor(c[12]);
        query.setTextColor(c[1]);
        query.setHintTextColor(c[12]);
        query.setHighlightColor(c[13]);
        for (Ic i : icons) { i.setColor(c[1]); i.setBg(i == homeTheme ? c[0] : c[4]); }
        homeTheme.setKind(dark ? Ic.SUN : Ic.MOON);
        Window w = getWindow();
        if (Build.VERSION.SDK_INT < 35) {
            w.setStatusBarColor(top);
            w.setNavigationBarColor(c[0]);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            int f = 0;
            if (!dark) {
                f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(f);
        }
    }

    private int style(String n) { return getResources().getIdentifier(n, "style", getPackageName()); }

    private Context dlgCtx() {
        return new ContextThemeWrapper(this, style(dark ? "DD" : "DL"));
    }

    // ---------- screens ----------

    private void showViewer(boolean on) {
        ((View) home.getParent()).setVisibility(on ? View.GONE : View.VISIBLE);
        viewer.setVisibility(on ? View.VISIBLE : View.GONE);
        applyTheme();
    }

    private void confirmClose() {
        if (!dirty) { closeFile(); return; }
        new AlertDialog.Builder(dlgCtx())
                .setTitle("Unsaved changes")
                .setMessage("Save changes to " + name + "?")
                .setPositiveButton("Save", (d, w) -> save(this::closeFile))
                .setNegativeButton("Discard", (d, w) -> closeFile())
                .setNeutralButton("Cancel", null)
                .show();
    }

    private void closeFile() {
        if (fromIntent) { finish(); return; }
        if (findBar.getVisibility() == View.VISIBLE) toggleFind(false);
        ++loadSeq;
        setEditing(false);
        uri = null; name = null; src = ""; dirty = false;
        doc.setText(Text.EMPTY);
        saveIc.setVisibility(View.GONE);
        showViewer(false);
    }

    // ---------- edit mode ----------

    private void setEditing(boolean on) { setEditing(on, false); }

    private void setEditing(boolean on, boolean announce) {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (on == editing) return;
        if (findBar.getVisibility() == View.VISIBLE) toggleFind(false);
        if (on) {
            if (ed == null) {
                ed = new Ed(this);
                ed.setColors(col());
                ed.addTextChangedListener(new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}
                    public void afterTextChanged(Editable s) { if (!muteWatch) setDirty(true); }
                });
                body.addView(ed, new FrameLayout.LayoutParams(-1, -1));
            }
            int line = doc.topLine();
            ed.setTextSize(TypedValue.COMPLEX_UNIT_PX, doc.textSize());
            muteWatch = true;
            ed.setText(src);
            muteWatch = false;
            ed.setVisibility(View.VISIBLE);
            doc.setVisibility(View.GONE);
            editing = true;
            ed.post(() -> {
                Layout l = ed.getLayout();
                if (l == null) return;
                int ln = Math.min(line, l.getLineCount() - 1);
                ed.setSelection(l.getLineStart(ln));
                ed.scrollTo(0, l.getLineTop(ln));
            });
            ed.requestFocus();
            if (src.length() > 2_000_000) toast("Large file — editing may be slow");
        } else {
            editing = false;
            if (ed != null) {
                Layout l = ed.getLayout();
                int line = l != null ? l.getLineForVertical(ed.getScrollY()) : 0;
                im.hideSoftInputFromWindow(ed.getWindowToken(), 0);
                src = ed.getText().toString();
                ed.setVisibility(View.GONE);
                muteWatch = true;
                ed.setText("");
                muteWatch = false;
                doc.setText(Text.of(src));
                doc.scrollToLine(line);
            }
            doc.setVisibility(View.VISIBLE);
        }
        editIc.setKind(editing ? Ic.TICK : Ic.PEN);
        updateMeta();
        if (name != null && viewer.getVisibility() == View.VISIBLE && announce) toast(editing ? "Edit mode enabled" : "Read only mode enabled");
    }

    private void setDirty(boolean d) {
        if (dirty == d) return;
        dirty = d;
        saveIc.setVisibility(d ? View.VISIBLE : View.GONE);
        updateMeta();
    }

    private String current() { return editing ? ed.getText().toString() : src; }

    // ---------- find ----------

    private void toggleFind(boolean show) {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        bar.setVisibility(show ? View.GONE : View.VISIBLE);
        findBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            query.requestFocus();
            query.selectAll();
            im.showSoftInput(query, 0);
            search(query.getText().toString());
        } else {
            im.hideSoftInputFromWindow(query.getWindowToken(), 0);
            search("");
            if (editing) ed.requestFocus();
        }
    }

    private void search(String q) {
        if (!editing) { doc.search(q); updateCount(); return; }
        emc = 0; ecur = -1; eqlen = q.length();
        if (eqlen > 0) {
            String s = ed.getText().toString(), ql = q.toLowerCase(Locale.ROOT);
            String sl = s.toLowerCase(Locale.ROOT);
            if (sl.length() == s.length()) {
                int i = 0, sel = ed.getSelectionStart();
                while ((i = sl.indexOf(ql, i)) >= 0 && emc < 200000) {
                    if (emc == em.length) em = java.util.Arrays.copyOf(em, Math.max(64, emc * 2));
                    if (ecur < 0 && i >= sel) ecur = emc;
                    em[emc++] = i;
                    i += eqlen;
                }
                if (emc > 0) { if (ecur < 0) ecur = 0; selectMatch(); }
            }
        }
        updateCount();
    }

    private void selectMatch() {
        ed.setSelection(em[ecur], em[ecur] + eqlen);
    }

    private void step(int d) {
        if (editing) {
            if (emc > 0) { ecur = (ecur + d + emc) % emc; selectMatch(); }
        } else doc.step(d);
        updateCount();
    }

    private void updateCount() {
        int n = editing ? emc : doc.matchCount();
        int cur = editing ? ecur : doc.current();
        count.setText(query.length() == 0 ? "" : n == 0 ? "No results" : (cur + 1) + " of " + n + (n >= 200000 ? "+" : ""));
    }

    // ---------- menu ----------

    private void menu(View anchor) {
        PopupMenu pm = new PopupMenu(new ContextThemeWrapper(this, style(dark ? "PD" : "PL")), anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Share");
        m.add(0, 6, 0, "Open with\u2026");
        m.add(0, 2, 0, "Rename");
        m.add(0, 3, 0, "Save as…");
        m.add(0, 4, 0, "Open file");
        m.add(0, 5, 0, dark ? "Light theme" : "Dark theme");
        pm.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: share(); break;
                case 6: openWith(); break;
                case 2: rename(); break;
                case 3: saveAs(null); break;
                case 4: if (dirty) toast("Save or discard changes first"); else open(); break;
                case 5: toggleTheme(anchor); break;
            }
            return true;
        });
        pm.show();
    }

    private void share() {
        String nm = name != null ? name : "untitled.txt";
        String mime = mimeFor(nm);
        Uri su = shareUri(nm, mime);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_STREAM, su);
        i.setClipData(android.content.ClipData.newRawUri(nm, su));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, null)); } catch (Exception e) { toast("Nothing to share with"); }
    }

    /** Real file when unchanged and reachable; otherwise a temp copy that is wiped when UTV closes. */
    private Uri shareUri(String nm, String mime) {
        byte[] d = encode(current());
        java.io.File f = null;
        String p = !dirty && uri != null && allFiles() ? realPath(uri) : null;
        if (p != null) f = new java.io.File(p);
        else {
            try {
                java.io.File dir = new java.io.File(getCacheDir(), "s");
                wipe(dir);
                dir.mkdirs();
                f = new java.io.File(dir, nm.replace('/', '_'));
                try (OutputStream o = new java.io.FileOutputStream(f)) { o.write(d); }
            } catch (Exception e) { f = null; }
        }
        return Share.set(d, nm, mime, f);
    }

    private static void wipe(java.io.File dir) {
        java.io.File[] fs = dir.listFiles();
        if (fs != null) for (java.io.File x : fs) x.delete();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) wipe(new java.io.File(getCacheDir(), "s"));
    }

    private void openWith() {
        String nm = name != null ? name : "untitled.txt";
        String mime = mimeFor(nm);
        if (mime.equals("application/octet-stream")) mime = "text/plain";
        Uri su = shareUri(nm, mime);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(su, mime);
        i.setClipData(android.content.ClipData.newRawUri(nm, su));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // keep UTV itself out of the list
        Intent c = Intent.createChooser(i, null);
        if (Build.VERSION.SDK_INT >= 24)
            c.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new android.content.ComponentName[]{new android.content.ComponentName(this, Main.class)});
        try { startActivity(c); } catch (Exception e) { toast("No app can open this file"); }
    }

    /** Real type for a file name; unknown extensions stay generic so nothing gets ".txt" appended. */
    private static String mimeFor(String nm) {
        int d = nm.lastIndexOf('.');
        if (d <= 0) return "application/octet-stream";
        String e = nm.substring(d + 1).toLowerCase(Locale.ROOT);
        String m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
        if (m == null) m = e.equals("md") || e.equals("markdown") ? "text/markdown" : "application/octet-stream";
        return m;
    }

    private void rename() {
        if (uri == null) { saveAs(null); return; }
        final EditText in = new EditText(dlgCtx());
        in.setSingleLine(true);
        in.setText(name);
        int dot = name.lastIndexOf('.');
        in.setSelection(0, dot > 0 ? dot : name.length());
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(in);
        AlertDialog d = new AlertDialog.Builder(dlgCtx())
                .setTitle("Rename")
                .setView(wrap)
                .setPositiveButton("Rename", (x, w) -> {
                    String nn = in.getText().toString().trim();
                    if (nn.isEmpty() || nn.equals(name) || nn.contains("/")) return;
                    doRename(nn);
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private String pendingName;
    private Uri pendingMedia;
    private static final int REQ_WRITE = 3;

    private boolean allFiles() {
        if (Build.VERSION.SDK_INT >= 30) return android.os.Environment.isExternalStorageManager();
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void askFileAccess() {
        if (allFiles()) return;
        android.content.SharedPreferences p = getSharedPreferences("p", MODE_PRIVATE);
        if (p.getBoolean("asked", false)) return;
        p.edit().putBoolean("asked", true).apply();
        new AlertDialog.Builder(dlgCtx())
                .setTitle("Allow file access")
                .setMessage("UTV needs access to your files to save and rename them in place.")
                .setPositiveButton("Allow", (d, w) -> requestFileAccess())
                .setNegativeButton("Not now", null)
                .show();
    }

    private void requestFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Exception ignored) {}
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE}, 9);
        }
    }

    /** Real filesystem path behind a uri, when it can be found. */
    private String realPath(Uri u) {
        try {
            if ("file".equals(u.getScheme())) return u.getPath();
            String auth = u.getAuthority();
            if (DocumentsContract.isDocumentUri(this, u) && "com.android.externalstorage.documents".equals(auth)) {
                String id = DocumentsContract.getDocumentId(u);
                int c = id.indexOf(':');
                String vol = id.substring(0, c), rel = id.substring(c + 1);
                return ("primary".equals(vol) ? android.os.Environment.getExternalStorageDirectory().getPath() : "/storage/" + vol) + "/" + rel;
            }
            if (DocumentsContract.isDocumentUri(this, u) && "com.android.providers.downloads.documents".equals(auth)) {
                String id = DocumentsContract.getDocumentId(u);
                if (id.startsWith("raw:")) return id.substring(4);
            }
            for (Uri q : new Uri[]{mediaUri(u), u}) {
                if (q == null) continue;
                try (Cursor c = getContentResolver().query(q, new String[]{"_data"}, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        String p = c.getString(0);
                        if (p != null && new java.io.File(p).exists()) return p;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void doRename(String nn) {
        if (allFiles()) {
            String p = realPath(uri);
            if (p != null) {
                java.io.File src = new java.io.File(p), dst = new java.io.File(src.getParentFile(), nn);
                if (dst.exists()) { toast("A file named " + nn + " already exists"); return; }
                if (src.renameTo(dst)) {
                    android.media.MediaScannerConnection.scanFile(this, new String[]{src.getPath(), dst.getPath()}, null, null);
                    renamed(Uri.fromFile(dst), nn);
                    return;
                }
            }
        }
        // The "Documents/Recent" category provider reports success without renaming, so skip it.
        if (!"com.android.providers.media.documents".equals(uri.getAuthority())) {
            try {
                Uri nu = DocumentsContract.renameDocument(getContentResolver(), uri, nn);
                renamed(nu != null ? nu : uri, nn);
                return;
            } catch (Exception ignored) {}
        }
        Uri m = mediaUri(uri);
        if (m != null) {
            if (mediaRename(m, nn)) { renamed(keepUri() ? uri : m, nn); return; }
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    java.util.List<Uri> l = new ArrayList<>();
                    l.add(m);
                    pendingName = nn; pendingMedia = m;
                    startIntentSenderForResult(android.provider.MediaStore.createWriteRequest(getContentResolver(), l).getIntentSender(), REQ_WRITE, null, 0, 0, 0);
                    return;
                } catch (Exception ignored) {}
            }
        }
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            java.io.File src = new java.io.File(uri.getPath()), dst = new java.io.File(src.getParentFile(), nn);
            if (!dst.exists() && src.renameTo(dst)) { renamed(Uri.fromFile(dst), nn); return; }
        }
        new AlertDialog.Builder(dlgCtx())
                .setNeutralButton(allFiles() ? null : "Allow file access", (d, w) -> requestFileAccess())
                .setTitle("Can't rename here")
                .setMessage("This location doesn't allow renaming. Save a copy as \u201C" + nn + "\u201D and remove the old file?")
                .setPositiveButton("Save copy", (d, w) -> {
                    renameFrom = uri;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mimeFor(nn));
                    i.putExtra(Intent.EXTRA_TITLE, nn);
                    try { startActivityForResult(i, REQ_SAVE_AS); } catch (Exception e) { toast("No file picker available"); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private Uri renameFrom;

    private boolean keepUri() {
        return "media".equals(uri.getAuthority()) || DocumentsContract.isDocumentUri(this, uri);
    }

    private boolean mediaRename(Uri m, String nn) {
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, nn);
            return getContentResolver().update(m, v, null, null) > 0 && nn.equals(nameOf(m));
        } catch (Exception e) { return false; }
    }

    private void renamed(Uri nu, String nn) {
        uri = nu; name = nn; title.setText(nn); toast("Renamed to " + nn);
    }

    /** Finds the MediaStore entry behind a content uri, if there is one. */
    private Uri mediaUri(Uri u) {
        String auth = u.getAuthority();
        if (auth == null) return null;
        if (auth.equals("media")) return u;
        try {
            if (DocumentsContract.isDocumentUri(this, u)) {
                String id = DocumentsContract.getDocumentId(u);
                int c = id.indexOf(':');
                String type = c > 0 ? id.substring(0, c) : "", num = c > 0 ? id.substring(c + 1) : id;
                if (auth.equals("com.android.providers.media.documents") && num.matches("\\d+")) {
                    Uri base = type.equals("image") ? android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            : type.equals("video") ? android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            : type.equals("audio") ? android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            : android.provider.MediaStore.Files.getContentUri("external");
                    return android.content.ContentUris.withAppendedId(base, Long.parseLong(num));
                }
                if (auth.equals("com.android.providers.downloads.documents") && id.startsWith("msf:"))
                    return android.content.ContentUris.withAppendedId(android.provider.MediaStore.Files.getContentUri("external"), Long.parseLong(id.substring(4)));
            }
        } catch (Exception ignored) {}
        // other apps' providers (file managers): match by name + size in MediaStore
        if (Build.VERSION.SDK_INT >= 29 && name != null) {
            try (Cursor c = getContentResolver().query(android.provider.MediaStore.Files.getContentUri("external"),
                    new String[]{"_id"}, "_display_name=? AND _size=?", new String[]{name, String.valueOf(bytes)}, null)) {
                if (c != null && c.getCount() == 1 && c.moveToFirst())
                    return android.content.ContentUris.withAppendedId(android.provider.MediaStore.Files.getContentUri("external"), c.getLong(0));
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ---------- save ----------

    private static Charset charset(int e) {
        switch (e) {
            case 2: return StandardCharsets.UTF_16LE;
            case 3: return StandardCharsets.UTF_16BE;
            case 4: return Charset.forName("windows-1252");
            case 5: return StandardCharsets.ISO_8859_1;
            default: return StandardCharsets.UTF_8;
        }
    }

    /** The encoding s can actually be written in: legacy 8-bit files move to UTF-8 once they hold text they can't store. */
    private int effectiveEnc(String s) {
        return enc < 4 || charset(enc).newEncoder().canEncode(s) ? enc : 0;
    }

    private byte[] encode(String s) { return encode(s, effectiveEnc(s)); }

    private byte[] encode(String s, int e) {
        if (eol == 1) s = s.replace("\n", "\r\n");
        else if (eol == 2) s = s.replace('\n', '\r');
        byte[] body = s.getBytes(charset(e));
        byte[] bom = e == 1 ? new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}
                : e == 2 ? new byte[]{(byte) 0xFF, (byte) 0xFE} : e == 3 ? new byte[]{(byte) 0xFE, (byte) 0xFF} : new byte[0];
        if (bom.length == 0) return body;
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    /** Adopts the encoding a successful save used; returns true if it differs from the file's previous one. */
    private boolean adoptEnc(int e) {
        if (e == enc) return false;
        enc = e;
        updateMeta();
        return true;
    }

    private boolean write(Uri u, byte[] data) {
        if (writeUri(u, data)) return true;
        String p = allFiles() ? realPath(u) : null;
        if (p == null) return false;
        try (OutputStream o = new java.io.FileOutputStream(p)) { o.write(data); } catch (Exception e) { return false; }
        android.media.MediaScannerConnection.scanFile(this, new String[]{p}, null, null);
        return true;
    }

    private boolean writeUri(Uri u, byte[] data) {
        for (String mode : new String[]{"wt", "w"}) {
            try (OutputStream o = getContentResolver().openOutputStream(u, mode)) {
                if (o == null) continue;
                o.write(data);
                return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void save(Runnable after) {
        if (uri == null) { saveAs(after); return; }
        String s = current();
        int e = effectiveEnc(s);
        byte[] data = encode(s, e);
        if (write(uri, data)) {
            if (!editing) src = s;
            bytes = data.length;
            setDirty(false);
            toast(adoptEnc(e) ? "Saved as UTF-8 (text no longer fits the original encoding)" : "Saved");
            if (after != null) after.run();
        } else {
            toast("No permission to write here — choose where to save");
            saveAs(after);
        }
    }

    private void saveAs(Runnable after) {
        afterSaveAs = after;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        String nm = name != null ? name : "untitled.txt";
        i.setType(mimeFor(nm));
        i.putExtra(Intent.EXTRA_TITLE, nm);
        try { startActivityForResult(i, REQ_SAVE_AS); } catch (Exception e) { toast("No file picker available"); }
    }

    // ---------- open / load ----------

    @Override
    public void onBackPressed() {
        if (findBar.getVisibility() == View.VISIBLE) toggleFind(false);
        else if (viewer.getVisibility() == View.VISIBLE) confirmClose();
        else super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (e.isCtrlPressed()) {
            if (code == KeyEvent.KEYCODE_F && viewer.getVisibility() == View.VISIBLE) { toggleFind(true); return true; }
            if (code == KeyEvent.KEYCODE_S && viewer.getVisibility() == View.VISIBLE) { save(null); return true; }
            if (code == KeyEvent.KEYCODE_O && !dirty) { open(); return true; }
        }
        return super.onKeyDown(code, e);
    }

    /** Picker filter: text, code and config files; unknown types (e.g. .tsx, .env) come through as octet-stream.
     *  Images, video, audio, PDFs, Office files, archives and APKs are hidden. */
    private static final String[] TEXT_TYPES = {
            "text/*", "application/octet-stream", "application/json", "application/ld+json", "application/x-ndjson",
            "application/xml", "application/xhtml+xml", "application/rss+xml", "application/atom+xml",
            "application/javascript", "application/x-javascript", "application/ecmascript", "application/typescript",
            "application/x-sh", "application/x-shellscript", "application/x-csh", "application/x-yaml", "application/yaml",
            "application/toml", "application/sql", "application/x-sql", "application/x-python", "application/x-python-code",
            "application/x-php", "application/x-httpd-php", "application/x-perl", "application/x-ruby",
            "application/x-tex", "application/x-latex", "application/x-subrip", "application/x-desktop",
            "application/x-wine-extension-ini", "application/x-ipynb+json", "application/graphql", "application/x-empty"};

    private void open() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, TEXT_TYPES);
        if (Build.VERSION.SDK_INT >= 26)
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri("com.android.providers.downloads.documents", "downloads"));
        try { startActivityForResult(i, REQ_OPEN); } catch (Exception e) {
            i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, TEXT_TYPES);
            try { startActivityForResult(i, REQ_OPEN); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_WRITE) {
            if (res == RESULT_OK && pendingMedia != null && mediaRename(pendingMedia, pendingName)) renamed(keepUri() ? uri : pendingMedia, pendingName);
            else if (res == RESULT_OK) toast("Can't rename this file");
            pendingMedia = null;
            return;
        }
        Uri u = res == RESULT_OK && data != null ? data.getData() : null;
        if (u == null) { afterSaveAs = null; renameFrom = null; return; }
        if (req == REQ_OPEN) {
            fromIntent = false;
            load(u);
        } else if (req == REQ_SAVE_AS) {
            String cur = current();
            int e = effectiveEnc(cur);
            byte[] d = encode(cur, e);
            if (write(u, d)) {
                boolean widened = adoptEnc(e);
                uri = u;
                name = nameOf(u);
                bytes = d.length;
                if (!editing) src = current();
                title.setText(name);
                setDirty(false);
                updateMeta();
                if (renameFrom != null) {
                    boolean del = false;
                    try { del = DocumentsContract.deleteDocument(getContentResolver(), renameFrom); } catch (Exception ignored) {}
                    toast(del ? "Renamed to " + name : "Saved as " + name + " (old file couldn't be removed)");
                    renameFrom = null;
                } else toast(widened ? "Saved as UTF-8 (text no longer fits the original encoding)" : "Saved");
                Runnable r = afterSaveAs;
                afterSaveAs = null;
                if (r != null) r.run();
            } else toast("Couldn't save file");
        }
    }

    private void handle(Intent i) {
        if (i == null) return;
        Uri u = i.getData();
        if (u == null && Intent.ACTION_SEND.equals(i.getAction())) {
            u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u == null) {
                CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (t != null) {
                    byte[] d = t.toString().getBytes(StandardCharsets.UTF_8);
                    int[] info = new int[2];
                    String s = decode(d, info);
                    enc = 0; eol = info[1]; uri = null;
                    show("untitled.txt", s, Text.of(s), d.length);
                    return;
                }
            }
        }
        if (u != null) load(u);
    }

    private void load(final Uri u) {
        final int seq = ++loadSeq;
        if (findBar.getVisibility() == View.VISIBLE) toggleFind(false);
        setEditing(false);
        setDirty(false);
        title.setText("Loading…");
        meta.setText("");
        doc.setText(Text.EMPTY);
        showViewer(true);
        new Thread(() -> {
            String nm = null, err = null, s = null;
            byte[] data = null;
            int[] info = new int[2];
            Text tx = null;
            try {
                nm = nameOf(u);
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    if (in == null) throw new java.io.IOException("Cannot open file");
                    byte[] buf = new byte[Math.max(65536, Math.min(in.available() + 1, 1 << 28))];
                    int n = 0, r;
                    while ((r = in.read(buf, n, buf.length - n)) > 0) {
                        n += r;
                        if (n == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                    }
                    data = buf.length == n ? buf : java.util.Arrays.copyOf(buf, n);
                }
                s = decode(data, info);
                tx = Text.of(s);
            } catch (OutOfMemoryError e) {
                err = "File too large";
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            final Text ft = tx;
            final String fn = nm != null ? nm : "Untitled", fe = err, fs = s;
            final int size = data != null ? data.length : 0;
            runOnUiThread(() -> {
                if (seq != loadSeq) return;
                if (ft == null) {
                    // nothing is open any more: don't leave the previous file's uri/text behind for edit and save to act on
                    uri = null; name = null; src = "";
                    if (fromIntent) { title.setText(fn); meta.setText(fe); }
                    else { showViewer(false); toast("Couldn't open " + fn + ": " + fe); }
                    return;
                }
                uri = u;
                enc = info[0];
                eol = info[1];
                show(fn, fs, ft, size);
            });
        }).start();
    }

    private void show(String nm, String s, Text t, int size) {
        showViewer(true);
        name = nm;
        src = s;
        bytes = size;
        title.setText(nm);
        doc.setText(t);
        updateMeta();
    }

    private void updateMeta() {
        if (name == null) return;
        String e = enc == 2 ? "UTF-16LE" : enc == 3 ? "UTF-16BE" : enc == 4 ? "Windows-1252" : enc == 5 ? "ISO-8859-1" : "UTF-8";
        String m = editing ? "EDIT MODE" : String.format(Locale.US, "READ ONLY · %,d lines · %s", doc.lineCount(), human(bytes));
        meta.setText(m + " · " + e + (eol == 1 ? " · CRLF" : eol == 2 ? " · CR" : "") + (dirty ? " · Modified" : ""));
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1 << 20) return String.format(Locale.US, "%.1f KB", b / 1024f);
        return String.format(Locale.US, "%.1f MB", b / 1048576f);
    }

    private String nameOf(Uri u) {
        if ("content".equals(u.getScheme())) {
            try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) { String s = c.getString(0); if (s != null) return s; }
            } catch (Exception ignored) {}
        }
        String s = u.getLastPathSegment();
        return s != null ? s : u.toString();
    }

    /** Decodes bytes; info[0] = encoding, info[1] = dominant line ending (0 LF, 1 CRLF, 2 CR). Returns text with '\n' line endings.
     *  Bytes that aren't valid UTF-8 are read as a legacy 8-bit encoding so saving writes the same bytes back. */
    static String decode(byte[] b, int[] info) {
        int n = b.length;
        String s;
        if (n >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) { info[0] = 2; s = new String(b, 2, n - 2, StandardCharsets.UTF_16LE); }
        else if (n >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) { info[0] = 3; s = new String(b, 2, n - 2, StandardCharsets.UTF_16BE); }
        else if (n >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) { info[0] = 1; s = new String(b, 3, n - 3, StandardCharsets.UTF_8); }
        else {
            info[0] = 0;
            s = strictUtf8(b);
            if (s == null) {
                Charset w = charset(4);
                s = new String(b, w);
                info[0] = 4;
                // windows-1252 leaves a few bytes undefined; ISO-8859-1 maps all 256, so it always round-trips
                if (!java.util.Arrays.equals(s.getBytes(w), b)) { s = new String(b, StandardCharsets.ISO_8859_1); info[0] = 5; }
            }
        }
        if (s.indexOf('\r') < 0) return s;
        int crlf = 0, cr = 0, lf = 0;
        for (int i = 0, len = s.length(); i < len; i++) {
            char ch = s.charAt(i);
            if (ch == '\r') { if (i + 1 < len && s.charAt(i + 1) == '\n') { crlf++; i++; } else cr++; }
            else if (ch == '\n') lf++;
        }
        info[1] = crlf > lf && crlf >= cr ? 1 : cr > lf && cr > crlf ? 2 : 0;
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** UTF-8 text, or null if the bytes aren't valid UTF-8. */
    private static String strictUtf8(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) { return null; }
    }

    /** Edit mode: plain EditText, no wrapping, line-number gutter drawn on the left. */
    static final class Ed extends EditText {
        private final Paint np = new Paint(Paint.ANTI_ALIAS_FLAG), fp = new Paint();
        private final float density;
        private int[] c = LIGHT;
        private int digits = -1;
        private final char[] num = new char[12];

        Ed(Context ctx) {
            super(ctx);
            density = getResources().getDisplayMetrics().density;
            setTypeface(Typeface.MONOSPACE);
            np.setTypeface(Typeface.MONOSPACE);
            np.setTextAlign(Paint.Align.RIGHT);
            setGravity(Gravity.TOP | Gravity.START);
            setHorizontallyScrolling(true);
            setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            setVerticalScrollBarEnabled(true);
            setLineSpacing(0, 1.15f);
        }

        void setColors(int[] c) {
            this.c = c;
            setBackgroundColor(c[0]);
            setTextColor(c[1]);
            setHighlightColor(c[13]);
            np.setColor(c[3]);
            if (Build.VERSION.SDK_INT >= 29) {
                GradientDrawable cur = new GradientDrawable();
                cur.setColor(c[1]);
                cur.setSize(Math.max(2, Math.round(2 * density)), 1);
                setTextCursorDrawable(cur);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            Layout l = getLayout();
            int lines = l != null ? l.getLineCount() : 1;
            int d = 2;
            for (int n = lines; n >= 100; n /= 10) d++;
            np.setTextSize(getTextSize());
            float cw = np.measureText("0");
            int pad = Math.round(6 * density);
            int gw = Math.round(d * cw + 2 * pad);
            if (d != digits) { digits = d; setPadding(gw + pad, pad, pad * 3, pad * 8); }
            super.onDraw(cv);
            if (l == null) return;
            int sx = getScrollX(), sy = getScrollY(), top = getExtendedPaddingTop();
            fp.setColor(c[2]);
            cv.drawRect(sx, sy, sx + gw, sy + getHeight(), fp);
            fp.setColor(c[10]);
            cv.drawRect(sx + gw - Math.max(1, density * 0.75f), sy, sx + gw, sy + getHeight(), fp);
            int first = l.getLineForVertical(Math.max(0, sy - top)), last = l.getLineForVertical(sy - top + getHeight());
            for (int i = first; i <= last && i < lines; i++) {
                int n = i + 1, k = num.length;
                do { num[--k] = (char) ('0' + n % 10); n /= 10; } while (n > 0);
                cv.drawText(num, k, num.length - k, sx + gw - pad, top + l.getLineBaseline(i), np);
            }
        }
    }

    /** Stroke-drawn icons; no image resources. */
    static final class Ic extends View {
        static final int CLOSE = 0, SEARCH = 1, PEN = 2, THEME = 3, UP = 4, DOWN = 5, EYE = 6, SAVE = 7, MORE = 8, TICK = 9, SUN = 10, MOON = 11;
        private int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Ic(Context c, int kind) {
            super(c);
            this.kind = kind;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        private int bg;
        void setColor(int c) { p.setColor(c); invalidate(); }
        void setBg(int c) { bg = c; invalidate(); }
        private float scale = 1f;
        void setScale(float f) { scale = f; invalidate(); }
        /** Icon morph: old glyph spins and shrinks away, new one pops in with overshoot. */
        void setKind(int k) {
            if (k == kind) return;
            animate().cancel();
            animate().rotation(-90).scaleX(0f).scaleY(0f).alpha(0f).setDuration(130)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        kind = k;
                        setRotation(90);
                        invalidate();
                        animate().rotation(0).scaleX(1f).scaleY(1f).alpha(1f).setDuration(380)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
                    }).start();
        }

        /** Theme toggle: the half-filled disc flips 180° with a little bounce. */
        void spin() {
            animate().cancel();
            setRotation(0);
            animate().rotation(180).scaleX(1f).scaleY(1f).setDuration(480)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f))
                    .withEndAction(() -> { setRotation(0); flip = !flip; invalidate(); }).start();
        }

        private boolean flip;
        private float slash; // 0 = plain pen, 1 = crossed pen
        private android.animation.ValueAnimator slashAnim;

        /** Pen ⇄ crossed pen: the strike line draws itself across (or retracts). */
        void setSlashed(boolean on) {
            float target = on ? 1f : 0f;
            if (slashAnim != null) slashAnim.cancel();
            slashAnim = android.animation.ValueAnimator.ofFloat(slash, target);
            slashAnim.setDuration(260);
            slashAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
            slashAnim.addUpdateListener(a -> { slash = (float) a.getAnimatedValue(); invalidate(); });
            slashAnim.start();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) animate().scaleX(0.82f).scaleY(0.82f).setDuration(90).start();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)
                animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new android.view.animation.OvershootInterpolator(4f)).start();
            return super.onTouchEvent(e);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float s = getResources().getDisplayMetrics().density;
            cv.save();
            s *= scale;
            cv.translate(getWidth() / 2f - 12 * s, getHeight() / 2f - 12 * s);
            cv.scale(s, s);
            p.setStrokeWidth(1.8f);
            p.setStyle(Paint.Style.STROKE);
            path.rewind();
            switch (kind) {
                case CLOSE: cv.drawLine(6, 6, 18, 18, p); cv.drawLine(18, 6, 6, 18, p); break;
                case SEARCH: cv.drawCircle(10.5f, 10.5f, 6, p); cv.drawLine(15, 15, 20, 20, p); break;
                case PEN:
                    path.moveTo(4, 20); path.lineTo(4.5f, 16); path.lineTo(15.5f, 5); path.lineTo(19, 8.5f);
                    path.lineTo(8, 19.5f); path.close();
                    cv.drawPath(path, p); cv.drawLine(13, 7.5f, 16.5f, 11, p);
                    if (slash > 0) {
                        float x = 3 + 18 * slash;
                        // gap in the icon colour's background so the strike reads clearly
                        Paint.Style st = p.getStyle();
                        int c = p.getColor();
                        p.setColor(bg); p.setStrokeWidth(4.2f);
                        cv.drawLine(3, 3, x, 3 + 18 * slash, p);
                        p.setColor(c); p.setStrokeWidth(1.8f); p.setStyle(st);
                        cv.drawLine(3, 3, x, 3 + 18 * slash, p);
                    }
                    break;
                case EYE:
                    path.moveTo(2.5f, 12); path.quadTo(12, 1, 21.5f, 12); path.quadTo(12, 23, 2.5f, 12); path.close();
                    cv.drawPath(path, p); cv.drawCircle(12, 12, 3, p); break;
                case SAVE:
                    cv.drawLine(12, 4, 12, 14.5f, p);
                    path.moveTo(7.5f, 10.5f); path.lineTo(12, 15); path.lineTo(16.5f, 10.5f);
                    path.moveTo(4.5f, 15); path.lineTo(4.5f, 19.5f); path.lineTo(19.5f, 19.5f); path.lineTo(19.5f, 15);
                    cv.drawPath(path, p); break;
                case TICK:
                    p.setStrokeWidth(2.2f);
                    path.moveTo(4.5f, 12.5f); path.lineTo(9.5f, 17.5f); path.lineTo(19.5f, 6.5f); cv.drawPath(path, p); break;
                case SUN:
                    cv.drawCircle(12, 12, 4.2f, p);
                    for (int k = 0; k < 8; k++) {
                        double a = k * Math.PI / 4;
                        float cx = (float) Math.cos(a), sy = (float) Math.sin(a);
                        cv.drawLine(12 + cx * 7, 12 + sy * 7, 12 + cx * 9.5f, 12 + sy * 9.5f, p);
                    }
                    break;
                case MOON: {
                    Path o = new Path();
                    path.addCircle(12, 12, 8.2f, Path.Direction.CW);
                    o.addCircle(16.5f, 8.2f, 7f, Path.Direction.CW);
                    path.op(o, Path.Op.DIFFERENCE);
                    cv.drawPath(path, p); break;
                }
                case THEME:
                    cv.drawCircle(12, 12, 8, p);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawArc(4, 4, 20, 20, flip ? 90 : -90, 180, true, p); break;
                case UP: path.moveTo(6, 15); path.lineTo(12, 9); path.lineTo(18, 15); cv.drawPath(path, p); break;
                case DOWN: path.moveTo(6, 9); path.lineTo(12, 15); path.lineTo(18, 9); cv.drawPath(path, p); break;
                case MORE:
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(12, 5.5f, 1.7f, p); cv.drawCircle(12, 12, 1.7f, p); cv.drawCircle(12, 18.5f, 1.7f, p); break;
            }
            cv.restore();
        }
    }

    /** Display layout: tabs -> 4 spaces, line start index. Input uses '\n' line endings. */
    static final class Text {
        static final Text EMPTY = new Text(new char[0], 0, new int[]{0, 0}, 1, 0, "", false);
        final char[] c;
        final int len;
        final int[] ls;
        final int lines, maxCols;
        private final String raw;       // the text as given, with its tabs
        private final boolean hasTabs;

        Text(char[] c, int len, int[] ls, int lines, int maxCols, String raw, boolean hasTabs) {
            this.c = c; this.len = len; this.ls = ls; this.lines = lines; this.maxCols = maxCols;
            this.raw = raw; this.hasTabs = hasTabs;
        }

        static Text of(String s) {
            int sl = s.length(), tabs = 0, nl = 1;
            for (int i = 0; i < sl; i++) {
                char ch = s.charAt(i);
                if (ch == '\t') tabs++;
                else if (ch == '\n') nl++;
            }
            char[] out = new char[sl + tabs * 3];
            int[] ls = new int[nl + 1];
            int o = 0, line = 0, col = 0, max = 0;
            for (int i = 0; i < sl; i++) {
                char ch = s.charAt(i);
                if (ch == '\n') {
                    out[o++] = '\n';
                    if (col > max) max = col;
                    col = 0;
                    ls[++line] = o;
                } else if (ch == '\t') {
                    int sp = 4 - (col & 3);
                    for (int k = 0; k < sp; k++) out[o++] = ' ';
                    col += sp;
                } else {
                    out[o++] = ch;
                    col++;
                }
            }
            if (col > max) max = col;
            return new Text(out, o, ls, line + 1, max, s, tabs > 0);
        }

        /** The original text behind display range [a, b): tabs come back as tabs. A tab the range only
         *  partly covers is included whole. */
        String slice(int a, int b) {
            if (!hasTabs) return new String(c, a, b - a);
            int line = lineOf(a), r = 0;
            for (int i = 0; i < line; i++) r = raw.indexOf('\n', r) + 1;
            StringBuilder sb = new StringBuilder(b - a);
            int o = ls[line], col = 0;
            for (; r < raw.length() && o < b; r++) {
                char ch = raw.charAt(r);
                int w = 1;
                if (ch == '\n') col = 0;
                else if (ch == '\t') { w = 4 - (col & 3); col += w; }
                else col++;
                if (o + w > a) sb.append(ch);
                o += w;
            }
            return sb.toString();
        }

        int lineEnd(int i) { return i + 1 < lines ? ls[i + 1] - 1 : len; }

        int lineOf(int off) {
            int lo = 0, hi = lines - 1;
            while (lo < hi) {
                int m = (lo + hi + 1) >>> 1;
                if (ls[m] <= off) lo = m; else hi = m - 1;
            }
            return lo;
        }
    }

    /** Read mode: virtualized monospace view; draws only visible lines/columns. */
    static final class Doc extends View {
        private static final int MAX_MATCHES = 200000;
        private Text t = Text.EMPTY;
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG), np = new Paint(Paint.ANTI_ALIAS_FLAG), fp = new Paint();
        private final OverScroller sc;
        private final GestureDetector gd;
        private final ScaleGestureDetector sg;
        private final float density;
        private int[] col = LIGHT;
        private float size, lineH, charW, asc, gutter, pad;
        private int sx, sy;
        private char[] lower;
        private int[] m = new int[0];
        private int mc, cur = -1, qlen;
        private boolean dragThumb;
        // read-mode selection
        private int selA = -1, selB = -1, anchorA, anchorB, dragSel; // dragSel: 1 long-press extend, 2 start handle, 3 end handle
        private android.view.ActionMode am;
        private final android.graphics.RectF tmpR = new android.graphics.RectF();
        private final char[] num = new char[12];

        Doc(Context ctx) {
            super(ctx);
            density = getResources().getDisplayMetrics().density;
            float sd = getResources().getDisplayMetrics().scaledDensity;
            tp.setTypeface(Typeface.MONOSPACE);
            np.setTypeface(Typeface.MONOSPACE);
            np.setTextAlign(Paint.Align.RIGHT);
            pad = 6 * density;
            sc = new OverScroller(ctx);
            setSize(13 * sd);
            gd = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { sc.forceFinished(true); return true; }
                @Override public boolean onSingleTapUp(MotionEvent e) { clearSel(); return true; }
                @Override public void onLongPress(MotionEvent e) {
                    if (t.len == 0) return;
                    int o = offsetAt(e.getX(), e.getY());
                    int a = o, b = o;
                    while (a > 0 && isWord(t.c[a - 1])) a--;
                    while (b < t.len && isWord(t.c[b])) b++;
                    if (a == b) { if (b < t.len && t.c[b] != '\n') b++; else if (a > 0) a--; }
                    selA = anchorA = a; selB = anchorB = b;
                    dragSel = 1;
                    hideActions();
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    invalidate();
                }
                @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    scrollTo2(sx + Math.round(dx), sy + Math.round(dy)); return true;
                }
                @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    sc.fling(sx, sy, -Math.round(vx), -Math.round(vy), 0, maxX(), 0, maxY());
                    postInvalidateOnAnimation(); return true;
                }
            });
            sg = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    float topLine = (sy + d.getFocusY()) / lineH;
                    float ns = Math.max(6 * sd, Math.min(48 * sd, size * d.getScaleFactor()));
                    if (ns != size) {
                        setSize(ns);
                        scrollTo2(sx, Math.round(topLine * lineH - d.getFocusY()));
                    }
                    return true;
                }
            });
            setFocusable(true);
        }

        private void setSize(float s) {
            size = s;
            tp.setTextSize(s);
            np.setTextSize(s);
            Paint.FontMetrics fm = tp.getFontMetrics();
            lineH = (float) Math.ceil(fm.descent - fm.ascent + s * 0.15f);
            asc = -fm.ascent + s * 0.075f;
            charW = tp.measureText("M");
            calcGutter();
            invalidate();
        }

        private void calcGutter() {
            int d = 1;
            for (int n = t.lines; n >= 10; n /= 10) d++;
            gutter = Math.max(d, 2) * charW + 2 * pad;
        }

        void setColors(int[] c) {
            col = c;
            tp.setColor(c[1]);
            np.setColor(c[3]);
            invalidate();
        }

        void setText(Text nt) {
            selA = selB = -1;
            hideActions();
            t = nt;
            lower = null;
            mc = 0; cur = -1; qlen = 0;
            sx = sy = 0;
            sc.forceFinished(true);
            calcGutter();
            invalidate();
        }

        int matchCount() { return mc; }
        int current() { return cur; }
        boolean capped() { return mc >= MAX_MATCHES; }
        int topLine() { return Math.max(0, Math.min(t.lines - 1, (int) (sy / lineH))); }
        int lineCount() { return t.lines; }
        float textSize() { return size; }
        void scrollToLine(int line) { post(() -> scrollTo2(sx, Math.round(line * lineH))); }

        void search(String q) {
            mc = 0; cur = -1; qlen = q.length();
            if (qlen == 0 || t.len == 0) { invalidate(); return; }
            if (lower == null) {
                lower = new char[t.len];
                for (int i = 0; i < t.len; i++) lower[i] = Character.toLowerCase(t.c[i]);
            }
            // same per-char folding as `lower`, so the query matches in every locale (String.toLowerCase()
            // follows the device language: "I" becomes dotless "ı" in Turkish) and keeps length qlen
            char[] p = new char[qlen];
            for (int k = 0; k < qlen; k++) p[k] = Character.toLowerCase(q.charAt(k));
            char first = p[0];
            int end = t.len - qlen;
            outer:
            for (int i = 0; i <= end; i++) {
                if (lower[i] != first) continue;
                for (int k = 1; k < qlen; k++) if (lower[i + k] != p[k]) continue outer;
                if (mc == m.length) m = java.util.Arrays.copyOf(m, Math.max(64, mc * 2));
                m[mc++] = i;
                if (mc >= MAX_MATCHES) break;
                i += qlen - 1;
            }
            if (mc > 0) {
                // first match at/after current top line
                int top = t.ls[Math.min(t.lines - 1, (int) (sy / lineH))];
                int lo = 0, hi = mc;
                while (lo < hi) { int mid = (lo + hi) >>> 1; if (m[mid] < top) lo = mid + 1; else hi = mid; }
                cur = lo < mc ? lo : 0;
                reveal();
            }
            invalidate();
        }

        void step(int d) {
            if (mc == 0) return;
            cur = (cur + d + mc) % mc;
            reveal();
            invalidate();
        }

        private void reveal() {
            int off = m[cur];
            int line = t.lineOf(off);
            int c = off - t.ls[line];
            int h = getHeight(), w = getWidth();
            float y = line * lineH;
            int ny = sy, nx = sx;
            if (y < sy || y + lineH > sy + h) ny = Math.round(y - h / 3f);
            float x0 = c * charW, x1 = (c + qlen) * charW, vw = w - gutter - 2 * pad;
            if (x0 < sx || x1 > sx + vw) nx = Math.round(x0 - vw / 3f);
            sc.forceFinished(true);
            scrollTo2(nx, ny);
        }

        private int maxY() { return Math.max(0, (int) (t.lines * lineH + pad * 2 - getHeight())); }
        private int maxX() { return Math.max(0, (int) (t.maxCols * charW + pad * 3 - (getWidth() - gutter))); }

        private void scrollTo2(int x, int y) {
            x = Math.max(0, Math.min(maxX(), x));
            y = Math.max(0, Math.min(maxY(), y));
            if (x != sx || y != sy) { sx = x; sy = y; invalidate(); }
        }

        @Override
        public void computeScroll() {
            if (sc.computeScrollOffset()) {
                scrollTo2(sc.getCurrX(), sc.getCurrY());
                if (!sc.isFinished()) postInvalidateOnAnimation();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) { scrollTo2(sx, sy); }

        private float thumbH() { return Math.max(40 * density, getHeight() * (float) getHeight() / (t.lines * lineH + pad * 2)); }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            boolean scrollable = maxY() > getHeight() * 3;
            if (a == MotionEvent.ACTION_DOWN && scrollable && e.getX() > getWidth() - 28 * density) {
                dragThumb = true;
                sc.forceFinished(true);
            }
            if (dragThumb) {
                float th = thumbH();
                float f = (e.getY() - th / 2) / (getHeight() - th);
                scrollTo2(sx, Math.round(Math.max(0, Math.min(1, f)) * maxY()));
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) dragThumb = false;
                return true;
            }
            if (a == MotionEvent.ACTION_DOWN && selA >= 0) {
                float hr = 26 * density;
                if (near(e, selA, hr)) dragSel = 2;
                else if (near(e, selB, hr)) dragSel = 3;
                if (dragSel != 0) { hideActions(); return true; }
            }
            if (dragSel != 0) {
                if (a == MotionEvent.ACTION_MOVE) {
                    float y = e.getY();
                    if (y < lineH) scrollTo2(sx, sy - Math.round(lineH / 2));
                    else if (y > getHeight() - lineH) scrollTo2(sx, sy + Math.round(lineH / 2));
                    int o = offsetAt(e.getX(), y);
                    if (dragSel == 1) { selA = Math.min(anchorA, o); selB = Math.max(anchorB, o); }
                    else if (dragSel == 2) { if (o >= selB) { selA = selB; selB = o; dragSel = 3; } else selA = o; }
                    else { if (o <= selA) { selB = selA; selA = o; dragSel = 2; } else selB = o; }
                    invalidate();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    dragSel = 0;
                    if (selB > selA) showActions(); else clearSel();
                }
                if (dragSel == 1) gd.onTouchEvent(e);
                return true;
            }
            sg.onTouchEvent(e);
            if (!sg.isInProgress()) gd.onTouchEvent(e);
            return true;
        }

        private static boolean isWord(char ch) { return Character.isLetterOrDigit(ch) || ch == '_'; }

        private int offsetAt(float x, float y) {
            int line = Math.max(0, Math.min(t.lines - 1, (int) ((y + sy - pad) / lineH)));
            int len = t.lineEnd(line) - t.ls[line];
            int col = Math.round((x - (gutter + pad - sx)) / charW);
            return t.ls[line] + Math.max(0, Math.min(len, col));
        }

        private float xOf(int off) {
            int line = t.lineOf(off);
            return gutter + pad - sx + (off - t.ls[line]) * charW;
        }

        private float yBottom(int off) { return (t.lineOf(off) + 1) * lineH + pad - sy; }

        private boolean near(MotionEvent e, int off, float r) {
            float dx = e.getX() - xOf(off), dy = e.getY() - (yBottom(off) + 8 * density);
            return dx * dx + dy * dy < r * r;
        }

        void clearSel() {
            if (selA < 0) return;
            selA = selB = -1;
            hideActions();
            invalidate();
        }

        private void hideActions() { if (am != null) { android.view.ActionMode m = am; am = null; m.finish(); } }

        private void copySel() {
            if (selB <= selA) return;
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(android.content.ClipData.newPlainText("text", t.slice(selA, selB)));
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(getContext(), "Copied", Toast.LENGTH_SHORT).show();
        }

        private void showActions() {
            final android.view.ActionMode.Callback base = new android.view.ActionMode.Callback() {
                public boolean onCreateActionMode(android.view.ActionMode m, Menu menu) {
                    menu.add(0, 1, 0, android.R.string.copy);
                    menu.add(0, 2, 1, android.R.string.selectAll);
                    return true;
                }
                public boolean onPrepareActionMode(android.view.ActionMode m, Menu menu) { return false; }
                public boolean onActionItemClicked(android.view.ActionMode m, android.view.MenuItem it) {
                    if (it.getItemId() == 1) { copySel(); clearSel(); }
                    else { selA = 0; selB = t.len; invalidate(); if (Build.VERSION.SDK_INT >= 23) m.invalidateContentRect(); }
                    return true;
                }
                public void onDestroyActionMode(android.view.ActionMode m) { if (am == m) am = null; }
            };
            // Floating toolbar (Android 6+); older versions get the classic contextual bar.
            android.view.ActionMode.Callback cb = Build.VERSION.SDK_INT < 23 ? base : new android.view.ActionMode.Callback2() {
                public boolean onCreateActionMode(android.view.ActionMode m, Menu menu) { return base.onCreateActionMode(m, menu); }
                public boolean onPrepareActionMode(android.view.ActionMode m, Menu menu) { return base.onPrepareActionMode(m, menu); }
                public boolean onActionItemClicked(android.view.ActionMode m, android.view.MenuItem it) { return base.onActionItemClicked(m, it); }
                public void onDestroyActionMode(android.view.ActionMode m) { base.onDestroyActionMode(m); }
                @Override public void onGetContentRect(android.view.ActionMode m, View v, android.graphics.Rect out) {
                    float y0 = Math.max(0, yBottom(selA) - lineH), y1 = Math.min(getHeight(), yBottom(selB));
                    if (y1 <= y0) y1 = y0 + lineH;
                    out.set((int) gutter, (int) y0, getWidth(), (int) y1);
                }
            };
            am = Build.VERSION.SDK_INT >= 23 ? startActionMode(cb, android.view.ActionMode.TYPE_FLOATING) : startActionMode(cb);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            int[] c = col;
            cv.drawColor(c[0]);
            fp.setColor(c[2]);
            cv.drawRect(0, 0, gutter, h, fp);
            fp.setColor(c[10]);
            cv.drawRect(gutter - Math.max(1, density * 0.75f), 0, gutter, h, fp);

            final Text t = this.t;
            int first = Math.max(0, (int) ((sy - pad) / lineH));
            int last = Math.min(t.lines, (int) ((sy - pad + h) / lineH) + 1);
            float textX = gutter + pad - sx;
            int c0 = Math.max(0, (int) (sx / charW) - 1);
            int vis = (int) ((w - gutter) / charW) + 3;

            // match lookup start
            int mi = 0;
            if (mc > 0 && first < t.lines) {
                int startOff = t.ls[first];
                int lo = 0, hi = mc;
                while (lo < hi) { int mid = (lo + hi) >>> 1; if (m[mid] + qlen <= startOff) lo = mid + 1; else hi = mid; }
                mi = lo;
            }

            cv.save();
            cv.clipRect(gutter, 0, w, h);
            for (int i = first; i < last; i++) {
                float top = i * lineH + pad - sy;
                int s = t.ls[i], e = t.lineEnd(i);
                // highlights
                while (mi < mc && m[mi] < e + 1) {
                    int ms = m[mi];
                    if (ms + qlen <= s) { mi++; continue; }
                    int a = Math.max(ms, s) - s, b = Math.min(ms + qlen, e) - s;
                    fp.setColor(mi == cur ? c[6] : c[5]);
                    cv.drawRect(textX + a * charW, top, textX + Math.max(b, a + 1) * charW, top + lineH, fp);
                    if (ms + qlen > e + 1) break;
                    mi++;
                }
                if (selA >= 0 && selA < e + 1 && selB > s) {
                    int a = Math.max(selA, s) - s, b = Math.min(selB, e + 1) - s;
                    fp.setColor(c[13]);
                    cv.drawRect(textX + a * charW, top, textX + Math.max(b, a) * charW + (selB > e ? charW * 0.5f : 0), top + lineH, fp);
                }
                int from = s + c0;
                if (from < e) {
                    int cnt = Math.min(vis, e - from);
                    if (from > s && Character.isLowSurrogate(t.c[from])) { from++; cnt--; }
                    if (cnt > 0) cv.drawText(t.c, from, cnt, textX + (from - s) * charW, top + asc, tp);
                }
            }
            cv.restore();

            if (selA >= 0 && selB > selA) {
                fp.setColor(c[1]);
                float r = 7 * density;
                for (int off : new int[]{selA, selB}) {
                    float hx = xOf(off), hy = yBottom(off);
                    if (hx < gutter - 1 || hy < 0 || hy > h + r * 2) continue;
                    cv.drawRect(hx - density, hy - lineH, hx + density, hy, fp);
                    cv.drawCircle(hx, hy + r, r, fp);
                }
            }

            float nx = gutter - pad;
            for (int i = first; i < last; i++) {
                int n = i + 1, k = num.length;
                do { num[--k] = (char) ('0' + n % 10); n /= 10; } while (n > 0);
                cv.drawText(num, k, num.length - k, nx, i * lineH + pad - sy + asc, np);
            }

            if (maxY() > h * 3) {
                float th = thumbH();
                float y = (h - th) * sy / (float) maxY();
                fp.setColor(c[7]);
                cv.drawRect(w - 4 * density, y, w, y + th, fp);
            }
        }
    }
}
