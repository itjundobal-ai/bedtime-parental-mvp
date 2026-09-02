package com.master.bedtime.parent;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String MODE = "selected_role";
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String role = getPreferences(MODE_PRIVATE).getString(MODE, "");
        if ("child".equals(role)) launchChild(); else if ("parent".equals(role)) showParentControl(); else showRoleChooser();
    }
    private void showRoleChooser() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER_HORIZONTAL); root.setPadding(40,60,40,40);
        TextView title = new TextView(this); title.setText("BEDTIME PARENTAL"); title.setTextSize(30); title.setGravity(Gravity.CENTER); root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        TextView q = new TextView(this); q.setText("Who are you?\n\nChoose the role for this device."); q.setTextSize(20); q.setGravity(Gravity.CENTER); LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(-1,-2); qp.topMargin=35; root.addView(q,qp);
        Button p=new Button(this); p.setText("👨 PARENT"); p.setTextSize(20); LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2); pp.topMargin=35; root.addView(p,pp);
        Button c=new Button(this); c.setText("👶 CHILD"); c.setTextSize(20); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.topMargin=16; root.addView(c,cp);
        p.setOnClickListener(v->{getPreferences(MODE_PRIVATE).edit().putString(MODE,"parent").apply();showParentControl();});
        c.setOnClickListener(v->{getPreferences(MODE_PRIVATE).edit().putString(MODE,"child").apply();launchChild();}); setContentView(root);
    }
    private void launchChild(){try{startActivity(new Intent(this,com.master.bedtime.child.MainActivity.class));}catch(Exception e){Toast.makeText(this,"Child mode could not start: "+e.getMessage(),Toast.LENGTH_LONG).show();getPreferences(MODE_PRIVATE).edit().remove(MODE).apply();showRoleChooser();}}
    private void showParentControl(){setContentView(R.layout.activity_main); android.widget.EditText backend=findViewById(R.id.backendUrl); android.widget.EditText child=findViewById(R.id.childId); android.widget.TextView status=findViewById(R.id.status); Button on=findViewById(R.id.btnOn); Button off=findViewById(R.id.btnOff); backend.setText(getPreferences(MODE_PRIVATE).getString("backend","https://bedtime-parental-api.itjundobal.workers.dev")); child.setText(getPreferences(MODE_PRIVATE).getString("child","child-001")); on.setOnClickListener(v->setBedtime(true,backend,child,status)); off.setOnClickListener(v->setBedtime(false,backend,child,status));}
    private void setBedtime(boolean enabled,android.widget.EditText backendUrl,android.widget.EditText childId,android.widget.TextView status){String base=backendUrl.getText().toString().trim().replaceAll("/$","");String id=childId.getText().toString().trim();if(base.isEmpty()||id.isEmpty())return;getPreferences(MODE_PRIVATE).edit().putString("backend",base).putString("child",id).apply();status.setText(enabled?"Sending Bedtime ON…":"Sending Bedtime OFF…");new Thread(()->{try{URL u=new URL(base+"/api/children/"+id+"/bedtime");HttpURLConnection x=(HttpURLConnection)u.openConnection();x.setRequestMethod("POST");x.setConnectTimeout(5000);x.setReadTimeout(5000);x.setRequestProperty("Content-Type","application/json");String key=getPreferences(MODE_PRIVATE).getString("parent_key","");if(!key.isEmpty())x.setRequestProperty("X-Parent-Key",key);x.setDoOutput(true);try(OutputStream os=x.getOutputStream()){os.write(("{\"active\":"+enabled+"}").getBytes(StandardCharsets.UTF_8));}int code=x.getResponseCode();runOnUiThread(()->status.setText(code>=200&&code<300?"Bedtime: "+(enabled?"ON":"OFF"):"Server error: "+code));x.disconnect();}catch(Exception e){runOnUiThread(()->{status.setText("Connection failed");Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();});}}).start();}
}
