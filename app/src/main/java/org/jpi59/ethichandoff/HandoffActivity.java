package org.jpi59.ethichandoff;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;

public class HandoffActivity extends Activity {
    private final int teal = Color.rgb(0,105,92);
    @Override public void onCreate(Bundle state) { super.onCreate(state); showHome(); }
    private TextView t(int id,float size){ TextView v=new TextView(this); v.setText(id); v.setTextSize(size); v.setTextColor(Color.rgb(24,35,34)); v.setPadding(0,8,0,8); return v; }
    private Button b(int id){ Button x=new Button(this); x.setText(id); x.setTextSize(16); x.setAllCaps(false); x.setTextColor(Color.WHITE); x.setBackgroundColor(teal); return x; }
    private void showHome(){
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(28,24,28,24); root.setBackgroundColor(Color.rgb(244,247,246)); setContentView(root);
        root.addView(t(R.string.title,30)); root.addView(t(R.string.subtitle,18)); TextView p=t(R.string.privacy,15); p.setTextColor(teal); root.addView(p);
        root.addView(t(R.string.choose_app,20)); TextView scope=t(R.string.app_name,18); scope.setTextColor(teal); root.addView(scope);
        TextView info=t(R.string.instructions,15); info.setTextColor(Color.DKGRAY); root.addView(info);
        Button start=b(R.string.start); start.setOnClickListener(v->openPhone()); root.addView(start);
        Button settings=new Button(this); settings.setText(R.string.settings); settings.setAllCaps(false); settings.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS))); root.addView(settings);
    }
    private void openPhone(){ try { startActivity(new Intent(Intent.ACTION_DIAL)); Toast.makeText(this,R.string.started,Toast.LENGTH_LONG).show(); } catch(Exception e) { Toast.makeText(this,R.string.not_enabled,Toast.LENGTH_LONG).show(); } }
}
