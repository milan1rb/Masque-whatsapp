package com.perso.wamasque;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Color;
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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, View> slots = new HashMap<>();
    private boolean scheduled = false;
    private boolean inWhatsApp = false;
    private long lastDumpTime = 0;

    // Macro de lancement : 0 = rien, 1 = placer la liste, 2 = cliquer, 3 = vérifier
    private int phase = 0;
    private long phaseDeadline = 0;
    private long waitUntil = 0;
    private int swipeTries = 0;
    private int lastD = Integer.MIN_VALUE;
    private boolean clickAfter = true;
    private long misalignedSince = 0;
    private long lastHomeTime = 0;
    private Map<String, Rect> lastWant = new HashMap<>();
    // Apprentissage de la précision du glissement
    private int calibReq = 0;
    private int calibLeft = Integer.MIN_VALUE;
    private String calibName = null;
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
        String name;
        boolean selected;
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
                & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0) + ")");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        schedule(100);
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

    // ---------- Boucle principale ----------

    private void update() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active == null) {
            if (phase != 0) schedule(300);
            return;
        }
        String pkg = String.valueOf(active.getPackageName());
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        int color = parseColor(p);

        if (!isWa(pkg)) {
            showMasks(new HashMap<>(), color);
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
            String saved = p.getString("liste", "").trim();
            boolean wantMacro = !saved.isEmpty()
                    && (p.getBoolean("slide", true) || p.getBoolean("click", true));
            if (saved.isEmpty()) log("WhatsApp ouvert : aucune liste enregistrée, macro désactivée");
            else if (!wantMacro) log("WhatsApp ouvert : les 2 cases de la macro sont décochées");
            if (wantMacro) {
                startMacro(now, true);
                log("WhatsApp ouvert : macro démarrée pour « " + saved + " »");
            }
        }

        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int W = dm.widthPixels, H = dm.heightPixels;

        // Toutes les fenêtres de WhatsApp : l'écran principal + menus/popups éventuels
        List<Item> items = null;
        String mainPkg = pkg;
        List<Rect> popups = new ArrayList<>();
        boolean smallWindows = true;
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || !isWa(root.getPackageName())) continue;
            List<Item> list = new ArrayList<>();
            collect(root, 0, list);
            if (items == null && isHome(list, H)) {
                items = list;
                mainPkg = String.valueOf(root.getPackageName());
            } else {
                // Partie visible du menu : ses lignes de texte (le fond invisible plein écran est ignoré)
                Rect b = null;
                for (Item it : list) {
                    if (!it.visible || it.r.isEmpty() || it.text.isEmpty()) continue;
                    Rect r = target(it.node, W);
                    if (r.width() > W * 0.95 || r.height() > H * 0.5) r = new Rect(it.r);
                    if (b == null) b = new Rect(r); else b.union(r);
                }
                smallWindows = smallWindows && list.size() < 80;
                if (b != null) popups.add(b);
            }
        }
        if (items == null) {
            if (smallWindows && !popups.isEmpty() && now - lastHomeTime < 60000) {
                // Un menu est ouvert par-dessus l'écran principal : on garde les caches
                Map<String, Rect> keep = new HashMap<>();
                for (Map.Entry<String, Rect> e : lastWant.entrySet()) {
                    boolean hit = false;
                    for (Rect pop : popups) if (Rect.intersects(e.getValue(), pop)) hit = true;
                    if (!hit) keep.put(e.getKey(), e.getValue());
                }
                log("Menu ouvert (écran principal masqué par Android) : caches conservés " + keep.keySet());
                showMasks(keep, p.getBoolean("debug", false) ? COLOR_TEST : color);
                return;
            }
            // Pas sur l'écran principal (dans une discussion, réglages…)
            showMasks(new HashMap<>(), color);
            if (phase != 0) runMacro(false, new ArrayList<>(), items, p, now, W);
            return;
        }
        lastHomeTime = now;
        if (!popups.isEmpty()) log("Menu ouvert, zone " + popups.get(0).toShortString());

        if (now - lastDumpTime > 1500) {
            lastDumpTime = now;
            lastDump = dump(items, mainPkg);
        }

        // ----- Masques -----
        boolean selectionMode = false;
        for (Item it : items) {
            if (it.visible && it.r.centerY() < H * 0.15
                    && (has(it, "archiv") || starts(it, "Épingl") || starts(it, "Supprimer"))) {
                selectionMode = true;
            }
        }

        Map<String, Rect> want = new HashMap<>();
        for (Item it : items) {
            if (!it.visible) continue;
            int cy = it.r.centerY();
            if (p.getBoolean("cam", true) && !selectionMode && cy < H * 0.15
                    && (it.id.endsWith("menuitem_camera") || is(it, "Caméra", "Appareil photo"))) {
                want.put("cam", new Rect(it.r));
            } else if (p.getBoolean("metaai", true) && cy > H * 0.4 && it.r.width() < W * 0.4
                    && (it.id.endsWith("extended_mini_fab") || has(it, "message à l'ia") || has(it, "meta ai"))
                    && !it.node.isEditable()) {
                if (!want.containsKey("metaai")) want.put("metaai", target(it.node, W));
            } else if (p.getBoolean("actus", true) && cy > H * 0.8 && is(it, "Actus")) {
                want.put("actus", target(it.node, W));
            } else if (p.getBoolean("commu", true) && cy > H * 0.8 && starts(it, "Communaut")) {
                want.put("commu", target(it.node, W));
            }
        }
        // Ne jamais recouvrir un menu ouvert (⋮, appui long…)
        for (String k : KEYS) {
            Rect r = want.get(k);
            if (r == null) continue;
            for (Rect pop : popups) if (Rect.intersects(r, pop)) { want.remove(k); break; }
        }
        if (popups.isEmpty()) lastWant = new HashMap<>(want);
        showMasks(want, p.getBoolean("debug", false) ? COLOR_TEST : color);

        // ----- Macro -----
        List<Chip> chips = findChips(items, H);
        if (phase != 0) {
            runMacro(true, chips, items, p, now, W);
        } else if (p.getBoolean("slide", true) && popups.isEmpty()) {
            watchdog(chips, items, p, now, W);
        }
    }

    private void startMacro(long now, boolean withClick) {
        phase = 1;
        clickAfter = withClick;
        phaseDeadline = now + 10000;
        waitUntil = now + 250;
        swipeTries = 0;
        lastD = Integer.MIN_VALUE;
        calibName = null;
    }

    // Toutes les 2 s : si la barre est partie trop à gauche, on la remet en place
    private void watchdog(List<Chip> chips, List<Item> items, SharedPreferences p, long now, int W) {
        String wanted = norm(p.getString("liste", ""));
        if (wanted.isEmpty() || chips.isEmpty() || gestureBusy) { misalignedSince = 0; return; }
        Chip goal = findGoal(chips, wanted);
        boolean tooLeft;
        if (goal == null) {
            boolean toutesVisible = false;
            for (Chip c : chips) if (norm(c.name).startsWith("toutes")) toutesVisible = true;
            tooLeft = !toutesVisible;
        } else {
            tooLeft = goal.r.left < targetLeft(chips, items) - dp(4);
        }
        if (!tooLeft) { misalignedSince = 0; return; }
        if (misalignedSince == 0) misalignedSince = now;
        if (now - misalignedSince >= 2000) {
            misalignedSince = 0;
            log("Barre décalée trop à gauche depuis 2 s : recalage");
            startMacro(now, false);
            waitUntil = now;
        }
        schedule(400);
    }

    private Chip findGoal(List<Chip> chips, String wanted) {
        for (Chip c : chips) if (norm(c.name).startsWith(wanted)) return c;
        return null;
    }

    // Position voulue : juste assez à gauche pour que la liste précédente soit entièrement cachée
    private int targetLeft(List<Chip> chips, List<Item> items) {
        int barLeft = 0;
        for (Item it : items) if (it.id.endsWith("filter_recycler_view")) { barLeft = it.r.left; break; }
        int gap = Integer.MAX_VALUE;
        for (int i = 0; i + 1 < chips.size(); i++) {
            int g = chips.get(i + 1).r.left - chips.get(i).r.right;
            if (g > 0 && g < gap) gap = g;
        }
        if (gap == Integer.MAX_VALUE) gap = dp(7);
        return barLeft + gap + 2;
    }

    private boolean isHome(List<Item> items, int H) {
        boolean disc = false, calls = false;
        for (Item it : items) {
            if (it.r.centerY() > H * 0.8) {
                if (is(it, "Discussions")) disc = true;
                if (is(it, "Appels")) calls = true;
            }
        }
        return disc && calls;
    }

    private void runMacro(boolean home, List<Chip> chips, List<Item> items, SharedPreferences p,
                          long now, int W) {
        if (now > phaseDeadline) {
            log("Macro abandonnée : délai dépassé (étape " + phase + ")");
            phase = 0;
            return;
        }
        schedule(120);

        if (!home) { log("En attente de l'écran principal…"); return; }
        if (now < waitUntil) return;
        if (gestureBusy) {
            if (now - gestureStart < 3000) return;
            gestureBusy = false;
        }
        if (chips.isEmpty()) { log("Barre des listes introuvable"); return; }

        String wanted = norm(p.getString("liste", ""));
        boolean slide = p.getBoolean("slide", true);
        Chip goal = findGoal(chips, wanted);

        if (goal == null) {
            calibName = null;
            if (swipeTries < 6) {
                swipeTries++;
                boolean toutesVisible = false;
                for (Chip c : chips) if (norm(c.name).startsWith("toutes")) toutesVisible = true;
                int dir = toutesVisible ? 1 : -1;   // liste à droite si Toutes est visible, sinon à gauche
                log("« " + wanted + " » pas visible, défilement " + (dir > 0 ? "→" : "←") + " — " + describe(chips));
                swipe(chips.get(0).r.centerY(), dir * W / 2, false);
                waitUntil = now + 100;
            } else {
                log("« " + wanted + " » introuvable — " + describe(chips));
                phase = 0;
            }
            return;
        }

        // Apprentissage : compare le déplacement demandé au déplacement réel
        if (calibName != null && calibName.equals(goal.name) && calibLeft != Integer.MIN_VALUE) {
            int moved = calibLeft - goal.r.left;
            if (moved != 0 && Integer.signum(moved) == Integer.signum(calibReq)) {
                int err = Math.abs(calibReq) - Math.abs(moved);
                int calib = p.getInt("calib", 0);
                int next = Math.max(-dp(40), Math.min(dp(80), calib + err * 7 / 10));
                p.edit().putInt("calib", next).apply();
            }
            calibName = null;
        }

        int d = goal.r.left - targetLeft(chips, items);   // > 0 : trop à droite, < 0 : trop à gauche
        boolean stuck = lastD != Integer.MIN_VALUE && Math.abs(d - lastD) < dp(3);

        if (phase == 1 || phase == 3) {
            int maxTries = phase == 1 ? 6 : 8;
            if (slide && Math.abs(d) > dp(4) && swipeTries < maxTries && !stuck) {
                swipeTries++;
                lastD = d;
                calibName = goal.name;
                calibLeft = goal.r.left;
                calibReq = d;
                log("Placement de « " + goal.name + " » : décalage " + d + " px — " + describe(chips));
                swipe(goal.r.centerY(), d, true);
                waitUntil = now + 100;
            } else {
                if (stuck) log("La barre ne peut pas aller plus loin — " + describe(chips));
                lastD = Integer.MIN_VALUE;
                if (phase == 1 && clickAfter) {
                    phase = 2;
                } else {
                    log("Macro terminée — " + describe(chips));
                    phase = 0;
                }
            }

        } else if (phase == 2) {
            if (p.getBoolean("click", true)) {
                if (goal.selected) log("Déjà sélectionnée : " + goal.name);
                else click(goal);
                waitUntil = now + 350;
            }
            phase = 3;
        }
    }

    // ---------- Barre des listes ----------
    // WhatsApp les décrit ainsi : RadioButton [Filtre POTO'S, ‎1, désélectionné]

    private List<Chip> findChips(List<Item> items, int H) {
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
            chips.add(c);
        }
        chips.sort((a, b) -> Integer.compare(a.r.left, b.r.left));
        return chips;
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
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60)).build();
        startGesture();
        boolean ok = dispatchGesture(g, callback("Appui"), null);
        if (!ok) gestureBusy = false;
        log("Appui sur « " + c.name + " » (geste envoyé : " + ok + ")");
    }

    // dist > 0 : contenu vers la gauche, dist < 0 : vers la droite.
    // Mouvement lent puis doigt immobile avant de relâcher (pas d'effet "lancer"),
    // et loin des bords de l'écran (geste retour d'Android).
    private void swipe(int y, int dist, boolean precise) {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int W = dm.widthPixels;
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        if (precise) slop += getSharedPreferences(PREFS, MODE_PRIVATE).getInt("calib", 0);
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
                new GestureDescription.StrokeDescription(move, 0, 220, true);
        Path hold = new Path();
        hold.moveTo(x1, y);
        hold.lineTo(x1 + step, y);
        GestureDescription.StrokeDescription s2 = s1.continueStroke(hold, 0, 130, false);

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
            @Override public void onCompleted(GestureDescription g) { gestureBusy = false; schedule(60); }
            @Override public void onCancelled(GestureDescription g) {
                gestureBusy = false;
                log(name + " annulé par Android");
            }
        };
    }

    // ---------- Masques ----------
    // Une fenêtre fixe par élément : jamais supprimée ni déplacée vers un autre élément,
    // juste cachée/affichée sur place, sans animation.

    private void showMasks(Map<String, Rect> want, int color) {
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
                paint(v, key, color);
                v.setClickable(true);
                try { wm.addView(v, params(g)); slots.put(key, v); } catch (Exception ignored) { }
                continue;
            }
            paint(v, key, color);
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
            if (v.getVisibility() != View.VISIBLE) {
                final View fv = v;
                v.post(() -> fv.setVisibility(View.VISIBLE));
            }
        }
    }

    private void paint(View v, String key, int color) {
        if (key.equals("metaai")) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
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

    private int parseColor(SharedPreferences p) {
        try {
            return Color.parseColor(p.getString("color", DEFAULT_COLOR).trim());
        } catch (Exception e) {
            return Color.parseColor(DEFAULT_COLOR);
        }
    }

    // ---------- Outils ----------

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
