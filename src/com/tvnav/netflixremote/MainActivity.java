package com.tvnav.netflixremote;

import android.app.Activity;
import android.content.Intent;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER); root.setPadding(32,24,32,24); root.setBackgroundColor(0xff171717);
        TextView title = new TextView(this); title.setText("Netflix Remote Helper"); title.setTextSize(26); title.setTextColor(0xffffffff); title.setGravity(Gravity.CENTER); root.addView(title);
        status = new TextView(this); status.setTextSize(18); status.setTextColor(0xffffd600); status.setGravity(Gravity.CENTER); status.setPadding(0,20,0,16); root.addView(status);
        TextView help = new TextView(this); help.setText("EN: Use arrows to move, OK to select. Long-press OK to toggle.\n\nTR: Oklarla gezin, OK ile seçin. Yardımcıyı açıp kapatmak için OK tuşuna uzun basın."); help.setTextSize(17); help.setTextColor(0xffffffff); root.addView(help);
        Button settings = new Button(this); settings.setText("Open Accessibility Settings"); settings.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }}); root.addView(settings);
        setContentView(root);
    }
    @Override protected void onResume(){ super.onResume(); if(status != null) status.setText("Service: " + (NavService.isEnabled(this) ? "Enabled" : "Disabled")); }
}
