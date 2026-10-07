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

        button("Ouvrir les réglages d'accessibilité",
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        title("Application visée");
        help("Par défaut l'application Instagram officielle. Si tu utilises une autre version "
                + "(clone, application dupliquée, version modifiée), touche Détecter puis ouvre-la : "
                + "elle sera retenue.");
        appInfo = new TextView(this);
        appInfo.setPadding(0, 0, 0, dp(6));
        root.addView(appInfo);
        button("Détecter l'application", v -> {
            prefs.edit().putBoolean("detect", true).apply();
            toast("Ouvre maintenant l'application à traiter");
        });
        button("Revenir à Instagram", v -> {
            prefs.edit().remove("pkg").apply();
            refreshApp();
            toast("Instagram officiel");
        });

        title("Barre du bas");
        help("La vraie barre d'Instagram est recouverte. Deux boutons la remplacent : "
                + "Rechercher à gauche, Messages à droite. Elle reste affichée dans la recherche, "
                + "où Instagram masque la sienne.");
        check("enabled", "Activer sur Instagram", true);
        check("divider", "Trait de séparation en haut", true);
        number("bar_pad", 10, 0, 60, "Hauteur sous les icônes, en pixels");
        number("search_index", 0, 0, 8, "Numéro de l'onglet Recherche (0 = trouvé tout seul)");

        title("Couleurs");
        hex("color", MaskService.DEFAULT_COLOR, "Fond de la barre");
        hex("iconcolor", MaskService.DEFAULT_ICON, "Icônes");
        check("debug", "Mode test : barre rouge transparente", false);

        title("Réglage dans Instagram");
        help("Ouvre Instagram avec un panneau pour déplacer la barre et ajuster sa couleur en direct.");
        button("Régler la barre dans Instagram", v -> {
            prefs.edit().putBoolean("adjust", true).apply();
            openInsta();
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

    private void refreshApp() {
        if (appInfo == null) return;
        String p = prefs.getString("pkg", "com.instagram.android");
        appInfo.setText("Application traitée : " + p);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshApp();
        boolean on = MaskService.running;
        status.setText(on ? "✅ Service actif" : "❌ Service inactif");
        status.setTextColor(on ? 0xFF4CAF50 : 0xFFE53935);
    }

    private void openInsta() {
        Intent i = getPackageManager().getLaunchIntentForPackage(
                prefs.getString("pkg", "com.instagram.android"));
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
