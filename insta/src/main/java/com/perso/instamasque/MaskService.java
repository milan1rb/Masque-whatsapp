package com.perso.instamasque;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Remplace la barre du bas d'Instagram par une barre à deux boutons :
 * Rechercher et Messages. La fausse barre reste affichée même dans la
 * recherche, où Instagram masque la sienne.
 */
public class MaskService extends AccessibilityService {

    static final String PREFS = "config";
    static final String INSTA = "com.instagram.android";
    static final String DEFAULT_COLOR = "#000000";
    static final String DEFAULT_ICON = "#FFFFFF";

    static volatile boolean running = false;
    static volatile MaskService instance = null;
    private static volatile String lastDump = "(Instagram pas encore analysé)";
    private static final StringBuilder LOG = new StringBuilder();
    private static String lastLogMsg = "";

    private WindowManager wm;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BarCanvas canvas;      // le dessin de la barre
    private View touchView;        // la fenêtre qui reçoit les appuis
    private View adjuster;         // le panneau de réglage

    private boolean scheduled = false;
    private int W = 1080, H = 2400;
    private long lastScan = 0, lastDumpTime = 0;
    private boolean inInsta = false;

    // Position de la barre d'Instagram et de ses onglets, mesurée puis retenue
    private final List<Rect> tabs = new ArrayList<>();
    private final List<AccessibilityNodeInfo> tabNodes = new ArrayList<>();
    private int selectedTab = -1;

    private final Runnable tick = () -> {
        scheduled = false;
        try {
            update();
        } catch (Exception e) {
            log("Erreur : " + e);
            schedule(400);
        }
    };

    static class Item {
        AccessibilityNodeInfo node;
        int depth;
        String text, desc, id, cls;
        Rect r = new Rect();
        boolean visible;
    }

    // ---------- Journal ----------

    static synchronized void log(String msg) {
        if (msg.equals(lastLogMsg)) return;
        lastLogMsg = msg;
        String t = new SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(new Date());
        LOG.append(t).append("  ").append(msg).append('\n');
        if (LOG.length() > 6000) LOG.delete(0, LOG.length() - 6000);
    }

    static synchronized String diagnostic() {
        return "Service actif : " + running + "\n\n=== Journal ===\n"
                + (LOG.length() == 0 ? "(vide)\n" : LOG.toString())
                + "\n=== Éléments de l'écran Instagram ===\n" + lastDump;
    }

    // ---------- Cycle de vie ----------

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        metrics();
        running = true;
        instance = this;
        log("Service connecté (gestes autorisés : "
                + ((getServiceInfo().getCapabilities()
                & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0) + ")");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (wm == null) return;
        int type = e.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            handler.removeCallbacks(tick);
            scheduled = true;
            handler.post(tick);
        } else {
            schedule(16);
        }
    }

    private void schedule(long delay) {
        if (!scheduled) {
            scheduled = true;
            handler.postDelayed(tick, delay);
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        handler.removeCallbacks(tick);
        hideBar();
        hideAdjuster();
        super.onDestroy();
    }

    private void metrics() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        W = dm.widthPixels;
        H = dm.heightPixels;
    }

    // ---------- Boucle principale ----------

    private void update() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String pkg = String.valueOf(root.getPackageName());

        if (!pkg.equals(INSTA)) {
            if (!pkg.equals("com.android.systemui")) {
                inInsta = false;
                hideBar();
                hideAdjuster();
            }
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (!inInsta) {
            inInsta = true;
            log("Instagram ouvert");
        }
        metrics();
        if (prefs.getBoolean("adjust", false)) showAdjuster(); else hideAdjuster();

        if (!prefs.getBoolean("enabled", true)) {
            hideBar();
            return;
        }

        if (now - lastScan > 200) {
            lastScan = now;
            scanTabs(root, now);
        }

        // Clavier ouvert : la barre s'efface pour ne pas gêner la saisie
        if (imeVisible()) {
            hideBar();
            schedule(150);
            return;
        }

        showBar(barRect());
        schedule(150);
    }

    /** Repère la vraie barre d'Instagram et retient sa position. */
    private void scanTabs(AccessibilityNodeInfo root, long now) {
        List<Item> items = new ArrayList<>();
        collect(root, 0, items, 1500);

        int bandTop = H - dp(130);
        List<Rect> found = new ArrayList<>();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        int sel = -1;
        for (Item it : items) {
            if (!it.visible || !it.node.isClickable()) continue;
            Rect r = it.r;
            if (r.centerY() < bandTop || r.isEmpty()) continue;
            if (r.width() < W / 12 || r.width() > W / 3) continue;
            if (r.height() > dp(90)) continue;
            boolean dup = false;
            for (Rect e : found) {
                if (e.contains(r.centerX(), r.centerY()) || r.contains(e.centerX(), e.centerY())) dup = true;
            }
            if (dup) continue;
            found.add(new Rect(r));
            nodes.add(it.node);
        }
        // tri de gauche à droite
        for (int i = 0; i < found.size(); i++) {
            for (int j = i + 1; j < found.size(); j++) {
                if (found.get(j).left < found.get(i).left) {
                    Rect tr = found.get(i); found.set(i, found.get(j)); found.set(j, tr);
                    AccessibilityNodeInfo tn = nodes.get(i); nodes.set(i, nodes.get(j)); nodes.set(j, tn);
                }
            }
        }

        if (found.size() >= 4) {
            int top = Integer.MAX_VALUE, bottom = 0;
            for (Rect r : found) {
                top = Math.min(top, r.top);
                bottom = Math.max(bottom, r.bottom);
            }
            prefs.edit().putInt(key("bar_top"), top).putInt(key("bar_bottom"), bottom).apply();
            tabs.clear();
            tabs.addAll(found);
            tabNodes.clear();
            tabNodes.addAll(nodes);
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i).isSelected()) sel = i;
            }
            if (sel >= 0) selectedTab = sel;
        }

        if (now - lastDumpTime > 10000) {
            lastDumpTime = now;
            lastDump = dump(items);
        }
    }

    /** La barre que l'on dessine : la zone de la vraie barre, pleine largeur. */
    private Rect barRect() {
        int top = prefs.getInt(key("bar_top"), H - dp(104));
        int bottom = prefs.getInt(key("bar_bottom"), H - dp(48));
        int extra = prefs.getInt("bar_pad", 10);      // marge sous les icônes, réglable
        int[] o = offsets();
        return new Rect(0, top - dp(8) + o[1], W, Math.min(H, bottom + dp(extra)) + o[3]);
    }

    private int[] offsets() {
        String[] n = prefs.getString("adj", "").split(",");
        int[] o = new int[4];
        if (n.length == 4) {
            try { for (int i = 0; i < 4; i++) o[i] = Integer.parseInt(n[i]); } catch (Exception ignored) { }
        }
        return o;
    }

    private String key(String base) {
        return base + ":" + W + "x" + H;
    }

    private boolean imeVisible() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect r = new Rect();
                    w.getBoundsInScreen(r);
                    if (!r.isEmpty() && r.height() > dp(120)) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    // ---------- Les deux boutons ----------

    /** Appui sur la fausse barre : moitié gauche = Rechercher, droite = Messages. */
    private void barTap(float x) {
        if (x < W / 2f) openSearch(); else openDirect();
    }

    private void openSearch() {
        // la recherche est le 2e onglet de la vraie barre d'Instagram
        int idx = Math.max(0, Math.min(tabNodes.size() - 1, prefs.getInt("search_index", 1)));
        if (tabNodes.size() >= 4) {
            try {
                AccessibilityNodeInfo n = tabNodes.get(idx);
                n.refresh();
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    selectedTab = idx;
                    if (canvas != null) canvas.invalidate();
                    log("Recherche ouverte (onglet " + (idx + 1) + ")");
                    return;
                }
            } catch (Exception ignored) { }
        }
        if (open("instagram://explore")) return;
        log("Recherche : la barre d'Instagram n'est pas visible, impossible d'y aller");
    }

    private void openDirect() {
        if (open("instagram://direct-inbox")) {
            log("Messages ouverts");
            return;
        }
        // secours : l'icône Messages en haut à droite de l'accueil
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            for (String t : new String[]{"Messages", "Direct"}) {
                for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(t)) {
                    if (!n.isVisibleToUser()) continue;
                    AccessibilityNodeInfo c = clickable(n);
                    if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        log("Messages ouverts (icône)");
                        return;
                    }
                }
            }
        }
        log("Messages : impossible d'ouvrir la boîte de réception");
    }

    private boolean open(String uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.setPackage(INSTA);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Dessin ----------

    private int barColor() {
        if (prefs.getBoolean("debug", false)) return 0x88FF1744;
        try {
            return Color.parseColor(prefs.getString("color", DEFAULT_COLOR).trim());
        } catch (Exception e) {
            return Color.BLACK;
        }
    }

    private int iconColor() {
        try {
            return Color.parseColor(prefs.getString("iconcolor", DEFAULT_ICON).trim());
        } catch (Exception e) {
            return Color.WHITE;
        }
    }

    class BarCanvas extends View {
        private Rect bar = new Rect();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        BarCanvas(MaskService ctx) {
            super(ctx);
            setWillNotDraw(false);
        }

        void set(Rect r) {
            if (r.equals(bar)) return;
            bar = new Rect(r);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (bar.isEmpty()) return;
            p.setStyle(Paint.Style.FILL);
            p.setColor(barColor());
            c.drawRect(bar.left, bar.top, bar.right, bar.bottom, p);

            if (prefs.getBoolean("divider", true)) {
                p.setColor(0xFF262626);
                c.drawRect(bar.left, bar.top, bar.right, bar.top + Math.max(1, dp(1) / 2), p);
            }

            float half = bar.width() / 2f;
            int cy = bar.top + dp(34);
            drawSearch(c, (int) (bar.left + half / 2), cy,
                    selectedTab == prefs.getInt("search_index", 1));
            drawDirect(c, (int) (bar.left + half + half / 2), cy);
        }
    }

    private void stroke(Paint p) {
        p.setColor(iconColor());
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2) + dp(1) / 3f);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Loupe, comme l'icône Rechercher d'Instagram. */
    private void drawSearch(Canvas c, int cx, int cy, boolean active) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke(p);
        if (active) p.setStrokeWidth(dp(3));
        float r = dp(11);
        c.drawCircle(cx - r * 0.18f, cy - r * 0.18f, r, p);
        c.drawLine(cx + r * 0.55f, cy + r * 0.55f, cx + r * 1.25f, cy + r * 1.25f, p);
    }

    /** Avion en papier, comme l'icône Messages d'Instagram. */
    private void drawDirect(Canvas c, int cx, int cy) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke(p);
        float s = dp(13);
        Path tri = new Path();
        tri.moveTo(cx - s, cy - s * 0.25f);
        tri.lineTo(cx + s, cy - s);
        tri.lineTo(cx + s * 0.1f, cy + s);
        tri.close();
        c.drawPath(tri, p);
        Path fold = new Path();
        fold.moveTo(cx - s, cy - s * 0.25f);
        fold.lineTo(cx + s * 0.1f, cy + s * 0.2f);
        fold.lineTo(cx + s, cy - s);
        c.drawPath(fold, p);
    }

    // ---------- Fenêtres ----------

    private void showBar(Rect r) {
        if (wm == null || r.isEmpty()) return;
        if (canvas == null) {
            canvas = new BarCanvas(this);
            WindowManager.LayoutParams lp = params(0, 0, W, H,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            try { wm.addView(canvas, lp); } catch (Exception e) { canvas = null; return; }
        }
        canvas.set(r);

        if (touchView == null) {
            touchView = new View(this);
            touchView.setOnTouchListener((v, ev) -> {
                if (ev.getAction() == MotionEvent.ACTION_UP) barTap(ev.getRawX());
                return true;
            });
            try {
                wm.addView(touchView, params(r.left, r.top, r.width(), r.height(), 0));
            } catch (Exception e) {
                touchView = null;
            }
        } else {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) touchView.getLayoutParams();
            if (lp.x != r.left || lp.y != r.top || lp.width != r.width() || lp.height != r.height()) {
                lp.x = r.left;
                lp.y = r.top;
                lp.width = r.width();
                lp.height = r.height();
                try { wm.updateViewLayout(touchView, lp); } catch (Exception ignored) { }
            }
            if (touchView.getVisibility() != View.VISIBLE) touchView.setVisibility(View.VISIBLE);
        }
    }

    private void hideBar() {
        if (canvas != null) canvas.set(new Rect());
        if (touchView != null && touchView.getVisibility() == View.VISIBLE) {
            touchView.setVisibility(View.INVISIBLE);
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) touchView.getLayoutParams();
            lp.width = 1;
            lp.height = 1;
            try { wm.updateViewLayout(touchView, lp); } catch (Exception ignored) { }
        }
    }

    private WindowManager.LayoutParams params(int x, int y, int w, int h, int extraFlags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | extraFlags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = x;
        lp.y = y;
        lp.windowAnimations = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
        return lp;
    }

    // ---------- Réglage à la main ----------

    private void showAdjuster() {
        if (adjuster != null) return;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackgroundColor(0xEE1C1C1C);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));

        final android.widget.TextView label = new android.widget.TextView(this);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(12);
        box.addView(label);
        final Runnable refresh = () -> {
            Rect r = barRect();
            label.setText("Barre : " + r.toShortString()
                    + "   couleur " + String.format("#%06X", barColor() & 0xFFFFFF));
            if (canvas != null) canvas.invalidate();
        };

        box.addView(row(new String[]{"↑", "↓", "haut +", "haut −"}, new Runnable[]{
                () -> { nudge(0, -3, 0, -3); refresh.run(); },
                () -> { nudge(0, 3, 0, 3); refresh.run(); },
                () -> { nudge(0, -3, 0, 0); refresh.run(); },
                () -> { nudge(0, 3, 0, 0); refresh.run(); }}));
        box.addView(row(new String[]{"Plus clair", "Plus foncé", "Réinit."}, new Runnable[]{
                () -> { tint(2); refresh.run(); },
                () -> { tint(-2); refresh.run(); },
                () -> {
                    prefs.edit().remove("adj").remove("color").apply();
                    refresh.run();
                }}));
        android.widget.Button done = new android.widget.Button(this);
        done.setText("Terminé");
        done.setOnClickListener(v -> {
            prefs.edit().putBoolean("adjust", false).apply();
            hideAdjuster();
        });
        box.addView(done);
        refresh.run();

        WindowManager.LayoutParams lp = params(0, (int) (H * 0.3),
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT, 0);
        try {
            wm.addView(box, lp);
            adjuster = box;
        } catch (Exception e) {
            log("Réglage impossible : " + e);
        }
    }

    private android.widget.LinearLayout row(String[] names, Runnable[] actions) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        for (int i = 0; i < names.length; i++) {
            final Runnable a = actions[i];
            android.widget.Button b = new android.widget.Button(this);
            b.setText(names[i]);
            b.setTextSize(11);
            b.setAllCaps(false);
            b.setPadding(dp(2), 0, dp(2), 0);
            b.setOnClickListener(v -> a.run());
            row.addView(b, new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private void nudge(int dl, int dt, int dr, int db) {
        int[] o = offsets();
        o[0] += dl; o[1] += dt; o[2] += dr; o[3] += db;
        prefs.edit().putString("adj", o[0] + "," + o[1] + "," + o[2] + "," + o[3]).apply();
        showBar(barRect());
    }

    private void tint(int d) {
        int c = barColor();
        int n = Color.rgb(clamp(Color.red(c) + d), clamp(Color.green(c) + d), clamp(Color.blue(c) + d));
        prefs.edit().putString("color", String.format("#%06X", n & 0xFFFFFF)).apply();
        if (canvas != null) canvas.invalidate();
    }

    private void hideAdjuster() {
        if (adjuster == null) return;
        try { wm.removeView(adjuster); } catch (Exception ignored) { }
        adjuster = null;
    }

    // ---------- Outils ----------

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 5 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private void collect(AccessibilityNodeInfo n, int depth, List<Item> out, int max) {
        if (n == null || depth > 45 || out.size() >= max) return;
        Item it = new Item();
        it.node = n;
        it.depth = depth;
        it.text = n.getText() == null ? "" : n.getText().toString().trim();
        it.desc = n.getContentDescription() == null ? "" : n.getContentDescription().toString().trim();
        it.id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName();
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        it.cls = cls.substring(cls.lastIndexOf('.') + 1);
        it.visible = n.isVisibleToUser();
        n.getBoundsInScreen(it.r);
        out.add(it);
        int count = n.getChildCount();
        for (int i = 0; i < count && out.size() < max; i++) collect(n.getChild(i), depth + 1, out, max);
    }

    private String dump(List<Item> items) {
        StringBuilder sb = new StringBuilder("Paquet : " + INSTA + "\n");
        for (Item it : items) {
            if (sb.length() > 40000) { sb.append("…(tronqué)"); break; }
            if (!it.visible) continue;
            for (int i = 0; i < Math.min(it.depth, 20); i++) sb.append(' ');
            sb.append(it.cls);
            if (!it.text.isEmpty()) sb.append(" \"").append(it.text).append('"');
            if (!it.desc.isEmpty()) sb.append(" [").append(it.desc).append(']');
            if (!it.id.isEmpty()) sb.append(" #").append(it.id.replace(INSTA + ":id/", ""));
            sb.append(' ').append(it.r.toShortString());
            if (it.node.isClickable()) sb.append(" CLIC");
            if (it.node.isSelected()) sb.append(" SELECT");
            sb.append('\n');
        }
        return sb.toString();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
