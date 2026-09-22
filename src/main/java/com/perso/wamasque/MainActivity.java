package com.perso.wamasque;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView status;
    private TextView summary;
    private TextView dumpView;
    private LinearLayout root;
    private LinearLayout cur;
    private LinearLayout pageWa, pageFb;
    private Button tabWa, tabFb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(MaskService.PREFS, MODE_PRIVATE);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(40));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        cur = root;

        // ---------- État ----------
        status = new TextView(this);
        status.setTextSize(17);
        status.setPadding(0, 0, 0, dp(8));
        root.addView(status);

        button("Ouvrir les réglages d'accessibilité",
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        summary = new TextView(this);
        summary.setPadding(0, dp(8), 0, 0);
        root.addView(summary);

        // ---------- Choix de l'application ----------
        LinearLayout apps = new LinearLayout(this);
        apps.setOrientation(LinearLayout.HORIZONTAL);
        apps.setPadding(0, dp(16), 0, 0);
        tabWa = new Button(this);
        tabWa.setText("WhatsApp");
        tabFb = new Button(this);
        tabFb.setText("Facebook");
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        apps.addView(tabWa, half);
        apps.addView(tabFb, half);
        root.addView(apps);
        pageWa = new LinearLayout(this);
        pageWa.setOrientation(LinearLayout.VERTICAL);
        pageFb = new LinearLayout(this);
        pageFb.setOrientation(LinearLayout.VERTICAL);
        root.addView(pageWa);
        root.addView(pageFb);
        tabWa.setOnClickListener(v -> showPage(false));
        tabFb.setOnClickListener(v -> showPage(true));

        cur = pageWa;

        // ---------- Éléments masqués ----------
        title("Éléments masqués");
        help("Les caches restent en place tant que tu es sur l'écran principal de WhatsApp.");
        check("title", "Nom « WhatsApp » en haut", true);
        check("cam", "Bouton appareil photo", true);
        check("metaai", "Bouton Meta AI", true);
        check("actus", "Onglet Actus", true);
        check("commu", "Onglet Communautés", true);
        check("disctxt", "Texte « Discussions »", true);
        check("appelstxt", "Texte « Appels »", true);

        // ---------- Position et taille ----------
        title("Position et taille des caches");
        help("Les positions sont retenues automatiquement à la première utilisation.");
        number("margin", 8, 0, 60, "Marge autour de chaque cache, en pixels");
        button("Ajuster la position dans WhatsApp", v -> openWhatsApp("adjust"));
        button("Redétecter les positions", v -> {
            prefs.edit().putBoolean("reset_pos", true).apply();
            toast("Les positions seront redétectées à la prochaine ouverture");
        });

        // ---------- Couleurs ----------
        title("Couleurs");
        help("Une couleur par zone, pour se fondre exactement dans le fond de WhatsApp.");
        hex("colortop", MaskService.DEFAULT_COLOR_TOP, "Zone du haut (nom, appareil photo)");
        hex("color", MaskService.DEFAULT_COLOR, "Zone du bas (onglets, bouton IA)");
        hex("colordim", MaskService.DEFAULT_COLOR_DIM, "Quand une fiche s'ouvre par-dessus");
        button("Régler les couleurs à l'œil dans WhatsApp", v -> openWhatsApp("tune"));
        check("debug", "Mode test : caches rouges transparents", false);

        // ---------- Macro ----------
        title("Macro au lancement");
        help("Au démarrage de WhatsApp : ouvrir une liste et la placer à gauche.");
        text("liste", "Nom de la liste (vide = macro désactivée)");
        check("click", "Ouvrir cette liste", true);
        check("slide", "La placer tout à gauche", true);
        number("posx", 10, 0, 500, "Position X voulue, en pixels depuis le bord gauche");
        button("Tester maintenant", v -> {
            MaskService.forceMacro = true;
            openWhatsApp(null);
        });

        // ---------- Apprentissage ----------
        title("Apprentissage");
        help("L'app retient les écrans et les enchaînements pour réagir plus vite avec le temps.");
        check("learn", "Apprentissage activé", true);
        button("Réinitialiser l'apprentissage", v -> {
            SharedPreferences.Editor ed = prefs.edit();
            for (String k : prefs.getAll().keySet()) {
                if (k.startsWith("cls:") || k.startsWith("cache:") || k.startsWith("go:")
                        || k.startsWith("gon:") || k.equals("gain") || k.equals("loss")) {
                    ed.remove(k);
                }
            }
            ed.apply();
            refresh();
            toast("Apprentissage effacé");
        });

        // ---------- Page Facebook ----------
        cur = pageFb;
        title("Facebook");
        help("Barre du bas : les boutons cochés sont masqués et bloqués en permanence.");
        check("fb_enabled", "Activer sur Facebook", true);
        check("fb1", "1 · Accueil", true);
        check("fb2", "2 · Vidéos", true);
        check("fb3", "3 · Amis", true);
        check("fb4", "4 · Groupes", false);
        check("fb5", "5 · Notifications", false);
        check("fb6", "6 · Profil / menu", true);
        hex("fbcolor", MaskService.DEFAULT_COLOR_FB, "Couleur des caches sur Facebook");
        button("Redétecter la position des boutons", v -> {
            prefs.edit().putBoolean("fb_reset", true).apply();
            toast("Positions redétectées à la prochaine ouverture de Facebook");
        });

        // ---------- Diagnostic (commun) ----------
        cur = root;
        title("Diagnostic");
        help("À envoyer en cas de problème : le journal indique ce que l'app a vu et fait.");
        button("Afficher", v -> dumpView.setText(MaskService.diagnostic()));
        button("Copier", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("diagnostic", MaskService.diagnostic()));
            toast("Copié");
        });
        dumpView = new TextView(this);
        dumpView.setTextIsSelectable(true);
        dumpView.setTextSize(10);
        dumpView.setTypeface(Typeface.MONOSPACE);
        root.addView(dumpView);
    }

    private void showPage(boolean fb) {
        pageWa.setVisibility(fb ? android.view.View.GONE : android.view.View.VISIBLE);
        pageFb.setVisibility(fb ? android.view.View.VISIBLE : android.view.View.GONE);
        tabWa.setTextColor(fb ? 0xFF888888 : 0xFF4CAF50);
        tabFb.setTextColor(fb ? 0xFF4CAF50 : 0xFF888888);
        prefs.edit().putBoolean("page_fb", fb).apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showPage(prefs.getBoolean("page_fb", false));
        refresh();
    }

    private void refresh() {
        boolean on = MaskService.running;
        status.setText(on ? "✅ Service actif" : "❌ Service inactif");
        status.setTextColor(on ? 0xFF4CAF50 : 0xFFE53935);

        int screens = 0, shortcuts = 0;
        for (String k : prefs.getAll().keySet()) {
            if (k.startsWith("cls:")) screens++;
            if (k.startsWith("go:")) shortcuts++;
        }
        int masks = 0;
        for (String k : new String[]{"title", "cam", "metaai", "actus", "commu", "disctxt", "appelstxt"}) {
            if (prefs.getBoolean(k, true)) masks++;
        }
        String list = prefs.getString("liste", "").trim();
        summary.setText(masks + " caches actifs"
                + "\nMacro : " + (list.isEmpty() ? "désactivée" : "liste « " + list + " »")
                + "\nÉcrans connus : " + screens + ", boutons anticipés : " + shortcuts);
    }

    // ---------- Petits outils d'interface ----------

    private void title(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(24), 0, dp(2));
        cur.addView(t);
    }

    private void help(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(0, 0, 0, dp(6));
        cur.addView(t);
    }

    private void button(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        cur.addView(b);
    }

    private void check(String key, String label, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(prefs.getBoolean(key, def));
        cb.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            refresh();
        });
        cur.addView(cb);
    }

    private EditText field(String label, int inputType) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setPadding(0, dp(8), 0, 0);
        cur.addView(t);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(inputType);
        cur.addView(e);
        return e;
    }

    private void text(String key, String label) {
        EditText e = field(label, InputType.TYPE_CLASS_TEXT);
        e.setText(prefs.getString(key, ""));
        e.addTextChangedListener(new Watcher(s -> {
            prefs.edit().putString(key, s.trim()).apply();
            refresh();
        }));
    }

    private void number(String key, int def, int min, int max, String label) {
        EditText e = field(label, InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(prefs.getInt(key, def)));
        e.addTextChangedListener(new Watcher(s -> {
            int v = def;
            try { v = Integer.parseInt(s.trim()); } catch (Exception ignored) { }
            prefs.edit().putInt(key, Math.max(min, Math.min(max, v))).apply();
        }));
    }

    private void hex(String key, String def, String label) {
        EditText e = field(label, InputType.TYPE_CLASS_TEXT);
        e.setText(prefs.getString(key, def));
        e.addTextChangedListener(new Watcher(s -> {
            String c = s.trim();
            if (!c.startsWith("#")) c = "#" + c;
            try {
                Color.parseColor(c);
                prefs.edit().putString(key, c).apply();
            } catch (Exception ignored) { }
        }));
    }

    private void openWhatsApp(String flag) {
        Intent wa = getPackageManager().getLaunchIntentForPackage("com.whatsapp");
        if (wa == null) {
            toast("WhatsApp introuvable");
            return;
        }
        if (flag != null) prefs.edit().putBoolean(flag, true).apply();
        startActivity(wa);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static class Watcher implements TextWatcher {
        interface OnText { void run(String s); }
        private final OnText action;
        Watcher(OnText action) { this.action = action; }
        public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
        public void onTextChanged(CharSequence c, int a, int b, int d) { }
        public void afterTextChanged(Editable e) { action.run(e.toString()); }
    }
}
