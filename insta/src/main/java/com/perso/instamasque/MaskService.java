package com.perso.instamasque;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
    private final List<String> tabNames = new ArrayList<>();
    private int selectedTab = -1;
    private int searchTab = -1;          // l'onglet Recherche, repéré par son nom
    private long touchOffUntil = 0;      // le temps d'un appui à travers la barre
    private String launcherPkg = "";

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
        try {
            ResolveInfo ri = getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null) launcherPkg = ri.activityInfo.packageName;
        } catch (Exception ignored) { }
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

    /**
     * Les versions d'Instagram sont reconnues à leur nom : l'officielle comme les
     * versions modifiées (com.dfinstagram.android et autres). Une application
     * détectée à la main prend le pas sur ce choix.
     */
    boolean isTarget(String pkg) {
        String stored = prefs.getString("pkg", "");
        if (!stored.isEmpty()) return pkg.equals(stored);
        return pkg.toLowerCase(Locale.ROOT).contains("instagram");
    }

    /** Le nom exact de l'application, pour lui envoyer un lien. */
    String target() {
        String stored = prefs.getString("pkg", "");
        if (!stored.isEmpty()) return stored;
        return prefs.getString("seen", INSTA);
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
        if (root == null) {          // plus aucune fenêtre lisible : on retire la barre
            inInsta = false;
            hideBar();
            return;
        }
        String pkg = String.valueOf(root.getPackageName());

        // Détection : la prochaine application ouverte devient celle que l'on traite
        if (prefs.getBoolean("detect", false) && !pkg.equals(getPackageName())
                && !pkg.equals(launcherPkg) && !pkg.equals("com.android.systemui")
                && !pkg.equals("android")) {
            prefs.edit().putString("pkg", pkg).putBoolean("detect", false).apply();
            log("Application détectée : " + pkg);
        }

        // 2. Hors de l'application visée — y compris les applications récentes,
        //    l'écran d'accueil ou le volet des notifications — la barre disparaît.
        if (!isTarget(pkg)) {
            inInsta = false;
            hideBar();
            hideAdjuster();
            return;
        }
        if (!pkg.equals(prefs.getString("seen", ""))) {
            prefs.edit().putString("seen", pkg).apply();
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

        // Dans une conversation, ou clavier ouvert : la barre s'efface
        if (imeVisible() || inConversation(root)) {
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
        List<String> names = new ArrayList<>();
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
            String nm = it.desc.isEmpty() ? it.text : it.desc;
            if (nm.isEmpty()) nm = it.id.contains("/") ? it.id.substring(it.id.indexOf('/') + 1) : "?";
            names.add(nm);
        }
        // tri de gauche à droite
        for (int i = 0; i < found.size(); i++) {
            for (int j = i + 1; j < found.size(); j++) {
                if (found.get(j).left < found.get(i).left) {
                    Rect tr = found.get(i); found.set(i, found.get(j)); found.set(j, tr);
                    AccessibilityNodeInfo tn = nodes.get(i); nodes.set(i, nodes.get(j)); nodes.set(j, tn);
                    String ts = names.get(i); names.set(i, names.get(j)); names.set(j, ts);
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
            // (retenu pour information : la géométrie dessinée est celle des réglages)
            tabs.clear();
            tabs.addAll(found);
            tabNodes.clear();
            tabNodes.addAll(nodes);
            tabNames.clear();
            tabNames.addAll(names);
            int srch = -1;
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i).isSelected()) sel = i;
                String n = names.get(i).toLowerCase(Locale.ROOT);
                if (n.contains("recherch") || n.contains("explor") || n.contains("search")) srch = i;
            }
            if (sel >= 0) selectedTab = sel;
            if (srch >= 0 && srch != searchTab) {
                searchTab = srch;
                log("Onglet Recherche repéré : n°" + (srch + 1) + " « " + names.get(srch) + " »");
            }
            if (searchTab < 0) log("Onglets vus : " + names);
        }

        if (now - lastDumpTime > 10000) {
            lastDumpTime = now;
            lastDump = dump(items);
        }
    }

    /**
     * La barre que l'on dessine : toujours au même endroit, pleine largeur.
     * Par défaut elle occupe exactement la place de la barre d'Instagram :
     * 56 dp de haut, posée 15 dp au-dessus du bas de l'écran.
     */
    private Rect barRect() {
        int gap = prefs.getInt("bar_gap", 15);        // distance au bas de l'écran, en dp
        int h = prefs.getInt("bar_height", 56);       // hauteur de la barre, en dp
        int bottom = H - dp(gap);
        int top = bottom - dp(h);
        int[] o = offsets();
        return new Rect(0, top + o[1], W, Math.min(H, bottom) + o[3]);
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

    /** Une conversation se reconnaît à son champ de saisie, en bas de l'écran. */
    private boolean inConversation(AccessibilityNodeInfo root) {
        if (!prefs.getBoolean("hide_in_chat", true)) return false;
        List<Item> items = new ArrayList<>();
        collect(root, 0, items, 900);
        for (Item it : items) {
            if (it.visible && it.node.isEditable() && it.r.centerY() > H * 0.7) return true;
        }
        return false;
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
        int idx = searchIndex();
        // 1. demander directement à l'onglet de s'ouvrir
        if (idx >= 0 && idx < tabNodes.size()) {
            try {
                AccessibilityNodeInfo n = tabNodes.get(idx);
                n.refresh();
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    selectedTab = idx;
                    if (canvas != null) canvas.invalidate();
                    log("Recherche ouverte (onglet " + (idx + 1) + ")");
                    revealRecent();
                    return;
                }
                // 2. l'onglet refuse : on appuie vraiment dessus, à travers la barre
                AccessibilityNodeInfo p = n.getParent();
                for (int k = 0; k < 3 && p != null; k++) {
                    if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        selectedTab = idx;
                        log("Recherche ouverte (parent de l'onglet)");
                        return;
                    }
                    p = p.getParent();
                }
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (!r.isEmpty()) {
                    tapThrough(r);
                    selectedTab = idx;
                    return;
                }
            } catch (Exception e) {
                log("Recherche : " + e);
            }
        }
        // 3. dernier recours : le lien interne
        for (String u : new String[]{"instagram://explore", "instagram://search",
                "https://www.instagram.com/explore/"}) {
            if (open(u)) {
                log("Recherche ouverte par " + u);
                return;
            }
        }
        log("Recherche : aucun onglet trouvé (" + tabNames + ")");
    }

    private int searchIndex() {
        int forced = prefs.getInt("search_index", 0);
        if (forced > 0 && forced <= tabNodes.size()) return forced - 1;   // choisi à la main
        if (searchTab >= 0 && searchTab < tabNodes.size()) return searchTab;
        return tabNodes.size() >= 4 ? 1 : -1;                             // 2e onglet par défaut
    }

    /**
     * Après la recherche : un petit glissement vers le bas, qui fait apparaître
     * les comptes consultés récemment.
     */
    private void revealRecent() {
        if (!prefs.getBoolean("swipe_recent", true)) return;
        int delay = prefs.getInt("swipe_delay", 450);
        handler.postDelayed(() -> {
            int dist = dp(prefs.getInt("swipe_dist", 55));
            int x = W / 2;
            int y0 = (int) (H * 0.32);
            try {
                Path move = new Path();
                move.moveTo(x, y0);
                move.lineTo(x, y0 + dist);
                GestureDescription.StrokeDescription s1 =
                        new GestureDescription.StrokeDescription(move, 0, 260, true);
                Path hold = new Path();
                hold.moveTo(x, y0 + dist);
                hold.lineTo(x, y0 + dist + 1);
                GestureDescription.StrokeDescription s2 = s1.continueStroke(hold, 0, 160, false);
                boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(s1).build(),
                        new GestureResultCallback() {
                            @Override public void onCompleted(GestureDescription g) {
                                dispatchGesture(new GestureDescription.Builder()
                                        .addStroke(s2).build(), null, null);
                            }
                            @Override public void onCancelled(GestureDescription g) {
                                log("Glissement annulé par Android");
                            }
                        }, null);
                log("Comptes récents : glissement de " + dist + " px (envoyé : " + ok + ")");
            } catch (Exception e) {
                log("Comptes récents : " + e);
            }
        }, delay);
    }

    /** Efface un instant la barre pour qu'un vrai appui atteigne l'application. */
    private void tapThrough(Rect r) {
        touchOffUntil = SystemClock.uptimeMillis() + 900;
        showBar(barRect());
        final int cx = r.centerX(), cy = r.centerY();
        handler.postDelayed(() -> {
            try {
                Path p = new Path();
                p.moveTo(cx, cy);
                boolean ok = dispatchGesture(new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
                log("Recherche : appui simulé en " + cx + "," + cy + " (envoyé : " + ok + ")");
                if (ok) revealRecent();
            } catch (Exception e) {
                log("Recherche : appui impossible, " + e);
            }
            handler.postDelayed(() -> {
                touchOffUntil = 0;
                schedule(16);
            }, 500);
        }, 120);
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
            i.setPackage(target());
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
            int cy = bar.centerY();
            drawSearch(c, (int) (bar.left + half / 2), cy,
                    selectedTab >= 0 && selectedTab == searchIndex());
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

        boolean paused = SystemClock.uptimeMillis() < touchOffUntil;
        if (paused) {
            if (touchView != null && touchView.getVisibility() == View.VISIBLE) {
                touchView.setVisibility(View.INVISIBLE);
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) touchView.getLayoutParams();
                lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                try { wm.updateViewLayout(touchView, lp); } catch (Exception ignored) { }
            }
            return;
        }
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
            if ((lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
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
        StringBuilder sb = new StringBuilder("Paquet : " + target() + "\n");
        for (Item it : items) {
            if (sb.length() > 40000) { sb.append("…(tronqué)"); break; }
            if (!it.visible) continue;
            for (int i = 0; i < Math.min(it.depth, 20); i++) sb.append(' ');
            sb.append(it.cls);
            if (!it.text.isEmpty()) sb.append(" \"").append(it.text).append('"');
            if (!it.desc.isEmpty()) sb.append(" [").append(it.desc).append(']');
            if (!it.id.isEmpty()) sb.append(" #").append(it.id.replace(target() + ":id/", ""));
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
