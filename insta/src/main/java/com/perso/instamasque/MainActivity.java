package com.perso.instamasque;

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
    private TextView dumpView;
    private TextView appInfo;
    private Button onOff;
    private LinearLayout root;

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

        status = new TextView(this);
        status.setTextSize(17);
        status.setPadding(0, 0, 0, dp(8));
        root.addView(status);

        onOff = new Button(this);
        onOff.setTextSize(18);
        onOff.setOnClickListener(v -> {
            prefs.edit().putBoolean("enabled", !prefs.getBoolean("enabled", true)).apply();
            refreshOnOff();
        });
        root.addView(onOff);

        button("Ouvrir les réglages d'accessibilité",
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        title("Application visée");
        help("Toutes les versions d'Instagram sont reconnues d'office, l'officielle comme "
                + "les versions modifiées. Si la tienne ne l'est pas, touche Détecter puis "
                + "ouvre-la : elle sera retenue définitivement.");
        appInfo = new TextView(this);
        appInfo.setPadding(0, 0, 0, dp(6));
        root.addView(appInfo);
        button("Détecter l'application", v -> {
            prefs.edit().putBoolean("detect", true).apply();
            toast("Ouvre maintenant l'application à traiter");
        });
        button("Oublier l'application forcée", v -> {
            prefs.edit().remove("pkg").apply();
            refreshApp();
            toast("Retour à la reconnaissance automatique");
        });

        title("Barre du bas");
        help("La vraie barre d'Instagram est recouverte. Deux boutons la remplacent : "
                + "Messages à gauche, Rechercher à droite. Elle reste affichée dans la recherche, "
                + "où Instagram masque la sienne.");
        check("divider", "Trait de séparation en haut", true);
        check("hide_in_chat", "Masquer la barre dans une conversation", true);
        number("bar_height", 56, 24, 120, "Hauteur de la barre, en dp (56 = comme Instagram)");
        number("bar_gap", 15, 0, 80, "Distance au bas de l'écran, en dp (15 = comme Instagram)");
        number("bg_size", 13, 4, 40, "Taille de l'icône Messages, en dp");
        number("bd_size", 11, 4, 40, "Taille de l'icône Recherche, en dp");
        number("bg_w", 0, 0, 400, "Largeur de la zone tactile Messages, en dp (0 = large)");
        number("bd_w", 0, 0, 400, "Largeur de la zone tactile Recherche, en dp (0 = large)");
        number("bg_th", 0, 0, 300, "Hauteur de la zone tactile Messages, en dp "
                + "(0 = hauteur de la barre)");
        number("bd_th", 0, 0, 300, "Hauteur de la zone tactile Recherche, en dp "
                + "(0 = hauteur de la barre)");
        number("search_index", 0, 0, 8, "Numéro de l'onglet Recherche (0 = trouvé tout seul)");

        title("Bouton Recherche");
        help("La macro se déroule en trois temps : ouvrir la recherche, appuyer sur la "
                + "barre « Rechercher » en haut, puis glisser un peu vers le bas pour "
                + "refermer le clavier et voir les comptes consultés récemment.");
        check("no_repeat", "Ne rien faire si je suis déjà dans la recherche", true);
        number("debounce", 700, 0, 3000,
                "Délai minimum entre deux appuis sur la barre, en millisecondes");
        check("swipe_recent", "Lancer la macro après le clic", true);
        check("gestures", "Autoriser les appuis et glissements simulés", true);
        check("macro_nodes", "Laisser l'app chercher le champ toute seule "
                + "(déconseillé : l'appui peut partir ailleurs)", false);
        number("field_delay", 550, 100, 3000,
                "Attente avant d'appuyer sur la barre Rechercher, en millisecondes");
        number("swipe_dist", 55, 10, 200, "Longueur du glissement, en dp");
        number("swipe_delay", 450, 100, 2000, "Attente avant le glissement, en millisecondes");

        title("Caches d'une conversation");
        help("Dans une conversation, la photo de profil à gauche du nom est recouverte. "
                + "Dans le menu de la conversation, le bouton Profil l'est aussi, avec son "
                + "icône. Les deux se placent au pixel depuis le panneau de réglage.");
        check("mask_chat", "Cacher la photo de profil en haut d'une conversation", true);
        check("mask_menu", "Cacher le bouton Profil dans le menu", true);

        title("Caches de la page Récent");
        help("Sur la page des comptes récents, le haut de l'écran (retour, barre de "
                + "recherche, ligne « Récent / Voir tout ») et la colonne des croix à "
                + "droite sont recouverts et ne répondent plus.");
        check("mask_recent", "Poser les caches", true);
        number("m_top", 18, 0, 200, "Début du cache du haut, en dp sous le bord de l'écran");
        number("m_bottom", 115, 20, 400, "Fin du cache du haut, en dp");
        number("m_right", 58, 0, 200, "Largeur du cache des croix, à droite, en dp");

        title("Couleurs");
        hex("color", MaskService.DEFAULT_COLOR, "Fond de la barre");
        hex("iconcolor", MaskService.DEFAULT_ICON, "Icônes");
        check("debug", "Mode test : barre rouge transparente", false);

        title("Réglage dans Instagram");
        help("Ouvre Instagram avec un panneau qui permet de tout déplacer et "
                + "redimensionner en direct : la barre, chacun des deux boutons et sa zone "
                + "tactile, les deux caches, le point où la macro appuie et le glissement. "
                + "Choisis d'abord l'élément, puis utilise les flèches et les boutons − +. "
                + "Les repères de couleur montrent les zones : bleu les boutons, vert les "
                + "caches, rose la cible de la macro, jaune le glissement. "
                + "« Tester l'appui » et « Tester la macro » les déclenchent tout de suite, "
                + "et tout est noté dans le journal.\n\n"
                + "Le plus simple pour les zones tactiles : touche « Apprendre Msg » ou "
                + "« Apprendre Rech », puis appuie sur l'écran exactement là où tu veux le "
                + "bouton. La zone se centre sur ton doigt, sans rien déclencher.");
        button("Régler et tester dans Instagram", v -> {
            prefs.edit().putBoolean("adjust", true).apply();
            openInsta();
        });
        button("Remettre la barre à sa position d'origine", v -> {
            prefs.edit().remove("adj").apply();
            toast("Position d'origine rétablie");
        });
        button("Tout remettre à zéro (sauf l'application visée)", v -> {
            String pkg = prefs.getString("pkg", "");
            boolean on = prefs.getBoolean("enabled", true);
            prefs.edit().clear().putString("pkg", pkg).putBoolean("enabled", on).apply();
            toast("Réglages remis à zéro — rouvre cette page");
            recreate();
        });

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

    private void refreshOnOff() {
        if (onOff == null) return;
        boolean on = prefs.getBoolean("enabled", true);
        onOff.setText(on ? "Barre activée  ·  toucher pour arrêter"
                         : "Barre arrêtée  ·  toucher pour activer");
        onOff.setTextColor(on ? 0xFF4CAF50 : 0xFFE53935);
    }

    private void refreshApp() {
        if (appInfo == null) return;
        String forced = prefs.getString("pkg", "");
        String seen = prefs.getString("seen", "");
        if (!forced.isEmpty()) appInfo.setText("Application forcée : " + forced);
        else if (!seen.isEmpty()) appInfo.setText("Application reconnue : " + seen);
        else appInfo.setText("Toute version d'Instagram, officielle ou modifiée");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshOnOff();
        refreshApp();
        boolean on = MaskService.running;
        status.setText(on ? "✅ Service actif" : "❌ Service inactif");
        status.setTextColor(on ? 0xFF4CAF50 : 0xFFE53935);
    }

    private void openInsta() {
        String p = prefs.getString("pkg", "");
        if (p.isEmpty()) p = prefs.getString("seen", "com.instagram.android");
        Intent i = getPackageManager().getLaunchIntentForPackage(p);
        if (i == null) {
            toast("Instagram introuvable");
            return;
        }
        startActivity(i);
    }

    // ---------- Petits outils d'interface ----------

    private void title(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(24), 0, dp(2));
        root.addView(t);
    }

    private void help(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(0, 0, 0, dp(6));
        root.addView(t);
    }

    private void button(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        root.addView(b);
    }

    private void check(String key, String label, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(prefs.getBoolean(key, def));
        cb.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(key, checked).apply());
        root.addView(cb);
    }

    private EditText field(String label, int inputType) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setPadding(0, dp(8), 0, 0);
        root.addView(t);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(inputType);
        root.addView(e);
        return e;
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
