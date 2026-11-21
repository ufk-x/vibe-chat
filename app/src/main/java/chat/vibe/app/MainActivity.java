package chat.vibe.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import chat.vibe.app.core.Wire;

public final class MainActivity extends ThemeActivity {
    private VibeApp a;
    private LinearLayout root,body,nav;
    private ScrollView scroll;
    private TextView sessionPill,headsetTitle,headsetHint,statusText,talk,talkHint,inviteText,noticeText;
    private Switch autoSwitch,overlaySwitch;private Ui.Wave heroWave;
    private int tab=0;private boolean syncing,pendingOverlay;
    private String pendingMode,pendingInvite,lastNotice="",renderKey="";
    private final Runnable observer=this::refresh;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);a=VibeApp.app;
        if(state!=null)tab=state.getInt("tab",0);else tab=getIntent().getIntExtra("tab",0);


        build();
    }
    @Override protected void onAppearanceChanged(){build();}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putInt("tab",tab);}
    @Override protected void onResume(){super.onResume();if(!a.listeners.contains(observer))a.listeners.add(observer);
        if(pendingOverlay){pendingOverlay=false;boolean granted=Settings.canDrawOverlays(this);a.prefs.edit().putBoolean("overlay",granted).apply();
            if(granted){if(VoiceService.current!=null)VoiceService.current.settingsChanged();else start("demo",null);}else a.notice("悬浮窗权限未开启，仍可在 App 内按住说话");}
        refresh();
    }
    @Override protected void onPause(){if(VoiceService.current!=null&&a.recording)VoiceService.current.endRecording(true);a.listeners.remove(observer);super.onPause();}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);tab=i.getIntExtra("tab",tab);build();}
    private int d(float n){return Ui.dp(this,n);}
    private void build(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets s=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());root.setPadding(s.left,s.top,s.right,s.bottom);}
            else root.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(d(24),d(15),d(24),d(14));
        LinearLayout brand=row();brand.setGravity(Gravity.CENTER_VERTICAL);Ui.Wave logo=new Ui.Wave(this,Ui.GREEN,false);brand.addView(logo,new LinearLayout.LayoutParams(d(27),d(25)));
        TextView name=Ui.bold(this," vibe chat",25,Ui.INK);name.setLetterSpacing(-.045f);brand.addView(name);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        sessionPill=Ui.bold(this,"ANDROID · 01",10,Ui.GREEN);sessionPill.setLetterSpacing(.05f);sessionPill.setPadding(d(10),d(7),d(10),d(7));sessionPill.setBackground(Ui.bg(Ui.LIME,20));header.addView(sessionPill);root.addView(header);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(d(24),d(12),d(24),d(24));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        nav=row();nav.setPadding(d(14),d(8),d(14),d(8));nav.setBackgroundColor(Ui.SURFACE);
        String[] tabs={"◉  对讲","▤  消息","☷  设置"};
        for(int i=0;i<3;i++){final int index=i;TextView n=Ui.bold(this,tabs[i],14,i==tab?Ui.GREEN:Ui.MUTED);n.setGravity(Gravity.CENTER);if(i==tab)n.setBackground(Ui.bg(Ui.LIME,18));nav.addView(n,new LinearLayout.LayoutParams(0,d(48),1));n.setOnClickListener(v->{tab=index;build();});}
        root.addView(nav);setContentView(root);
        headsetTitle=null;headsetHint=null;statusText=null;talk=null;talkHint=null;inviteText=null;noticeText=null;autoSwitch=null;overlaySwitch=null;
        if(tab==0)home();else if(tab==1)messages();else settings();refresh();
    }
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void gap(LinearLayout parent,int n){parent.addView(new View(this),new LinearLayout.LayoutParams(1,d(n)));}
    private TextView label(LinearLayout parent,String text,float size,int color){TextView v=Ui.text(this,text,size,color);v.setLineSpacing(d(3),1);parent.addView(v);return v;}
    private LinearLayout card(int color,int padding){LinearLayout l=column();l.setPadding(d(padding),d(padding),d(padding),d(padding));l.setBackground(Ui.bg(color,24));return l;}
    private TextView button(String title,int bg,int fg,Runnable action){TextView b=Ui.bold(this,title,15,fg);b.setGravity(Gravity.CENTER);b.setBackground(Ui.bg(bg,16));b.setMinHeight(d(52));b.setPadding(d(12),d(12),d(12),d(12));b.setOnClickListener(v->action.run());return b;}
    private void home(){
        TextView eyebrow=label(body,"LESS TAPPING, MORE TALKING",10,Ui.MUTED);eyebrow.setLetterSpacing(.13f);gap(body,10);
        TextView title=label(body,"让对话，自然发生。",29,Ui.INK);title.setTypeface(Typeface.create("sans-serif-medium",0));title.setLetterSpacing(-.04f);
        gap(body,7);label(body,"耳机里听见朋友，按住就能回应。",14,Ui.MUTED);gap(body,24);
        LinearLayout hero=card(Ui.DARK,22);
        LinearLayout top=row();top.setGravity(Gravity.CENTER_VERTICAL);TextView mode=Ui.bold(this,"耳机对讲",17,Ui.WHITE);top.addView(mode,new LinearLayout.LayoutParams(0,-2,1));TextView badge=Ui.text(this,"VOICE FIRST",10,Ui.ON_DARK_SECONDARY);badge.setLetterSpacing(.1f);top.addView(badge);hero.addView(top);gap(hero,22);
        heroWave=new Ui.Wave(this,Ui.ON_DARK_SECONDARY,true);LinearLayout.LayoutParams waveLp=new LinearLayout.LayoutParams(d(205),d(46));waveLp.gravity=Gravity.CENTER;hero.addView(heroWave,waveLp);gap(hero,14);
        headsetTitle=Ui.bold(this,"等待连接耳机",18,Ui.WHITE);hero.addView(headsetTitle);gap(hero,5);headsetHint=label(hero,"连接蓝牙、有线或 USB 耳机",12,Ui.ON_DARK_SECONDARY);gap(hero,16);
        View divider=new View(this);divider.setBackgroundColor(0xff355448);hero.addView(divider,new LinearLayout.LayoutParams(-1,d(1)));gap(hero,12);
        LinearLayout auto=row();auto.setGravity(Gravity.CENTER_VERTICAL);LinearLayout copy=column();label(copy,"收到语音自动播放",14,Ui.WHITE);label(copy,"仅在耳机连接时生效",11,Ui.ON_DARK_SECONDARY);auto.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        autoSwitch=new Switch(this);autoSwitch.setContentDescription("收到语音自动播放");auto.addView(autoSwitch);hero.addView(auto);
        autoSwitch.setOnCheckedChangeListener((v,checked)->{if(syncing)return;a.prefs.edit().putBoolean("auto",checked).apply();if(VoiceService.current!=null)VoiceService.current.settingsChanged();refresh();});body.addView(hero);gap(body,22);
        LinearLayout roomTitle=row();roomTitle.setGravity(Gravity.CENTER_VERTICAL);TextView heading=Ui.bold(this,"开始一段对话",18,Ui.INK);roomTitle.addView(heading,new LinearLayout.LayoutParams(0,-2,1));TextView local=Ui.text(this,"同一 Wi-Fi",11,Ui.MUTED);roomTitle.addView(local);body.addView(roomTitle);gap(body,11);
        LinearLayout roomCard=card(Ui.SURFACE,17);statusText=Ui.bold(this,"创建房间，邀请朋友一起说话",14,Ui.INK);roomCard.addView(statusText);gap(roomCard,12);
        LinearLayout actions=row();TextView create=button("＋ 创建房间",Ui.ACTION,Ui.WHITE,()->switchRoom("host",null));TextView join=button("加入房间 ↗",Ui.LIME,Ui.GREEN,this::joinDialog);
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,d(50),1);half.setMarginEnd(d(8));actions.addView(create,half);actions.addView(join,new LinearLayout.LayoutParams(0,d(50),1));roomCard.addView(actions);
        inviteText=label(roomCard,"",12,Ui.GREEN);inviteText.setPadding(0,d(12),0,0);inviteText.setOnClickListener(v->shareInvite());body.addView(roomCard);gap(body,12);
        LinearLayout floating=card(Ui.LIME,16);LinearLayout floatingRow=row();floatingRow.setGravity(Gravity.CENTER_VERTICAL);LinearLayout floatingCopy=column();label(floatingCopy,"悬浮语音按钮",14,Ui.INK);label(floatingCopy,"在其他应用里，也能按住回复",11,Ui.MUTED);floatingRow.addView(floatingCopy,new LinearLayout.LayoutParams(0,-2,1));overlaySwitch=new Switch(this);overlaySwitch.setContentDescription("悬浮语音按钮");floatingRow.addView(overlaySwitch);floating.addView(floatingRow);body.addView(floating);
        overlaySwitch.setOnCheckedChangeListener((v,checked)->{if(syncing)return;setOverlay(checked);});gap(body,18);
        LinearLayout dock=column();dock.setPadding(d(24),d(10),d(24),d(10));dock.setBackgroundColor(Ui.BG);root.addView(dock,root.getChildCount()-1);
        talk=button("按住说话",Ui.LIME,Ui.INK,()->{});talk.setMinHeight(d(62));talk.setTextSize(18);dock.addView(talk,new LinearLayout.LayoutParams(-1,d(64)));
        Ui.attachTalk(talk,()->{if(!a.active){a.notice("先点下方「单机回声体验」，或创建 / 加入房间");return;}if(VoiceService.current!=null)VoiceService.current.beginRecording();},cancel->{if(VoiceService.current!=null)VoiceService.current.endRecording(cancel);});
        gap(dock,6);talkHint=label(dock,"松开发送 · 上滑取消 · 最长 30 秒",11,Ui.MUTED);talkHint.setGravity(Gravity.CENTER);gap(body,8);
        TextView echo=Ui.bold(this,"一个人也能试试：单机回声体验  →",13,Ui.GREEN);echo.setGravity(Gravity.CENTER);echo.setPadding(0,d(9),0,d(9));echo.setOnClickListener(v->switchRoom("demo",null));body.addView(echo);
        noticeText=label(body,"",12,Ui.RED);noticeText.setPadding(0,d(10),0,0);
        TextView end=Ui.text(this,"结束当前对讲",12,Ui.MUTED);end.setGravity(Gravity.CENTER);end.setPadding(0,d(14),0,d(8));end.setOnClickListener(v->{stopService(new Intent(this,VoiceService.class));});body.addView(end);
    }
    private void refresh(){
        if(root==null)return;
        syncing=true;
        sessionPill.setText(a.active?"●  对讲已开启":"ANDROID · 01");
        if(tab==0){
            if(heroWave!=null)heroWave.setPlaying(a.playing||a.recording);
            headsetTitle.setText(a.hasHeadset?"耳机已连接":"等待连接耳机");headsetHint.setText(a.hasHeadset?a.headset:"连接蓝牙、有线或 USB 耳机");
            statusText.setText(a.active?a.room+" · "+a.connection:"创建房间，邀请朋友一起说话");
            autoSwitch.setChecked(a.auto());overlaySwitch.setChecked(a.overlay()&&Settings.canDrawOverlays(this));
            talk.setText(a.recording?"正在录音  "+a.recordSeconds+"″ · 松开发送":"按住说话");talk.setBackground(Ui.bg(a.recording?0xffb92532:Ui.LIME,18));talk.setTextColor(a.recording?Ui.WHITE:Ui.INK);
            talkHint.setText(a.recording?"上滑取消 · 最长 30 秒":a.playing?"正在播放语音…":"松开发送 · 上滑取消 · 最长 30 秒");
            inviteText.setText(a.invite.isEmpty()?a.demo&&a.active?"体验模式 · 你的语音会原样返回":"双方连接同一 Wi-Fi，或连接同一个手机热点":"复制 / 分享邀请码  →");
            noticeText.setText(a.notice);noticeText.setVisibility(a.notice.isEmpty()?View.GONE:View.VISIBLE);
        }else if(tab==1){String key=messageKey();if(!key.equals(renderKey)){body.removeAllViews();messages();}}
        if(!a.notice.isEmpty()&&!a.notice.equals(lastNotice)){lastNotice=a.notice;Toast.makeText(this,a.notice,Toast.LENGTH_SHORT).show();}
        syncing=false;
    }
    private String messageKey(){StringBuilder k=new StringBuilder(a.playingId);for(VibeApp.Entry e:a.messages)k.append(e.id).append(e.status).append(e.heard);return k.toString();}
    private void messages(){
        renderKey=messageKey();label(body,"对话的回声",28,Ui.INK);gap(body,6);label(body,"语音保存在本机，最近 60 条随时重听。",13,Ui.MUTED);gap(body,24);
        if(a.messages.isEmpty()){
            LinearLayout empty=card(Ui.SURFACE,24);gap(empty,24);Ui.Wave wave=new Ui.Wave(this,Ui.GREEN,true);empty.addView(wave,new LinearLayout.LayoutParams(-1,d(55)));gap(empty,24);label(empty,"第一句话，从你开始",20,Ui.INK);gap(empty,8);label(empty,"和朋友连接房间，或者先在单机回声体验里试试自己的声音。",14,Ui.MUTED);gap(empty,20);empty.addView(button("去说句话 →",Ui.LIME,Ui.INK,()->{tab=0;build();}));body.addView(empty);return;
        }
        ArrayList<VibeApp.Entry> list=new ArrayList<>();for(VibeApp.Entry e:a.messages)if(e.chat.isEmpty()&&!e.id.startsWith("cloud:"))list.add(e);Collections.reverse(list);
        SimpleDateFormat fmt=new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA);
        for(VibeApp.Entry e:list){
            LinearLayout item=card(e.outgoing?Ui.LIME:Ui.SURFACE,17);
            LinearLayout meta=row();meta.addView(Ui.bold(this,e.outgoing?"我":e.name,13,Ui.INK),new LinearLayout.LayoutParams(0,-2,1));meta.addView(Ui.text(this,fmt.format(new Date(e.time)),10,Ui.MUTED));item.addView(meta);gap(item,14);
            LinearLayout audioRow=row();audioRow.setGravity(Gravity.CENTER_VERTICAL);TextView play=Ui.bold(this,a.playingId.equals(e.id)?"■":"▶",17,Ui.GREEN);audioRow.addView(play,new LinearLayout.LayoutParams(d(36),d(34)));Ui.Wave wave=new Ui.Wave(this,Ui.GREEN,false);wave.setPlaying(a.playing&&a.playingId.equals(e.id));audioRow.addView(wave,new LinearLayout.LayoutParams(0,d(30),1));TextView duration=Ui.bold(this,e.seconds+"″",17,Ui.GREEN);duration.setGravity(Gravity.END);audioRow.addView(duration,new LinearLayout.LayoutParams(d(46),-2));item.addView(audioRow);gap(item,10);
            label(item,e.status,11,e.status.contains("失败")||e.status.contains("未确认")?Ui.RED:Ui.MUTED);
            item.setContentDescription((e.outgoing?"我":e.name)+"的语音，"+e.seconds+"秒，点击播放");item.setOnClickListener(v->play(e));
            if(e.outgoing&&(e.status.equals("发送失败")||e.status.equals("未确认送达"))){gap(item,8);TextView retry=Ui.bold(this,"重发到当前房间 →",12,Ui.GREEN);retry.setPadding(0,d(8),0,d(8));retry.setOnClickListener(v->new AppDialog.Builder(this).setTitle("重发这条语音？").setMessage("接收方是当前连接的房间。未确认的消息可能已经被对方收到。").setNegativeButton("取消",null).setPositiveButton("重发",(dialog,which)->{if(VoiceService.current!=null)VoiceService.current.retry(e);else a.notice("请先开启对讲并加入房间");}).show());item.addView(retry);}
            body.addView(item);gap(body,12);
        }
    }
    private void play(VibeApp.Entry e){
        if(VoiceService.current==null){a.notice("请先在对讲页开启单机体验或连接房间，再播放语音");return;}
        VoiceService.current.play(e,false);
    }
    private void settings(){
        label(body,"按你的节奏来",28,Ui.INK);gap(body,6);label(body,"轻一点的聊天，刚刚好的打扰。",13,Ui.MUTED);gap(body,24);
        settingRow("我的昵称",a.name(),()->{
            EditText input=input("昵称，最多 20 字");input.setText(a.name());input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(20)});
            new AppDialog.Builder(this).setTitle("大家怎么称呼你？").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",(dialog,w)->{String name=input.getText().toString().trim();if(!name.isEmpty())a.prefs.edit().putString("name",name).apply();build();}).show();
        });
        settingRow("悬浮窗权限",Settings.canDrawOverlays(this)?"已允许 · 可在其他 App 上回复":"点击开启",()->setOverlay(true));
        settingRow("后台运行与电池","如果锁屏后收不到消息，检查应用电池设置",()->{
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));
        });
        settingRow("清空本机语音","删除最近 60 条录音与消息记录",()->new AppDialog.Builder(this).setTitle("清空本机语音？").setMessage("此操作会删除这台手机上的所有 Vibe Chat 语音，无法恢复。").setNegativeButton("保留",null).setPositiveButton("清空",(dialog,w)->{if(VoiceService.current!=null&&a.recording){a.notice("请先结束录音");return;}if(VoiceService.current!=null)VoiceService.current.clearHistory();else a.clearHistory();Toast.makeText(this,"已清空",Toast.LENGTH_SHORT).show();}).show());gap(body,18);
        LinearLayout notes=card(Ui.LIME,20);label(notes,"关于这个早期版本",16,Ui.INK);gap(notes,10);
        label(notes,"• Android 8.0 及以上，双方安装 Vibe Chat。\n\n• 无需账号。同一 Wi-Fi 或手机热点下创建 / 加入房间；房主离开，房间会关闭。\n\n• 支持多人房间，语音发给房间内所有在线成员。暂不保存离线消息。\n\n• 耳机连接不等于已经佩戴。录音使用系统默认麦克风；摘下仍连接的耳机时，可手动关闭自动播放。\n\n• 悬浮按钮上方可拖动；按住录音，松开发送，上滑取消。\n\n• 语音使用房间密钥加密传输，邀请码请只发给信任的人。",13,Ui.MUTED);body.addView(notes);gap(body,20);
        label(body,"VIBE CHAT  /  0.1.0\n为每一句想说的话，少点一次。",11,Ui.MUTED);
    }
    private void settingRow(String title,String detail,Runnable action){
        LinearLayout c=card(Ui.SURFACE,18);label(c,title+"  ↗",15,Ui.INK);gap(c,5);label(c,detail,12,Ui.MUTED);c.setOnClickListener(v->action.run());body.addView(c);gap(body,10);
    }
    private EditText input(String hint){EditText e=new EditText(this);e.setTextSize(14);e.setTextColor(Ui.INK);e.setHintTextColor(Ui.MUTED);e.setBackground(Ui.bg(Ui.BG,12));e.setHint(hint);e.setSingleLine(false);e.setPadding(d(24),d(14),d(24),d(14));return e;}
    private void joinDialog(){
        EditText code=input("粘贴朋友分享的完整邀请码\nvibe1|…");code.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout fields=column();fields.setPadding(d(24),0,d(24),d(8));fields.addView(code,new LinearLayout.LayoutParams(-1,-2));
        new AppDialog.Builder(this).setTitle("加入朋友的房间").setMessage("双方先连接同一 Wi-Fi 或同一手机热点。").setView(fields).setNegativeButton("取消",null).setBeforePositive(()->{
            try{Wire.parseInvitation(code.getText().toString().trim());return true;}catch(Exception e){code.setError("邀请码不完整，请重新粘贴");return false;}
        }).setPositiveButton("加入",(dialog,which)->switchRoom("join",code.getText().toString().trim())).show();
    }
    private void switchRoom(String mode,String invite){
        if(a.active&&!a.demo){new AppDialog.Builder(this).setTitle("离开当前房间？").setMessage("将切换到新的房间；如果你是房主，当前房间会关闭。").setNegativeButton("取消",null).setPositiveButton("切换",(d,w)->start(mode,invite)).show();}
        else start(mode,invite);
    }
    private void start(String mode,String invite){
        pendingMode=mode;pendingInvite=invite;
        ArrayList<String> permissions=new ArrayList<>();if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)permissions.add(Manifest.permission.RECORD_AUDIO);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!permissions.isEmpty()){requestPermissions(permissions.toArray(new String[0]),5);return;}launchSession();
    }
    private void launchSession(){
        a.notice="";Intent i=new Intent(this,VoiceService.class).setAction(VoiceService.START).putExtra("mode",pendingMode==null?"demo":pendingMode).putExtra("invite",pendingInvite);
        try{startForegroundService(i);}catch(Exception e){a.notice("请保持 App 在前台，再尝试开启对讲");}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);if(request!=5)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)launchSession();
        else new AppDialog.Builder(this).setTitle("对讲需要麦克风权限").setMessage("只有按住说话时才录音。可在应用权限设置中开启麦克风。").setNegativeButton("暂不开启",null).setPositiveButton("打开设置",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).show();
    }
    private void setOverlay(boolean enabled){
        if(enabled&&!Settings.canDrawOverlays(this)){pendingOverlay=true;new AppDialog.Builder(this).setTitle("让语音按钮跟着你").setMessage("接下来请允许 Vibe Chat 显示在其他应用上层。按钮只在对讲开启时出现，可随时隐藏。").setNegativeButton("暂不开启",(d,w)->{pendingOverlay=false;refresh();}).setPositiveButton("前往设置",(d,w)->{
            try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}catch(Exception e){pendingOverlay=false;a.notice("请在系统设置中手动开启悬浮窗权限");}
        }).setOnCancelListener(d->{pendingOverlay=false;refresh();}).show();return;}
        a.prefs.edit().putBoolean("overlay",enabled).apply();if(VoiceService.current!=null)VoiceService.current.settingsChanged();else if(enabled)start("demo",null);refresh();
    }
    private void shareInvite(){
        if(a.invite.isEmpty())return;
        new AppDialog.Builder(this).setTitle("邀请朋友加入").setMessage("请让朋友连接同一 Wi-Fi，打开 Vibe Chat → 加入房间。邀请码包含房间密钥，仅分享给信任的人。").setNegativeButton("复制邀请码",(dialog,w)->{
            android.content.ClipboardManager clip=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData data=ClipData.newPlainText("Vibe Chat 房间邀请码",a.invite);
            if(Build.VERSION.SDK_INT>=33){PersistableBundle extra=new PersistableBundle();extra.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true);data.getDescription().setExtras(extra);}clip.setPrimaryClip(data);Toast.makeText(this,"邀请码已复制",Toast.LENGTH_SHORT).show();
        }).setPositiveButton("分享",(dialog,w)->startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,a.invite),"分享 Vibe Chat 邀请码"))).show();
    }
}
