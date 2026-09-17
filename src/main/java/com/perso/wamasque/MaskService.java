package com.perso.wamasque;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MaskService extends AccessibilityService {

    static final String PREFS = "config";
    static volatile boolean running = false;
    private static volatile String lastDump = "(aucune capture de l'écran principal de WhatsApp)";
    private static final StringBuilder LOG = new StringBuilder();
    private static String lastLogMsg = "";

    private static final int COLOR_MASK = 0xFF0B141A;
    private static final int COLOR_TEST = 0x88FF0000;

    private WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> views = new ArrayList<>();
    private final List<Rect> shown = new ArrayList<>();
    private int shownColor = 0;
    private boolean scheduled = false;
    private boolean inWhatsApp = false;
    private long lastDumpTime = 0;

    // Macro de lancement : 0 = rien, 1 = ramener les listes au début, 2 = cliquer, 3 = vérifier
    private int phase = 0;
    private long phaseDeadline = 0;
    private long waitUntil = 0;
    private int swipeTries = 0;
    private boolean gestureBusy = false;
    private long gestureStart = 0;

    private final Runnable tick = () -> {
        scheduled = false;
        try { update(); } catch (Exception e) { log("Erreur : " + e); }
    };

    static class Item {
        AccessibilityNodeInfo node;
        int depth;
        String text, desc, id, cls;
        Rect r = new Rect();
        boolean visible;
    }

    static class Chip {
        Rect r;
        AccessibilityNodeInfo node;
        boolean toutes, nonLues, selected;
        List<String> labels = new ArrayList<>();
    }

    // ---------- Journal (visible dans l'app, bouton diagnostic) ----------

    static synchronized void log(String msg) {
        if (msg.equals(lastLogMsg)) return;
        lastLogMsg = msg;
        String t = new SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(new Date());
        LOG.append(t).append("  ").append(msg).append('\n');
        if (LOG.length() > 6000) LOG.delete(0, LOG.length() - 6000);
    }

    static synchronized String diagnostic() {
        return "Service actif : " + running + "\n\n=== Journal des macros ===\n"
                + (LOG.length() == 0 ? "(vide)\n" : LOG.toString())
                + "\n=== Éléments de l'écran WhatsApp ===\n" + lastDump;
    }

    // ---------- Cycle de vie ----------

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        running = true;
        log("Service connecté (gestes autorisés : "
                + ((getServiceInfo().getCapabilities()
                & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0)
                + ")");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        schedule(120);
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
        handler.removeCallbacks(tick);
        showRects(new ArrayList<>(), 0);
        super.onDestroy();
    }

    // ---------- Boucle principale ----------

    private void update() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            if (phase != 0) schedule(300);
            return;
        }
        String pkg = String.valueOf(root.getPackageName());

        if (!pkg.equals("com.whatsapp") && !pkg.equals("com.whatsapp.w4b")) {
            showRects(new ArrayList<>(), 0);
            if (!pkg.equals("com.android.systemui")) {
                inWhatsApp = false;
                phase = 0;
            }
            return;
        }

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        long now = SystemClock.uptimeMillis();

        if (!inWhatsApp) {
            inWhatsApp = true;
            boolean wantMacro = p.getBoolean("slide", true) || p.getBoolean("autofirst", true)
                    || !p.getString("liste", "").trim().isEmpty();
            if (wantMacro) {
                phase = 1;
                phaseDeadline = now + 10000;
                waitUntil = now + 600;
                swipeTries = 0;
                log("WhatsApp ouvert : macro démarrée");
            }
        }

        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int W = dm.widthPixels, H = dm.heightPixels;

        List<Item> items = new ArrayList<>();
        collect(root, 0, items);

        boolean hasDisc = false, hasCalls = false, discSelected = false, callsSelected = false;
        for (Item it : items) {
            if (it.r.centerY() > H * 0.8) {
                if (is(it, "Discussions")) {
                    hasDisc = true;
                    if (it.node.isSelected() || clickableNode(it.node, W).isSelected()) discSelected = true;
                }
                if (is(it, "Appels")) {
                    hasCalls = true;
                    if (it.node.isSelected() || clickableNode(it.node, W).isSelected()) callsSelected = true;
                }
            }
        }
        boolean home = hasDisc && hasCalls;

        if (home && now - lastDumpTime > 1500) {
            lastDumpTime = now;
            lastDump = dump(items, pkg);
        }

        List<Chip> chips = home ? findChips(items, W, H) : new ArrayList<>();

        // ----- Masques -----
        List<Rect> rects = new ArrayList<>();
        if (home) {
            // Mode sélection (appui long) : la barre du haut affiche Archiver, Épingler...
            boolean selectionMode = false;
            for (Item it : items) {
                if (it.visible && it.r.centerY() < H * 0.15
                        && (has(it, "archiv") || starts(it, "Épingl") || starts(it, "Supprimer")
                        || starts(it, "Désactiver"))) {
                    selectionMode = true;
                }
            }
            boolean onDiscussions = !callsSelected && (discSelected || !chips.isEmpty());

            boolean aiFound = false;
            for (Item it : items) {
                if (!it.visible) continue;
                int cy = it.r.centerY();

                if (p.getBoolean("cam", true) && !selectionMode && cy < H * 0.15
                        && (is(it, "Appareil photo", "Caméra", "Camera") || it.id.contains("camera"))) {
                    add(rects, target(it.node, W));

                } else if (p.getBoolean("metaai", true) && onDiscussions && cy > H * 0.5
                        && it.r.left > W * 0.6 && it.r.width() < W * 0.4 && !it.node.isEditable()
                        && (has(it, "meta ai") || it.id.contains("meta_ai"))) {
                    add(rects, target(it.node, W));
                    aiFound = true;

                } else if (p.getBoolean("actus", true) && cy > H * 0.8 && is(it, "Actus")) {
                    add(rects, target(it.node, W));

                } else if (p.getBoolean("commu", true) && cy > H * 0.8 && starts(it, "Communaut")) {
                    add(rects, target(it.node, W));
                }
            }
            if (p.getBoolean("metaai", true) && onDiscussions && !aiFound) {
                maskMetaAiFallback(items, rects, W, H);
            }
        }
        showRects(rects, p.getBoolean("debug", false) ? COLOR_TEST : COLOR_MASK);

        // ----- Macro de lancement -----
        if (phase != 0) runMacro(home, chips, p, now);
    }

    private void runMacro(boolean home, List<Chip> chips, SharedPreferences p, long now) {
        if (now > phaseDeadline) {
            log("Macro abandonnée : délai dépassé (étape " + phase + ")");
            phase = 0;
            return;
        }
        schedule(300); // on continue de vérifier même si l'écran ne bouge plus

        if (!home) { log("En attente de l'écran principal…"); return; }
        if (now < waitUntil) return;
        if (gestureBusy) {
            if (now - gestureStart < 3000) return;
            gestureBusy = false;
        }
        if (chips.isEmpty()) { log("Barre des listes introuvable"); return; }

        Chip first = firstAfterToutes(chips);
        if (first == null) { log("Liste après Toutes introuvable : " + describe(chips)); return; }
        int d = first.r.left - dp(16);
        boolean slide = p.getBoolean("slide", true);

        if (phase == 1) {
            if (slide && d > dp(12) && swipeTries < 4) {
                swipeTries++;
                log("Glissement de " + d + " px, essai " + swipeTries + " — " + describe(chips));
                swipeLeft(first.r.centerY(), d);
                waitUntil = now + 700;
            } else {
                if (slide) log("Listes au début — " + describe(chips));
                phase = 2;
            }

        } else if (phase == 2) {
            String wanted = norm(p.getString("liste", ""));
            Chip goal = null;
            if (!wanted.isEmpty()) {
                for (Chip c : chips) for (String l : c.labels) if (norm(l).startsWith(wanted)) goal = c;
                if (goal == null) log("Liste « " + wanted + " » introuvable — " + describe(chips));
            } else if (p.getBoolean("autofirst", true)) {
                goal = first;
            }
            if (goal != null) {
                if (goal.selected) log("Déjà sélectionnée : " + label(goal));
                else click(goal);
            }
            phase = 3;
            swipeTries = 0;
            waitUntil = now + 800;

        } else if (phase == 3) {
            if (slide && d > dp(12) && swipeTries < 2) {
                swipeTries++;
                log("Recalage de " + d + " px après le clic");
                swipeLeft(first.r.centerY(), d);
                waitUntil = now + 700;
            } else {
                log("Macro terminée");
                phase = 0;
            }
        }
    }

    // ---------- Barre des listes ----------

    private List<Chip> findChips(List<Item> items, int W, int H) {
        List<Chip> chips = new ArrayList<>();
        Rect band = null;
        for (Item it : items) {
            if (it.r.centerY() < H * 0.4 && (starts(it, "Toutes") || starts(it, "Non lues"))) {
                band = target(it.node, W);
                break;
            }
        }
        if (band == null) return chips;

        for (Item it : items) {
            int cy = it.r.centerY();
            if (cy < band.top || cy > band.bottom || it.r.isEmpty()) continue;
            if (it.text.isEmpty() && it.desc.isEmpty()) continue;
            AccessibilityNodeInfo cn = clickableNode(it.node, W);
            Rect r = new Rect();
            cn.getBoundsInScreen(r);
            if (r.isEmpty() || r.height() > H * 0.1 || r.width() > W * 0.6) continue;
            Chip c = null;
            for (Chip e : chips) if (r.left < e.r.right && r.right > e.r.left) { c = e; break; }
            if (c == null) {
                c = new Chip();
                c.r = new Rect(r);
                chips.add(c);
            } else {
                c.r.union(r);
            }
            if (c.node == null || (!c.node.isClickable() && cn.isClickable())) c.node = cn;
            if (starts(it, "Toutes")) c.toutes = true;
            if (starts(it, "Non lues")) c.nonLues = true;
            if (!it.text.isEmpty()) c.labels.add(it.text);
            if (!it.desc.isEmpty()) c.labels.add(it.desc);
            if (it.node.isSelected() || it.node.isChecked() || cn.isSelected() || cn.isChecked()) c.selected = true;
        }
        chips.sort((a, b) -> Integer.compare(a.r.left, b.r.left));
        return chips;
    }

    private Chip firstAfterToutes(List<Chip> chips) {
        for (int i = 0; i < chips.size(); i++) {
            if (chips.get(i).toutes) return i + 1 < chips.size() ? chips.get(i + 1) : null;
        }
        for (Chip c : chips) if (c.nonLues) return c;
        return null;
    }

    private String label(Chip c) {
        return c.labels.isEmpty() ? "?" : c.labels.get(0);
    }

    private String describe(List<Chip> chips) {
        StringBuilder sb = new StringBuilder();
        for (Chip c : chips) {
            sb.append('[').append(label(c)).append(" x=").append(c.r.left).append('-').append(c.r.right);
            if (c.selected) sb.append(" SEL");
            sb.append("] ");
        }
        return sb.toString();
    }

    // ---------- Gestes ----------

    private void click(Chip c) {
        if (c.node != null && c.node.isClickable() && c.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            log("Clic sur « " + label(c) + " » (action)");
            return;
        }
        Path path = new Path();
        path.moveTo(c.r.centerX(), c.r.centerY());
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60)).build();
        startGesture();
        boolean ok = dispatchGesture(g, callback("Appui"), null);
        if (!ok) gestureBusy = false;
        log("Appui sur « " + label(c) + " » (geste envoyé : " + ok + ")");
    }

    // Glisse la barre vers la gauche de "dist" px : mouvement lent puis doigt immobile
    // avant de relâcher, pour éviter l'effet "lancer" qui irait trop loin.
    private void swipeLeft(int y, int dist) {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int x0 = (int) (dm.widthPixels * 0.92);
        int total = Math.min(dist + ViewConfiguration.get(this).getScaledTouchSlop(), x0 - dp(4));
        int x1 = x0 - total;

        Path move = new Path();
        move.moveTo(x0, y);
        move.lineTo(x1, y);
        GestureDescription.StrokeDescription s1 =
                new GestureDescription.StrokeDescription(move, 0, 400, true);
        Path hold = new Path();
        hold.moveTo(x1, y);
        hold.lineTo(x1 - 1, y);
        GestureDescription.StrokeDescription s2 = s1.continueStroke(hold, 0, 300, false);

        startGesture();
        boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(s1).build(),
                new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) {
                        if (!dispatchGesture(new GestureDescription.Builder().addStroke(s2).build(),
                                callback("Glissement"), null)) {
                            gestureBusy = false;
                            log("Glissement : 2e partie refusée");
                        }
                    }
                    @Override public void onCancelled(GestureDescription g) {
                        gestureBusy = false;
                        log("Glissement annulé par Android");
                    }
                }, null);
        if (!ok) {
            gestureBusy = false;
            log("Geste refusé par Android : désactive puis réactive WA Masque dans Accessibilité");
        }
    }

    private void startGesture() {
        gestureBusy = true;
        gestureStart = SystemClock.uptimeMillis();
    }

    private GestureResultCallback callback(String name) {
        return new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { gestureBusy = false; schedule(150); }
            @Override public void onCancelled(GestureDescription g) {
                gestureBusy = false;
                log(name + " annulé par Android");
            }
        };
    }

    // ---------- Masques ----------

    // Bouton Meta AI : juste au-dessus du bouton vert "nouvelle discussion"
    private void maskMetaAiFallback(List<Item> items, List<Rect> rects, int W, int H) {
        Item fab = null;
        for (Item it : items) {
            Rect r = it.r;
            if (!it.node.isClickable()) continue;
            if (r.left < W * 0.6 || r.centerY() < H * 0.6 || r.centerY() > H * 0.9) continue;
            if (r.width() < W * 0.09 || r.width() > W * 0.3) continue;
            if (Math.abs(r.width() - r.height()) > r.width() * 0.3) continue;
            if (fab == null || r.bottom > fab.r.bottom) fab = it;
        }
        if (fab == null || has(fab, "appel")) return;
        Rect f = fab.r;
        Rect ai = null;
        for (Item it : items) {
            Rect r = it.r;
            if (it == fab || !it.node.isClickable()) continue;
            if (r.bottom <= f.top + dp(4) && r.bottom > f.top - f.height()
                    && r.centerX() > f.left && r.centerX() < f.right
                    && r.width() < f.width() * 1.2 && r.width() > f.width() * 0.4) {
                ai = new Rect(r);
                break;
            }
        }
        if (ai == null) {
            int size = f.width() * 82 / 100;
            int cx = f.centerX();
            int bottom = f.top - f.height() * 30 / 100;
            ai = new Rect(cx - size / 2, bottom - size, cx + size / 2, bottom);
        }
        add(rects, ai);
    }

    private void collect(AccessibilityNodeInfo n, int depth, List<Item> out) {
        if (n == null || depth > 45 || out.size() > 1500) return;
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
        for (int i = 0; i < count; i++) collect(n.getChild(i), depth + 1, out);
    }

    private AccessibilityNodeInfo clickableNode(AccessibilityNodeInfo n, int W) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur.isClickable()) {
                Rect r = new Rect();
                cur.getBoundsInScreen(r);
                if (r.width() < W * 0.6) return cur;
                break;
            }
            cur = cur.getParent();
        }
        return n;
    }

    private Rect target(AccessibilityNodeInfo n, int W) {
        Rect r = new Rect();
        clickableNode(n, W).getBoundsInScreen(r);
        return r;
    }

    private void add(List<Rect> rects, Rect r) {
        if (r.isEmpty()) return;
        int m = dp(3);
        Rect g = new Rect(r.left - m, r.top - m, r.right + m, r.bottom + m);
        for (Rect e : rects) if (e.contains(g)) return;
        for (int i = rects.size() - 1; i >= 0; i--) if (g.contains(rects.get(i))) rects.remove(i);
        rects.add(g);
    }

    private void showRects(List<Rect> rects, int color) {
        if (wm == null) return;
        if (rects.equals(shown) && color == shownColor) return;

        while (views.size() > rects.size()) {
            View v = views.remove(views.size() - 1);
            try { wm.removeView(v); } catch (Exception ignored) { }
        }
        for (int i = 0; i < rects.size(); i++) {
            WindowManager.LayoutParams lp = params(rects.get(i));
            if (i < views.size()) {
                View v = views.get(i);
                v.setBackgroundColor(color);
                try { wm.updateViewLayout(v, lp); } catch (Exception ignored) { }
            } else {
                View v = new View(this);
                v.setBackgroundColor(color);
                v.setClickable(true);
                try { wm.addView(v, lp); views.add(v); } catch (Exception ignored) { }
            }
        }
        shown.clear();
        for (Rect r : rects) shown.add(new Rect(r));
        shownColor = color;
    }

    private WindowManager.LayoutParams params(Rect r) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                r.width(), r.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = r.left;
        lp.y = r.top;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
        return lp;
    }

    private String dump(List<Item> items, String pkg) {
        StringBuilder sb = new StringBuilder("Paquet : " + pkg + "\n");
        for (Item it : items) {
            if (sb.length() > 40000) { sb.append("…(tronqué)"); break; }
            for (int i = 0; i < Math.min(it.depth, 20); i++) sb.append(' ');
            sb.append(it.cls);
            if (!it.text.isEmpty()) sb.append(" \"").append(it.text).append('"');
            if (!it.desc.isEmpty()) sb.append(" [").append(it.desc).append(']');
            if (!it.id.isEmpty()) sb.append(" #").append(it.id.replace(pkg + ":id/", ""));
            sb.append(' ').append(it.r.toShortString());
            if (it.node.isClickable()) sb.append(" CLIC");
            if (it.node.isSelected() || it.node.isChecked()) sb.append(" SELECT");
            if (it.node.isScrollable()) sb.append(" SCROLL");
            if (!it.visible) sb.append(" invisible");
            sb.append('\n');
        }
        return sb.toString();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String norm(String s) {
        return s == null ? "" : s.replace('\u2019', '\'').trim().toLowerCase(Locale.ROOT);
    }

    private static boolean is(Item it, String... words) {
        String t = norm(it.text), d = norm(it.desc);
        for (String w : words) {
            String x = norm(w);
            if (t.equals(x) || d.equals(x)) return true;
        }
        return false;
    }

    private static boolean starts(Item it, String w) {
        String x = norm(w), t = norm(it.text), d = norm(it.desc);
        if (x.isEmpty()) return false;
        return (!t.isEmpty() && t.startsWith(x)) || (!d.isEmpty() && d.startsWith(x));
    }

    private static boolean has(Item it, String w) {
        String x = norm(w);
        return norm(it.text).contains(x) || norm(it.desc).contains(x);
    }
}
