package chat.vibe.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

public final class UpdateActivity extends ThemeActivity {
    private AppUpdater updater;
    private TextView state,version,notes,action;
    private ProgressBar progress;
    private boolean autoInstall,waitingPermission,resumed;
    private final Runnable observer=this::render;
    private int d(int n){return Ui.dp(this,n);}
    private TextView label(LinearLayout p,String value,int size,int color){TextView t=Ui.text(this,value,size,color);p.addView(t);return t;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);updater=AppUpdater.get();if(saved!=null){autoInstall=saved.getBoolean("auto");waitingPermission=saved.getBoolean("permission");}
        build();if(!updater.busy&&!updater.ready)updater.check();
    }
    @Override protected void onAppearanceChanged(){build();}
    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(Ui.BG);root.setPadding(d(24),d(24),d(24),d(24));root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(d(24)+bars.left,d(16)+bars.top,d(24)+bars.right,d(24)+bars.bottom);}else v.setPadding(d(24),d(16)+insets.getSystemWindowInsetTop(),d(24),d(24)+insets.getSystemWindowInsetBottom());return insets;});
        TextView back=label(root,"‹ 返回",16,Ui.GREEN);back.setPadding(0,d(12),0,d(24));back.setOnClickListener(v->finish());
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(1);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Ui.Icon icon=new Ui.Icon(this,"update",Ui.GREEN);icon.setPadding(d(20),d(20),d(20),d(20));icon.setBackground(Ui.bg(Ui.LIME,26));body.addView(icon,new LinearLayout.LayoutParams(d(84),d(84)));TextView title=label(body,"保持新鲜。",30,Ui.INK);title.setPadding(0,d(24),0,d(8));label(body,"Vibe Chat · 应用更新",14,Ui.MUTED);
        version=label(body,"当前版本 "+updater.installedName(),14,Ui.INK);version.setPadding(0,d(30),0,d(12));state=label(body,"",16,Ui.GREEN);state.setPadding(0,0,0,d(16));progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);body.addView(progress,new LinearLayout.LayoutParams(-1,d(8)));notes=label(body,"",15,Ui.INK);notes.setLineSpacing(d(6),1);notes.setPadding(0,d(24),0,d(30));
        action=Ui.bold(this,"检查更新",16,Ui.WHITE);action.setGravity(Gravity.CENTER);action.setPadding(d(20),d(17),d(20),d(17));action.setBackground(Ui.bg(Ui.ACTION,16));root.addView(action);action.setOnClickListener(v->{if(updater.busy){autoInstall=false;updater.cancel();}else if(updater.ready)install();else if(updater.available()){autoInstall=true;updater.download();}else updater.check();});
        TextView hint=label(root,"下载后会打开系统安装界面，确认即可更新。账号和聊天记录会保留。",12,Ui.MUTED);hint.setPadding(0,d(14),0,0);setContentView(root);render();
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putBoolean("auto",autoInstall);b.putBoolean("permission",waitingPermission);}
    @Override protected void onResume(){super.onResume();resumed=true;updater.listeners.add(observer);render();if(waitingPermission){waitingPermission=false;if(getPackageManager().canRequestPackageInstalls())install();else state.setText("允许安装此来源的应用后，可继续安装");}}
    @Override protected void onPause(){resumed=false;updater.listeners.remove(observer);super.onPause();}
    private void render(){state.setText(updater.status+(updater.busy&&updater.progress>0?" "+updater.progress+"%":""));progress.setVisibility(updater.busy?View.VISIBLE:View.GONE);progress.setProgress(updater.progress);progress.setIndeterminate(updater.busy&&updater.progress==0);notes.setText(updater.release==null?"":"版本 "+updater.release.optString("versionName")+"\n\n"+updater.release.optString("notes"));action.setText(updater.busy?"取消":updater.ready?"立即安装":updater.available()?"下载并安装":"重新检查");if(updater.ready&&autoInstall&&resumed){autoInstall=false;install();}}
    private void install(){if(!updater.ready)return;
        if(!getPackageManager().canRequestPackageInstalls()){waitingPermission=true;state.setText("请允许 Vibe Chat 安装应用，返回后继续");try{startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}catch(Exception e){waitingPermission=false;state.setText("请在系统设置中允许安装此来源的应用");}return;}
        updater.prepareInstall(uri->{if(uri==null||!resumed||isFinishing())return;Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("Vibe Chat update",uri));
            try{startActivity(intent);state.setText("请在系统安装界面确认更新");}catch(Exception e){state.setText("无法打开安装器，请稍后重试");}});
    }
}
