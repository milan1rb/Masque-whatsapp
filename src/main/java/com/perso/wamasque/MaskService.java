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
    static volatile MaskService instance = null;
    private static volatile String lastDump = "(aucune capture de l'écran principal de WhatsApp)";
    private static final StringBuilder LOG = new StringBuilder();
    private static String lastLogMsg = "";

    private static final int COLOR_TEST = 0x88FF0000;
    private static final String[] KEYS = {"title", "cam", "metaai", "actus", "commu", "disctxt", "appelstxt", "fb1", "fb2", "fb3", "fb4", "fb5", "fb6", "fo1", "fo2", "fo3", "fo4", "fo5", "ms1", "ms2", "ms3", "ms4", "ms5", "ms6", "fobar", "msbar", "fbsvback", "fbsvforum"};
    static final String DEFAULT_COLOR_MS = "#000000";
    static final String DEFAULT_COLOR_FO = "#242526";
    static final String MESSENGER = "com.facebook.orca";
    private static final String[] FORUM_GUESS = {"com.facebook.ember", "com.facebook.forum", "com.meta.forum"};
    static final String FB = "com.facebook.katana";
    static final String DEFAULT_COLOR_FB = "#FFFFFF";
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
        try {
            update();
        } catch (Exception e) {
            log("Erreur : " + e);
            schedule(300);      // on se remet en route quoi qu'il arrive
        }
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
        List<Rect> system = new ArrayList<>();
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
        instance = this;
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
                    if (wasHome) {
                        showMasks(loadCache(cls), 0);   // on n'a jamais quitté : rien à attendre
                    } else {
                        showAt = Math.max(showAt, t + 90);
                    }
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
        instance = null;
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
        if (dm.widthPixels != W || dm.heightPixels != H) {
            W = dm.widthPixels;
            H = dm.heightPixels;
            loadPositions();     // chaque taille d'écran a ses propres positions
        }
    }

    // ---------- Boucle principale ----------

    private void update() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active == null) {
            if (phase != 0) schedule(200);
            return;
        }
        String pkg = String.valueOf(active.getPackageName());
        if (pkg.equals(FB)) {
            handleFacebook(active, SystemClock.uptimeMillis());
            return;
        }
        if (prefs.getBoolean("forum_detect", false) && !pkg.equals(getPackageName())
                && !pkg.equals(launcherPkg) && !pkg.equals("com.android.systemui")
                && !isWa(pkg) && !pkg.equals(FB) && !pkg.equals(MESSENGER)) {
            prefs.edit().putString("forum_pkg", pkg).putBoolean("forum_detect", false).apply();
            log("Forum détecté : " + pkg);
        }
        if (isForum(pkg)) {
            handleForum(active, SystemClock.uptimeMillis());
            return;
        }
        if (pkg.equals(MESSENGER)) {
            handleMessenger(active, SystemClock.uptimeMillis());
            return;
        }

        if (!pkg.equals("com.android.systemui")) inFacebook = false;
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
            wasHome = false;
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
                wasHome = false;
                showMasks(new HashMap<>(), 0);
            }
            if (now < fastUntil) schedule(16);
            if (phase != 0) runMacro(false, new ArrayList<>(), new ArrayList<>(), now);
            return;
        }

        if (!wasHome) {            // on vient de revenir sur l'écran principal
            wasHome = true;
            showAt = now + 90;     // laisser l'écran arriver avant de reposer les caches
        }
        lastHomeTime = now;
        suppressUntil = 0;
        if (now < showAt) {
            schedule(16);       // on attend simplement, sans rien retirer de ce qui est affiché
            return;
        }
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
        if (!sc.system.isEmpty()) want = withoutPopups(want, sc.system);   // laisser passer les notifications
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
            // Fenêtres du système : notification affichée en haut, volet déroulé…
            // On ne masque rien par-dessus, sinon on cacherait la notification.
            if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM) {
                Rect sb = new Rect();
                w.getBoundsInScreen(sb);
                if (sb.height() > dp(48)) sc.system.add(sb);   // ni la barre d'état ni la barre de navigation
                continue;
            }
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
        if (key.startsWith("fbsv") || (savedPage && key.startsWith("fb"))) {
            try {
                return Color.parseColor(prefs.getString("fbsvcolor", "#242526").trim());
            } catch (Exception e) {
                return Color.parseColor("#242526");
            }
        }
        if (key.startsWith("ms")) {
            try {
                return Color.parseColor(prefs.getString("mscolor", DEFAULT_COLOR_MS).trim());
            } catch (Exception e) {
                return Color.BLACK;
            }
        }
        if (key.startsWith("fo")) {
            try {
                return Color.parseColor(prefs.getString("focolor", DEFAULT_COLOR_FO).trim());
            } catch (Exception e) {
                return Color.parseColor(DEFAULT_COLOR_FO);
            }
        }
        if (key.startsWith("fb")) {
            try {
                return Color.parseColor(prefs.getString("fbcolor", DEFAULT_COLOR_FB).trim());
            } catch (Exception e) {
                return Color.WHITE;
            }
        }
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

    // ---------- Facebook : blocage constant de la barre du bas ----------
    // La barre de Facebook a 6 boutons de même largeur. On mémorise leur position la
    // première fois qu'on les voit (sinon on la calcule), puis les caches choisis restent
    // posés en permanence tant que Facebook est à l'écran.

    static final boolean[] FB_DEFAULT = {true, true, true, false, false, true};
    private long lastFbDump = 0;
    private long fbBarSeen = 0;          // dernière fois que la barre a été vue
    private long fbCheckTime = 0;
    private boolean fbBarEver = false;   // la détection a-t-elle déjà fonctionné ?
    private boolean fbLoggedFallback = false;
    private boolean inFacebook = false;
    private boolean fbMacroPending = false;
    private long fbEnterTime = 0;

    private void handleFacebook(AccessibilityNodeInfo root, long now) {
        if (!inFacebook) {
            inFacebook = true;
            fbEnterTime = now;
            int slot = prefs.getInt("fb_macro", 0);
            fbMacroPending = slot >= 1 && slot <= 6;
            if (fbMacroPending) log("Facebook ouvert : macro vers le bouton " + slot);
        }
        inWhatsApp = false;
        phase = 0;
        dimMasks = false;
        if (!prefs.getBoolean("fb_enabled", true)) {
            showMasks(new HashMap<>(), 0);
            return;
        }
        metrics();
        if (prefs.getBoolean("fb_reset", false)) {
            SharedPreferences.Editor ed = prefs.edit().putBoolean("fb_reset", false);
            for (int i = 1; i <= 6; i++) ed.remove(fbKey(i));
            ed.apply();
            log("Facebook : positions réinitialisées");
        }
        // apprentissage des positions réelles (une seule fois)
        if (prefs.getString(fbKey(1), "").isEmpty()) {
            List<Rect> tabs = new ArrayList<>();
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText("onglet")) {
                Rect r = bounds(n);
                if (n.isVisibleToUser() && r.centerY() > H * 0.8 && r.width() < W / 3) addTab(tabs, r);
            }
            if (tabs.size() == 6) {
                tabs.sort((a, b) -> Integer.compare(a.left, b.left));
                SharedPreferences.Editor ed = prefs.edit();
                for (int i = 0; i < 6; i++) {
                    Rect r = tabs.get(i);
                    ed.putString(fbKey(i + 1), r.left + "," + r.top + "," + r.right + "," + r.bottom);
                }
                ed.apply();
                log("Facebook : positions des 6 boutons retenues");
            } else if (now - lastFbDump > 10000) {
                lastFbDump = now;
                List<Item> items = new ArrayList<>();
                collect(root, 0, items, 800);
                lastDump = dump(items, FB);
            }
        }
        // Condition : la barre du bas doit être à l'écran
        boolean bar = fbBarVisible(root, now);
        if (bar) {
            fbBarSeen = now;
            fbBarEver = true;
        }
        boolean show;
        if (!fbBarEver) {
            // la barre n'a jamais pu être reconnue : on reste en blocage permanent plutôt que rien
            show = true;
            if (!fbLoggedFallback) {
                fbLoggedFallback = true;
                log("Facebook : barre non reconnue, blocage permanent (envoie-moi le diagnostic)");
            }
        } else {
            show = now - fbBarSeen < 200;   // petit sursis pour ne pas clignoter
        }
        Map<String, Rect> want = new HashMap<>();
        if (show) {
            for (int i = 1; i <= 6; i++) {
                if (prefs.getBoolean("fb" + i, FB_DEFAULT[i - 1])) want.put("fb" + i, fbRect(i));
            }
        }
        // Page des Enregistrements : flèche retour masquée, raccourci Forum à la place de la loupe
        if (prefs.getBoolean("fbsaved", true) && onSavedPage(root)) {
            int sb = statusBar();
            int bottom = sb + dp(48);             // la barre de titre fait 48 dp, trait non compris
            want.put("fbsvback", new Rect(0, sb, dp(64), bottom));
            want.put("fbsvforum", new Rect(W - dp(64), sb, W, bottom));
            savedPage = true;
        } else {
            savedPage = false;
        }
        showMasks(want, 0);

        if (fbMacroPending) {
            if (now - fbEnterTime > 8000) {
                fbMacroPending = false;
                log("Facebook : macro abandonnée, barre jamais trouvée");
            } else if (bar && now - fbEnterTime > 400) {
                fbMacroPending = false;
                fbClick(root, prefs.getInt("fb_macro", 0));
            }
        }
        schedule(100);     // vérifie régulièrement : la barre peut partir sans évènement
    }

    private boolean savedPage = false;

    private boolean onSavedPage(AccessibilityNodeInfo root) {
        int sb = statusBar();
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText("Enregistrements")) {
            Rect r = bounds(n);
            if (n.isVisibleToUser() && r.centerY() < sb + dp(80) && r.centerX() > W * 0.25
                    && r.centerX() < W * 0.75) return true;     // le titre, centré en haut
        }
        return false;
    }

    private int statusBar() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    // Appuie sur une des 6 cases : d'abord par l'accessibilité (fonctionne même sous un cache),
    // sinon par un vrai geste si la case n'est pas masquée
    private void fbClick(AccessibilityNodeInfo root, int slot) {
        Rect r = fbRect(slot);
        int cx = r.centerX(), cy = r.centerY();
        List<Item> items = new ArrayList<>();
        collect(root, 0, items, 1500);
        AccessibilityNodeInfo best = null;
        long bestArea = Long.MAX_VALUE;
        for (Item it : items) {
            if (it.visible && it.node.isClickable() && it.r.contains(cx, cy)) {
                long area = (long) it.r.width() * it.r.height();
                if (area < bestArea) { bestArea = area; best = it.node; }
            }
        }
        if (best != null && best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            log("Facebook : bouton " + slot + " ouvert");
            return;
        }
        if (prefs.getBoolean("fb" + slot, FB_DEFAULT[slot - 1])) {
            log("Facebook : bouton " + slot + " masqué, appui impossible");
            return;
        }
        Path path = new Path();
        path.moveTo(cx, cy);
        startGesture();
        boolean ok = dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50)).build(),
                callback("Appui Facebook"), null);
        if (!ok) gestureBusy = false;
        log("Facebook : appui sur le bouton " + slot + " (geste envoyé : " + ok + ")");
    }

    // La barre est présente si l'on trouve ses boutons en bas de l'écran
    private boolean fbBarVisible(AccessibilityNodeInfo root, long now) {
        int count = 0;
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText("onglet")) {
            Rect r = bounds(n);
            if (n.isVisibleToUser() && r.centerY() > H * 0.8) count++;
        }
        if (count >= 3) return true;
        // secours, au plus 3 fois par seconde : des boutons cliquables alignés à l'emplacement de la barre
        if (now - fbCheckTime < 300) return now - fbBarSeen < 300;
        fbCheckTime = now;
        Rect slot = fbRect(1);
        List<Item> items = new ArrayList<>();
        collect(root, 0, items, 1500);
        List<Rect> found = new ArrayList<>();
        for (Item it : items) {
            if (it.visible && it.node.isClickable() && it.r.centerY() > slot.top && it.r.centerY() < slot.bottom
                    && it.r.width() > W / 10 && it.r.width() < W / 4) {
                addTab(found, it.r);
            }
        }
        return found.size() >= 3;
    }

    private String fbKey(int i) {
        return "fbpos:" + i + ":" + W + "x" + H;
    }

    private Rect fbRect(int i) {
        String[] n = prefs.getString(fbKey(i), "").split(",");
        if (n.length == 4) {
            try {
                return new Rect(Integer.parseInt(n[0]), Integer.parseInt(n[1]),
                        Integer.parseInt(n[2]), Integer.parseInt(n[3]));
            } catch (NumberFormatException ignored) { }
        }
        // position calculée : 6 cases égales, au-dessus de la barre de navigation d'Android
        int nav = 0;
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (id > 0) nav = getResources().getDimensionPixelSize(id);
        int bottom = H - nav;
        int top = bottom - dp(88);
        int w = W / 6;
        return new Rect((i - 1) * w, top, i * w, bottom);
    }

    private void addTab(List<Rect> tabs, Rect r) {
        for (Rect t : tabs) if (t.contains(r.centerX(), r.centerY()) || r.contains(t.centerX(), t.centerY())) return;
        tabs.add(new Rect(r));
    }

    // ---------- Forum : barre du bas à 5 cases ----------
    // Cases 3 et 5 remplacées : 3 ouvre les Enregistrements de Facebook, 5 ouvre Messenger.

    static final boolean[] FO_DEFAULT = {false, false, true, false, true};
    private long foSeen = 0, foCheckTime = 0;
    private boolean foEver = false, foBarCached = false;

    private boolean isForum(String pkg) {
        String stored = prefs.getString("forum_pkg", "");
        if (!stored.isEmpty()) return pkg.equals(stored);
        for (String g : FORUM_GUESS) if (g.equals(pkg)) return true;
        return false;
    }

    private void handleForum(AccessibilityNodeInfo root, long now) {
        inWhatsApp = false;
        inFacebook = false;
        phase = 0;
        dimMasks = false;
        if (!prefs.getBoolean("fo_enabled", true)) {
            showMasks(new HashMap<>(), 0);
            return;
        }
        metrics();
        if (now - foCheckTime > 300) {
            foCheckTime = now;
            Rect slot = foRect(1);
            List<Item> items = new ArrayList<>();
            collect(root, 0, items, 1500);
            List<Rect> found = new ArrayList<>();
            List<Item> tabs = new ArrayList<>();
            foTyping = false;
            for (Item it : items) {
                if (it.visible && it.node.isClickable() && it.r.centerY() > slot.top
                        && it.r.centerY() < slot.bottom && it.r.width() > W / 10 && it.r.width() < W / 3) {
                    int before = found.size();
                    addTab(found, it.r);
                    if (found.size() > before) tabs.add(it);
                }
                // un champ de saisie posé sur la zone de la barre : la fausse barre s'efface
                if (it.visible && it.node.isEditable() && Rect.intersects(it.r, slot)) foTyping = true;
            }
            foBarCached = found.size() >= 3;
            if (tabs.size() == 5) {
                tabs.sort((a, b) -> Integer.compare(a.r.left, b.r.left));
                foNodes.clear();
                for (Item it : tabs) foNodes.add(it.node);   // les vrais onglets, pour les actionner
                for (int i = 0; i < 5; i++) {
                    Item it = tabs.get(i);
                    String d = norm(it.desc);
                    if (it.node.isSelected() || (d.contains("sélectionné") && !d.contains("non sélectionné"))
                            || d.contains("selected")) {
                        if (foSelected != i + 1 && canvas != null) canvas.invalidate();
                        foSelected = i + 1;
                    }
                }
            }
        }
        if (foBarCached) {
            foSeen = now;
            foEver = true;
        }
        Map<String, Rect> want = new HashMap<>();
        if (prefs.getBoolean("fo_fakebar", true)) {
            // Fausse barre : toujours là, sauf si l'on tape du texte en bas de l'écran
            if (foEver && !foTyping && imeBounds() == null) {
                Rect bar = new Rect(0, foRect(1).top, W, foRect(1).bottom);
                want.put("fobar", bar);
            }
        } else {
            boolean show = !foEver || now - foSeen < 200;
            if (show) {
                for (int i = 1; i <= 5; i++) {
                    if (prefs.getBoolean("fo" + i, FO_DEFAULT[i - 1])) want.put("fo" + i, foRect(i));
                }
            }
        }
        showMasks(want, 0);
        schedule(100);
    }

    private boolean foTyping = false;
    private int foSelected = 1;
    private final List<AccessibilityNodeInfo> foNodes = new ArrayList<>();

    // Appui sur la fausse barre : même fonctionnement que la barre d'origine
    private void fakeBarTap(float x) {
        int slot = Math.max(1, Math.min(5, (int) (x / (W / 5f)) + 1));
        if (slot == 3 && prefs.getBoolean("fo3", true)) { openSaved(); return; }
        if (slot == 5 && prefs.getBoolean("fo5", true)) { openMessenger(); return; }
        if (foNodes.size() == 5) {
            AccessibilityNodeInfo n = foNodes.get(slot - 1);
            try {
                n.refresh();
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    foSelected = slot;                      // retour visuel immédiat
                    if (canvas != null) canvas.invalidate();
                    return;
                }
            } catch (Exception ignored) { }
        }
        log("Forum : l'onglet " + slot + " n'a pas pu être ouvert (barre d'origine pas encore vue)");
    }

    // Dessin de la fausse barre : fond, trait fin, cinq icônes au trait, onglet actif grisé
    private void drawFakeBar(Canvas c, Rect g) {
        Paint line = new Paint();
        line.setColor(0xFF3A3B3C);
        c.drawRect(g.left, g.top, g.right, g.top + Math.max(1, dp(1) / 2), line);
        float w = g.width() / 5f;
        for (int i = 1; i <= 5; i++) {
            Rect cell = new Rect((int) (g.left + (i - 1) * w), g.top, (int) (g.left + i * w), g.bottom);
            boolean active = i == foSelected;
            if (active) {
                Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
                pill.setColor(0xFF3A3B3C);
                float pw = dp(32), ph = dp(17);
                c.drawRoundRect(cell.centerX() - pw, cell.centerY() - ph,
                        cell.centerX() + pw, cell.centerY() + ph, ph, ph, pill);
            }
            iconColor = active ? 0xFFFFFFFF : 0xFFE4E6EB;
            if (i == 1) drawHome(c, cell, active);
            else if (i == 2) drawAsk(c, cell);
            else if (i == 3) {
                if (prefs.getBoolean("fo3", true)) drawBookmark(c, cell); else drawPlus(c, cell);
            } else if (i == 4) drawBell(c, cell);
            else {
                if (prefs.getBoolean("fo5", true)) drawMessenger(c, cell); else drawProfile(c, cell);
            }
            iconColor = 0xFFFFFFFF;
        }
    }

    private int iconColor = 0xFFFFFFFF;

    // Maison aux angles arrondis, porte au centre (remplie quand l'onglet est actif)
    private void drawHome(Canvas c, Rect g, boolean filled) {
        iconStyle();
        float cx = g.centerX(), cy = g.centerY() + dp(1), s = dp(11);
        Path p = new Path();
        p.moveTo(cx - s, cy - s * 0.15f);
        p.lineTo(cx, cy - s * 1.05f);
        p.lineTo(cx + s, cy - s * 0.15f);
        p.lineTo(cx + s, cy + s);
        p.lineTo(cx - s, cy + s);
        p.close();
        if (filled) {
            Paint f = new Paint(iconPaint);
            f.setStyle(Paint.Style.FILL_AND_STROKE);
            c.drawPath(p, f);
            Paint door = new Paint(Paint.ANTI_ALIAS_FLAG);
            door.setColor(0xFF3A3B3C);
            c.drawRoundRect(cx - s * 0.32f, cy + s * 0.2f, cx + s * 0.32f, cy + s, dp(2), dp(2), door);
        } else {
            c.drawPath(p, iconPaint);
            c.drawRoundRect(cx - s * 0.32f, cy + s * 0.2f, cx + s * 0.32f, cy + s, dp(2), dp(2), iconPaint);
        }
    }

    // Bulle de discussion avec l'étincelle de l'IA, comme l'onglet « Demander »
    private void drawAsk(Canvas c, Rect g) {
        iconStyle();
        float cx = g.centerX() - dp(2), cy = g.centerY() - dp(1), r = dp(11);
        Path b = new Path();
        b.addCircle(cx, cy, r, Path.Direction.CW);
        c.drawPath(b, iconPaint);
        Path spark = new Path();                  // étincelle à 4 branches
        float k = r * 0.5f;
        spark.moveTo(cx, cy - k);
        spark.quadTo(cx, cy, cx + k, cy);
        spark.quadTo(cx, cy, cx, cy + k);
        spark.quadTo(cx, cy, cx - k, cy);
        spark.quadTo(cx, cy, cx, cy - k);
        c.drawPath(spark, iconPaint);
        c.drawCircle(cx + r * 0.95f, cy + r * 0.95f, r * 0.42f, iconPaint);
    }

    private void drawPlus(Canvas c, Rect g) {
        iconStyle();
        float cx = g.centerX(), cy = g.centerY(), r = dp(12);
        c.drawCircle(cx, cy, r, iconPaint);
        c.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.5f, iconPaint);
        c.drawLine(cx - r * 0.5f, cy, cx + r * 0.5f, cy, iconPaint);
    }

    // Cloche arrondie avec son battant
    private void drawBell(Canvas c, Rect g) {
        iconStyle();
        float cx = g.centerX(), cy = g.centerY() - dp(1), s = dp(10);
        Path p = new Path();
        p.moveTo(cx - s * 1.05f, cy + s * 0.75f);
        p.quadTo(cx - s * 0.75f, cy + s * 0.45f, cx - s * 0.75f, cy - s * 0.05f);
        p.cubicTo(cx - s * 0.75f, cy - s * 1.15f, cx + s * 0.75f, cy - s * 1.15f, cx + s * 0.75f, cy - s * 0.05f);
        p.quadTo(cx + s * 0.75f, cy + s * 0.45f, cx + s * 1.05f, cy + s * 0.75f);
        p.close();
        c.drawPath(p, iconPaint);
        c.drawArc(cx - s * 0.35f, cy + s * 0.75f, cx + s * 0.35f, cy + s * 1.35f, 0, 180, false, iconPaint);
    }

    private void drawProfile(Canvas c, Rect g) {
        iconStyle();
        float cx = g.centerX(), cy = g.centerY(), r = dp(12);
        c.drawCircle(cx, cy, r, iconPaint);
        c.drawCircle(cx, cy - r * 0.22f, r * 0.33f, iconPaint);
        c.drawArc(cx - r * 0.6f, cy + r * 0.2f, cx + r * 0.6f, cy + r * 1.1f, 200, 140, false, iconPaint);
    }

    private Rect foRect(int i) {
        int nav = 0;
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (id > 0) nav = getResources().getDimensionPixelSize(id);
        int bottom = H - nav;
        int top = bottom - dp(78);
        int w = W / 5;
        return new Rect((i - 1) * w, top, i * w, bottom);
    }

    private void openMessenger() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(MESSENGER);
            if (i == null) i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("fb-messenger://"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            log("Messenger introuvable : " + e);
        }
    }

    // Plusieurs adresses internes possibles pour les Enregistrements : Facebook accepte
    // toutes ses adresses mais n'en honore que certaines, on laisse donc choisir la méthode.
    static final String[] SAVED_URIS = {
            "fb://facewebmodal/f?href=https%3A%2F%2Fm.facebook.com%2Fsaved%2F",
            "fb://saved",
            "fb://bookmarks/saved",
            "https://www.facebook.com/saved/"};

    void openSaved() {
        int start = Math.max(0, Math.min(SAVED_URIS.length - 1, prefs.getInt("saved_method", 1)));
        for (int k = 0; k < SAVED_URIS.length; k++) {
            String u = SAVED_URIS[(start + k) % SAVED_URIS.length];
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u));
                i.setPackage(FB);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
                log("Enregistrements : ouverture par " + u);
                return;
            } catch (Exception ignored) { }
        }
        log("Impossible d'ouvrir les Enregistrements de Facebook");
    }

    // ---------- Messenger : barre du bas à 4 cases ----------
    // Cases 2, 3 et 4 masquées ; la 4e devient un bouton qui ramène sur Forum.

    static final boolean[] MS_DEFAULT = {false, true, true, true};
    private long msSeen = 0, msCheckTime = 0;
    private boolean msEver = false, msBarCached = false;

    private void handleMessenger(AccessibilityNodeInfo root, long now) {
        inWhatsApp = false;
        inFacebook = false;
        phase = 0;
        dimMasks = false;
        if (!prefs.getBoolean("ms_enabled", true)) {
            showMasks(new HashMap<>(), 0);
            return;
        }
        metrics();
        if (now - msCheckTime > 200) {
            msCheckTime = now;
            Rect slot = msRect(1);
            msAi = null;
            List<Item> items = new ArrayList<>();
            collect(root, 0, items, 1500);
            List<Rect> found = new ArrayList<>();
            List<Item> tabs = new ArrayList<>();
            for (Item it : items) {
                if (it.visible && it.node.isClickable() && it.r.centerY() > slot.top
                        && it.r.centerY() < slot.bottom && it.r.width() > W / 10 && it.r.width() < W / 3) {
                    int before = found.size();
                    addTab(found, it.r);
                    if (found.size() > before) tabs.add(it);
                }
                // le bouton Meta AI : collé à droite, posé juste sur la barre, rond ou allongé
                if (it.visible && it.node.isClickable() && it.r.right > W * 0.85
                        && it.r.bottom <= slot.top + dp(8) && it.r.bottom > slot.top - dp(40)
                        && it.r.height() > dp(36) && it.r.height() < dp(80) && it.r.width() >= dp(36)) {
                    if (msAi == null || it.r.width() > msAi.width()) msAi = new Rect(it.r);
                }
            }
            msBarCached = found.size() >= 3;
            if (tabs.size() == 4) {
                tabs.sort((a, b) -> Integer.compare(a.r.left, b.r.left));
                msNodes.clear();
                for (Item it : tabs) msNodes.add(it.node);
                int sel = 0;
                for (int i = 0; i < 4; i++) {
                    Item it = tabs.get(i);
                    String d = norm(it.desc) + " " + norm(it.text);
                    if (it.node.isSelected() || (d.contains("sélectionné") && !d.contains("non sélectionné"))
                            || d.contains("selected")) sel = i + 1;
                }
                if (sel != msSelected && canvas != null) canvas.invalidate();
                msSelected = sel;
            }
        }
        if (msBarCached) {
            msSeen = now;
            msEver = true;
        }
        boolean show = !msEver || now - msSeen < 200;
        Map<String, Rect> want = new HashMap<>();
        if (show) {
            if (prefs.getBoolean("ms_fakebar", true)) {
                Rect r1 = msRect(1);
                want.put("msbar", new Rect(0, r1.top, W, r1.bottom));
            } else {
                for (int i = 1; i <= 4; i++) {
                    if (prefs.getBoolean("ms" + i, MS_DEFAULT[i - 1])) want.put("ms" + i, msRect(i));
                }
            }
            // bouton Meta AI : pastille ronde ou version allongée, juste au-dessus de la barre
            if (prefs.getBoolean("ms6", true)) {
                Rect ai = msAi == null ? null : new Rect(msAi);
                if (ai == null) {       // position habituelle de la pastille ronde
                    Rect bar = msRect(1);
                    ai = new Rect(W - dp(76), bar.top - dp(58), W - dp(15), bar.top + dp(1));
                }
                want.put("ms6", ai);
            }
            // les 2 icônes en haut à droite (nouveau message, Facebook), sur l'écran principal
            if (prefs.getBoolean("ms5", true)) {
                int sb = 0;
                int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) sb = getResources().getDimensionPixelSize(id);
                want.put("ms5", new Rect((int) (W * 0.74), sb + dp(8), (int) (W * 0.98), sb + dp(62)));
            }
        }
        showMasks(want, 0);
        schedule(100);
    }

    private Rect msRect(int i) {
        int nav = 0;
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (id > 0) nav = getResources().getDimensionPixelSize(id);
        int bottom = H - nav;
        int top = bottom - dp(82);
        int w = W / 4;
        return new Rect((i - 1) * w, top, i * w, bottom);
    }

    private final List<AccessibilityNodeInfo> msNodes = new ArrayList<>();
    private Rect msAi = null;
    private int msSelected = 1;

    private void msBarTap(float x) {
        int slot = Math.max(1, Math.min(4, (int) (x / (W / 4f)) + 1));
        if (slot == 4) { openForum(); return; }
        if (slot == 1 && msNodes.size() == 4) {
            try {
                AccessibilityNodeInfo n = msNodes.get(0);
                n.refresh();
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    msSelected = 1;
                    if (canvas != null) canvas.invalidate();
                }
            } catch (Exception ignored) { }
        }
        // cases 2 et 3 : bloquées
    }

    // Barre Messenger dans le style de Forum : Discussions (actif, grisé) et Forum
    private void drawMsBar(Canvas c, Rect g) {
        Paint line = new Paint();
        line.setColor(0xFF262626);
        c.drawRect(g.left, g.top, g.right, g.top + Math.max(1, dp(1) / 2), line);
        float w = g.width() / 4f;
        Rect c1 = new Rect(g.left, g.top, (int) (g.left + w), g.bottom);
        Rect c4 = new Rect((int) (g.left + 3 * w), g.top, g.right, g.bottom);
        if (msSelected == 1) {               // grisé uniquement quand on est dans Discussions
            Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
            pill.setColor(0xFF3A3B3C);
            float pw = dp(32), ph = dp(17);
            c.drawRoundRect(c1.centerX() - pw, c1.centerY() - ph, c1.centerX() + pw, c1.centerY() + ph, ph, ph, pill);
        }
        // bulle de discussion pleine
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setColor(0xFFFFFFFF);
        float cx = c1.centerX(), cy = c1.centerY() - dp(1), r = dp(11);
        c.drawOval(cx - r * 1.1f, cy - r * 0.9f, cx + r * 1.1f, cy + r * 0.8f, f);
        Path tail = new Path();
        tail.moveTo(cx - r * 0.7f, cy + r * 0.45f);
        tail.lineTo(cx - r * 0.95f, cy + r * 1.15f);
        tail.lineTo(cx - r * 0.15f, cy + r * 0.7f);
        tail.close();
        c.drawPath(tail, f);
        drawForumLogo(c, c4);
    }

    // Logo Forum : deux guillemets arrondis, en blanc
    // Logo Forum simplifié : bulle de discussion au trait, trois lignes de texte
    private void drawForumLogo(Canvas c, Rect g) {
        iconColor = 0xFFFFFFFF;
        iconStyle();
        float cx = g.centerX(), cy = g.centerY() - dp(1);
        float w = dp(12), h = dp(9), r = dp(4);
        Path p = new Path();
        p.moveTo(cx - w + r, cy - h);
        p.lineTo(cx + w - r, cy - h);
        p.quadTo(cx + w, cy - h, cx + w, cy - h + r);
        p.lineTo(cx + w, cy + h - r);
        p.quadTo(cx + w, cy + h, cx + w - r, cy + h);
        p.lineTo(cx - w * 0.2f, cy + h);
        p.lineTo(cx - w * 0.6f, cy + h + dp(5));        // la pointe de la bulle
        p.lineTo(cx - w * 0.6f, cy + h);
        p.lineTo(cx - w + r, cy + h);
        p.quadTo(cx - w, cy + h, cx - w, cy + h - r);
        p.lineTo(cx - w, cy - h + r);
        p.quadTo(cx - w, cy - h, cx - w + r, cy - h);
        p.close();
        c.drawPath(p, iconPaint);
        c.drawLine(cx - w * 0.55f, cy - h * 0.4f, cx + w * 0.55f, cy - h * 0.4f, iconPaint);
        c.drawLine(cx - w * 0.55f, cy + h * 0.1f, cx + w * 0.55f, cy + h * 0.1f, iconPaint);
        c.drawLine(cx - w * 0.55f, cy + h * 0.6f, cx + w * 0.15f, cy + h * 0.6f, iconPaint);
    }

    private void quote(Canvas c, Paint f, float x, float y, float s) {
        Path p = new Path();
        float w = s * 1.25f, h = s * 1.15f, rr = s * 0.35f;
        p.addRoundRect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, rr, rr, Path.Direction.CW);
        c.drawPath(p, f);
        Path tail = new Path();                                 // la queue du guillemet
        tail.moveTo(x - w / 2, y + h / 2 - rr);
        tail.quadTo(x - w / 2 + s * 0.1f, y + h / 2 + s * 0.95f, x - w / 2 + s * 0.2f, y + h / 2 + s * 1.05f);
        tail.quadTo(x - w / 2 + s * 0.55f, y + h / 2 + s * 0.4f, x - w / 2 + s * 0.7f, y + h / 2 - 1);
        tail.close();
        c.drawPath(tail, f);
    }

    private void openForum() {
        String fp = prefs.getString("forum_pkg", "");
        if (fp.isEmpty()) fp = "com.facebook.ember";     // identifiant connu de Forum
        try {
            Intent i = fp.isEmpty() ? null : getPackageManager().getLaunchIntentForPackage(fp);
            if (i == null) {
                log("Forum : application pas encore détectée (page Forum du menu)");
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            log("Forum introuvable : " + e);
        }
    }

    // Icône Forum : deux bulles de discussion, avec le libellé comme les autres onglets
    private void drawForum(Canvas c, Rect g) {
        if (true) { drawForumLogo(c, g); return; }
        iconStyle();
        float cx = g.centerX(), cy = g.top + g.height() * 0.40f, r = dp(11);
        c.drawRoundRect(cx - r * 1.4f, cy - r, cx + r * 0.6f, cy + r * 0.5f, r * 0.6f, r * 0.6f, iconPaint);
        c.drawRoundRect(cx - r * 0.4f, cy - r * 0.2f, cx + r * 1.5f, cy + r * 1.2f, r * 0.6f, r * 0.6f, iconPaint);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0xFFB0B3B8);
        t.setTextSize(dp(15));
        t.setTextAlign(Paint.Align.CENTER);
        c.drawText("Forum", cx, g.top + g.height() * 0.86f, t);
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
                if (e.getKey().equals("ms6")) {
                    float rad = g.height() / 2f;       // rond replié, pastille allongée dépliée
                    c.drawRoundRect(g.left, g.top, g.right, g.bottom, rad, rad, paint);
                } else if (e.getKey().equals("metaai")) {
                    float rad = g.width() > g.height() * 1.4f
                            ? g.height() / 2f : Math.min(g.width(), g.height()) * 0.30f;
                    c.drawRoundRect(g.left, g.top, g.right, g.bottom, rad, rad, paint);
                } else {
                    c.drawRect(g.left, g.top, g.right, g.bottom, paint);
                    if (e.getKey().equals("fobar")) drawFakeBar(c, g);
                    else if (e.getKey().equals("msbar")) drawMsBar(c, g);
                    else if (e.getKey().equals("fbsvforum")) drawForumLogo(c, g);
                    else if (e.getKey().equals("fo5")) drawMessenger(c, g);
                    else if (e.getKey().equals("fo3")) drawBookmark(c, g);
                    else if (e.getKey().equals("ms4")) drawForum(c, g);
                }
            }
        }
    }

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void iconStyle() {
        iconPaint.setColor(iconColor);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(dp(2) + dp(1) / 3f);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    // Icône Messenger : bulle ronde avec l'éclair
    private void drawMessenger(Canvas c, Rect g) {
        iconStyle();
        float cx = g.centerX(), cy = g.centerY() - dp(1), r = dp(12);
        Path p = new Path();
        p.addCircle(cx, cy, r, Path.Direction.CW);
        c.drawPath(p, iconPaint);
        Path tail = new Path();
        tail.moveTo(cx - r * 0.75f, cy + r * 0.55f);
        tail.lineTo(cx - r * 0.95f, cy + r * 1.15f);
        tail.lineTo(cx - r * 0.25f, cy + r * 0.95f);
        c.drawPath(tail, iconPaint);
        Path bolt = new Path();
        bolt.moveTo(cx - r * 0.55f, cy + r * 0.22f);
        bolt.lineTo(cx - r * 0.18f, cy - r * 0.22f);
        bolt.lineTo(cx + r * 0.08f, cy + r * 0.04f);
        bolt.lineTo(cx + r * 0.55f, cy - r * 0.3f);
        bolt.lineTo(cx + r * 0.18f, cy + r * 0.14f);
        bolt.lineTo(cx - r * 0.08f, cy - r * 0.1f);
        bolt.close();
        Paint fill = new Paint(iconPaint);
        fill.setStyle(Paint.Style.FILL_AND_STROKE);
        fill.setStrokeWidth(dp(1));
        c.drawPath(bolt, fill);
    }

    // Icône Enregistrements : marque-page
    private void drawBookmark(Canvas c, Rect g) {
        iconStyle();
        float cx = g.centerX(), cy = g.centerY(), w = dp(8), h = dp(11);
        Path p = new Path();
        p.moveTo(cx - w, cy - h);
        p.lineTo(cx + w, cy - h);
        p.lineTo(cx + w, cy + h);
        p.lineTo(cx, cy + h * 0.45f);
        p.lineTo(cx - w, cy + h);
        p.close();
        c.drawPath(p, iconPaint);
    }

    private Rect maskRect(String key, Rect r) {
        boolean tab = key.equals("actus") || key.equals("commu")
                || key.equals("disctxt") || key.equals("appelstxt");
        int m = (key.startsWith("fb") || key.startsWith("fo") || key.startsWith("ms"))
                ? 0 : prefs.getInt("margin", 8);   // marge permanente, réglable
        Rect g = tab ? new Rect(r.left - m, r.top + dp(1), r.right + m, r.bottom + m)
                     : new Rect(r.left - m, r.top - m, r.right + m, r.bottom + m);

        return g;
    }

    private long expandUntil = 0;
    private long fastUntil = 0;
    private long showAt = 0;
    private boolean wasHome = true;

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
        if (wm == null) return;
        if (canvas != null && !canvas.isAttachedToWindow()) {
            try { wm.removeView(canvas); } catch (Exception ignored) { }
            canvas = null;
            log("Calque perdu, recréation");
        }
        if (canvas != null) return;
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
        // les fenêtres ci-dessous ne servent qu'à bloquer le toucher : on les met à jour
        // juste après, pour ne pas retarder l'image
        final Map<String, Rect> copy = new HashMap<>(want);
        handler.post(() -> touchWindows(copy));
    }

    private void touchWindows(Map<String, Rect> want) {
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
            Rect g = maskRect(key, r);

            if (v == null) {
                v = new View(this);
                paint(v, key, maskColor(key));
                v.setClickable(true);
                if (key.equals("fo5")) v.setOnClickListener(x -> openMessenger());
                else if (key.equals("fo3")) v.setOnClickListener(x -> openSaved());
                else if (key.equals("ms4")) v.setOnClickListener(x -> openForum());
                else if (key.equals("fbsvforum")) v.setOnClickListener(x -> openForum());
                else if (key.equals("msbar")) v.setOnTouchListener((vv, ev) -> {
                    if (ev.getAction() == android.view.MotionEvent.ACTION_UP) msBarTap(ev.getRawX());
                    return true;
                });
                else if (key.equals("fobar")) v.setOnTouchListener((vv, ev) -> {
                    if (ev.getAction() == android.view.MotionEvent.ACTION_UP) fakeBarTap(ev.getRawX());
                    return true;
                });
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
