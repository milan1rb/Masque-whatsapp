package com.perso.wamasque;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MaskService extends AccessibilityService {

    static final String PREFS = "config";
    static volatile boolean running = false;
    static volatile String lastDump =
            "Rien pour l'instant : active le service, ouvre WhatsApp sur l'écran principal, puis reviens ici.";

    // Couleur des masques (fond du thème sombre de WhatsApp) et couleur du mode test
    private static final int COLOR_MASK = 0xFF0B141A;
    private static final int COLOR_TEST = 0x88FF0000;

    private WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> views = new ArrayList<>();
    private final List<Rect> shown = new ArrayList<>();
    private int shownColor = 0;
    private boolean scheduled = false;
    private boolean inWhatsApp = false;
    private long selectUntil = 0;
    private int scrollTries = 0;
    private long lastDumpTime = 0;

    private final Runnable tick = () -> {
        scheduled = false;
        try { update(); } catch (Exception ignored) { }
    };

    static class Item {
        AccessibilityNodeInfo node;
        int depth;
        String text, desc, id, cls;
        Rect r = new Rect();
        boolean visible;
    }

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        running = true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!scheduled) {
            scheduled = true;
            handler.postDelayed(tick, 120);
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

    private void update() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String pkg = String.valueOf(root.getPackageName());

        if (!pkg.equals("com.whatsapp") && !pkg.equals("com.whatsapp.w4b")) {
            showRects(new ArrayList<>(), 0);
            if (!pkg.equals("com.android.systemui")) inWhatsApp = false;
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (!inWhatsApp) {
            inWhatsApp = true;
            selectUntil = now + 8000;
            scrollTries = 0;
        }

        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int W = dm.widthPixels, H = dm.heightPixels;

        List<Item> items = new ArrayList<>();
        collect(root, 0, items);

        // Écran principal = onglets "Discussions" et "Appels" présents en bas
        boolean hasDisc = false, hasCalls = false;
        for (Item it : items) {
            if (it.r.centerY() > H * 0.8) {
                if (is(it, "Discussions")) hasDisc = true;
                if (is(it, "Appels")) hasCalls = true;
            }
        }
        boolean home = hasDisc && hasCalls;

        if (home && now - lastDumpTime > 1500) {
            lastDumpTime = now;
            lastDump = dump(items, pkg);
        }

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        List<Rect> rects = new ArrayList<>();

        if (home) {
            boolean toutesFound = false, aiFound = false;
            for (Item it : items) {
                if (!it.visible) continue;
                int cy = it.r.centerY();

                if (p.getBoolean("cam", true) && cy < H * 0.15
                        && (is(it, "Appareil photo", "Caméra", "Camera") || it.id.contains("camera"))) {
                    add(rects, target(it.node, W));

                } else if (p.getBoolean("toutes", true) && cy < H * 0.35 && starts(it, "Toutes")) {
                    add(rects, target(it.node, W));
                    toutesFound = true;

                } else if (p.getBoolean("metaai", true) && cy > H * 0.5 && it.r.left > W * 0.6
                        && it.r.width() < W * 0.4 && !it.node.isEditable()
                        && (has(it, "meta ai") || it.id.contains("meta_ai"))) {
                    add(rects, target(it.node, W));
                    aiFound = true;

                } else if (p.getBoolean("actus", true) && cy > H * 0.8 && is(it, "Actus")) {
                    add(rects, target(it.node, W));

                } else if (p.getBoolean("commu", true) && cy > H * 0.8 && starts(it, "Communaut")) {
                    add(rects, target(it.node, W));
                }
            }
            if (p.getBoolean("toutes", true) && !toutesFound) maskToutesFallback(items, rects, W, H);
            if (p.getBoolean("metaai", true) && !aiFound) maskMetaAiFallback(items, rects, W, H);
        }

        showRects(rects, p.getBoolean("debug", false) ? COLOR_TEST : COLOR_MASK);

        String wanted = norm(p.getString("liste", ""));
        if (home && !wanted.isEmpty() && now < selectUntil) {
            trySelect(items, wanted, W, H);
        }
    }

    // "Toutes" introuvable par son nom : on le déduit de la position de "Non lues"
    // (la liste juste à sa gauche), sinon du premier élément de la barre des listes.
    private void maskToutesFallback(List<Item> items, List<Rect> rects, int W, int H) {
        for (Item it : items) {
            if (it.r.centerY() < H * 0.35 && starts(it, "Non lues")) {
                Rect chip = target(it.node, W);
                int w = chip.width() * 75 / 100;
                int right = chip.left - dp(8);
                Rect g = new Rect(Math.max(0, right - w), chip.top, right, chip.bottom);
                if (g.width() > dp(20)) add(rects, g);
                return;
            }
        }
        for (Item it : items) {
            if (it.node.isScrollable() && it.r.centerY() < H * 0.35 && it.r.height() < H * 0.15
                    && it.node.getChildCount() > 0) {
                AccessibilityNodeInfo first = it.node.getChild(0);
                if (first == null) return;
                Rect r = new Rect();
                first.getBoundsInScreen(r);
                if (r.left < W * 0.3) add(rects, r);
                return;
            }
        }
    }

    // Bouton Meta AI introuvable par son nom : il est toujours juste au-dessus
    // du bouton vert "nouvelle discussion", on masque cette zone.
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
        if (fab == null) return;
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

    private void trySelect(List<Item> items, String wanted, int W, int H) {
        for (Item it : items) {
            if (it.visible && it.r.centerY() < H * 0.35 && starts(it, wanted)) {
                AccessibilityNodeInfo c = clickableNode(it.node, W);
                boolean already = c.isSelected() || c.isChecked()
                        || it.node.isSelected() || it.node.isChecked();
                if (!already) c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                selectUntil = 0;
                return;
            }
        }
        // Liste pas visible : on fait défiler la barre des listes vers la droite
        if (scrollTries < 5) {
            for (Item it : items) {
                if (it.node.isScrollable() && it.r.centerY() < H * 0.35 && it.r.height() < H * 0.15) {
                    it.node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    scrollTries++;
                    return;
                }
            }
        }
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

    // Remonte jusqu'à l'élément cliquable (le bouton entier, pas juste son texte)
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
