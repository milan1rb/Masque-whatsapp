package com.perso.wamasque;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MaskService extends AccessibilityService {

    static final String PREFS = "config";
    static final String DEFAULT_COLOR = "#0B1014";
    static volatile boolean running = false;
    static volatile boolean forceMacro = false;
    private static volatile String lastDump = "(aucune capture de l'écran principal de WhatsApp)";
    private static final StringBuilder LOG = new StringBuilder();
    private static String lastLogMsg = "";

    private static final int COLOR_TEST = 0x88FF0000;
    private static final String[] KEYS = {"cam", "metaai", "actus", "commu"};

    private WindowManager wm;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, View> slots = new HashMap<>();
    private boolean scheduled = false;
    private boolean inWhatsApp = false;
    private long lastDumpTime = 0;
    private String launcherPkg = "";
    private int W = 1080, H = 2400;

    // Macro : 0 = rien, 1 = cliquer + placer la liste, 3 = vérification finale
    private int phase = 0;
    private long phaseDeadline = 0;
    private long waitUntil = 0;
    private int swipeTries = 0;
    private int lastD = Integer.MIN_VALUE;
    private boolean clickAfter = true;
    private boolean clicked = false;
    private boolean gestureBusy = false;
    private long gestureStart = 0;

    // Retour automatique à la position de base
    private long misalignedSince = 0;
    private int watchPos = Integer.MIN_VALUE;

    // Apprentissage
    private long lastHomeTime = 0;
    private Map<String, Rect> lastWant = new HashMap<>();
    private String savedCacheStr = null;
    private String pendingClass = null;
    private long pendingClassTime = 0;
    private int calibReq = 0;
    private int calibLeft = Integer.MIN_VALUE;
    private String calibName = null;
    private boolean fastBroken = false;
    private long lastFullCheck = 0;
    private long lastChipScan = 0;
    // Apprentissage de la couleur exacte du fond
    private long lastShot = 0;
    private boolean shotBusy = false;
    private boolean overlaysInShot = true;
    private final Map<String, Integer> colorTries = new HashMap<>();

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
        String name;
        boolean selected;
    }

    static class Scan {
        boolean home, bigOther, selectionMode, callsSelected;
        Map<String, Rect> want = new HashMap<>();
        List<Rect> popups = new ArrayList<>();
        List<Item> chipItems = new ArrayList<>();
        AccessibilityNodeInfo homeRoot;
        String homePkg = "com.whatsapp";
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
                + "\n=== Éléments de l'écran WhatsApp ===\n" + lastDump;
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
        log("Service connecté (gestes autorisés : "
                + ((getServiceInfo().getCapabilities()
                & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0) + ")");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (wm == null) return;
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pk = e.getPackageName();
            String cls = String.valueOf(e.getClassName());
            if (isWa(pk) && cls.startsWith("com.whatsapp")) {
                // Écran déjà connu : on réagit tout de suite, sans attendre l'analyse
                pendingClass = cls;
                pendingClassTime = SystemClock.uptimeMillis();
                int score = prefs.getInt("cls:" + cls, 0);
                if (score >= 2) {
                    metrics();
                    showMasks(loadCache(), 0);
                } else if (score <= -2) {
                    showMasks(new HashMap<>(), 0);
                }
            } else if (pk != null && pk.toString().equals(launcherPkg)) {
                showMasks(new HashMap<>(), 0);
            }
        }
        schedule(30);
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
        for (View v : slots.values()) {
            try { wm.removeView(v); } catch (Exception ignored) { }
        }
        slots.clear();
        super.onDestroy();
    }

    private static boolean isWa(CharSequence pkg) {
        return pkg != null && (pkg.toString().equals("com.whatsapp") || pkg.toString().equals("com.whatsapp.w4b"));
    }

    private void metrics() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        W = dm.widthPixels;
        H = dm.heightPixels;
    }

    // ---------- Boucle principale ----------

    private void update() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active == null) {
            if (phase != 0) schedule(200);
            return;
        }
        String pkg = String.valueOf(active.getPackageName());

        if (!isWa(pkg)) {
            showMasks(new HashMap<>(), 0);
            if (!pkg.equals("com.android.systemui")) {
                inWhatsApp = false;
                phase = 0;
            }
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (!inWhatsApp || forceMacro) {
            inWhatsApp = true;
            forceMacro = false;
            String saved = prefs.getString("liste", "").trim();
            boolean wantMacro = !saved.isEmpty()
                    && (prefs.getBoolean("slide", true) || prefs.getBoolean("click", true));
            if (saved.isEmpty()) log("WhatsApp ouvert : aucune liste enregistrée, macro désactivée");
            else if (!wantMacro) log("WhatsApp ouvert : les 2 cases de la macro sont décochées");
            if (wantMacro) {
                startMacro(now, true);
                log("WhatsApp ouvert : macro pour « " + saved + " »");
            }
        }

        metrics();
        Scan sc = scan(now);

        if (!sc.home) {
            learnClass(now, false, sc);
            if (!sc.bigOther && !sc.popups.isEmpty() && now - lastHomeTime < 60000) {
                // Menu ouvert par-dessus l'écran principal : on garde les caches
                showMasks(withoutPopups(lastWant, sc.popups), 0);
            } else {
                showMasks(new HashMap<>(), 0);
            }
            if (phase != 0) runMacro(false, new ArrayList<>(), new ArrayList<>(), now);
            return;
        }

        lastHomeTime = now;
        learnClass(now, true, sc);

        if (now - lastDumpTime > 10000) {
            lastDumpTime = now;
            List<Item> all = new ArrayList<>();
            collect(sc.homeRoot, 0, all, 1500);
            lastDump = dump(all, sc.homePkg);
        }

        Map<String, Rect> want = withoutPopups(sc.want, sc.popups);
        if (sc.popups.isEmpty()) {
            lastWant = new HashMap<>(want);
            saveCache(want);
        }
        showMasks(want, 0);
        if (sc.popups.isEmpty()) learnColors(want, now);

        List<Chip> chips = findChips(sc.chipItems);
        if (chips.isEmpty() && (phase != 0 || prefs.getBoolean("slide", true))
                && now - lastChipScan > 1000) {
            lastChipScan = now;
            List<Item> all = new ArrayList<>();
            collect(sc.homeRoot, 0, all, 900);
            chips = findChips(all);
            if (!chips.isEmpty()) {
                sc.chipItems = all;
                log("Barre des listes trouvée par analyse complète");
            }
        }
        if (phase != 0) {
            runMacro(true, chips, sc.chipItems, now);
        } else if (prefs.getBoolean("slide", true) && sc.popups.isEmpty()) {
            watchdog(chips, sc.chipItems, now);
        }
    }

    private Map<String, Rect> withoutPopups(Map<String, Rect> in, List<Rect> popups) {
        Map<String, Rect> out = new HashMap<>();
        for (Map.Entry<String, Rect> e : in.entrySet()) {
            boolean hit = false;
            for (Rect pop : popups) if (Rect.intersects(e.getValue(), pop)) hit = true;
            if (!hit) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    // ---------- Analyse de l'écran ----------

    private Scan scan(long now) {
        Scan sc = new Scan();
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || !isWa(root.getPackageName())) continue;
            String wp = String.valueOf(root.getPackageName());

            if (!sc.home) {
                boolean ok = fastBroken ? fullHome(root, sc) : fastHome(root, wp, sc);
                if (ok) {
                    sc.home = true;
                    sc.homeRoot = root;
                    sc.homePkg = wp;
                    continue;
                }
            }
            // Autre fenêtre : menu, boîte de dialogue ou autre écran
            List<Item> list = new ArrayList<>();
            collect(root, 0, list, 90);
            if (list.size() >= 90) sc.bigOther = true;
            Rect b = null;
            for (Item it : list) {
                if (!it.visible || it.r.isEmpty() || it.text.isEmpty()) continue;
                Rect r = bounds(clickableNode(it.node));
                if (r.width() > W * 0.95 || r.height() > H * 0.5) r = new Rect(it.r);
                if (b == null) b = new Rect(r); else b.union(r);
            }
            if (b != null) sc.popups.add(b);
        }

        // Sécurité : si une mise à jour de WhatsApp a changé ses identifiants,
        // on repasse en analyse complète (plus lente mais fiable)
        if (!sc.home && !fastBroken && now - lastFullCheck > 5000) {
            lastFullCheck = now;
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || !isWa(root.getPackageName())) continue;
                List<Item> list = new ArrayList<>();
                collect(root, 0, list, 700);
                if (isHome(list)) {
                    fastBroken = true;
                    log("Identifiants WhatsApp inconnus : passage en analyse complète");
                    schedule(30);
                    break;
                }
            }
        }
        return sc;
    }

    // Analyse rapide : quelques requêtes ciblées au lieu de parcourir tout l'écran
    private boolean fastHome(AccessibilityNodeInfo root, String wp, Scan sc) {
        String px = wp + ":id/";
        List<AccessibilityNodeInfo> nav = root.findAccessibilityNodeInfosByViewId(px + "bottom_nav");
        if (nav == null || nav.isEmpty() || !nav.get(0).isVisibleToUser()) return false;

        boolean disc = false, calls = false;
        Map<String, Rect> tabs = new HashMap<>();
        for (String id : new String[]{"navigation_bar_item_small_label_view", "navigation_bar_item_large_label_view"}) {
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(px + id)) {
                String t = norm(n.getText() == null ? "" : n.getText().toString());
                if (t.equals("discussions")) disc = true;
                else if (t.equals("appels")) { calls = true; if (n.isSelected()) sc.callsSelected = true; }
                else if (t.equals("actus")) tabs.put("actus", bounds(clickableNode(n)));
                else if (t.startsWith("communaut")) tabs.put("commu", bounds(clickableNode(n)));
            }
        }
        if (!disc || !calls) return false;
        if (prefs.getBoolean("actus", true) && tabs.containsKey("actus")) sc.want.put("actus", tabs.get("actus"));
        if (prefs.getBoolean("commu", true) && tabs.containsKey("commu")) sc.want.put("commu", tabs.get("commu"));

        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText("Archiver")) {
            if (n.isVisibleToUser() && bounds(n).centerY() < H * 0.15) sc.selectionMode = true;
        }
        if (prefs.getBoolean("cam", true) && !sc.selectionMode) {
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(px + "menuitem_camera")) {
                if (n.isVisibleToUser()) sc.want.put("cam", bounds(n));
            }
        }
        if (prefs.getBoolean("metaai", true) && !sc.callsSelected) {
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(px + "extended_mini_fab")) {
                Rect r = bounds(n);
                if (n.isVisibleToUser() && r.width() < W * 0.4) sc.want.put("metaai", r);
            }
        }

        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(
                px + "conversations_swipe_to_reveal_filter_recycler_view")) {
            if (!n.isVisibleToUser()) continue;
            AccessibilityNodeInfo base = n;
            for (int i = 0; i < 2; i++) {
                AccessibilityNodeInfo up = base.getParent();
                if (up == null) break;
                base = up;
            }
            collect(base, 0, sc.chipItems, 150);
            break;
        }
        return true;
    }

    // Analyse complète (secours)
    private boolean fullHome(AccessibilityNodeInfo root, Scan sc) {
        List<Item> items = new ArrayList<>();
        collect(root, 0, items, 1500);
        if (!isHome(items)) return false;

        for (Item it : items) {
            if (it.visible && it.r.centerY() < H * 0.15
                    && (has(it, "archiv") || starts(it, "Épingl") || starts(it, "Supprimer"))) {
                sc.selectionMode = true;
            }
            if (it.r.centerY() > H * 0.8 && is(it, "Appels") && it.node.isSelected()) sc.callsSelected = true;
        }
        for (Item it : items) {
            if (!it.visible) continue;
            int cy = it.r.centerY();
            if (prefs.getBoolean("cam", true) && !sc.selectionMode && cy < H * 0.15
                    && (it.id.endsWith("menuitem_camera") || is(it, "Caméra", "Appareil photo"))) {
                sc.want.put("cam", new Rect(it.r));
            } else if (prefs.getBoolean("metaai", true) && !sc.callsSelected && cy > H * 0.4
                    && it.r.width() < W * 0.4
                    && (it.id.endsWith("extended_mini_fab") || has(it, "message à l'ia") || has(it, "meta ai"))
                    && !it.node.isEditable()) {
                if (!sc.want.containsKey("metaai")) sc.want.put("metaai", bounds(clickableNode(it.node)));
            } else if (prefs.getBoolean("actus", true) && cy > H * 0.8 && is(it, "Actus")) {
                sc.want.put("actus", bounds(clickableNode(it.node)));
            } else if (prefs.getBoolean("commu", true) && cy > H * 0.8 && starts(it, "Communaut")) {
                sc.want.put("commu", bounds(clickableNode(it.node)));
            }
        }
        sc.chipItems = items;
        return true;
    }

    private boolean isHome(List<Item> items) {
        boolean disc = false, calls = false;
        for (Item it : items) {
            if (it.r.centerY() > H * 0.8) {
                if (is(it, "Discussions")) disc = true;
                if (is(it, "Appels")) calls = true;
            }
        }
        return disc && calls;
    }

    // Retient si un écran de WhatsApp (activité) est l'écran principal ou non
    private void learnClass(long now, boolean home, Scan sc) {
        if (pendingClass == null) return;
        long age = now - pendingClassTime;
        if (age < 300) return;
        if (age > 4000) { pendingClass = null; return; }
        if (!home && !sc.bigOther) return; // pas encore sûr
        String key = "cls:" + pendingClass;
        int score = prefs.getInt(key, 0);
        score = home ? Math.min(5, score + 1) : Math.max(-5, score - 1);
        prefs.edit().putInt(key, score).apply();
        pendingClass = null;
    }

    // ---------- Macro ----------

    private void startMacro(long now, boolean withClick) {
        phase = 1;
        clickAfter = withClick;
        clicked = false;
        phaseDeadline = now + 10000;
        waitUntil = now;
        swipeTries = 0;
        lastD = Integer.MIN_VALUE;
        calibName = null;
    }

    private void runMacro(boolean home, List<Chip> chips, List<Item> items, long now) {
        if (now > phaseDeadline) {
            log("Macro abandonnée : délai dépassé");
            phase = 0;
            return;
        }
        schedule(60);

        if (!home) return;
        if (now < waitUntil) return;
        if (gestureBusy) {
            if (now - gestureStart < 3000) return;
            gestureBusy = false;
        }
        if (chips.isEmpty()) { log("Barre des listes introuvable"); return; }

        String wanted = norm(prefs.getString("liste", ""));
        boolean slide = prefs.getBoolean("slide", true);
        Chip goal = findGoal(chips, wanted);

        if (goal == null) {
            calibName = null;
            if (swipeTries < 6) {
                swipeTries++;
                int dir = toutesVisible(chips) ? 1 : -1;
                log("« " + wanted + " » pas visible, défilement — " + describe(chips));
                swipe(chips.get(0).r.centerY(), dir * W / 2, false);
                waitUntil = now;
            } else {
                log("« " + wanted + " » introuvable — " + describe(chips));
                phase = 0;
            }
            return;
        }

        // Clic immédiat : la liste s'affiche sans attendre le glissement
        if (phase == 1 && clickAfter && !clicked && prefs.getBoolean("click", true)) {
            clicked = true;
            if (goal.selected) log("Déjà sélectionnée : " + goal.name);
            else click(goal);
            if (gestureBusy) return;
        }

        // Apprentissage : compare le déplacement demandé au déplacement réel
        if (calibName != null && calibName.equals(goal.name) && calibLeft != Integer.MIN_VALUE) {
            int moved = calibLeft - goal.r.left;
            if (moved != 0 && Integer.signum(moved) == Integer.signum(calibReq)) {
                int err = Math.abs(calibReq) - Math.abs(moved);
                int calib = prefs.getInt("calib", 0);
                int next = Math.max(-dp(40), Math.min(dp(80), calib + err * 8 / 10));
                prefs.edit().putInt("calib", next).apply();
            }
            calibName = null;
        }

        int base = targetBase(chips, items);        // au-delà : la liste précédente dépasse
        boolean ok = goal.r.left <= base && goal.r.left >= base - dp(8);
        int d = goal.r.left - (base - dp(3));      // on vise un peu à gauche de la limite
        boolean stuck = lastD != Integer.MIN_VALUE && Math.abs(d - lastD) < dp(2);
        int maxTries = phase == 1 ? 5 : 3;

        if (slide && !ok && swipeTries < maxTries && !stuck) {
            swipeTries++;
            lastD = d;
            calibName = goal.name;
            calibLeft = goal.r.left;
            calibReq = d;
            log("Placement de « " + goal.name + " » : " + d + " px (correction apprise "
                    + prefs.getInt("calib", 0) + ")");
            swipe(goal.r.centerY(), d, true);
            waitUntil = now;
        } else {
            if (stuck) log("La barre ne peut pas aller plus loin");
            lastD = Integer.MIN_VALUE;
            if (phase == 1) {
                phase = 3;
                swipeTries = 0;
                waitUntil = now + 250;
            } else {
                log("Macro terminée — " + describe(chips));
                phase = 0;
            }
        }
    }

    // Si la barre reste 2 s plus à droite que la position de base, on la remet en place
    private void watchdog(List<Chip> chips, List<Item> items, long now) {
        String wanted = norm(prefs.getString("liste", ""));
        if (wanted.isEmpty() || chips.isEmpty() || gestureBusy) { misalignedSince = 0; return; }
        Chip goal = findGoal(chips, wanted);
        boolean tooRight;
        int pos;
        if (goal == null) {
            tooRight = toutesVisible(chips);
            pos = chips.get(0).r.left;
        } else {
            tooRight = goal.r.left > targetBase(chips, items) + dp(2);
            pos = goal.r.left;
        }
        if (!tooRight) {
            misalignedSince = 0;
            watchPos = Integer.MIN_VALUE;
            return;
        }
        if (Math.abs(pos - watchPos) > dp(2)) {   // la barre bouge encore : on attend qu'elle s'arrête
            watchPos = pos;
            misalignedSince = now;
        }
        if (now - misalignedSince >= 2000) {
            misalignedSince = 0;
            watchPos = Integer.MIN_VALUE;
            log("Retour à la position de base");
            startMacro(now, false);
            return;
        }
        schedule(250);
    }

    // ---------- Barre des listes ----------
    // WhatsApp les décrit ainsi : RadioButton [Filtre POTO'S, ‎1, désélectionné]

    private List<Chip> findChips(List<Item> items) {
        List<Chip> chips = new ArrayList<>();
        for (Item it : items) {
            String d = it.desc.replace("\u200E", "").trim();
            if (it.r.isEmpty() || it.r.centerY() > H * 0.4) continue;
            if (!norm(d).startsWith("filtre ")) continue;
            String rest = d.substring(7);
            int comma = rest.indexOf(',');
            Chip c = new Chip();
            c.name = (comma >= 0 ? rest.substring(0, comma) : rest).trim();
            String state = norm(d);
            c.selected = it.node.isChecked()
                    || (state.endsWith("sélectionné") && !state.endsWith("désélectionné"));
            c.r = new Rect(it.r);
            c.node = it.node;
            boolean dup = false;
            for (Chip e : chips) if (e.r.equals(c.r)) dup = true;
            if (!dup) chips.add(c);
        }
        chips.sort((a, b) -> Integer.compare(a.r.left, b.r.left));
        return chips;
    }

    private Chip findGoal(List<Chip> chips, String wanted) {
        for (Chip c : chips) if (norm(c.name).startsWith(wanted)) return c;
        return null;
    }

    private boolean toutesVisible(List<Chip> chips) {
        for (Chip c : chips) if (norm(c.name).startsWith("toutes")) return true;
        return false;
    }

    // Position limite : bord de la barre + écart entre deux listes
    private int targetBase(List<Chip> chips, List<Item> items) {
        int barLeft = 0;
        for (Item it : items) if (it.id.endsWith("filter_recycler_view")) { barLeft = it.r.left; break; }
        int gap = Integer.MAX_VALUE;
        for (int i = 0; i + 1 < chips.size(); i++) {
            int g = chips.get(i + 1).r.left - chips.get(i).r.right;
            if (g > 0 && g < gap) gap = g;
        }
        if (gap == Integer.MAX_VALUE) gap = dp(7);
        return barLeft + gap;
    }

    private String describe(List<Chip> chips) {
        StringBuilder sb = new StringBuilder();
        for (Chip c : chips) {
            sb.append('[').append(c.name).append(" x=").append(c.r.left).append('-').append(c.r.right);
            if (c.selected) sb.append(" SEL");
            sb.append("] ");
        }
        return sb.toString();
    }

    // ---------- Gestes ----------

    private void click(Chip c) {
        if (c.node != null && c.node.isClickable() && c.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            log("Clic sur « " + c.name + " »");
            return;
        }
        Path path = new Path();
        path.moveTo(c.r.centerX(), c.r.centerY());
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50)).build();
        startGesture();
        boolean ok = dispatchGesture(g, callback("Appui"), null);
        if (!ok) gestureBusy = false;
        log("Appui sur « " + c.name + " » (geste envoyé : " + ok + ")");
    }

    // dist > 0 : contenu vers la gauche, dist < 0 : vers la droite.
    // Mouvement rapide puis doigt immobile avant de relâcher (pas d'effet "lancer"),
    // loin des bords de l'écran (geste retour d'Android).
    private void swipe(int y, int dist, boolean precise) {
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        if (precise) slop += prefs.getInt("calib", 0);
        int x0, x1;
        if (dist > 0) {
            x0 = (int) (W * 0.85);
            x1 = Math.max((int) (W * 0.08), x0 - dist - slop);
        } else {
            x0 = (int) (W * 0.15);
            x1 = Math.min((int) (W * 0.92), x0 - dist + slop);
        }
        int step = x1 > x0 ? 1 : -1;

        Path move = new Path();
        move.moveTo(x0, y);
        move.lineTo(x1, y);
        GestureDescription.StrokeDescription s1 =
                new GestureDescription.StrokeDescription(move, 0, 200, true);
        Path hold = new Path();
        hold.moveTo(x1, y);
        hold.lineTo(x1 + step, y);
        GestureDescription.StrokeDescription s2 = s1.continueStroke(hold, 0, 110, false);

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
            @Override public void onCompleted(GestureDescription g) { gestureBusy = false; schedule(40); }
            @Override public void onCancelled(GestureDescription g) {
                gestureBusy = false;
                log(name + " annulé par Android");
            }
        };
    }

    // ---------- Masques ----------

    private int baseColor() {
        try {
            return Color.parseColor(prefs.getString("color", DEFAULT_COLOR).trim());
        } catch (Exception e) {
            return Color.parseColor(DEFAULT_COLOR);
        }
    }

    private int colorFor(String key) {
        if (prefs.getBoolean("debug", false)) return COLOR_TEST;
        return prefs.getInt("col:" + key, baseColor());
    }

    // Prend une capture de l'écran, compare la couleur du cache à celle du fond juste à côté
    // et corrige, jusqu'à ce que les deux soient identiques.
    private void learnColors(Map<String, Rect> want, long now) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        if (!prefs.getBoolean("calibcolor", false) || prefs.getBoolean("debug", false)) return;
        if (shotBusy || want.isEmpty() || now - lastShot < 1500) return;
        boolean todo = false;
        for (String k : want.keySet()) if (colorTries.getOrDefault(k, 0) < 8) todo = true;
        if (!todo) { finishColor("8 essais"); return; }

        lastShot = now;
        shotBusy = true;
        log("Mesure de la couleur du fond…");
        final Map<String, Rect> snapshot = new HashMap<>(want);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    shotBusy = false;
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                        if (hw != null) {
                            Bitmap bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                            if (bmp != null) {
                                analyzeColors(bmp, snapshot);
                                bmp.recycle();
                            }
                        }
                        result.getHardwareBuffer().close();
                    } catch (Exception e) {
                        log("Couleur : " + e);
                    }
                }
                @Override public void onFailure(int errorCode) {
                    shotBusy = false;
                }
            });
        } catch (Exception e) {
            shotBusy = false;
        }
    }

    private void analyzeColors(Bitmap bmp, Map<String, Rect> want) {
        float sx = bmp.getWidth() / (float) W;
        float sy = bmp.getHeight() / (float) H;
        boolean changed = false;
        SharedPreferences.Editor ed = prefs.edit();

        for (Map.Entry<String, Rect> e : want.entrySet()) {
            String key = e.getKey();
            Rect r = e.getValue();
            int tries = colorTries.getOrDefault(key, 0);
            if (tries >= 8) continue;

            int pad = dp(5);
            int[] in = new int[]{
                    pixel(bmp, sx, sy, r.centerX(), r.centerY()),
                    pixel(bmp, sx, sy, r.left + pad, r.top + pad),
                    pixel(bmp, sx, sy, r.right - pad, r.bottom - pad),
                    pixel(bmp, sx, sy, r.left + pad, r.bottom - pad)};
            int lo = 255 * 3, hi = 0, inside = -1;
            for (int c : in) {
                if (c == -1) continue;
                int sum = Color.red(c) + Color.green(c) + Color.blue(c);
                lo = Math.min(lo, sum);
                hi = Math.max(hi, sum);
                inside = c;
            }
            if (inside == -1) continue;
            if (hi - lo > 12) {
                if (overlaysInShot) log("Capture sans les caches : réglage direct de la couleur");
                overlaysInShot = false;
            }

            // Fond juste à côté du cache : on garde le point le plus sombre (le fond, pas une icône)
            int out = -1, best = Integer.MAX_VALUE;
            int off = dp(8);
            int[][] pts = {{r.left - off, r.centerY()}, {r.right + off, r.centerY()},
                    {r.centerX(), r.top - off}, {r.centerX(), r.bottom + off},
                    {r.left - off, r.top - off}, {r.right + off, r.bottom + off}};
            for (int[] pt : pts) {
                int c = pixel(bmp, sx, sy, pt[0], pt[1]);
                if (c == -1) continue;
                int sum = Color.red(c) + Color.green(c) + Color.blue(c);
                if (sum < best) { best = sum; out = c; }
            }
            if (out == -1) continue;

            int cur = colorFor(key);
            int target;
            if (overlaysInShot) {
                target = Color.rgb(
                        clamp(Color.red(cur) + Color.red(out) - Color.red(inside)),
                        clamp(Color.green(cur) + Color.green(out) - Color.green(inside)),
                        clamp(Color.blue(cur) + Color.blue(out) - Color.blue(inside)));
            } else {
                target = out;
            }
            colorTries.put(key, tries + 1);
            if (target != cur) {
                ed.putInt("col:" + key, target);
                changed = true;
                log("Couleur ajustée pour " + key + " : " + String.format("#%06X", target & 0xFFFFFF));
            }
        }
        if (changed) {
            ed.apply();
            schedule(30);
        } else {
            finishColor("couleur stable");
        }
    }

    private void finishColor(String why) {
        if (!prefs.getBoolean("calibcolor", false)) return;
        prefs.edit().putBoolean("calibcolor", false).apply();
        colorTries.clear();
        StringBuilder sb = new StringBuilder();
        for (String k : KEYS) sb.append(k).append('=').append(String.format("#%06X", colorFor(k) & 0xFFFFFF)).append(' ');
        log("Calibration couleur terminée (" + why + ") : " + sb);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private int pixel(Bitmap bmp, float sx, float sy, int x, int y) {
        int px = Math.round(x * sx), py = Math.round(y * sy);
        if (px < 0 || py < 0 || px >= bmp.getWidth() || py >= bmp.getHeight()) return -1;
        return bmp.getPixel(px, py);
    }

    private String cacheKey() {
        return "cache:" + W + "x" + H;
    }

    private void saveCache(Map<String, Rect> want) {
        StringBuilder sb = new StringBuilder();
        for (String k : KEYS) {
            Rect r = want.get(k);
            if (r == null) continue;
            sb.append(k).append(':').append(r.left).append(',').append(r.top).append(',')
                    .append(r.right).append(',').append(r.bottom).append(';');
        }
        String str = sb.toString();
        if (!str.equals(savedCacheStr)) {
            savedCacheStr = str;
            prefs.edit().putString(cacheKey(), str).apply();
        }
    }

    private Map<String, Rect> loadCache() {
        Map<String, Rect> m = new HashMap<>();
        for (String part : prefs.getString(cacheKey(), "").split(";")) {
            String[] kv = part.split(":");
            if (kv.length != 2 || !prefs.getBoolean(kv[0], true)) continue;
            String[] n = kv[1].split(",");
            if (n.length != 4) continue;
            try {
                m.put(kv[0], new Rect(Integer.parseInt(n[0]), Integer.parseInt(n[1]),
                        Integer.parseInt(n[2]), Integer.parseInt(n[3])));
            } catch (NumberFormatException ignored) { }
        }
        return m;
    }

    // Une fenêtre fixe par élément, cachée/affichée sur place, sans animation
    private void showMasks(Map<String, Rect> want, int unusedColor) {
        if (wm == null) return;
        int m = dp(2);
        for (String key : KEYS) {
            Rect r = want.get(key);
            View v = slots.get(key);

            if (r == null || r.isEmpty()) {
                if (v != null && v.getVisibility() == View.VISIBLE) {
                    v.setVisibility(View.INVISIBLE);
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
                    lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    try { wm.updateViewLayout(v, lp); } catch (Exception ignored) { }
                }
                continue;
            }
            boolean tab = key.equals("actus") || key.equals("commu");
            Rect g = tab ? new Rect(r.left, r.top + dp(1), r.right, r.bottom)
                         : new Rect(r.left - m, r.top - m, r.right + m, r.bottom + m);

            if (v == null) {
                v = new View(this);
                paint(v, key, colorFor(key));
                v.setClickable(true);
                try { wm.addView(v, params(g)); slots.put(key, v); } catch (Exception ignored) { }
                continue;
            }
            paint(v, key, colorFor(key));
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
            boolean changed = false;
            if ((lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                changed = true;
            }
            if (lp.x != g.left || lp.y != g.top || lp.width != g.width() || lp.height != g.height()) {
                lp.x = g.left;
                lp.y = g.top;
                lp.width = g.width();
                lp.height = g.height();
                changed = true;
            }
            if (changed) {
                try { wm.updateViewLayout(v, lp); } catch (Exception ignored) { }
            }
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
        }
    }

    private final Map<String, Integer> painted = new HashMap<>();

    private void paint(View v, String key, int color) {
        Integer prev = painted.get(key);
        if (prev != null && prev == color) return;
        painted.put(key, color);
        if (key.equals("metaai")) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color);
            bg.setCornerRadius(dp(18));
            v.setBackground(bg);
        } else {
            v.setBackgroundColor(color);
        }
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
        lp.windowAnimations = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
        return lp;
    }

    // ---------- Outils ----------

    private Item makeItem(AccessibilityNodeInfo n, int depth) {
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
        return it;
    }

    private void collect(AccessibilityNodeInfo n, int depth, List<Item> out, int max) {
        if (n == null || depth > 45 || out.size() >= max) return;
        out.add(makeItem(n, depth));
        int count = n.getChildCount();
        for (int i = 0; i < count && out.size() < max; i++) collect(n.getChild(i), depth + 1, out, max);
    }

    private AccessibilityNodeInfo clickableNode(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur.isClickable()) {
                if (bounds(cur).width() < W * 0.6) return cur;
                break;
            }
            cur = cur.getParent();
        }
        return n;
    }

    private static Rect bounds(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r;
    }

    private String dump(List<Item> items, String pkg) {
        StringBuilder sb = new StringBuilder("Paquet : " + pkg + "\n");
        for (Item it : items) {
            if (sb.length() > 40000) { sb.append("…(tronqué)"); break; }
            if (!it.visible) continue;
            for (int i = 0; i < Math.min(it.depth, 20); i++) sb.append(' ');
            sb.append(it.cls);
            if (!it.text.isEmpty()) sb.append(" \"").append(it.text).append('"');
            if (!it.desc.isEmpty()) sb.append(" [").append(it.desc).append(']');
            if (!it.id.isEmpty()) sb.append(" #").append(it.id.replace(pkg + ":id/", ""));
            sb.append(' ').append(it.r.toShortString());
            if (it.node.isClickable()) sb.append(" CLIC");
            if (it.node.isSelected() || it.node.isChecked()) sb.append(" SELECT");
            if (it.node.isScrollable()) sb.append(" SCROLL");
            sb.append('\n');
        }
        return sb.toString();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String norm(String s) {
        return s == null ? "" : s.replace('\u2019', '\'').replace("\u200E", "").trim().toLowerCase(Locale.ROOT);
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
