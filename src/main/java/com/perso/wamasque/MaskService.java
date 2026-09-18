package com.perso.wamasque;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
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
import android.view.SurfaceControlViewHost;
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
    static final String DEFAULT_COLOR = "#080E15";
    static final String DEFAULT_COLOR_TOP = "#0A1013";
    static final String DEFAULT_COLOR_DIM = "#060A0D";
    static final String KEY_DIM = "colordim";
    static volatile boolean running = false;
    static volatile boolean forceMacro = false;
    private static volatile String lastDump = "(aucune capture de l'écran principal de WhatsApp)";
    private static final StringBuilder LOG = new StringBuilder();
    private static String lastLogMsg = "";

    private static final int COLOR_TEST = 0x88FF0000;
    private static final String[] KEYS = {"title", "cam", "metaai", "actus", "commu", "disctxt", "appelstxt"};
    private static final String KEY_TOP = "colortop";

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
    private boolean clickAfter = true;
    private int tries = 0;
    private int lastLeft = Integer.MIN_VALUE;
    private boolean gestureBusy = false;
    private long gestureStart = 0;

    // Retour automatique à la position de base
    private long misalignedSince = 0;
    private int watchPos = Integer.MIN_VALUE;

    // Apprentissage
    private long lastHomeTime = 0;
    private Map<String, Rect> lastWant = new HashMap<>();
    private final Map<String, Rect> fixed = new HashMap<>();
    private String savedCacheStr = null;
    private String pendingClass = null;
    private long pendingClassTime = 0;
    private String currentClass = "";
    private String pendingClick = null;
    private long pendingClickTime = 0;
    private int pendingFinger = 0;
    private long suppressUntil = 0;
    private boolean fastBroken = false;
    private int fastFails = 0;
    private long lastFullCheck = 0;
    private long lastChipScan = 0;
    private int lastHomeWinId = -1;
    // Apprentissage de la couleur exacte du fond
    private MaskCanvas canvas = null;
    private View picker = null;
    private View tuner = null;
    private View adjuster = null;
    private int adjustIndex = 0;
    private int tuneZone = 1;
    private boolean shotBusy = false;
    private long lastShot = 0;

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
        boolean homeActive = true;
        int homeWindowId = -1;
        Rect homeBounds = new Rect();
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
        loadPositions();
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
        long t = SystemClock.uptimeMillis();

        // Apprentissage : ce bouton mène en général à tel écran, donc on prépare les caches avant
        if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED && isWa(e.getPackageName())) {
            String key = clickKey(e);
            // Une ligne de discussion ouvre TOUJOURS une conversation : on retire les caches
            // à l'instant de l'appui, sans attendre que la nouvelle fenêtre arrive.
            boolean leaves = key != null && (key.endsWith("contact_row_container")
                    || key.endsWith("conversations_row_header")
                    || key.endsWith("conversations_archive_header")
                    || key.endsWith(":id/fab")
                    || key.endsWith("search_bar_inner_layout"));
            if (!leaves) {
                // beaucoup de lignes de discussion sont signalées par leur nom, sans identifiant :
                // on reconnaît alors une ligne à sa forme, large et située dans la liste
                AccessibilityNodeInfo src = e.getSource();
                if (src != null) {
                    Rect b = bounds(src);
                    metrics();
                    if (b.width() > W * 0.6 && b.centerY() > H * 0.22 && b.centerY() < H * 0.88) {
                        leaves = true;
                    }
                }
            }
            if (leaves) {
                suppressUntil = t + 1200;
                showMasks(new HashMap<>(), 0);
            }
            if (key != null) {
                log("Appui sur " + key.replace("com.whatsapp:id/", ""));
                pendingClick = key;
                pendingClickTime = t;
                if (prefs.getBoolean("learn", true) && prefs.getInt("gon:" + key, 0) >= 1) {
                    String cls = prefs.getString("go:" + key, "");
                    int score = prefs.getInt("cls:" + cls, 0);
                    if (score >= 1 && imeBounds() == null) {
                        metrics();
                        suppressUntil = 0;
                        showMasks(loadCache(cls), 0);
                    }
                }
            }
        }

        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pk = e.getPackageName();
            String cls = String.valueOf(e.getClassName());
            if (isWa(pk) && cls.startsWith("com.whatsapp")) {
                // Écran déjà connu : on réagit tout de suite, sans attendre l'analyse
                pendingClass = cls;
                pendingClassTime = t;
                currentClass = cls;
                if (prefs.getBoolean("learn", true) && pendingClick != null
                        && t - pendingClickTime < 1500) {
                    String k = "go:" + pendingClick;
                    if (cls.equals(prefs.getString(k, ""))) {
                        prefs.edit().putInt("gon:" + pendingClick,
                                Math.min(5, prefs.getInt("gon:" + pendingClick, 0) + 1)).apply();
                    } else {
                        prefs.edit().putString(k, cls).putInt("gon:" + pendingClick, 1).apply();
                    }
                    pendingClick = null;
                }
                int score = prefs.getInt("cls:" + cls, 0);
                if (score >= 1 && imeBounds() == null) {
                    metrics();
                    suppressUntil = 0;
                    expandUntil = t + 450;
                    showMasks(loadCache(cls), 0);
                    schedule(16);
                }
                // écran inconnu : on ne touche à rien, l'analyse tranchera juste après
            } else if (pk != null && pk.toString().equals(launcherPkg)) {
                showMasks(new HashMap<>(), 0);
            }
        }
        int type = e.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            fastUntil = t + 900;
            scheduleNow();
        } else {
            schedule(30);
        }
    }

    private String clickKey(AccessibilityEvent e) {
        AccessibilityNodeInfo src = e.getSource();
        String id = src == null || src.getViewIdResourceName() == null ? "" : src.getViewIdResourceName();
        String txt = e.getText() == null || e.getText().isEmpty() ? "" : String.valueOf(e.getText().get(0));
        String key = !id.isEmpty() ? id : norm(txt);
        return key.isEmpty() ? null : key;
    }

    private void schedule(long delay) {
        if (!scheduled) {
            scheduled = true;
            handler.postDelayed(tick, delay);
        }
    }

    private void scheduleNow() {
        handler.removeCallbacks(tick);
        scheduled = true;
        handler.post(tick);
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
        if (canvas != null) {
            try { wm.removeView(canvas); } catch (Exception ignored) { }
            canvas = null;
        }
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
            hidePicker();
            hideTuner();
            hideAdjuster();
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
        if (now < suppressUntil) {
            showMasks(new HashMap<>(), 0);
            schedule(120);
            return;
        }
        if (prefs.getBoolean("tune", false)) showTuner(); else hideTuner();
        if (prefs.getBoolean("adjust", false)) showAdjuster(); else hideAdjuster();
        Scan sc = scan(now);

        if (sc.bigOther) {
            // une discussion (ou un autre écran complet) est passée devant : retrait immédiat
            showMasks(new HashMap<>(), 0);
            if (now < fastUntil) schedule(16);
            if (phase != 0) runMacro(false, new ArrayList<>(), new ArrayList<>(), now);
            return;
        }
        if (!sc.home) {
            learnClass(now, false, sc);
            if (!sc.bigOther && now - lastHomeTime < 1500) {
                // fiche contact, menu, ou simple hoquet d'analyse pendant une animation :
                // l'écran principal est toujours là derrière, on ne touche à rien
                if (!dimMasks) {
                    dimMasks = true;
                    if (canvas != null) canvas.invalidate();
                }
                showMasks(lastWant, 0);
            } else {
                showMasks(new HashMap<>(), 0);
            }
            if (now < fastUntil) schedule(16);
            if (phase != 0) runMacro(false, new ArrayList<>(), new ArrayList<>(), now);
            return;
        }

        lastHomeTime = now;
        suppressUntil = 0;
        fastFails = 0;
        learnClass(now, true, sc);

        if (now - lastDumpTime > 10000) {
            lastDumpTime = now;
            List<Item> all = new ArrayList<>();
            collect(sc.homeRoot, 0, all, 1500);
            lastDump = dump(all, sc.homePkg);
        }

        if (prefs.getBoolean("reset_pos", false)) {
            prefs.edit().putBoolean("reset_pos", false).apply();
            for (String k : KEYS) prefs.edit().remove(posKey(k)).apply();
            fixed.clear();
            log("Positions des caches réinitialisées");
        }
        Map<String, Rect> masks = fixedMasks(sc);
        boolean attached = attachOverlay(sc, masks);
        boolean dim = !attached && (!sc.popups.isEmpty() || !sc.homeActive);
        if (dim != dimMasks) {
            dimMasks = dim;
            if (canvas != null) canvas.invalidate();
        }
        // attaché à la fenêtre : les menus passent naturellement au-dessus, rien à retirer
        Map<String, Rect> want = masks;   // jamais retirés : ils restent identiques
        noteMotion(now, want);
        drawOnDisplay = !attached;
        Rect ime = imeBounds();
        if (ime != null) {
            List<Rect> one = new ArrayList<>();
            one.add(ime);
            want = withoutPopups(want, one);
        }
        if (sc.popups.isEmpty()) {
            lastWant = new HashMap<>(want);
            saveCache(want);
        }
        showMasks(want, 0);

        List<Chip> chips = findChips(sc.chipItems);
        if (chips.isEmpty() && phase != 0 && now - lastChipScan > 1000) {
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

    private Rect imeBounds() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect r = new Rect();
                    w.getBoundsInScreen(r);
                    if (!r.isEmpty()) return r;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // Tous les caches sont à une position fixe, retenue une fois pour toutes.
    // La détection ne sert plus qu'à trouver la position la première fois.
    private Map<String, Rect> fixedMasks(Scan sc) {
        Map<String, Rect> out = new HashMap<>();
        for (String key : KEYS) {
            if (!prefs.getBoolean(key, true)) continue;
            if (sc.selectionMode && (key.equals("cam") || key.equals("title"))) continue;
            Rect r = fixed.get(key);
            if (key.equals("metaai")) {
                Rect seenAi = sc.want.get(key);
                if (r != null && (r.width() > W * 0.35
                        || (seenAi != null && !seenAi.equals(r)))) {
                    r = null;                  // il a bougé : on reprend sa vraie position
                    fixed.remove(key);
                }
            }
            if (r == null) {
                Rect seen = sc.want.get(key);
                if (seen == null) continue;
                r = new Rect(seen);
                fixed.put(key, r);
                savePos(key, r);
                log("Position retenue pour " + key + " : " + r.toShortString());
            }
            out.put(key, r);
        }
        return out;
    }

    private String posKey(String key) {
        return "pos:" + key + ":" + W + "x" + H;
    }

    private void savePos(String key, Rect r) {
        prefs.edit().putString(posKey(key),
                r.left + "," + r.top + "," + r.right + "," + r.bottom).apply();
    }

    private void loadPositions() {
        fixed.clear();
        for (String key : KEYS) {
            String v = prefs.getString(posKey(key), "");
            String[] n = v.split(",");
            if (n.length != 4) continue;
            try {
                fixed.put(key, new Rect(Integer.parseInt(n[0]), Integer.parseInt(n[1]),
                        Integer.parseInt(n[2]), Integer.parseInt(n[3])));
            } catch (NumberFormatException ignored) { }
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
                    sc.homeWindowId = w.getId();
                    lastHomeWinId = w.getId();
                    sc.homeActive = w.isActive();
                    w.getBoundsInScreen(sc.homeBounds);
                    continue;
                }
            }
            // Autre fenêtre : menu, boîte de dialogue ou autre écran
            List<Item> list = new ArrayList<>();
            collect(root, 0, list, 80);
            // la fenêtre de l'écran principal ne doit jamais être prise pour un autre écran,
            // même si la reconnaissance échoue une fraction de seconde pendant une animation
            if (w.getId() != lastHomeWinId) {
                if (list.size() >= 60) sc.bigOther = true;       // un écran entier, pas une fiche
                for (Item it : list) if (it.node.isEditable()) sc.bigOther = true;   // champ de saisie
            }
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
                    if (++fastFails >= 3) {
                        fastBroken = true;
                        log("Identifiants WhatsApp inconnus : passage en analyse complète");
                        schedule(30);
                    }
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
        if (nav == null || nav.isEmpty() || bounds(nav.get(0)).isEmpty()) return false;

        boolean disc = false, calls = false;
        Map<String, Rect> tabs = new HashMap<>();
        for (String id : new String[]{"navigation_bar_item_small_label_view", "navigation_bar_item_large_label_view"}) {
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(px + id)) {
                String t = norm(n.getText() == null ? "" : n.getText().toString());
                if (t.equals("discussions")) {
                    disc = true;
                    if (prefs.getBoolean("disctxt", true) && n.isVisibleToUser())
                        sc.want.put("disctxt", bounds(n));
                } else if (t.equals("appels")) {
                    calls = true;
                    if (n.isSelected()) sc.callsSelected = true;
                    if (prefs.getBoolean("appelstxt", true) && n.isVisibleToUser())
                        sc.want.put("appelstxt", bounds(n));
                }
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
        if (prefs.getBoolean("title", true) && !sc.selectionMode) {
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(px + "toolbar_logo")) {
                if (n.isVisibleToUser()) sc.want.put("title", bounds(n));
            }
        }
        if (prefs.getBoolean("metaai", true) && !sc.callsSelected) {
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(px + "extended_mini_fab")) {
                Rect r = bounds(n);
                // uniquement le petit bouton rond, jamais la version allongée
                if (n.isVisibleToUser() && r.width() < W * 0.35) sc.want.put("metaai", r);
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
            if (prefs.getBoolean("title", true) && !sc.selectionMode && cy < H * 0.15
                    && it.id.endsWith("toolbar_logo")) {
                sc.want.put("title", new Rect(it.r));
            } else if (prefs.getBoolean("cam", true) && !sc.selectionMode && cy < H * 0.15
                    && (it.id.endsWith("menuitem_camera") || is(it, "Caméra", "Appareil photo"))) {
                sc.want.put("cam", new Rect(it.r));
            } else if (prefs.getBoolean("metaai", true) && !sc.callsSelected && cy > H * 0.4
                    && it.r.width() < W * 0.35
                    && (it.id.endsWith("extended_mini_fab") || has(it, "message à l'ia") || has(it, "meta ai"))
                    && !it.node.isEditable()) {
                if (!sc.want.containsKey("metaai")) sc.want.put("metaai", bounds(clickableNode(it.node)));
            } else if (prefs.getBoolean("actus", true) && cy > H * 0.8 && is(it, "Actus")) {
                sc.want.put("actus", bounds(clickableNode(it.node)));
            } else if (prefs.getBoolean("commu", true) && cy > H * 0.8 && starts(it, "Communaut")) {
                sc.want.put("commu", bounds(clickableNode(it.node)));
            } else if (prefs.getBoolean("disctxt", true) && cy > H * 0.8 && is(it, "Discussions")
                    && !it.text.isEmpty()) {
                sc.want.put("disctxt", new Rect(it.r));
            } else if (prefs.getBoolean("appelstxt", true) && cy > H * 0.8 && is(it, "Appels")
                    && !it.text.isEmpty()) {
                sc.want.put("appelstxt", new Rect(it.r));
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
        if (age < 120) return;
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
        tries = 0;
        lastLeft = Integer.MIN_VALUE;
        phaseDeadline = now + 15000;
        waitUntil = now;
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
        Chip goal = findGoal(chips, wanted);

        if (phase == 1) {
            if (clickAfter && prefs.getBoolean("click", true)) {
                if (goal == null) log("Liste « " + wanted + " » pas visible — " + describe(chips));
                else if (goal.selected) log("Déjà sélectionnée : " + goal.name);
                else click(goal);
            }
            phase = 2;
            tries = 0;
            lastLeft = Integer.MIN_VALUE;
            waitUntil = now + 150;   // WhatsApp refait la mise en page après le clic
            log("Clic fait, placement dans 150 ms");
            return;
        }

        if (!prefs.getBoolean("slide", true)) { phase = 0; return; }

        if (goal == null) {
            // la liste est sortie de l'écran : on fait défiler la barre pour la retrouver
            if (tries < 5) {
                tries++;
                int dir = toutesVisible(chips) ? 1 : -1;
                swipeOnBar(chips, dir * W / 2);
                waitUntil = now;
            } else {
                log("« " + wanted + " » introuvable — " + describe(chips));
                phase = 0;
            }
            return;
        }

        int target = prefs.getInt("posx", 10);
        int d = goal.r.left - target;              // > 0 : trop à droite
        boolean stuck = lastLeft != Integer.MIN_VALUE && Math.abs(goal.r.left - lastLeft) < dp(2);

        // Apprentissage : combien la barre a réellement bougé pour le geste demandé
        if (prefs.getBoolean("learn", true) && lastLeft != Integer.MIN_VALUE && pendingFinger > 0) {
            int moved = Math.abs(lastLeft - goal.r.left);
            if (moved > 40) {
                int measured = (int) Math.round(moved * 1000.0 / pendingFinger);
                int gain = prefs.getInt("gain", 1000);
                int next = Math.max(500, Math.min(1600, (gain * 3 + measured) / 4));
                prefs.edit().putInt("gain", next).apply();
            }
            pendingFinger = 0;
        }

        if (Math.abs(d) <= dp(6) || tries >= 6 || stuck) {
            if (phase == 2) {
                // une dernière vérification une fois que WhatsApp a fini de bouger
                phase = 3;
                tries = 0;
                lastLeft = Integer.MIN_VALUE;
                waitUntil = now + 350;
                log("Placement : « " + goal.name + " » à x=" + goal.r.left + " (cible " + target + ")");
                return;
            }
            log("Macro terminée : « " + goal.name + " » à x=" + goal.r.left
                    + (stuck ? " (butée)" : "") + " — cible " + target);
            phase = 0;
            return;
        }
        tries++;
        lastLeft = goal.r.left;
        log("Glissement de " + d + " px (« " + goal.name + " » x=" + goal.r.left + " → " + target + ")");
        swipeOnBar(chips, d);
        waitUntil = now;
    }

    // Le glissement se fait toujours sur la ligne des listes, jamais ailleurs
    private void swipeOnBar(List<Chip> chips, int dist) {
        int top = Integer.MAX_VALUE, bottom = 0;
        for (Chip c : chips) {
            top = Math.min(top, c.r.top);
            bottom = Math.max(bottom, c.r.bottom);
        }
        swipe((top + bottom) / 2, dist);
    }

    // Si la liste s'éloigne vers la droite et que la barre reste immobile 2 s, on la remet en place
    private void watchdog(List<Chip> chips, List<Item> items, long now) {
        if (chips.isEmpty() || gestureBusy || !prefs.getBoolean("slide", true)) {
            misalignedSince = 0;
            return;
        }
        Chip goal = findGoal(chips, norm(prefs.getString("liste", "")));
        int target = prefs.getInt("posx", 10);
        int pos = goal == null ? chips.get(0).r.left : goal.r.left;
        boolean off = goal == null ? toutesVisible(chips) : goal.r.left > target + dp(6);
        if (!off) {
            misalignedSince = 0;
            watchPos = Integer.MIN_VALUE;
            return;
        }
        if (Math.abs(pos - watchPos) > dp(2)) {   // la barre bouge encore : on attend
            watchPos = pos;
            misalignedSince = now;
        }
        if (now - misalignedSince >= 2000) {
            misalignedSince = 0;
            watchPos = Integer.MIN_VALUE;
            log("Retour à la position de base");
            startMacro(now, false);
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
    private void swipe(int y, int dist) {
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        int gain = prefs.getInt("gain", 1000);
        int finger = prefs.getBoolean("learn", true) ? (int) Math.round(dist * 1000.0 / gain) : dist;
        pendingFinger = Math.abs(finger);
        swipeFinger(y, finger, slop);
    }

    // Glissement du doigt d'une distance exacte, sans correction
    private void swipeRaw(int y, int dist) {
        swipeFinger(y, dist, 0);
    }

    private void swipeFinger(int y, int dist, int slop) {
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
                new GestureDescription.StrokeDescription(move, 0, 150, true);
        Path hold = new Path();
        hold.moveTo(x1, y);
        hold.lineTo(x1 + step, y);
        GestureDescription.StrokeDescription s2 = s1.continueStroke(hold, 0, 90, false);

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
                        if (tries > 0) tries--;
                        log("Glissement annulé par Android, nouvel essai");
                        schedule(120);
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
                if (tries > 0) tries--;   // geste avalé pendant une animation : on réessaiera
                log(name + " annulé par Android, nouvel essai");
                schedule(120);
            }
        };
    }

    // ---------- Masques ----------

    static boolean isTopKey(String key) {
        return key.equals("cam") || key.equals("title");
    }

    private int maskColor(String key) {
        if (prefs.getBoolean("debug", false)) return COLOR_TEST;
        boolean top = isTopKey(key);
        String pref = dimMasks ? KEY_DIM : (top ? KEY_TOP : "color");
        String def = dimMasks ? DEFAULT_COLOR_DIM : (top ? DEFAULT_COLOR_TOP : DEFAULT_COLOR);
        try {
            return Color.parseColor(prefs.getString(pref, def).trim());
        } catch (Exception e) {
            return Color.parseColor(def);
        }
    }

    private int avg(Bitmap bmp, int x, int y) {
        long r = 0, g = 0, b = 0;
        int n = 0;
        float sx = bmp.getWidth() / (float) W, sy = bmp.getHeight() / (float) H;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                int px = Math.round((x + dx) * sx), py = Math.round((y + dy) * sy);
                if (px < 0 || py < 0 || px >= bmp.getWidth() || py >= bmp.getHeight()) continue;
                int c = bmp.getPixel(px, py);
                r += Color.red(c); g += Color.green(c); b += Color.blue(c);
                n++;
            }
        }
        return n == 0 ? 0 : Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ---------- Réglage manuel de la couleur, en direct sur WhatsApp ----------

    private void showTuner() {
        if (tuner != null) return;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackgroundColor(0xEE202020);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));

        final android.widget.TextView label = new android.widget.TextView(this);
        label.setTextColor(0xFFFFFFFF);
        box.addView(label);

        android.widget.LinearLayout zone = new android.widget.LinearLayout(this);
        zone.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.Button top = new android.widget.Button(this);
        android.widget.Button bottom = new android.widget.Button(this);
        android.widget.Button over = new android.widget.Button(this);
        top.setText("Haut");
        bottom.setText("Bas");
        over.setText("Fiche");
        Runnable refresh = () -> {
            top.setTextColor(tuneZone == 0 ? 0xFF4CAF50 : 0xFFFFFFFF);
            bottom.setTextColor(tuneZone == 1 ? 0xFF4CAF50 : 0xFFFFFFFF);
            over.setTextColor(tuneZone == 2 ? 0xFF4CAF50 : 0xFFFFFFFF);
            label.setText(tuneLabel() + " : " + tuneHex());
        };
        top.setOnClickListener(v -> { tuneZone = 0; refresh.run(); });
        bottom.setOnClickListener(v -> { tuneZone = 1; refresh.run(); });
        over.setOnClickListener(v -> { tuneZone = 2; refresh.run(); });
        zone.addView(top);
        zone.addView(bottom);
        zone.addView(over);
        box.addView(zone);
        refresh.run();

        int[][] steps = {{1, 1, 1}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        String[] names = {"Tout", "R", "V", "B"};
        for (int i = 0; i < steps.length; i++) {
            final int[] st = steps[i];
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            android.widget.TextView t = new android.widget.TextView(this);
            t.setText(names[i] + "  ");
            t.setTextColor(0xFFFFFFFF);
            row.addView(t);
            row.addView(tuneButton("-", st, -1, label));
            row.addView(tuneButton("+", st, 1, label));
            box.addView(row);
        }
        android.widget.Button done = new android.widget.Button(this);
        done.setText("Terminé");
        done.setOnClickListener(v -> {
            prefs.edit().putBoolean("tune", false).apply();
            hideTuner();
        });
        box.addView(done);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(16);
        lp.y = (int) (H * 0.35);
        lp.windowAnimations = 0;
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
        try {
            wm.addView(box, lp);
            tuner = box;
        } catch (Exception e) {
            log("Réglage couleur impossible : " + e);
        }
    }

    private String tunePref() {
        return tuneZone == 0 ? KEY_TOP : (tuneZone == 1 ? "color" : KEY_DIM);
    }

    private String tuneDefault() {
        return tuneZone == 0 ? DEFAULT_COLOR_TOP : (tuneZone == 1 ? DEFAULT_COLOR : DEFAULT_COLOR_DIM);
    }

    private String tuneLabel() {
        return tuneZone == 0 ? "Haut" : (tuneZone == 1 ? "Bas" : "Fiche contact");
    }

    private String tuneHex() {
        return prefs.getString(tunePref(), tuneDefault());
    }

    private android.widget.Button tuneButton(String text, int[] st, int sign, android.widget.TextView label) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(text);
        b.setOnClickListener(v -> {
            int c;
            try { c = Color.parseColor(tuneHex().trim()); } catch (Exception e) { c = Color.parseColor(tuneDefault()); }
            int nc = Color.rgb(clamp(Color.red(c) + st[0] * sign),
                    clamp(Color.green(c) + st[1] * sign), clamp(Color.blue(c) + st[2] * sign));
            String hex = String.format("#%06X", nc & 0xFFFFFF);
            prefs.edit().putString(tunePref(), hex).apply();
            painted.clear();
            label.setText(tuneLabel() + " : " + hex);
            if (canvas != null) canvas.invalidate();
        });
        return b;
    }

    private void hideTuner() {
        if (tuner == null) return;
        try { wm.removeView(tuner); } catch (Exception ignored) { }
        tuner = null;
    }

    // ---------- Ajustement manuel des caches ----------

    private void showAdjuster() {
        if (adjuster != null) return;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackgroundColor(0xEE202020);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));

        final android.widget.TextView label = new android.widget.TextView(this);
        label.setTextColor(0xFFFFFFFF);
        box.addView(label);
        Runnable refresh = () -> {
            Rect r = fixed.get(KEYS[adjustIndex]);
            label.setText("Cache : " + adjustName(KEYS[adjustIndex])
                    + (r == null ? " (pas encore détecté)" : " " + r.toShortString()));
        };

        android.widget.Button pick = new android.widget.Button(this);
        pick.setText("Changer de cache");
        pick.setOnClickListener(v -> {
            adjustIndex = (adjustIndex + 1) % KEYS.length;
            refresh.run();
        });
        box.addView(pick);

        box.addView(adjustRow(new String[]{"←", "→", "↑", "↓"},
                new int[][]{{-4, 0, -4, 0}, {4, 0, 4, 0}, {0, -4, 0, -4}, {0, 4, 0, 4}}, refresh));
        box.addView(adjustRow(new String[]{"larg -", "larg +", "haut -", "haut +"},
                new int[][]{{0, 0, -4, 0}, {0, 0, 4, 0}, {0, 0, 0, -4}, {0, 0, 0, 4}}, refresh));

        android.widget.Button done = new android.widget.Button(this);
        done.setText("Terminé");
        done.setOnClickListener(v -> {
            prefs.edit().putBoolean("adjust", false).apply();
            hideAdjuster();
        });
        box.addView(done);
        refresh.run();

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = (int) (H * 0.3);
        lp.windowAnimations = 0;
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
        try {
            wm.addView(box, lp);
            adjuster = box;
        } catch (Exception e) {
            log("Ajustement impossible : " + e);
        }
    }

    private android.widget.LinearLayout adjustRow(String[] names, int[][] deltas, Runnable refresh) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        for (int i = 0; i < names.length; i++) {
            final int[] d = deltas[i];
            android.widget.Button b = new android.widget.Button(this);
            b.setText(names[i]);
            b.setOnClickListener(v -> {
                String key = KEYS[adjustIndex];
                Rect r = fixed.get(key);
                if (r == null) return;
                r.set(r.left + d[0], r.top + d[1], r.right + d[2], r.bottom + d[3]);
                savePos(key, r);
                if (canvas != null) canvas.invalidate();
                showMasks(lastWant, 0);
                refresh.run();
            });
            row.addView(b);
        }
        return row;
    }

    private static String adjustName(String key) {
        switch (key) {
            case "title": return "Nom WhatsApp";
            case "cam": return "Appareil photo";
            case "metaai": return "Bouton IA";
            case "actus": return "Onglet Actus";
            case "commu": return "Onglet Communautés";
            case "disctxt": return "Texte Discussions";
            case "appelstxt": return "Texte Appels";
            default: return key;
        }
    }

    private void hideAdjuster() {
        if (adjuster == null) return;
        try { wm.removeView(adjuster); } catch (Exception ignored) { }
        adjuster = null;
    }

    // ---------- Pipette ----------

    private void showPicker() {
        if (picker != null) return;
        android.widget.FrameLayout f = new android.widget.FrameLayout(this);
        f.setBackgroundColor(0x01000000);
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText("Touche la zone dont tu veux copier la couleur");
        t.setTextColor(0xFFFFFFFF);
        t.setBackgroundColor(0xCC000000);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        flp.gravity = Gravity.TOP;
        flp.topMargin = dp(80);
        f.addView(t, flp);
        f.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_UP) {
                final int x = Math.round(ev.getRawX()), y = Math.round(ev.getRawY());
                hidePicker();
                handler.postDelayed(() -> sampleAt(x, y), 250);
            }
            return true;
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        lp.windowAnimations = 0;
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
        try {
            wm.addView(f, lp);
            picker = f;
            log("Pipette : en attente de ton appui");
        } catch (Exception e) {
            log("Pipette impossible : " + e);
        }
    }

    private void hidePicker() {
        if (picker == null) return;
        try { wm.removeView(picker); } catch (Exception ignored) { }
        picker = null;
    }

    private void sampleAt(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log("Pipette : Android 11 minimum");
            prefs.edit().putBoolean("calibcolor", false).apply();
            return;
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    int color = 0;
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                        if (hw != null) {
                            Bitmap bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                            if (bmp != null) {
                                long r = 0, g = 0, b = 0;
                                int n = 0;
                                float sx = bmp.getWidth() / (float) W, sy = bmp.getHeight() / (float) H;
                                for (int dx = -2; dx <= 2; dx++) {
                                    for (int dy = -2; dy <= 2; dy++) {
                                        int px = Math.round((x + dx) * sx), py = Math.round((y + dy) * sy);
                                        if (px < 0 || py < 0 || px >= bmp.getWidth() || py >= bmp.getHeight()) continue;
                                        int c = bmp.getPixel(px, py);
                                        r += Color.red(c); g += Color.green(c); b += Color.blue(c);
                                        n++;
                                    }
                                }
                                if (n > 0) color = Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
                                bmp.recycle();
                            }
                        }
                        result.getHardwareBuffer().close();
                    } catch (Exception e) {
                        log("Pipette : " + e);
                    }
                    prefs.edit().putBoolean("calibcolor", false).apply();
                    if (color != 0) {
                        String hex = String.format("#%06X", color & 0xFFFFFF);
                        prefs.edit().putString(y < H * 0.2 ? KEY_TOP : "color", hex).apply();
                        painted.clear();
                        log("Pipette : couleur " + hex + " pour la zone "
                                + (y < H * 0.2 ? "haut" : "bas") + " (" + x + "," + y + ")");
                        schedule(30);
                    } else {
                        log("Pipette : mesure impossible");
                    }
                }
                @Override public void onFailure(int errorCode) {
                    prefs.edit().putBoolean("calibcolor", false).apply();
                    log("Pipette : capture refusée (" + errorCode + ")");
                }
            });
        } catch (Exception e) {
            prefs.edit().putBoolean("calibcolor", false).apply();
            log("Pipette : " + e);
        }
    }

    private String cacheKey(String cls) {
        return "cache:" + (cls == null || cls.isEmpty() ? "?" : cls) + ":" + W + "x" + H;
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
            prefs.edit().putString(cacheKey(currentClass), str).putString(cacheKey(""), str).apply();
        }
    }

    private Map<String, Rect> loadCache(String cls) {
        Map<String, Rect> m = new HashMap<>();
        String str = prefs.getString(cacheKey(cls), "");
        if (str.isEmpty()) str = prefs.getString(cacheKey(""), "");
        for (String part : str.split(";")) {
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

    private boolean dimMasks = false;
    private boolean drawOnDisplay = true;

    class MaskCanvas extends View {
        private Map<String, Rect> shown = new HashMap<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        MaskCanvas(MaskService ctx) {
            super(ctx);
            setWillNotDraw(false);
        }

        void set(Map<String, Rect> want) {
            if (want.equals(shown)) return;
            shown = new HashMap<>(want);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            for (Map.Entry<String, Rect> e : shown.entrySet()) {
                Rect g = maskRect(e.getKey(), e.getValue());
                paint.setColor(maskColor(e.getKey()));
                if (e.getKey().equals("metaai")) {
                    float rad = g.width() > g.height() * 1.4f
                            ? g.height() / 2f : Math.min(g.width(), g.height()) * 0.30f;
                    c.drawRoundRect(g.left, g.top, g.right, g.bottom, rad, rad, paint);
                } else {
                    c.drawRect(g.left, g.top, g.right, g.bottom, paint);
                }
            }
        }
    }

    private Rect maskRect(String key, Rect r) {
        boolean tab = key.equals("actus") || key.equals("commu")
                || key.equals("disctxt") || key.equals("appelstxt");
        int m = prefs.getInt("margin", 8);            // marge permanente, réglable
        Rect g = tab ? new Rect(r.left - m, r.top + dp(1), r.right + m, r.bottom + m)
                     : new Rect(r.left - m, r.top - m, r.right + m, r.bottom + m);

        return g;
    }

    private long expandUntil = 0;
    private long fastUntil = 0;

    // Un mouvement ou un changement d'écran déclenche la marge de transition
    private void noteMotion(long now, Map<String, Rect> want) {
        boolean moved = want.size() != lastWant.size();
        if (!moved) {
            for (Map.Entry<String, Rect> e : want.entrySet()) {
                Rect old = lastWant.get(e.getKey());
                if (old == null || Math.abs(old.top - e.getValue().top) > 2
                        || Math.abs(old.left - e.getValue().left) > 2) {
                    moved = true;
                    break;
                }
            }
        }
        if (moved) expandUntil = now + 400;
        if (now < expandUntil) {
            if (canvas != null) canvas.invalidate();
            schedule(16);
        } else if (now < fastUntil) {
            schedule(16);       // juste après un appui : on suit l'écran image par image
        }
    }

    private SurfaceControlViewHost host = null;
    private MaskCanvas hostCanvas = null;
    private int hostW = 0, hostH = 0;
    private int attachedWindowId = -1;
    private boolean attachOk = true;

    // Android 14+ : on colle notre calque À LA FENÊTRE de WhatsApp. Il est alors composé
    // avec elle : il suit ses animations, s'assombrit avec elle et disparaît avec elle.
    private boolean attachOverlay(Scan sc, Map<String, Rect> want) {
        if (Build.VERSION.SDK_INT < 34 || !attachOk || sc.homeWindowId == -1) return false;
        Rect wb = sc.homeBounds;
        if (wb.isEmpty()) return false;
        try {
            if (host == null) {
                Display display = ((android.hardware.display.DisplayManager)
                        getSystemService(DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
                host = new SurfaceControlViewHost(this, display, (android.os.IBinder) null);
                hostCanvas = new MaskCanvas(this);
                host.setView(hostCanvas, wb.width(), wb.height());
                hostW = wb.width();
                hostH = wb.height();
            } else if (hostW != wb.width() || hostH != wb.height()) {
                host.relayout(wb.width(), wb.height());
                hostW = wb.width();
                hostH = wb.height();
            }
            if (attachedWindowId != sc.homeWindowId) {
                attachAccessibilityOverlayToWindow(sc.homeWindowId,
                        host.getSurfacePackage().getSurfaceControl());
                attachedWindowId = sc.homeWindowId;
                log("Calque attaché à la fenêtre de WhatsApp");
            }
            Map<String, Rect> local = new HashMap<>();
            for (Map.Entry<String, Rect> e : want.entrySet()) {
                Rect r = e.getValue();
                local.put(e.getKey(), new Rect(r.left - wb.left, r.top - wb.top,
                        r.right - wb.left, r.bottom - wb.top));
            }
            hostCanvas.set(local);
            return true;
        } catch (Throwable t) {
            attachOk = false;
            log("Calque attaché impossible, mode normal : " + t);
            return false;
        }
    }

    private void ensureCanvas() {
        if (canvas != null || wm == null) return;
        MaskCanvas c = new MaskCanvas(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        lp.windowAnimations = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
        try {
            wm.addView(c, lp);
            canvas = c;
        } catch (Exception e) {
            log("Calque impossible : " + e);
        }
    }

    // Le dessin se fait dans une seule fenêtre transparente (instantané, une image suffit) ;
    // les petites fenêtres ci-dessous ne servent plus qu'à bloquer le toucher.
    private void showMasks(Map<String, Rect> want, int unusedColor) {
        if (wm == null) return;
        ensureCanvas();
        if (canvas != null) canvas.set(drawOnDisplay ? want : new HashMap<>());
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
            Rect g = maskRect(key, r);

            if (v == null) {
                v = new View(this);
                paint(v, key, maskColor(key));
                v.setClickable(true);
                try { wm.addView(v, params(g)); slots.put(key, v); } catch (Exception ignored) { }
                continue;
            }
            paint(v, key, maskColor(key));
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
        v.setBackgroundColor(Color.TRANSPARENT);   // le visuel est dessiné par le calque
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
