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
    private TextView forumInfo;
    private LinearLayout root;
    private LinearLayout cur;
    private LinearLayout pageWa, pageFb, pageFo, pageMs;
    private Button tabWa, tabFb, tabFo, tabMs;

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
        tabFo = new Button(this);
        tabFo.setText("Forum");
        LinearLayout.LayoutParams third = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        apps.addView(tabWa, third);
        apps.addView(tabFb, third);
        apps.addView(tabFo, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabMs = new Button(this);
        tabMs.setText("Messenger");
        apps.addView(tabMs, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        for (Button b : new Button[]{tabWa, tabFb, tabFo, tabMs}) {
            b.setTextSize(11);
            b.setAllCaps(false);
        }
        root.addView(apps);
        pageWa = new LinearLayout(this);
        pageWa.setOrientation(LinearLayout.VERTICAL);
        pageFb = new LinearLayout(this);
        pageFb.setOrientation(LinearLayout.VERTICAL);
        pageFo = new LinearLayout(this);
        pageFo.setOrientation(LinearLayout.VERTICAL);
        root.addView(pageWa);
        root.addView(pageFb);
        root.addView(pageFo);
        pageMs = new LinearLayout(this);
        pageMs.setOrientation(LinearLayout.VERTICAL);
        root.addView(pageMs);
        tabMs.setOnClickListener(v -> showPage(3));
        tabWa.setOnClickListener(v -> showPage(0));
        tabFb.setOnClickListener(v -> showPage(1));
        tabFo.setOnClickListener(v -> showPage(2));

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
        check("fbsaved", "Page Enregistrements : flèche masquée, raccourci Forum en haut à droite", true);
        hex("fbsvcolor", "#242526", "Couleur de la barre du haut des Enregistrements");

        title("Macro Facebook");
        help("À l'ouverture de Facebook, appuyer automatiquement sur une des 6 cases.");
        final String[] names = {"Aucune", "1 · Accueil", "2 · Vidéos", "3 · Amis",
                "4 · Groupes", "5 · Notifications", "6 · Profil / menu"};
        Button macro = new Button(this);
        Runnable label = () -> macro.setText("Ouvrir au lancement : " + names[prefs.getInt("fb_macro", 0)]);
        label.run();
        macro.setOnClickListener(v -> {
            prefs.edit().putInt("fb_macro", (prefs.getInt("fb_macro", 0) + 1) % names.length).apply();
            label.run();
        });
        cur.addView(macro);
        button("Redétecter la position des boutons", v -> {
            prefs.edit().putBoolean("fb_reset", true).apply();
            toast("Positions redétectées à la prochaine ouverture de Facebook");
        });

        // ---------- Page Forum ----------
        cur = pageFo;
        title("Forum");
        help("Barre du bas à 5 cases. La 3e ouvre tes Enregistrements Facebook, la 5e ouvre Messenger.");
        check("fo_enabled", "Activer sur Forum", true);
        check("fo_fakebar", "Fausse barre toujours visible (ne disparaît plus au défilement)", true);
        check("fo1", "1 · Accueil", false);
        check("fo2", "2 · Demander (IA)", false);
        check("fo3", "3 · Créer → remplacé par Enregistrements", true);
        check("fo4", "4 · Notifications", false);
        check("fo5", "5 · Profil → remplacé par Messenger", true);
        hex("focolor", MaskService.DEFAULT_COLOR_FO, "Couleur de la barre de Forum");

        help("Si le bouton Enregistrements ouvre Facebook sans aller sur la bonne page, change de méthode et teste.");
        Button method = new Button(this);
        Runnable mLabel = () -> method.setText("Méthode Enregistrements : "
                + (prefs.getInt("saved_method", 1) + 1) + " / " + MaskService.SAVED_URIS.length);
        mLabel.run();
        method.setOnClickListener(v -> {
            prefs.edit().putInt("saved_method",
                    (prefs.getInt("saved_method", 1) + 1) % MaskService.SAVED_URIS.length).apply();
            mLabel.run();
        });
        cur.addView(method);
        button("Tester cette méthode", v -> {
            if (MaskService.instance != null) MaskService.instance.openSaved();
            else toast("Active d'abord le service");
        });
        forumInfo = new TextView(this);
        forumInfo.setTextSize(13);
        forumInfo.setPadding(0, dp(8), 0, 0);
        cur.addView(forumInfo);
        button("Détecter l'application Forum", v -> {
            prefs.edit().putBoolean("forum_detect", true).apply();
            toast("Ouvre maintenant l'application Forum");
        });

        // ---------- Page Messenger ----------
        cur = pageMs;
        title("Messenger");
        help("Barre du bas à 4 cases. La 4e devient un bouton qui ramène sur Forum.");
        check("ms_enabled", "Activer sur Messenger", true);
        check("ms_fakebar", "Barre du bas redessinée dans le style de Forum", true);
        check("ms1", "1 · Discussions", false);
        check("ms2", "2 · Personnes", true);
        check("ms3", "3 · Notifications", true);
        check("ms4", "4 · Menu → remplacé par Forum", true);
        check("ms5", "Icônes en haut à droite (nouveau message, Facebook)", true);
        check("ms6", "Bouton Meta AI (rond ou allongé)", true);
        hex("mscolor", MaskService.DEFAULT_COLOR_MS, "Couleur de la barre de Messenger");

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

    private void showPage(int page) {
        LinearLayout[] pages = {pageWa, pageFb, pageFo, pageMs};
        Button[] tabs = {tabWa, tabFb, tabFo, tabMs};
        for (int i = 0; i < 4; i++) {
            pages[i].setVisibility(i == page ? android.view.View.VISIBLE : android.view.View.GONE);
            tabs[i].setTextColor(i == page ? 0xFF4CAF50 : 0xFF888888);
        }
        prefs.edit().putInt("page", page).apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showPage(prefs.getInt("page", 0));
        refresh();
    }

    private void refresh() {
        if (forumInfo != null) {
            String fp = prefs.getString("forum_pkg", "");
            forumInfo.setText(fp.isEmpty() ? "Application Forum : pas encore détectée"
                    : "Application Forum : " + fp);
        }
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
