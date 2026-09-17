package com.perso.wamasque;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(MaskService.PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        status = new TextView(this);
        status.setTextSize(16);
        root.addView(status);

        Button acc = new Button(this);
        acc.setText("Ouvrir les réglages d'accessibilité");
        acc.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(acc);

        title(root, "Éléments à masquer");
        check(root, "cam", "Bouton appareil photo (en haut)", true);
        check(root, "metaai", "Bouton Meta AI (rond violet)", true);
        check(root, "actus", "Onglet Actus", true);
        check(root, "commu", "Onglet Communautés", true);
        check(root, "debug", "Mode test : masques rouges transparents", false);

        TextView colorLabel = new TextView(this);
        colorLabel.setText("Couleur des masques (code hexadécimal)");
        colorLabel.setPadding(0, dp(12), 0, 0);
        root.addView(colorLabel);
        EditText color = new EditText(this);
        color.setSingleLine(true);
        color.setHint(MaskService.DEFAULT_COLOR);
        color.setText(prefs.getString("color", MaskService.DEFAULT_COLOR));
        root.addView(color);
        Button saveColor = new Button(this);
        saveColor.setText("Enregistrer la couleur");
        saveColor.setOnClickListener(v -> {
            String c = color.getText().toString().trim();
            if (!c.startsWith("#")) c = "#" + c;
            try {
                android.graphics.Color.parseColor(c);
                prefs.edit().putString("color", c).apply();
                Toast.makeText(this, "Couleur enregistrée", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Code couleur invalide", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(saveColor);

        title(root, "Macro au lancement de WhatsApp");
        TextView help = new TextView(this);
        help.setText("Écris le nom de la liste à mettre en premier (ex : Non lues, POTO'S). "
                + "Laisse vide pour désactiver la macro.");
        root.addView(help);
        EditText list = new EditText(this);
        list.setHint("Nom de la liste");
        list.setSingleLine(true);
        list.setText(prefs.getString("liste", ""));
        root.addView(list);
        TextView savedInfo = new TextView(this);
        root.addView(savedInfo);
        Runnable refresh = () -> {
            String cur = prefs.getString("liste", "");
            savedInfo.setText(cur.isEmpty() ? "⚠️ Aucune liste enregistrée" : "✅ Liste enregistrée : « " + cur + " »");
        };
        refresh.run();
        list.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(android.text.Editable e) {
                prefs.edit().putString("liste", e.toString().trim()).apply();
                refresh.run();
            }
        });
        Button launch = new Button(this);
        launch.setText("Tester : ouvrir WhatsApp avec la macro");
        launch.setOnClickListener(v -> {
            Intent wa = getPackageManager().getLaunchIntentForPackage("com.whatsapp");
            if (wa == null) {
                Toast.makeText(this, "WhatsApp introuvable", Toast.LENGTH_SHORT).show();
                return;
            }
            MaskService.forceMacro = true;
            startActivity(wa);
        });
        root.addView(launch);
        check(root, "slide", "Placer cette liste tout à gauche", true);
        check(root, "click", "Cliquer dessus", true);

        title(root, "Diagnostic");
        Button show = new Button(this);
        show.setText("Afficher les éléments détectés");
        show.setOnClickListener(v -> dumpView.setText(MaskService.diagnostic()));
        root.addView(show);
        Button copy = new Button(this);
        copy.setText("Copier le diagnostic");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("diagnostic", MaskService.diagnostic()));
            Toast.makeText(this, "Copié", Toast.LENGTH_SHORT).show();
        });
        root.addView(copy);

        dumpView = new TextView(this);
        dumpView.setTextIsSelectable(true);
        dumpView.setTextSize(11);
        dumpView.setTypeface(Typeface.MONOSPACE);
        root.addView(dumpView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.setText(MaskService.running
                ? "✅ Service actif"
                : "❌ Service inactif : active « WA Masque » dans les réglages d'accessibilité");
    }

    private void title(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(6));
        root.addView(t);
    }

    private void check(LinearLayout root, String key, String label, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(prefs.getBoolean(key, def));
        cb.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(key, checked).apply());
        root.addView(cb);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
