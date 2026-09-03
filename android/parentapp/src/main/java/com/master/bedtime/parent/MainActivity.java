package com.master.bedtime.parent;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

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
    private void showParentControl(){
        setContentView(R.layout.activity_main);
        EditText backend=findViewById(R.id.backendUrl); EditText child=findViewById(R.id.childId); TextView status=findViewById(R.id.status); Button on=findViewById(R.id.btnOn); Button off=findViewById(R.id.btnOff);
        backend.setText(getPreferences(MODE_PRIVATE).getString("backend","https://bedtime-parental-api.itjundobal.workers.dev")); child.setText(getPreferences(MODE_PRIVATE).getString("child","child-001"));
        LinearLayout root=(LinearLayout)status.getParent();
        TextView pairCode=new TextView(this); pairCode.setGravity(Gravity.CENTER); pairCode.setTextSize(28); pairCode.setText("No pairing code yet");
        Button generate=new Button(this); generate.setText("GENERATE 6-DIGIT CHILD PAIRING CODE");
        TextView pairStatus=new TextView(this); pairStatus.setText("Generate a code, then enter it on the Child phone. Code expires in 15 minutes.");
        root.addView(pairCode,0); root.addView(generate,1); root.addView(pairStatus,2);
        generate.setOnClickListener(v->generatePairing(backend,child,pairCode,pairStatus));
        on.setOnClickListener(v->setBedtime(true,backend,child,status)); off.setOnClickListener(v->setBedtime(false,backend,child,status));
    }
    private void generatePairing(EditText backend, EditText child, TextView codeView, TextView status){
        String base=backend.getText().toString().trim().replaceAll("/$",""); if(base.isEmpty()){status.setText("Backend URL is empty");return;}
        status.setText("Generating secure pairing code…");
        new Thread(()->{try{
            URL u=new URL(base+"/api/pairing/create"); HttpURLConnection x=(HttpURLConnection)u.openConnection(); x.setRequestMethod("POST"); x.setConnectTimeout(5000); x.setReadTimeout(5000); x.setRequestProperty("Content-Type","application/json"); x.setDoOutput(true);
            try(OutputStream os=x.getOutputStream()){os.write("{}".getBytes(StandardCharsets.UTF_8));}
            int response=x.getResponseCode(); String body=readBody(x,response); if(response<200||response>=300)throw new Exception(body);
            JSONObject d=new JSONObject(body); String token=d.getString("parentToken"); String childId=d.getString("childId"); String code=d.getString("code");
            getPreferences(MODE_PRIVATE).edit().putString("backend",base).putString("child",childId).putString("parent_token",token).apply();
            runOnUiThread(()->{child.setText(childId);codeView.setText(code);status.setText("Enter this 6-digit code on the Child phone. Expires in 15 minutes.");}); x.disconnect();
        }catch(Exception e){runOnUiThread(()->status.setText("Pairing failed: "+e.getMessage()));}}).start();
    }
    private String readBody(HttpURLConnection x,int code)throws Exception{try(BufferedReader br=new BufferedReader(new InputStreamReader(code>=400?x.getErrorStream():x.getInputStream()))){StringBuilder s=new StringBuilder();String line;while((line=br.readLine())!=null)s.append(line);return s.toString();}}
    private void setBedtime(boolean enabled,EditText backendUrl,EditText childId,TextView status){String base=backendUrl.getText().toString().trim().replaceAll("/$","");String id=childId.getText().toString().trim();if(base.isEmpty()||id.isEmpty())return;getPreferences(MODE_PRIVATE).edit().putString("backend",base).putString("child",id).apply();status.setText(enabled?"Sending Bedtime ON…":"Sending Bedtime OFF…");new Thread(()->{try{URL u=new URL(base+"/api/children/"+id+"/bedtime");HttpURLConnection x=(HttpURLConnection)u.openConnection();x.setRequestMethod("POST");x.setConnectTimeout(5000);x.setReadTimeout(5000);x.setRequestProperty("Content-Type","application/json");String token=getPreferences(MODE_PRIVATE).getString("parent_token","");if(!token.isEmpty())x.setRequestProperty("X-Parent-Token",token);String key=getPreferences(MODE_PRIVATE).getString("parent_key","");if(!key.isEmpty())x.setRequestProperty("X-Parent-Key",key);x.setDoOutput(true);try(OutputStream os=x.getOutputStream()){os.write(("{\"active\":"+enabled+"}").getBytes(StandardCharsets.UTF_8));}int code=x.getResponseCode();runOnUiThread(()->status.setText(code>=200&&code<300?"Bedtime: "+(enabled?"ON":"OFF"):"Server error: "+code));x.disconnect();}catch(Exception e){runOnUiThread(()->{status.setText("Connection failed");Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();});}}).start();}
}
