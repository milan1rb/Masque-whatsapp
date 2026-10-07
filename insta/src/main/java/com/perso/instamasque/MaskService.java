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
    private static long lastLogTime = 0;

    private WindowManager wm;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BarCanvas canvas;      // le dessin de la barre
    private View touchView;        // la fenêtre qui reçoit les appuis
    private View adjuster;         // le panneau de réglage
    private View blockTop;         // cache du haut de la page Récent
    private View blockRight;       // cache de la colonne des croix

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
    private long maskOffUntil = 0;       // le temps d'un appui à travers les caches
    private String launcherPkg = "";
    private boolean onRecent = false;    // la page des comptes consultés récemment
    private int learn = 0;               // 1 : apprendre la zone gauche, -1 : la droite
    private long lastTapTime = 0;        // anti-rebond des appuis sur la barre

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
        long now = SystemClock.uptimeMillis();
        // on ne masque un message répété que s'il revient dans la seconde :
        // deux appuis identiques doivent rester visibles dans le journal
        if (msg.equals(lastLogMsg) && now - lastLogTime < 1200) return;
        lastLogMsg = msg;
        lastLogTime = now;
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
        hideMasks();
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
            hideMasks();
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
            hideMasks();
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
            hideMasks();
            return;
        }

        List<Item> items = new ArrayList<>();
        collect(root, 0, items, 1500);

        if (now - lastScan > 200) {
            lastScan = now;
            scanTabs(items, now);
        }

        // Les caches de la page « Récent » restent même quand le clavier est ouvert
        boolean recent = prefs.getBoolean("mask_recent", true) && recentPage(items);
        if (recent != onRecent) {
            onRecent = recent;
            log(recent ? "Page Récent : caches posés" : "Page Récent quittée");
        }
        if (recent) showMasks(); else hideMasks();

        // Dans une conversation, ou clavier ouvert : la barre s'efface
        if (imeVisible() || inConversation(items)) {
            hideBar();
            schedule(150);
            return;
        }

        showBar(barRect());
        schedule(150);
    }

    /**
     * La page des comptes consultés récemment se reconnaît à son titre « Récent »,
     * tout en haut de la liste.
     */
    private boolean recentPage(List<Item> items) {
        for (Item it : items) {
            if (!it.visible || it.r.isEmpty()) continue;
            if (it.r.centerY() > H * 0.35) continue;
            String s = (it.text + " " + it.desc).toLowerCase(Locale.ROOT).trim();
            if (s.equals("récent") || s.equals("recent") || s.startsWith("récent ")
                    || s.startsWith("recent ")) return true;
        }
        return false;
    }

    /** Repère la vraie barre d'Instagram et retient sa position. */
    private void scanTabs(List<Item> items, long now) {
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

        // Une vraie barre d'onglets, c'est une seule ligne d'éléments alignés qui
        // traverse tout l'écran. Sans cette vérification, les photos de profil des
        // dernières conversations passaient pour des onglets — et l'appui partait
        // sur une conversation, en bas à gauche.
        List<Integer> line = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            List<Integer> grp = new ArrayList<>();
            int cy = found.get(i).centerY();
            for (int j = 0; j < found.size(); j++) {
                if (Math.abs(found.get(j).centerY() - cy) <= dp(16)) grp.add(j);
            }
            if (grp.size() > line.size()) line = grp;
        }
        if (line.size() >= 4) {
            int left = Integer.MAX_VALUE, right = 0;
            for (int i : line) {
                left = Math.min(left, found.get(i).left);
                right = Math.max(right, found.get(i).right);
            }
            if (right - left < W * 3 / 4 || left > W / 8) {
                log("Barre d'onglets refusée : " + line.size() + " éléments de " + left
                        + " à " + right + ", ils ne traversent pas l'écran");
                line = new ArrayList<>();
            }
        } else if (!found.isEmpty()) {
            log("Barre d'onglets refusée : " + found.size() + " éléments en bas, "
                    + "mais jamais 4 sur une même ligne");
            line = new ArrayList<>();
        }
        if (line.size() >= 4) {
            List<Rect> fr = new ArrayList<>();
            List<AccessibilityNodeInfo> fn = new ArrayList<>();
            List<String> fs = new ArrayList<>();
            for (int i : line) {
                fr.add(found.get(i));
                fn.add(nodes.get(i));
                fs.add(names.get(i));
            }
            found = fr;
            nodes = fn;
            names = fs;

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

    // ---------- Position et taille des deux boutons ----------

    /** Le centre d'un bouton, en pixels depuis le bord gauche. */
    private int btnX(boolean left) {
        int v = prefs.getInt(left ? "bg_x" : "bd_x", -1);
        if (v < 0) v = left ? W / 4 : 3 * W / 4;
        return Math.max(0, Math.min(W, v));
    }

    /** La taille du dessin de l'icône, en dp. */
    private int btnSize(boolean left) {
        return Math.max(4, prefs.getInt(left ? "bg_size" : "bd_size", left ? 13 : 11));
    }

    /** Le centre de la zone qui répond à l'appui : suit l'icône tant qu'il n'est pas réglé. */
    private int zoneX(boolean left) {
        int v = prefs.getInt(left ? "bg_tx" : "bd_tx", -1);
        return v < 0 ? btnX(left) : Math.max(0, Math.min(W, v));
    }

    private int zoneY(boolean left) {
        int v = prefs.getInt(left ? "bg_ty" : "bd_ty", -1);
        return v < 0 ? barRect().centerY() : Math.max(0, Math.min(H, v));
    }

    /** La largeur de la zone qui répond à l'appui, en pixels. */
    private int btnZone(boolean left) {
        int v = prefs.getInt(left ? "bg_w" : "bd_w", 0);
        if (v > 0) return dp(v);
        // sans réglage : une zone large, la moitié de l'écart entre les deux icônes
        return Math.max(dp(72), Math.abs(btnX(false) - btnX(true)));
    }

    /** La hauteur de la zone, en pixels. */
    private int zoneH(boolean left) {
        int v = prefs.getInt(left ? "bg_th" : "bd_th", 0);
        return v > 0 ? dp(v) : barRect().height();
    }

    private Rect btnRect(boolean left) {
        int w = btnZone(left) / 2, h = zoneH(left) / 2;
        int cx = zoneX(left), cy = zoneY(left);
        return new Rect(cx - w, cy - h, cx + w, cy + h);
    }

    /** La fenêtre qui reçoit les appuis : la barre, plus les deux zones. */
    private Rect touchRect() {
        Rect r = new Rect(barRect());
        r.union(btnRect(true));
        r.union(btnRect(false));
        if (r.top < 0) r.top = 0;
        if (r.bottom > H) r.bottom = H;
        return r;
    }

    /** Le point exact où la macro appuie pour ouvrir la barre « Rechercher ». */
    private int macroX() {
        int v = prefs.getInt("macro_x", -1);
        return v < 0 ? W / 2 : Math.max(0, Math.min(W, v));
    }

    private int macroY() {
        int v = prefs.getInt("macro_y", -1);
        return v < 0 ? dp(50) : Math.max(0, Math.min(H, v));
    }

    /**
     * Le cache du haut de la page Récent : la flèche de retour, la barre
     * « Rechercher » et la ligne « Récent / Voir tout ».
     */
    private Rect maskTopRect() {
        int from = dp(prefs.getInt("m_top", 18));
        int to = dp(prefs.getInt("m_bottom", 115));
        if (to <= from) return new Rect();
        return new Rect(0, from, W, to);
    }

    /** Le cache de la colonne de croix, à droite de la liste. */
    private Rect maskRightRect() {
        int w = dp(prefs.getInt("m_right", 58));
        int rt = prefs.getInt("m_rtop", -1);
        int top = dp(rt < 0 ? prefs.getInt("m_bottom", 115) : rt);
        int bottom = barRect().top;
        if (w <= 0 || bottom <= top) return new Rect();
        return new Rect(W - w, top, W, bottom);
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
    private boolean inConversation(List<Item> items) {
        if (!prefs.getBoolean("hide_in_chat", true)) return false;
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

    /**
     * Appui sur la fausse barre. Chaque bouton a sa propre zone, réglable :
     * en dehors de ces deux zones, l'appui est avalé sans rien déclencher.
     */
    private void barTap(float fx, float fy) {
        int x = (int) fx, y = (int) fy;

        // Mode apprentissage : cet appui ne déclenche rien, il place la zone
        if (learn != 0) {
            boolean l = learn > 0;
            prefs.edit().putInt(l ? "bg_tx" : "bd_tx", x)
                        .putInt(l ? "bg_ty" : "bd_ty", y).apply();
            learn = 0;
            log("Zone " + (l ? "Messages" : "Recherche") + " placée sur " + x + "," + y
                    + " → " + btnRect(l).toShortString());
            if (canvas != null) canvas.invalidate();
            showBar(barRect());
            return;
        }

        Rect g = btnRect(true), d = btnRect(false);
        boolean left = g.contains(x, y);
        boolean right = d.contains(x, y);
        log("Appui barre en " + x + "," + y + " — Messages " + g.toShortString()
                + " Recherche " + d.toShortString() + " → "
                + (left ? "Messages" : right ? "Recherche" : "aucun bouton"));
        if (!left && !right) return;

        // Anti-rebond : un même bouton ne repart pas deux fois de suite
        long now = SystemClock.uptimeMillis();
        if (now - lastTapTime < prefs.getInt("debounce", 700)) {
            log("Appui trop rapproché du précédent, ignoré");
            return;
        }
        lastTapTime = now;

        if (left) {
            openDirect();
        } else if (onRecent && prefs.getBoolean("no_repeat", true)) {
            log("Déjà dans la recherche : rien à faire");
        } else {
            openSearch();
        }
    }

    private void openSearch() {
        openSearch(true);
    }

    private void openSearch(boolean mayGoBack) {
        int idx = searchIndex();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tabNames.size() && i < tabs.size(); i++) {
            sb.append(i + 1).append('=').append(tabNames.get(i))
              .append(tabs.get(i).toShortString()).append("  ");
        }
        log("Recherche : indice retenu " + (idx + 1) + " sur " + tabNodes.size()
                + " onglets — " + (sb.length() == 0 ? "(aucun onglet mémorisé)" : sb));
        AccessibilityNodeInfo n = freshTab(idx);
        if (n == null) log("Recherche : l'onglet mémorisé n'est pas affiché ici");

        // 1. l'onglet est bien là, sous les yeux : on lui demande de s'ouvrir
        if (n != null) {
            try {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    selectedTab = idx;
                    if (canvas != null) canvas.invalidate();
                    log("Recherche ouverte (onglet " + (idx + 1) + ")");
                    revealRecent();
                    return;
                }
                // 2. l'onglet refuse : on cherche un parent qui accepte le clic
                AccessibilityNodeInfo p = n.getParent();
                for (int k = 0; k < 3 && p != null; k++) {
                    if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        selectedTab = idx;
                        log("Recherche ouverte (parent de l'onglet)");
                        revealRecent();
                        return;
                    }
                    p = p.getParent();
                }
                // 3. on appuie vraiment dessus, à travers la barre
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (inBottomBand(r)) {
                    tapThrough(r);
                    selectedTab = idx;
                    return;
                }
            } catch (Exception e) {
                log("Recherche : " + e);
            }
        }

        // 4. pas de barre d'onglets sur cet écran (la boîte de réception, par
        //    exemple) : on n'appuie SURTOUT pas sur une position mémorisée, qui
        //    tomberait n'importe où. On passe par le lien interne.
        for (String u : new String[]{"instagram://explore", "instagram://search",
                "https://www.instagram.com/explore/"}) {
            if (open(u)) {
                log("Recherche ouverte par " + u);
                revealRecent();
                return;
            }
        }

        // 5. dernier recours : revenir en arrière pour retrouver la barre d'onglets
        if (mayGoBack) {
            log("Recherche : pas d'onglet ici, retour en arrière");
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(() -> {
                AccessibilityNodeInfo r2 = getRootInActiveWindow();
                if (r2 != null) {
                    List<Item> it2 = new ArrayList<>();
                    collect(r2, 0, it2, 1500);
                    scanTabs(it2, SystemClock.uptimeMillis());
                }
                openSearch(false);
            }, 600);
            return;
        }
        log("Recherche : aucun onglet trouvé (" + tabNames + ")");
    }

    /**
     * L'onglet mémorisé, mais seulement s'il est encore réellement affiché dans
     * la barre du bas. Sans cette vérification, un onglet périmé garde ses
     * anciennes coordonnées et l'appui part au hasard sur l'écran.
     */
    private AccessibilityNodeInfo freshTab(int idx) {
        if (idx < 0 || idx >= tabNodes.size()) return null;
        try {
            AccessibilityNodeInfo n = tabNodes.get(idx);
            if (n == null || !n.refresh() || !n.isVisibleToUser()) return null;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            return inBottomBand(r) ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean inBottomBand(Rect r) {
        return !r.isEmpty() && r.centerY() > H - dp(130) && r.centerY() < H;
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
        handler.postDelayed(this::tapField, prefs.getInt("field_delay", 550));
    }

    /**
     * Deuxième temps de la macro : appuyer sur la barre « Rechercher » en haut
     * de l'écran. C'est elle qui fait apparaître la liste des comptes récents.
     */
    void tapField() {
        int cx = macroX(), cy = macroY();

        // Option : laisser l'app chercher le champ toute seule. Désactivée par
        // défaut, car un champ mal reconnu fait partir l'appui n'importe où.
        if (prefs.getBoolean("macro_nodes", false)) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                List<Item> items = new ArrayList<>();
                collect(root, 0, items, 1200);
                for (Item it : items) {
                    if (!it.visible || it.r.isEmpty()) continue;
                    if (!it.r.contains(cx, cy)) continue;        // il doit couvrir la cible
                    if (it.r.height() > dp(90)) continue;
                    String s = (it.text + " " + it.desc + " " + it.id).toLowerCase(Locale.ROOT);
                    if (!it.node.isEditable() && !s.contains("recherch") && !s.contains("search")) {
                        continue;
                    }
                    AccessibilityNodeInfo c = clickable(it.node);
                    try {
                        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            log("Barre Rechercher ouverte par son élément " + it.r.toShortString());
                            swipeDown();
                            return;
                        }
                    } catch (Exception ignored) { }
                }
                log("Barre Rechercher : aucun élément sur la cible, appui direct");
            }
        }

        // les caches laissent passer le temps de l'appui
        maskOffUntil = SystemClock.uptimeMillis() + 700;
        blockTop = blocker(blockTop, new Rect());
        Path p = new Path();
        p.moveTo(cx, cy);
        tap(p, 60, "barre Rechercher en " + cx + "," + cy);
        swipeDown();
    }

    /** Envoie un geste, sauf si les appuis simulés sont coupés dans les réglages. */
    private boolean tap(Path p, int ms, String what) {
        if (!prefs.getBoolean("gestures", true)) {
            log("Appui simulé coupé dans les réglages : " + what);
            return false;
        }
        try {
            boolean sent = dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, ms)).build(),
                    null, null);
            log("Appui simulé : " + what + " (envoyé : " + sent + ")");
            return sent;
        } catch (Exception e) {
            log("Appui simulé impossible : " + what + " — " + e);
            return false;
        }
    }

    /** Troisième temps : le petit glissement vers le bas, qui referme le clavier. */
    private void swipeDown() {
        int delay = prefs.getInt("swipe_delay", 450);
        handler.postDelayed(() -> {
            if (!prefs.getBoolean("gestures", true)) {
                log("Glissement coupé dans les réglages");
                return;
            }
            int dist = dp(prefs.getInt("swipe_dist", 55));
            int x = prefs.getInt("swipe_x", -1) < 0 ? W / 2 : prefs.getInt("swipe_x", W / 2);
            int y0 = prefs.getInt("swipe_y", -1) < 0 ? (int) (H * 0.32)
                    : prefs.getInt("swipe_y", 0);
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
                log("Glissement depuis " + x + "," + y0 + " sur " + dist
                        + " px (envoyé : " + ok + ")");
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
            Path p = new Path();
            p.moveTo(cx, cy);
            if (tap(p, 60, "onglet Recherche en " + cx + "," + cy)) revealRecent();
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
        private Rect mt = new Rect();
        private Rect mr = new Rect();
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

        void setMasks(Rect top, Rect right) {
            if (top.equals(mt) && right.equals(mr)) return;
            mt = new Rect(top);
            mr = new Rect(right);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(barColor());
            if (!mt.isEmpty()) c.drawRect(mt.left, mt.top, mt.right, mt.bottom, p);
            if (!mr.isEmpty()) c.drawRect(mr.left, mr.top, mr.right, mr.bottom, p);

            if (prefs.getBoolean("adjust", false)) guides(c);

            if (bar.isEmpty()) return;
            p.setStyle(Paint.Style.FILL);
            p.setColor(barColor());
            c.drawRect(bar.left, bar.top, bar.right, bar.bottom, p);

            if (prefs.getBoolean("divider", true)) {
                p.setColor(0xFF262626);
                c.drawRect(bar.left, bar.top, bar.right, bar.top + Math.max(1, dp(1) / 2), p);
            }

            int cy = bar.centerY();
            drawDirect(c, btnX(true), cy, dp(btnSize(true)));
            drawSearch(c, btnX(false), cy, dp(btnSize(false)),
                    selectedTab >= 0 && selectedTab == searchIndex());
        }

        /** En mode réglage : les contours des zones et la cible de la macro. */
        private void guides(Canvas c) {
            Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
            q.setStyle(Paint.Style.STROKE);
            q.setStrokeWidth(Math.max(2, dp(1)));

            q.setColor(0xFF00E5FF);                       // les deux boutons
            for (boolean l : new boolean[]{true, false}) {
                Rect b = btnRect(l);
                c.drawRect(b.left, b.top, b.right, b.bottom, q);
            }
            q.setColor(0xFF76FF03);                       // les deux caches
            Rect t = maskTopRect(), r = maskRightRect();
            if (!t.isEmpty()) c.drawRect(t.left + 1, t.top, t.right - 1, t.bottom, q);
            if (!r.isEmpty()) c.drawRect(r.left, r.top, r.right - 1, r.bottom, q);

            q.setColor(0xFFFF4081);                       // la cible de la macro
            int mx = macroX(), my = macroY();
            c.drawCircle(mx, my, dp(10), q);
            c.drawLine(mx - dp(18), my, mx + dp(18), my, q);
            c.drawLine(mx, my - dp(18), mx, my + dp(18), q);

            q.setColor(0xFFFFC107);                       // le départ du glissement
            int sx = prefs.getInt("swipe_x", -1) < 0 ? W / 2 : prefs.getInt("swipe_x", W / 2);
            int sy = prefs.getInt("swipe_y", -1) < 0 ? (int) (H * 0.32) : prefs.getInt("swipe_y", 0);
            int d = dp(prefs.getInt("swipe_dist", 55));
            c.drawCircle(sx, sy, dp(8), q);
            c.drawLine(sx, sy, sx, sy + d, q);
            c.drawLine(sx - dp(7), sy + d - dp(7), sx, sy + d, q);
            c.drawLine(sx + dp(7), sy + d - dp(7), sx, sy + d, q);
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
    private void drawSearch(Canvas c, int cx, int cy, int size, boolean active) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke(p);
        if (active) p.setStrokeWidth(dp(3));
        float r = size;
        c.drawCircle(cx - r * 0.18f, cy - r * 0.18f, r, p);
        c.drawLine(cx + r * 0.55f, cy + r * 0.55f, cx + r * 1.25f, cy + r * 1.25f, p);
    }

    /** Avion en papier, comme l'icône Messages d'Instagram. */
    private void drawDirect(Canvas c, int cx, int cy, int size) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke(p);
        float s = size;
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

    /** La toile de dessin : une seule fenêtre, plein écran, qui ne reçoit aucun appui. */
    private boolean ensureCanvas() {
        if (canvas != null) return true;
        if (wm == null) return false;
        canvas = new BarCanvas(this);
        WindowManager.LayoutParams lp = params(0, 0, W, H,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        try {
            wm.addView(canvas, lp);
        } catch (Exception e) {
            canvas = null;
            return false;
        }
        return true;
    }

    // ---------- Caches de la page Récent ----------

    private void showMasks() {
        if (!ensureCanvas()) return;
        final Rect top = maskTopRect();
        final Rect right = maskRightRect();
        canvas.setMasks(top, right);
        // pendant un appui simulé, les caches laissent passer : le dessin reste,
        // seules les fenêtres qui avalent les appuis s'effacent un instant
        final boolean paused = SystemClock.uptimeMillis() < maskOffUntil;
        handler.post(() -> {
            blockTop = blocker(blockTop, paused ? new Rect() : top);
            blockRight = blocker(blockRight, paused ? new Rect() : right);
        });
    }

    private void hideMasks() {
        if (canvas != null) canvas.setMasks(new Rect(), new Rect());
        blockTop = blocker(blockTop, new Rect());
        blockRight = blocker(blockRight, new Rect());
        onRecent = false;
    }

    /** Une fenêtre invisible qui avale les appuis de sa seule zone. */
    private View blocker(View v, Rect r) {
        if (wm == null) return v;
        if (r.isEmpty()) {
            if (v != null) {
                try { wm.removeView(v); } catch (Exception ignored) { }
            }
            return null;
        }
        if (v == null) {
            View nv = new View(this);
            nv.setOnTouchListener((vv, ev) -> ev.getAction() != MotionEvent.ACTION_OUTSIDE);
            try {
                wm.addView(nv, params(r.left, r.top, r.width(), r.height(), 0));
            } catch (Exception e) {
                return null;
            }
            return nv;
        }
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
        if (lp.x != r.left || lp.y != r.top || lp.width != r.width() || lp.height != r.height()) {
            lp.x = r.left;
            lp.y = r.top;
            lp.width = r.width();
            lp.height = r.height();
            try { wm.updateViewLayout(v, lp); } catch (Exception ignored) { }
        }
        return v;
    }

    private void showBar(Rect r) {
        if (wm == null || r.isEmpty()) return;
        if (!ensureCanvas()) return;
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
                if (ev.getAction() == MotionEvent.ACTION_OUTSIDE) return false;
                if (ev.getAction() == MotionEvent.ACTION_UP) {
                    barTap(ev.getRawX(), ev.getRawY());
                }
                return true;
            });
            Rect t = touchRect();
            try {
                wm.addView(touchView, params(t.left, t.top, t.width(), t.height(), 0));
                log("Fenêtre tactile " + t.toShortString());
            } catch (Exception e) {
                touchView = null;
            }
        } else {
            Rect t = touchRect();
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) touchView.getLayoutParams();
            if (lp.x != t.left || lp.y != t.top || lp.width != t.width() || lp.height != t.height()) {
                lp.x = t.left;
                lp.y = t.top;
                lp.width = t.width();
                lp.height = t.height();
                try { wm.updateViewLayout(touchView, lp); } catch (Exception ignored) { }
                log("Fenêtre tactile " + t.toShortString());
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
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
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

    private static final String[] MODES = {
            "Barre", "Icône Messages", "Icône Recherche",
            "Zone tactile Messages", "Zone tactile Recherche",
            "Cache du haut", "Cache des croix", "Cible de la macro", "Glissement"};
    private int mode = 0;
    private int panelPos = 2;            // 0 haut, 1 milieu haut, 2 milieu bas, 3 bas

    private void showAdjuster() {
        if (adjuster != null) return;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackgroundColor(0xF21C1C1C);
        box.setPadding(dp(6), dp(4), dp(6), dp(4));

        final android.widget.TextView label = new android.widget.TextView(this);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(11);
        box.addView(label);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            label.setText((learn != 0 ? "✛ touche l'écran pour placer la zone\n" : "")
                    + "▸ " + MODES[mode] + "\n" + values());
            if (canvas != null) canvas.invalidate();
            showBar(barRect());
        };

        box.addView(row(new String[]{"Barre", "Icône Msg", "Icône Rech"}, new Runnable[]{
                () -> { mode = 0; refresh[0].run(); },
                () -> { mode = 1; refresh[0].run(); },
                () -> { mode = 2; refresh[0].run(); }}));
        box.addView(row(new String[]{"Zone Msg", "Zone Rech", "Apprendre Msg", "Apprendre Rech"},
                new Runnable[]{
                () -> { mode = 3; refresh[0].run(); },
                () -> { mode = 4; refresh[0].run(); },
                () -> {
                    mode = 3; learn = 1;
                    log("Apprentissage : touche l'écran là où doit être le bouton Messages");
                    refresh[0].run();
                },
                () -> {
                    mode = 4; learn = -1;
                    log("Apprentissage : touche l'écran là où doit être le bouton Recherche");
                    refresh[0].run();
                }}));
        box.addView(row(new String[]{"Cache haut", "Cache croix", "Cible", "Glisse"},
                new Runnable[]{
                () -> { mode = 5; refresh[0].run(); },
                () -> { mode = 6; refresh[0].run(); },
                () -> { mode = 7; refresh[0].run(); },
                () -> { mode = 8; refresh[0].run(); }}));
        box.addView(row(new String[]{"←", "→", "↑", "↓", "−", "+"}, new Runnable[]{
                () -> { move(-1, 0); refresh[0].run(); },
                () -> { move(1, 0); refresh[0].run(); },
                () -> { move(0, -1); refresh[0].run(); },
                () -> { move(0, 1); refresh[0].run(); },
                () -> { resize(-1); refresh[0].run(); },
                () -> { resize(1); refresh[0].run(); }}));
        box.addView(row(new String[]{"←←", "→→", "↑↑", "↓↓", "−−", "++"}, new Runnable[]{
                () -> { move(-6, 0); refresh[0].run(); },
                () -> { move(6, 0); refresh[0].run(); },
                () -> { move(0, -6); refresh[0].run(); },
                () -> { move(0, 6); refresh[0].run(); },
                () -> { resize(-6); refresh[0].run(); },
                () -> { resize(6); refresh[0].run(); }}));
        box.addView(row(new String[]{"Tester l'appui", "Tester la macro", "Panneau ↕"},
                new Runnable[]{
                () -> { log("— test de l'appui demandé —"); tapOnce(); },
                () -> { log("— test de la macro demandé —"); tapField(); },
                () -> { panelPos = (panelPos + 1) % 4; placeAdjuster(); }}));
        box.addView(row(new String[]{"Plus clair", "Plus foncé", "Terminé"}, new Runnable[]{
                () -> { tint(2); refresh[0].run(); },
                () -> { tint(-2); refresh[0].run(); },
                () -> {
                    prefs.edit().putBoolean("adjust", false).apply();
                    hideAdjuster();
                    if (canvas != null) canvas.invalidate();
                }}));
        refresh[0].run();

        try {
            wm.addView(box, params(0, panelY(), WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT, 0));
            adjuster = box;
        } catch (Exception e) {
            log("Réglage impossible : " + e);
        }
    }

    private int panelY() {
        return (int) (H * new float[]{0.08f, 0.30f, 0.50f, 0.68f}[panelPos]);
    }

    private void placeAdjuster() {
        if (adjuster == null) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) adjuster.getLayoutParams();
        lp.y = panelY();
        try { wm.updateViewLayout(adjuster, lp); } catch (Exception ignored) { }
    }

    /** Les valeurs du réglage en cours, telles qu'elles sont enregistrées. */
    private String values() {
        switch (mode) {
            case 1:
            case 2: {
                boolean l = mode == 1;
                return "centre x " + btnX(l) + " px · taille " + btnSize(l) + " dp";
            }
            case 3:
            case 4: {
                boolean l = mode == 3;
                return "centre " + zoneX(l) + "," + zoneY(l) + " px · "
                        + btnZone(l) + "×" + zoneH(l) + " px\n" + btnRect(l).toShortString();
            }
            case 5:
                return "haut " + prefs.getInt("m_top", 18) + " dp · bas "
                        + prefs.getInt("m_bottom", 115) + " dp\n" + maskTopRect().toShortString();
            case 6: {
                int rt = prefs.getInt("m_rtop", -1);
                return "largeur " + prefs.getInt("m_right", 58) + " dp · haut "
                        + (rt < 0 ? "comme le cache du haut" : rt + " dp") + "\n"
                        + maskRightRect().toShortString();
            }
            case 7:
                return "appui en " + macroX() + "," + macroY() + " px";
            case 8: {
                int sx = prefs.getInt("swipe_x", -1), sy = prefs.getInt("swipe_y", -1);
                return "départ " + (sx < 0 ? W / 2 : sx) + "," + (sy < 0 ? (int) (H * 0.32) : sy)
                        + " px · longueur " + prefs.getInt("swipe_dist", 55) + " dp";
            }
            default:
                return "hauteur " + prefs.getInt("bar_height", 56) + " dp · bas "
                        + prefs.getInt("bar_gap", 15) + " dp\n" + barRect().toShortString()
                        + " · couleur " + String.format("#%06X", barColor() & 0xFFFFFF);
        }
    }

    private void setInt(String key, int v) {
        prefs.edit().putInt(key, v).apply();
    }

    /** Les flèches : déplacent l'élément choisi. Un pas vaut 2 px, ou 1 dp. */
    private void move(int dx, int dy) {
        switch (mode) {
            case 0:
                nudge(0, dy * 2, 0, dy * 2);
                break;
            case 1:
                setInt("bg_x", btnX(true) + dx * 6);
                break;
            case 2:
                setInt("bd_x", btnX(false) + dx * 6);
                break;
            case 3:
                setInt("bg_tx", zoneX(true) + dx * 6);
                setInt("bg_ty", zoneY(true) + dy * 6);
                break;
            case 4:
                setInt("bd_tx", zoneX(false) + dx * 6);
                setInt("bd_ty", zoneY(false) + dy * 6);
                break;
            case 5:
                setInt("m_top", Math.max(0, prefs.getInt("m_top", 18) + dy));
                setInt("m_bottom", Math.max(4, prefs.getInt("m_bottom", 115) + dy));
                break;
            case 6: {
                int rt = prefs.getInt("m_rtop", -1);
                if (rt < 0) rt = prefs.getInt("m_bottom", 115);
                setInt("m_rtop", Math.max(0, rt + dy));
                if (dx != 0) setInt("m_right", Math.max(0, prefs.getInt("m_right", 58) + dx));
                break;
            }
            case 7:
                setInt("macro_x", macroX() + dx * 6);
                setInt("macro_y", macroY() + dy * 6);
                break;
            case 8: {
                int sx = prefs.getInt("swipe_x", -1), sy = prefs.getInt("swipe_y", -1);
                setInt("swipe_x", (sx < 0 ? W / 2 : sx) + dx * 6);
                setInt("swipe_y", (sy < 0 ? (int) (H * 0.32) : sy) + dy * 6);
                break;
            }
            default:
                break;
        }
    }

    /** Les boutons − et + : changent la taille de l'élément choisi. */
    private void resize(int d) {
        switch (mode) {
            case 0:
                setInt("bar_height", Math.max(20, prefs.getInt("bar_height", 56) + d));
                break;
            case 1:
                setInt("bg_size", Math.max(4, btnSize(true) + d));
                break;
            case 2:
                setInt("bd_size", Math.max(4, btnSize(false) + d));
                break;
            case 3:
                setInt("bg_w", Math.max(8, btnZone(true) / dp(1) + d));
                break;
            case 4:
                setInt("bd_w", Math.max(8, btnZone(false) / dp(1) + d));
                break;
            case 5:
                setInt("m_bottom", Math.max(4, prefs.getInt("m_bottom", 115) + d));
                break;
            case 6:
                setInt("m_right", Math.max(0, prefs.getInt("m_right", 58) + d));
                break;
            case 7:
                break;
            case 8:
                setInt("swipe_dist", Math.max(10, prefs.getInt("swipe_dist", 55) + d));
                break;
            default:
                break;
        }
    }

    /** Un simple appui sur la cible, sans la suite de la macro. */
    private void tapOnce() {
        maskOffUntil = SystemClock.uptimeMillis() + 700;
        blockTop = blocker(blockTop, new Rect());
        Path p = new Path();
        p.moveTo(macroX(), macroY());
        tap(p, 60, "test en " + macroX() + "," + macroY());
    }

    private android.widget.LinearLayout row(String[] names, Runnable[] actions) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        for (int i = 0; i < names.length; i++) {
            final Runnable a = actions[i];
            android.widget.Button b = new android.widget.Button(this);
            b.setText(names[i]);
            b.setTextSize(names.length > 4 ? 10 : 11);
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
