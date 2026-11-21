package chat.vibe.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;

public final class CloudActivity extends ThemeActivity {
    private VibeApp a; private CloudSession s;
    private LinearLayout root,body; private ScrollView scroll;
    private ListView messageList; private MessageAdapter messageAdapter; private TextView historyHint;
    private Motion.PageHost pageHost; private int pageNavigation;
    private final java.text.DateFormat clockFormat=new java.text.SimpleDateFormat("HH:mm",Locale.getDefault());
    private FrameLayout chatAvatar; private String headerAvatarKey="";
    private EditText composer,account,password,nickname;
    private TextView connection,talk,headset,loginStatus,replyTarget,serviceToggle,chatTitle;
    private int tab,rendered=-1,previousTab=-1,navigationMotion; private boolean chatOpen,building,loginScreen;
    private String draft="",lastNotice="",loadingVoice=""; private int manualRequest;
    private AudioEngine manualAudio;
    private Ui.Wave heroWave;
    private View updateTabDot,updateRowDot,meTab,updateRow;
    private TextView updateSummary,loginUpdate;
    private final Runnable updateObserver=this::refreshUpdates;
    private final BroadcastReceiver noisy=new BroadcastReceiver(){public void onReceive(Context c,Intent i){stopManual();}};
    private final Runnable observer=this::refresh;
    private int d(float n){return Ui.dp(this,n);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(1);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private void gap(LinearLayout p,int n){p.addView(new View(this),new LinearLayout.LayoutParams(1,d(n)));}
    private TextView text(LinearLayout p,String value,int size,int color){TextView v=Ui.text(this,value,size,color);v.setLineSpacing(d(3),1);p.addView(v);return v;}
    private TextView button(LinearLayout p,String value,Runnable action){TextView v=Ui.bold(this,value,14,Ui.GREEN);v.setGravity(Gravity.CENTER);v.setPadding(d(12),d(14),d(12),d(14));v.setBackground(Ui.bg(Ui.LIME,14));Ui.touch(v);v.setOnClickListener(w->action.run());p.addView(v,new LinearLayout.LayoutParams(-1,-2));gap(p,9);return v;}
    private LinearLayout card(LinearLayout p){LinearLayout c=column();c.setPadding(d(18),d(16),d(18),d(16));c.setBackground(Ui.bg(Ui.SURFACE,18));p.addView(c,new LinearLayout.LayoutParams(-1,-2));gap(p,10);return c;}
    private EditText input(LinearLayout p,String hint,boolean secret){EditText e=new EditText(this);e.setTextSize(15);e.setTextColor(Ui.INK);e.setHintTextColor(Ui.MUTED);e.setBackground(Ui.bg(Ui.BG,12));e.setSingleLine(true);e.setHint(hint);e.setPadding(d(14),d(12),d(14),d(12));e.setMinimumHeight(d(54));e.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT);p.addView(e,new LinearLayout.LayoutParams(-1,-2));gap(p,8);return e;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);a=VibeApp.app;s=a.cloud;manualAudio=new AudioEngine(this,a.main,()->{});
        if(Build.VERSION.SDK_INT>=33)registerReceiver(noisy,new IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),Context.RECEIVER_NOT_EXPORTED);else registerReceiver(noisy,new IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY));if(saved!=null){tab=saved.getInt("tab");chatOpen=saved.getBoolean("chatOpen");draft=saved.getString("draft","");}build();}
    @Override protected void onAppearanceChanged(){build();}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putInt("tab",tab);b.putBoolean("chatOpen",chatOpen);b.putString("draft",composer==null?draft:composer.getText().toString());}
    @Override protected void onResume(){super.onResume();a.listeners.add(observer);AppUpdater.get().listeners.add(updateObserver);refreshUpdates();s.foreground=true;s.viewingChat=tab==0&&chatOpen&&!s.selected.isEmpty();s.wake();if(s.viewingChat)s.loadHistory();if(a.overlay()&&!Settings.canDrawOverlays(this)){a.prefs.edit().putBoolean("overlay",false).apply();}if(VoiceService.current!=null)VoiceService.current.settingsChanged();if(tab==3&&!loginScreen)build();else refresh();}
    @Override protected void onPause(){a.listeners.remove(observer);AppUpdater.get().listeners.remove(updateObserver);s.foreground=false;s.viewingChat=false;stopManual();if(a.recording&&VoiceService.current!=null)VoiceService.current.endRecording(true);super.onPause();}
    @Override protected void onDestroy(){if(pageHost!=null)pageHost.finish();manualAudio.close();unregisterReceiver(noisy);super.onDestroy();}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);String id=i.getStringExtra("chat_id");JSONObject c=id==null?null:s.chat(id);if(c!=null)open(c);}
    @Override public void onBackPressed(){if(chatOpen){closeConversation();}else super.onBackPressed();}
    private void refresh(){if(building)return;if(!a.notice.isEmpty()&&!a.notice.equals(lastNotice)){lastNotice=a.notice;Toast.makeText(this,a.notice,Toast.LENGTH_LONG).show();}
        if(loginScreen){if(s.loggedIn()){build();return;}if(loginStatus!=null)loginStatus.setText(s.busy?"正在连接…":s.status);return;}
        if(!s.loggedIn()){build();return;}
        if(!a.recording&&rendered!=s.revision){
            if(tab==0&&chatOpen&&composer!=null&&!s.selected.isEmpty()){
                building=true;rendered=s.revision;messageAdapter.submit();building=false;
            }else{if(pageHost!=null&&pageHost.moving())return;build();return;}
        }
        if(connection!=null)connection.setText(a.active&&a.cloudMode?"● 对讲开启 · "+s.status:s.status);
        if(talk!=null){talk.setText(a.recording?"正在录音 "+a.recordSeconds+"″ · 松开发送":"按住说话");talk.setBackground(Ui.bg(a.recording?0xffb92532:Ui.ACTION,16));talk.setTextColor(Ui.WHITE);}
        if(messageAdapter!=null)messageAdapter.playback();
        if(heroWave!=null)heroWave.setPlaying(a.playing||a.recording);
        if(serviceToggle!=null)serviceToggle.setText(a.active&&a.cloudMode?"结束对讲":"开启对讲");
        if(headset!=null)headset.setText(a.playing?(a.hasHeadset?"正在通过耳机播放":"正在通过扬声器播放"):!a.auto()?"自动播放已关闭":a.hasHeadset?a.headset:(a.speakerAuto()?"无耳机 · 允许自动外放":"未连接耳机 · 自动播放已暂停"));
        if(chatTitle!=null)chatTitle.setText(s.selectedName);
        if(chatAvatar!=null){JSONObject c=s.chat(s.selected);if(c!=null){String key=c.optString("peer_id")+c.optString("avatar_version")+s.selectedName;if(!key.equals(headerAvatarKey)){headerAvatarKey=key;chatAvatar.removeAllViews();chatAvatar.addView(avatarView(c.optString("peer_id"),s.selectedName,c.optString("kind"),38,c.optString("avatar_version")),new FrameLayout.LayoutParams(-1,-1));}}}
        if(replyTarget!=null)replyTarget.setText(a.active&&a.cloudMode?a.room:(s.selectedName.isEmpty()?"还没有选择":s.selectedName));
    }
    private void refreshUpdates(){
        AppUpdater updater=AppUpdater.get();boolean available=updater.available();
        if(updateTabDot!=null)updateTabDot.setVisibility(available?View.VISIBLE:View.GONE);
        if(updateRowDot!=null)updateRowDot.setVisibility(available?View.VISIBLE:View.GONE);
        if(meTab!=null)meTab.setContentDescription(available?"我，有新版本":"我");
        if(updateRow!=null)updateRow.setContentDescription(available?"检查更新，有新版本 "+updater.release.optString("versionName"):"检查更新");
        if(updateSummary!=null)updateSummary.setText(available?"新版本 "+updater.release.optString("versionName")+" 可更新":"当前版本 "+updater.installedName());
        if(loginUpdate!=null){android.graphics.drawable.GradientDrawable dot=Ui.bg(Ui.BADGE,4);dot.setBounds(0,0,d(8),d(8));loginUpdate.setCompoundDrawablesRelative(null,null,available?dot:null,null);loginUpdate.setContentDescription(available?"检查更新，有新版本":"检查更新");}
    }
    private View updateDot(){View dot=new View(this);dot.setBackground(Ui.bg(Ui.BADGE,4));dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);dot.setVisibility(AppUpdater.get().available()?View.VISIBLE:View.GONE);return dot;}
    private void build(){if(building)return;building=true;boolean restoreFocus=composer!=null&&composer.hasFocus();boolean restoreIme=false;if(restoreFocus&&root!=null){if(Build.VERSION.SDK_INT>=30&&root.getRootWindowInsets()!=null)restoreIme=root.getRootWindowInsets().isVisible(WindowInsets.Type.ime());else{Rect visible=new Rect();root.getWindowVisibleDisplayFrame(visible);restoreIme=root.getRootView().getHeight()-visible.bottom>d(150);}}int selection=composer==null?0:composer.getSelectionEnd();if(composer!=null)draft=composer.getText().toString();composer=null;talk=null;headset=null;connection=null;replyTarget=null;serviceToggle=null;chatTitle=null;chatAvatar=null;headerAvatarKey="";heroWave=null;messageList=null;messageAdapter=null;historyHint=null;updateTabDot=null;updateRowDot=null;meTab=null;updateRow=null;updateSummary=null;loginUpdate=null;rendered=s.revision;loginScreen=!s.loggedIn();s.viewingChat=!loginScreen&&tab==0&&chatOpen&&!s.selected.isEmpty();
        String enteredAccount=account==null?"":account.getText().toString(),enteredPassword=password==null?"":password.getText().toString(),enteredName=nickname==null?"":nickname.getText().toString();
        root=column();root.setBackgroundColor(Ui.BG);root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        boolean conversation=tab==0&&chatOpen&&!s.selected.isEmpty()&&!loginScreen;
        LinearLayout header=row();header.setPadding(d(20),d(10),d(16),d(10));header.setBackgroundColor(Ui.BG);
        if(conversation){
            header.addView(iconButton("back","返回聊天",this::closeConversation));
            JSONObject current=s.chat(s.selected);View avatar=avatarView(current==null?"":current.optString("peer_id"),s.selectedName,current==null?"direct":current.optString("kind"),38,current==null?"":current.optString("avatar_version"));LinearLayout.LayoutParams av=new LinearLayout.LayoutParams(d(38),d(38));av.setMarginEnd(d(12));chatAvatar=new FrameLayout(this);chatAvatar.addView(avatar,new FrameLayout.LayoutParams(-1,-1));header.addView(chatAvatar,av);
            LinearLayout titles=column();chatTitle=text(titles,s.selectedName,18,Ui.INK);connection=text(titles,s.status,11,Ui.MUTED);header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));header.addView(iconButton("more","会话选项",this::chatOptions));
        }else{
            LinearLayout titles=column();TextView brand=text(titles,loginScreen?"vibe chat":new String[]{"消息","好友","对讲","我"}[tab],32,Ui.INK);brand.setTypeface(Typeface.create("sans-serif-bold",0));brand.setLetterSpacing(-.03f);
            if(!loginScreen)connection=text(titles,s.status,11,Ui.MUTED);header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
            if(!loginScreen){header.addView(iconButton("scan","扫一扫",this::scan));if(tab==0)header.addView(iconButton("plus","发起群聊或创建房间",this::createChat));}
        }
        root.addView(header);scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);body=column();body.setPadding(d(20),d(18),d(20),d(20));scroll.addView(body);FrameLayout stage=new FrameLayout(this);stage.addView(scroll,new FrameLayout.LayoutParams(-1,-1));root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        if(conversation){stage.removeAllViews();messageList=new ListView(this);messageList.setDivider(null);messageList.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));messageList.setCacheColorHint(Color.TRANSPARENT);messageList.setVerticalScrollBarEnabled(false);messageList.setItemsCanFocus(true);messageList.setPadding(d(20),d(18),d(20),d(20));messageList.setClipToPadding(false);stage.addView(messageList,new FrameLayout.LayoutParams(-1,-1));historyHint=Ui.text(this,"",14,Ui.MUTED);historyHint.setGravity(Gravity.CENTER);historyHint.setPadding(d(24),d(24),d(24),d(24));stage.addView(historyHint,new FrameLayout.LayoutParams(-1,-1));messageList.setEmptyView(historyHint);historyHint.setOnClickListener(v->{if(!s.historyLoading){s.loadHistory();messageAdapter.submit();}});messageAdapter=new MessageAdapter();messageList.setAdapter(messageAdapter);}

        if(loginScreen){login();account.setText(enteredAccount);password.setText(enteredPassword);nickname.setText(enteredName);}else{account=null;password=null;nickname=null;
            if(tab==0){if(conversation)conversation();else chats();}else if(tab==1)friends();else if(tab==2)walkie();else profile();
            if(!conversation){
                body.setPadding(d(20),d(18),d(20),d(24));
                LinearLayout nav=row();nav.setBackground(Ui.glass());nav.setPadding(d(6),d(6),d(6),d(6));nav.setElevation(d(6));if(Build.VERSION.SDK_INT>=28){nav.setOutlineAmbientShadowColor(0x22505970);nav.setOutlineSpotShadowColor(0x22505970);}
                String[] names={"聊天","好友","对讲","我"},icons={"chat","friends","mic","me"};
                for(int i=0;i<4;i++){int index=i;LinearLayout n=column();n.setGravity(Gravity.CENTER);n.setMinimumHeight(d(58));n.setPadding(0,d(5),0,d(5));n.setContentDescription(names[i]);n.setSelected(i==tab);
                    FrameLayout symbol=new FrameLayout(this);symbol.addView(new Ui.Icon(this,icons[i],i==tab?Ui.GREEN:Ui.MUTED),new FrameLayout.LayoutParams(d(25),d(25),Gravity.CENTER));
                    if(i==3){meTab=n;updateTabDot=updateDot();symbol.addView(updateTabDot,new FrameLayout.LayoutParams(d(8),d(8),Gravity.TOP|Gravity.END));}
                    n.addView(symbol,new LinearLayout.LayoutParams(d(35),d(27)));
                    TextView label=Ui.bold(this,names[i],11,i==tab?Ui.GREEN:Ui.MUTED);label.setGravity(Gravity.CENTER);label.setPadding(0,d(3),0,0);n.addView(label);FrameLayout slot=new FrameLayout(this);if(i==tab){View selected=new View(this);selected.setBackground(Ui.bg(Ui.RAISED,29));slot.addView(selected,new FrameLayout.LayoutParams(-1,0));n.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{FrameLayout.LayoutParams size=(FrameLayout.LayoutParams)selected.getLayoutParams();if(size.height!=n.getHeight()){size.height=n.getHeight();selected.setLayoutParams(size);}});if(previousTab>=0)Motion.tab(selected,previousTab-tab);}slot.addView(n,new FrameLayout.LayoutParams(-1,-2));slot.setClipChildren(false);nav.setClipChildren(false);nav.addView(slot,new LinearLayout.LayoutParams(0,-2,1));Ui.touch(n);n.setMinimumHeight(d(58));n.setOnClickListener(v->switchTab(index));
                }
                FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);np.setMargins(d(18),0,d(18),d(12));stage.addView(nav,np);
                nav.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{FrameLayout.LayoutParams area=(FrameLayout.LayoutParams)scroll.getLayoutParams();int bottom=nav.getHeight()+d(24);if(area.bottomMargin!=bottom){area.bottomMargin=bottom;scroll.setLayoutParams(area);}});
            }
        }
        if(pageHost==null){pageHost=new Motion.PageHost(this,this::refresh);setContentView(pageHost);}pageHost.show(root,pageNavigation);pageNavigation=0;if(navigationMotion!=0)Motion.enter(scroll,navigationMotion);previousTab=-1;navigationMotion=0;building=false;if(restoreFocus&&composer!=null){composer.requestFocus();composer.setSelection(Math.max(0,Math.min(selection,composer.length())));if(restoreIme)composer.post(()->{if(composer!=null&&composer.hasFocus())((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(composer,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});}refresh();refreshUpdates();
    }
    private void login(){text(body,"让对话，自然发生。",28,Ui.INK);gap(body,8);text(body,"耳机里听见朋友，按住就能回应。",14,Ui.MUTED);gap(body,24);LinearLayout c=card(body);text(c,"欢迎来到 Vibe Chat",19,Ui.INK);gap(c,12);account=input(c,"账号：字母、数字或下划线",false);account.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);password=input(c,"密码：至少 10 个字符",true);nickname=input(c,"昵称：注册时填写",false);button(c,"登录",()->s.signIn(s.origin(),account.getText().toString(),password.getText().toString(),nickname.getText().toString(),false)).setBackground(Ui.bg(Ui.LIME,16));button(c,"创建个人账号",()->s.signIn(s.origin(),account.getText().toString(),password.getText().toString(),nickname.getText().toString(),true));loginStatus=text(c,s.status,12,Ui.MUTED);gap(body,12);text(body,"账号和聊天记录保存在你自己的服务器。",12,Ui.MUTED);button(body,"服务地址",()->edit("服务地址",s.origin(),"https://…",value->{try{String valid=CloudSession.validOrigin(value);a.prefs.edit().putString("cloud_origin",valid).apply();a.cloud=new CloudSession(a);s=a.cloud;build();}catch(Exception e){a.notice(e.getMessage());}}));loginUpdate=button(body,"检查更新",()->startActivity(new Intent(this,UpdateActivity.class)));button(body,"先体验耳机对讲",()->startActivity(new Intent(this,MainActivity.class)));}
    private View iconButton(String icon,String description,Runnable action){
        android.widget.FrameLayout box=new android.widget.FrameLayout(this);box.setContentDescription(description);Ui.Icon v=new Ui.Icon(this,icon,Ui.INK);android.widget.FrameLayout.LayoutParams lp=new android.widget.FrameLayout.LayoutParams(d(22),d(22),Gravity.CENTER);box.addView(v,lp);LinearLayout.LayoutParams buttonLayout=new LinearLayout.LayoutParams(d(48),d(48));buttonLayout.setMarginEnd(d(4));box.setLayoutParams(buttonLayout);box.setBackground(Ui.bg(Ui.SURFACE,24));Ui.touch(box);box.setOnClickListener(w->action.run());return box;
    }
    private String time(long millis){return millis==0?"":clockFormat.format(new Date(millis));}
    private void chats(){
        LinearLayout status=row();status.setPadding(d(14),d(12),d(14),d(12));status.setBackground(Ui.bg(Ui.SURFACE,18));
        status.addView(new Ui.Icon(this,"headset",Ui.GREEN),new LinearLayout.LayoutParams(d(24),d(24)));
        LinearLayout copy=column();copy.setPadding(d(12),0,d(8),0);text(copy,a.active&&a.cloudMode?"对讲已开启":"随时听见，随时回应",14,Ui.INK);
        text(copy,a.active&&a.cloudMode?(a.speakerAuto()?"新语音允许自动外放":"连接耳机后自动播放"):"轻点设置耳机与自动播放",12,Ui.MUTED);
        status.addView(copy,new LinearLayout.LayoutParams(0,-2,1));text(status,"›",23,Ui.MUTED);Ui.touch(status);status.setOnClickListener(v->switchTab(2));body.addView(status);gap(body,24);
        LinearLayout caption=row();TextView label=Ui.bold(this,"最近聊天",14,Ui.MUTED);caption.addView(label,new LinearLayout.LayoutParams(0,-2,1));TextView add=Ui.bold(this,"添加朋友",13,Ui.GREEN);add.setPadding(d(12),0,d(2),0);add.setGravity(Gravity.CENTER);Ui.touch(add);add.setOnClickListener(v->scan());caption.addView(add);body.addView(caption);gap(body,4);
        JSONArray list=s.state.optJSONArray("chats");if(list==null||list.length()==0){LinearLayout c=card(body);gap(c,18);text(c,"第一句你好，从这里开始",19,Ui.INK);gap(c,10);text(c,"分享你的二维码，邀请朋友一起聊。",14,Ui.MUTED);gap(c,20);button(c,"我的二维码",()->qr(null));}
        LinearLayout listBox=column();listBox.setBackground(Ui.bg(Ui.SURFACE,22));body.addView(listBox);
        for(int i=0;list!=null&&i<list.length();i++){JSONObject item=list.optJSONObject(i);if(item==null)continue;
            LinearLayout c=row();c.setPadding(d(14),d(16),d(14),d(16));String kind=item.optString("kind");c.addView(avatarView(item.optString("peer_id"),item.optString("name"),kind,52,item.optString("avatar_version")),new LinearLayout.LayoutParams(d(52),d(52)));
            LinearLayout lines=column();lines.setPadding(d(14),0,d(8),0);LinearLayout top=row();TextView name=Ui.bold(this,item.optString("name"),16,Ui.INK);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);top.addView(name,new LinearLayout.LayoutParams(0,-2,1));text(top,time(item.optLong("updated")),11,Ui.MUTED);lines.addView(top);gap(lines,5);
            LinearLayout bottom=row();String preview=item.isNull("last_preview")?"":item.optString("last_preview");if(item.optString("last_kind").equals("voice"))preview=preview.isEmpty()?"[语音消息]":"[语音] "+preview;if(preview.isEmpty())preview=kind.equals("direct")?"点击开始聊天":(kind.equals("room")?"对讲房间":"群聊")+" · "+item.optInt("member_count")+" 人";
            TextView subtitle=Ui.text(this,preview,14,Ui.MUTED);subtitle.setSingleLine(true);subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);bottom.addView(subtitle,new LinearLayout.LayoutParams(0,-2,1));int unread=item.optInt("unread");if(unread>0){TextView badge=Ui.bold(this,unread>99?"99+":String.valueOf(unread),10,Ui.WHITE);badge.setGravity(Gravity.CENTER);badge.setMinWidth(d(20));badge.setPadding(d(6),d(2),d(6),d(2));badge.setBackground(Ui.bg(Ui.ACTION,12));bottom.addView(badge);}lines.addView(bottom);c.addView(lines,new LinearLayout.LayoutParams(0,-2,1));listBox.addView(c);c.setOnClickListener(v->open(item));if(i<list.length()-1){View divider=new View(this);divider.setBackgroundColor(Ui.LINE);LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(-1,d(1));dl.setMarginStart(d(80));dl.setMarginEnd(d(16));listBox.addView(divider,dl);}
        }
    }
    private void switchTab(int index){if(tab==index)return;stopManual();previousTab=tab;navigationMotion=index>tab?1:-1;tab=index;build();}
    private void closeConversation(){stopManual();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);chatOpen=false;draft="";composer=null;pageNavigation=-1;build();}
    private void open(JSONObject c){stopManual();draft="";composer=null;tab=0;chatOpen=true;building=true;try{s.select(c.optString("id"),c.optString("name"));}finally{building=false;}pageNavigation=1;build();}
    private void conversation(){messageAdapter.submit();
        if(composer==null){LinearLayout dock=column();dock.setPadding(d(20),d(8),d(20),d(8));dock.setBackgroundColor(Ui.SURFACE);LinearLayout line=row();composer=new EditText(this);composer.setSingleLine(false);composer.setMaxLines(3);composer.setTextSize(14);composer.setHint("发消息…");composer.setTextColor(Ui.INK);composer.setHintTextColor(Ui.MUTED);composer.setBackground(Ui.bg(Ui.BG,16));composer.setPadding(d(14),d(10),d(14),d(10));composer.setText(draft);line.addView(composer,new LinearLayout.LayoutParams(0,-2,1));composer.setMinimumHeight(d(48));TextView send=Ui.bold(this,"发送",15,Ui.GREEN);Ui.touch(send);send.setGravity(Gravity.CENTER);send.setPadding(d(14),d(10),0,d(10));send.setOnClickListener(v->{String value=composer.getText().toString().trim();if(value.isEmpty())return;s.sendText(value,()->{draft="";if(composer!=null)composer.setText("");});});line.addView(send);dock.addView(line);gap(dock,10);talkButton(dock);root.addView(dock);}}

    private View serverMessage(JSONObject m,MessageHolder holder){boolean mine=m.optString("sender").equals(s.userId);
            LinearLayout line=row();line.setGravity(Gravity.TOP|(mine?Gravity.END:Gravity.START));
            if(!mine)addMessageAvatar(line,m.optString("sender"),s.displayName(m.optString("sender"),m.optString("sender_name")),m.optString("avatar_version"),false);
            LinearLayout stack=column();stack.setGravity(mine?Gravity.END:Gravity.START);int maxWidth=messageWidth();
            if(!mine){text(stack,s.displayName(m.optString("sender"),m.optString("sender_name")),11,Ui.MUTED);gap(stack,5);}
            LinearLayout bubble=column();bubble.setPadding(d(15),d(12),d(15),d(12));bubble.setBackground(Ui.bg(mine?Ui.ACTION:Ui.SURFACE,18));int ink=mine?Ui.WHITE:Ui.INK;
            if(m.optString("kind").equals("text")){TextView msg=text(bubble,m.optString("text"),16,ink);msg.setMaxWidth(maxWidth-d(30));}
            else{LinearLayout audio=row();TextView play=Ui.bold(this,"▶",17,ink);play.setGravity(Gravity.CENTER);audio.addView(play,new LinearLayout.LayoutParams(d(26),d(30)));holder.voiceId=s.voiceId(m);holder.label=play;
                Ui.Wave wave=new Ui.Wave(this,ink,false);holder.wave=wave;LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(Math.min(d(96),maxWidth-d(112)),d(26));wp.setMargins(d(8),0,d(12),0);audio.addView(wave,wp);text(audio,m.optInt("seconds")+"″",13,ink);bubble.addView(audio);bubble.setTag((mine?"我":s.displayName(m.optString("sender"),m.optString("sender_name")))+"的语音，"+m.optInt("seconds")+"秒");holder.control=bubble;bubble.setContentDescription(bubble.getTag()+"，点击播放");bubble.setOnClickListener(v->play(m));}
            stack.addView(bubble,new LinearLayout.LayoutParams(-2,-2));if(m.optString("kind").equals("voice"))transcript(stack,m,mine,maxWidth);TextView stamp=text(stack,time(m.optLong("created")),10,Ui.MUTED);stamp.setPadding(d(4),d(5),d(4),0);stamp.setGravity(mine?Gravity.END:Gravity.START);line.addView(stack,new LinearLayout.LayoutParams(maxWidth,-2));if(mine)addMessageAvatar(line,s.userId,s.name,"",true);line.setPadding(0,0,0,d(16));return line;
    }
    private View pendingMessage(VibeApp.Entry e){
            boolean sending=e.delivery==chat.vibe.app.core.VoiceDelivery.SENDING;
            LinearLayout stack=column();stack.setGravity(Gravity.END);LinearLayout bubble=row();bubble.setPadding(d(15),d(12),d(15),d(12));bubble.setBackground(Ui.bg(Ui.ACTION,18));
            Ui.Wave wave=new Ui.Wave(this,Ui.WHITE,false);LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(d(96),d(26));wp.setMarginEnd(d(12));bubble.addView(wave,wp);text(bubble,e.seconds+"″",13,Ui.WHITE);stack.addView(bubble,new LinearLayout.LayoutParams(-2,-2));
            TextView status=text(stack,sending?"发送中…":"发送失败 · 点此重试",11,sending?Ui.MUTED:Ui.RED);status.setPadding(d(8),d(8),d(8),d(8));
            if(!sending){status.setContentDescription("重试这条语音");status.setOnClickListener(v->s.retryVoice(e));bubble.setOnClickListener(v->s.retryVoice(e));}
            LinearLayout pending=row();pending.setGravity(Gravity.TOP|Gravity.END);pending.addView(stack,new LinearLayout.LayoutParams(messageWidth(),-2));addMessageAvatar(pending,s.userId,s.name,"",true);pending.setPadding(0,0,0,d(8));return pending;
    }
    private final class MessageHolder {
        String key="",signature="",voiceId="";TextView label;Ui.Wave wave;View control;
        void playback(){if(label==null)return;boolean playing=a.playing&&voiceId.equals(a.playingId);label.setText(voiceId.equals(loadingVoice)?"···":playing?"■":"▶");wave.setPlaying(playing);control.setContentDescription(control.getTag()+(playing?"，正在播放，点击停止":"，点击播放"));}
    }
    private static final class MessageRow {
        final String key,signature,identity;final JSONObject message;final VibeApp.Entry pending;final long id;
        MessageRow(String key,String signature,String identity,JSONObject message,VibeApp.Entry pending,long id){this.key=key;this.signature=signature;this.identity=identity;this.message=message;this.pending=pending;this.id=id;}
    }
    private final class MessageAdapter extends BaseAdapter {
        private ArrayList<MessageRow> rows=new ArrayList<>();private final Map<String,Long> pendingIds=new HashMap<>();private long nextPending=-1;
        int boundRows;
        void submit(){
            historyHint.setText(!s.historyError.isEmpty()?s.historyError:s.historyLoaded?"说一句你好吧。":"正在加载对话…");
            Map<String,MessageRow> old=new HashMap<>();for(MessageRow row:rows)old.put(row.key,row);
            ArrayList<MessageRow> next=new ArrayList<>();
            for(int i=0;i<s.history.length();i++){JSONObject m=s.history.optJSONObject(i);if(m==null)continue;String key="m:"+m.optLong("id"),sender=m.optString("sender");String identity=(sender.equals(s.userId)?s.name:s.displayName(sender,m.optString("sender_name")))+"|"+s.avatarVersion(sender,m.optString("avatar_version"));MessageRow previous=old.get(key);
                if(previous!=null&&previous.message==m&&previous.identity.equals(identity))next.add(previous);else next.add(new MessageRow(key,m.toString()+identity,identity,m,null,m.optLong("id")));
            }
            for(VibeApp.Entry e:a.messages)if(e.cloudPending()&&e.account.equals(s.userId)&&e.chat.equals(s.selected)){if(!pendingIds.containsKey(e.id))pendingIds.put(e.id,nextPending--);next.add(new MessageRow("p:"+e.id,e.delivery+"|"+e.seconds+"|"+s.name+"|"+s.avatarVersion(s.userId,""),"",null,e,pendingIds.get(e.id)));}
            boolean changed=rows.size()!=next.size();for(int i=0;!changed&&i<rows.size();i++)changed=!rows.get(i).key.equals(next.get(i).key)||!rows.get(i).signature.equals(next.get(i).signature);
            if(!changed)return;
            int first=messageList.getFirstVisiblePosition();View firstView=messageList.getChildAt(0),lastView=messageList.getChildAt(messageList.getChildCount()-1);
            String anchor=first<rows.size()?rows.get(first).key:"";int top=firstView==null?0:firstView.getTop()-messageList.getPaddingTop();
            boolean bottom=rows.isEmpty()||(messageList.getLastVisiblePosition()>=rows.size()-1&&(lastView==null||lastView.getBottom()<=messageList.getHeight()-messageList.getPaddingBottom()+d(32)));
            rows=next;notifyDataSetChanged();
            if(bottom)messageList.setSelection(rows.size()-1);else for(int i=0;i<rows.size();i++)if(rows.get(i).key.equals(anchor)){messageList.setSelectionFromTop(i,top);break;}
        }
        void playback(){for(int i=0;i<messageList.getChildCount();i++){Object tag=messageList.getChildAt(i).getTag();if(tag instanceof MessageHolder)((MessageHolder)tag).playback();}}
        public int getCount(){return rows.size();}public Object getItem(int position){return rows.get(position);}public long getItemId(int position){return rows.get(position).id;}public boolean hasStableIds(){return true;}
        public boolean areAllItemsEnabled(){return false;}public boolean isEnabled(int position){return false;}
        public View getView(int position,View recycled,ViewGroup parent){
            MessageRow item=rows.get(position);FrameLayout container=recycled instanceof FrameLayout?(FrameLayout)recycled:new FrameLayout(CloudActivity.this);MessageHolder holder=container.getTag() instanceof MessageHolder?(MessageHolder)container.getTag():new MessageHolder();
            if(!holder.key.equals(item.key)||!holder.signature.equals(item.signature)){
                container.removeAllViews();holder=new MessageHolder();holder.key=item.key;holder.signature=item.signature;View content=item.message==null?pendingMessage(item.pending):serverMessage(item.message,holder);container.addView(content,new FrameLayout.LayoutParams(-1,-2));container.setTag(holder);boundRows++;
            }
            holder.playback();return container;
        }
    }
    private int messageWidth(){return Math.max(d(160),Math.min(d(360),d(getResources().getConfiguration().screenWidthDp)-d(116)));}
    private void addMessageAvatar(LinearLayout line,String id,String name,String fallback,boolean mine){
        View avatar=avatarView(id,name,"direct",38,s.avatarVersion(id,fallback));avatar.setContentDescription(mine?"我的头像":name+"的头像");
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(d(38),d(38));if(mine)size.setMarginStart(d(8));else size.setMarginEnd(d(8));line.addView(avatar,size);
    }
    private void transcript(LinearLayout stack,JSONObject message,boolean mine,int width){
        String status=message.optString("transcript_status","unavailable"),value=message.optString("transcript");gap(stack,6);
        if(status.equals("ready")&&!value.isEmpty()){
            TextView words=text(stack,value,15,Ui.INK);words.setMaxWidth(width);words.setTextIsSelectable(true);words.setPadding(d(13),d(10),d(13),d(10));words.setBackground(Ui.bg(mine?Ui.LIME:Ui.SURFACE,14));
            words.setContentDescription("语音转文字："+value);return;
        }
        boolean retry=status.equals("failed")&&message.optInt("transcript_attempts")<3;
        String label=status.equals("pending")||status.equals("processing")?"正在转文字…":status.equals("empty")?"未识别到清晰人声":retry?"转文字未完成 · 重试":status.equals("unavailable")?"暂未生成文字":"暂时无法转文字，可收听语音";
        TextView hint=text(stack,label,11,Ui.MUTED);hint.setMaxWidth(width);hint.setPadding(d(4),d(4),d(4),d(4));
        if(retry){hint.setContentDescription("重试语音转文字");hint.setOnClickListener(v->{hint.setText("正在转文字…");hint.setOnClickListener(null);s.retryTranscript(message);});}
    }
    private void talkButton(LinearLayout parent){talk=button(parent,"按住说话",()->{});talk.setBackground(Ui.bg(Ui.ACTION,18));talk.setTextSize(16);talk.setMinimumHeight(d(56));talk.setTextColor(Ui.WHITE);Ui.attachTalk(talk,()->{stopManual();if(VoiceService.current==null||!a.cloudMode){startVoice();a.notice("对讲已准备开启，请再次按住说话");return;}VoiceService.current.beginRecording(tab==0&&chatOpen);},cancel->{if(VoiceService.current!=null)VoiceService.current.endRecording(cancel);});}
    private void friends(){
        JSONArray requests=s.state.optJSONArray("requests");for(int i=0;requests!=null&&i<requests.length();i++){JSONObject r=requests.optJSONObject(i);if(r==null)continue;LinearLayout c=card(body);text(c,r.optString("name")+" 想加你为好友",16,Ui.INK);button(c,"接受",()->s.request("POST","/v1/requests/"+r.optString("id"),CloudSession.obj("accept",true),v->s.refresh()));button(c,"忽略",()->s.request("POST","/v1/requests/"+r.optString("id"),CloudSession.obj("accept",false),v->s.refresh()));}
        JSONArray friends=s.state.optJSONArray("friends");text(body,"我的好友"+(friends==null?"":" · "+friends.length()),13,Ui.MUTED);gap(body,12);LinearLayout list=column();list.setBackground(Ui.bg(Ui.SURFACE,20));body.addView(list);
        for(int i=0;friends!=null&&i<friends.length();i++){JSONObject f=friends.optJSONObject(i);if(f==null)continue;String name=s.displayName(f.optString("id"),f.optString("name"));LinearLayout c=row();c.setPadding(d(16),d(14),d(16),d(14));c.addView(avatarView(f.optString("id"),name,"direct",48,f.optString("avatar_version")),new LinearLayout.LayoutParams(d(48),d(48)));LinearLayout copy=column();copy.setPadding(d(14),0,d(8),0);text(copy,name,17,Ui.INK);text(copy,f.optString("remark").isEmpty()?"@"+f.optString("username"):"昵称："+f.optString("name"),12,Ui.MUTED);c.addView(copy,new LinearLayout.LayoutParams(0,-2,1));View edit=iconButton("more","设置 "+name+" 的备注",()->remark(f));c.addView(edit);list.addView(c);c.setOnLongClickListener(v->{remark(f);return true;});c.setOnClickListener(v->s.request("POST","/v1/chats",CloudSession.obj("kind","direct","friend_id",f.optString("id")),r->{open(CloudSession.obj("id",r.optString("chat_id"),"name",name));s.refresh();}));}
        if(friends==null||friends.length()==0){gap(body,20);text(body,"扫一扫朋友的二维码，开始第一段对话。",14,Ui.MUTED);}
    }
    private void remark(JSONObject friend){edit("好友备注 · 仅自己可见",friend.optString("remark"),"最多 40 字，留空恢复昵称",value->s.request("PUT","/v1/friends/"+friend.optString("id")+"/remark",CloudSession.obj("remark",value),r->{s.refresh();a.notice(value.isEmpty()?"已恢复显示好友昵称":"备注已保存");}));}
    private View avatarView(String id,String name,String kind,int size,String version){FrameLayout box=new FrameLayout(this);box.addView(Ui.avatar(this,name,kind,size),new FrameLayout.LayoutParams(-1,-1));if(kind.equals("direct")&&!id.isEmpty()&&!version.isEmpty()){
        ImageView photo=new ImageView(this);photo.setScaleType(ImageView.ScaleType.CENTER_CROP);photo.setBackground(Ui.bg(Ui.SURFACE,size/2f));photo.setClipToOutline(true);photo.setVisibility(View.INVISIBLE);photo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);box.addView(photo,new FrameLayout.LayoutParams(-1,-1));s.avatar(id,version,bitmap->{photo.setImageBitmap(bitmap);photo.setVisibility(View.VISIBLE);});
    }return box;}
    private void walkie(){text(body,"按下就说，松开就送达。",14,Ui.MUTED);gap(body,18);LinearLayout hero=card(body);hero.setBackground(Ui.bg(Ui.SURFACE,24));text(hero,"当前回复对象",12,Ui.MUTED);replyTarget=text(hero,a.active&&a.cloudMode?a.room:(s.selectedName.isEmpty()?"还没有选择":s.selectedName),26,Ui.INK);replyTarget.setTypeface(Typeface.create("sans-serif-medium",0));gap(hero,24);heroWave=new Ui.Wave(this,Ui.GREEN,true);hero.addView(heroWave,new LinearLayout.LayoutParams(-1,d(36)));gap(hero,18);headset=text(hero,a.hasHeadset?a.headset:(a.speakerAuto()?"无耳机 · 允许自动外放":"未连接耳机 · 自动播放已暂停"),14,Ui.MUTED);gap(hero,20);serviceToggle=button(hero,a.active&&a.cloudMode?"结束对讲":"开启对讲",()->{if(a.active&&a.cloudMode){stopService(new Intent(this,VoiceService.class));build();}else startVoice();});serviceToggle.setBackground(Ui.bg(Ui.ACTION,15));serviceToggle.setTextColor(Ui.WHITE);talkButton(body);gap(body,10);toggle("收到语音自动播放","开启对讲后播放新语音，不补播历史消息",a.auto(),enabled->{a.prefs.edit().putBoolean("auto",enabled).apply();if(VoiceService.current!=null)VoiceService.current.settingsChanged();});toggle("无耳机也自动播放","自动播放开启时，允许新语音通过扬声器播放",a.speakerAuto(),enabled->{a.prefs.edit().putBoolean("auto_speaker",enabled).apply();if(VoiceService.current!=null)VoiceService.current.settingsChanged();refresh();});toggle("悬浮语音按钮","按住麦克风回复，拖到底部 × 可隐藏",a.overlay()&&Settings.canDrawOverlays(this),this::overlay);gap(body,10);text(body,"松开发送 · 上滑取消 · 最长 30 秒\n连接耳机不代表已经戴好；摘下时可关闭自动播放。",12,Ui.MUTED);button(body,"选择回复的好友或群聊",()->{chatOpen=false;switchTab(0);});}
    private void toggle(String title,String hint,boolean checked,java.util.function.Consumer<Boolean> changed){LinearLayout c=card(body);LinearLayout line=row();LinearLayout copy=column();text(copy,title,15,Ui.INK);text(copy,hint,12,Ui.MUTED);copy.setPadding(0,0,d(14),0);line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));Switch control=new Switch(this);control.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.WHITE));control.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xff249d50,Ui.SWITCH_OFF}));control.setContentDescription(title);control.setMinHeight(d(48));control.setChecked(checked);control.setOnCheckedChangeListener((v,on)->changed.accept(on));line.addView(control);c.addView(line);c.setOnClickListener(v->control.setChecked(!control.isChecked()));}
    private void settingsRow(LinearLayout parent,String title,String subtitle,Runnable action){
        LinearLayout line=row();line.setPadding(d(16),d(15),d(16),d(15));Ui.touch(line);
        String icon=title.contains("外观")?"appearance":title.contains("头像")?"photo":title.contains("密码")?"lock":title.contains("更新")?"update":title.contains("二维码")?"qr":title.contains("扫一扫")?"scan":title.contains("后台")?"headset":"me";
        FrameLayout badge=new FrameLayout(this);badge.setBackground(Ui.bg(Ui.LIME,10));badge.addView(new Ui.Icon(this,icon,Ui.GREEN),new FrameLayout.LayoutParams(d(20),d(20),Gravity.CENTER));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(d(34),d(34));bp.setMarginEnd(d(14));line.addView(badge,bp);
        LinearLayout copy=column();text(copy,title,16,Ui.INK);if(subtitle!=null){gap(copy,3);TextView detail=text(copy,subtitle,12,Ui.MUTED);if(title.equals("检查更新"))updateSummary=detail;}line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));if(title.equals("检查更新")){updateRow=line;updateRowDot=updateDot();LinearLayout.LayoutParams dotSize=new LinearLayout.LayoutParams(d(8),d(8));dotSize.setMarginEnd(d(12));line.addView(updateRowDot,dotSize);}text(line,"›",23,Ui.MUTED);parent.addView(line);line.setOnClickListener(v->action.run());
        View divider=new View(this);divider.setBackgroundColor(Ui.LINE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.max(1,d(.5f)));lp.setMargins(d(64),0,d(16),0);parent.addView(divider,lp);
    }
    private void profile(){
        LinearLayout identity=row();identity.setPadding(d(20),d(22),d(16),d(22));identity.setBackground(Ui.bg(Ui.SURFACE,22));View ownAvatar=avatarView(s.userId,s.name,"direct",64,s.avatarVersion(s.userId,""));ownAvatar.setContentDescription("更换我的头像");ownAvatar.setOnClickListener(v->chooseAvatar());identity.addView(ownAvatar,new LinearLayout.LayoutParams(d(64),d(64)));LinearLayout names=column();names.setPadding(d(16),0,d(8),0);text(names,s.name,24,Ui.INK);TextView handle=text(names,"@"+s.username,13,Ui.MUTED);handle.setSingleLine(true);handle.setEllipsize(android.text.TextUtils.TruncateAt.END);identity.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout code=column();code.setGravity(Gravity.CENTER);code.setMinimumWidth(d(64));code.setPadding(d(10),d(6),d(10),d(6));code.addView(new Ui.Icon(this,"qr",Ui.MUTED),new LinearLayout.LayoutParams(d(25),d(25)));TextView qrLabel=text(code,"二维码",11,Ui.MUTED);qrLabel.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));qrLabel.setSingleLine(true);qrLabel.setGravity(Gravity.CENTER);qrLabel.setPadding(0,d(5),0,0);qrLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);Ui.touch(code);code.setContentDescription("我的二维码");code.setTooltipText("我的二维码");code.setOnClickListener(v->qr(null));identity.addView(code);body.addView(identity);gap(body,22);
        LinearLayout settings=column();settings.setBackground(Ui.bg(Ui.SURFACE,20));body.addView(settings);
        settingsRow(settings,"外观",Ui.appearanceName(this),()->startActivity(new Intent(this,AppearanceActivity.class)));
        settingsRow(settings,"更换头像","从相册选择你的头像",this::chooseAvatar);
        settingsRow(settings,"检查更新","当前版本 "+AppUpdater.get().installedName(),()->startActivity(new Intent(this,UpdateActivity.class)));
        settingsRow(settings,"修改昵称",null,()->edit("修改昵称",s.name,"昵称",value->s.request("PATCH","/v1/profile",CloudSession.obj("name",value),r->{s.name=value;s.refresh();})));
        settingsRow(settings,"修改密码",null,this::passwordDialog);
        settingsRow(settings,"后台运行设置","锁屏后保持接收消息",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        gap(body,20);button(body,"退出账号",()->new AppDialog.Builder(this).setTitle("退出当前账号？").setMessage("对讲会关闭，之后可重新登录。").setNegativeButton("取消",null).setPositiveDestructive().setPositiveButton("退出",(v,w)->s.logout()).show());gap(body,18);
        text(body,"Vibe Chat  "+AppUpdater.get().installedName()+"\n服务器保留最近 30 天消息。语音默认通过阿里云识别为文字，结果仅会话成员可见。暂不提供端到端加密。",11,Ui.MUTED);gap(body,14);text(body,s.origin(),10,Ui.MUTED);
    }
    private void createChat(){new AppDialog.Builder(this).setTitle("发起对话").setItemDetails(new String[]{"friends","mic"},new String[]{"邀请好友，一起分享日常","按住说话，随时保持联系"}).setItems(new String[]{"创建群聊","创建对讲房间"},(d,which)->edit(which==0?"群聊名称":"房间名称","", "最多 40 字",name->s.request("POST","/v1/chats",CloudSession.obj("kind",which==0?"group":"room","name",name),r->{open(CloudSession.obj("id",r.optString("chat_id"),"name",name));s.refresh();}))).show();}
    private void edit(String title,String initial,String hint,java.util.function.Consumer<String> done){edit(title,initial,hint,"保存",done);}
    private void edit(String title,String initial,String hint,String action,java.util.function.Consumer<String> done){LinearLayout c=column();c.setPadding(d(24),0,d(24),d(8));EditText input=input(c,hint,false);input.setText(initial);new AppDialog.Builder(this).setTitle(title).setView(c).setNegativeButton("取消",null).setPositiveButton(action,(dialog,which)->done.accept(input.getText().toString().trim())).show();}
    private void passwordDialog(){LinearLayout c=column();c.setPadding(d(20),d(8),d(20),d(8));EditText old=input(c,"原密码",true),next=input(c,"新密码，至少 10 个字符",true);new AppDialog.Builder(this).setTitle("修改密码").setView(c).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->s.request("POST","/v1/password",CloudSession.obj("old_password",old.getText().toString(),"password",next.getText().toString()),r->{a.notice("密码已修改，请用新密码重新登录");s.signIn(s.origin(),s.username,next.getText().toString(),s.name,false);})).show();}
    private void chatOptions(){
        JSONObject c=s.chat(s.selected);if(c==null){s.refresh();return;}String cid=s.selected;
        if(c.optString("kind").equals("direct")){
            JSONObject friend=s.friend(c.optString("peer_id"));
            new AppDialog.Builder(this).setTitle(s.selectedName).setItemDetails(new String[]{"edit","me"},null).setItems(new String[]{"设置好友备注","查看好友信息"},(dialog,index)->{
                if(friend==null){s.refresh();return;}
                if(index==0)remark(friend);
                else new AppDialog.Builder(this).asSheet().setTitle("好友信息").setMessage("昵称："+friend.optString("name")+"\n账号：@"+friend.optString("username")+"\n\n备注仅你自己可见").setPositiveButton("关闭",null).show();
            }).show();return;
        }
        boolean owner=c.optString("owner").equals(s.userId);
        String[] actions=owner?new String[]{"邀请二维码","邀请好友加入","查看成员","解散会话"}:new String[]{"查看成员","退出会话"};
        String[] icons=owner?new String[]{"qr","plus","friends","trash"}:new String[]{"friends","exit"};
        new AppDialog.Builder(this).setTitle(s.selectedName).setItemDetails(icons,null).setDestructiveItem(actions.length-1).setItems(actions,(d,index)->{
            if(owner&&index==0)qr(cid);
            else if(owner&&index==1)addFriend();
            else if(index==(owner?2:0))s.request("GET","/v1/chats/"+cid,null,r->{
                JSONArray members=r.optJSONArray("members");StringBuilder names=new StringBuilder();
                for(int i=0;members!=null&&i<members.length();i++)names.append(s.displayName(members.optJSONObject(i).optString("id"),members.optJSONObject(i).optString("name"))).append("\n");
                new AppDialog.Builder(this).asSheet().setTitle("会话成员").setMessage(names.toString().trim()).setPositiveButton("关闭",null).show();
            });
            else new AppDialog.Builder(this).setTitle(owner?"解散这个会话？":"退出这个会话？").setMessage(owner?"所有成员将无法继续访问这个会话。":"退出后将无法继续读取会话消息。").setNegativeButton("取消",null).setPositiveDestructive().setPositiveButton(owner?"解散":"退出",(x,w)->s.request(owner?"DELETE":"POST","/v1/chats/"+cid+(owner?"":"/leave"),owner?null:CloudSession.obj(),r->{s.select("","");chatOpen=false;s.refresh();build();})).show();
        }).show();
    }
    private void addFriend(){JSONArray list=s.state.optJSONArray("friends");if(list==null||list.length()==0){a.notice("先扫一扫添加好友");return;}String[] names=new String[list.length()];for(int i=0;i<names.length;i++)names[i]=s.displayName(list.optJSONObject(i).optString("id"),list.optJSONObject(i).optString("name"));String cid=s.selected;new AppDialog.Builder(this).setTitle("邀请好友加入").setItems(names,(d,index)->s.request("POST","/v1/chats/"+cid+"/members",CloudSession.obj("friend_id",list.optJSONObject(index).optString("id")),r->{a.notice("好友已加入");s.refresh();})).show();}
    private void qr(String cid){String pref="qr:"+s.userId+":"+(cid==null?"me":cid);String cached=a.prefs.getString(pref,"");long expires=a.prefs.getLong(pref+":expires",0);if(!cached.isEmpty()&&expires>System.currentTimeMillis()){showQr(cached,cid,pref);return;}newQr(cid,pref);}
    private void newQr(String cid,String pref){s.request("POST","/v1/invites",cid==null?CloudSession.obj():CloudSession.obj("chat_id",cid),r->{String code=r.optString("code");a.prefs.edit().putString(pref,code).putLong(pref+":expires",r.optLong("expires")).apply();showQr(code,cid,pref);});}
    private void showQr(String code,String cid,String pref){try{
        BitMatrix matrix=new MultiFormatWriter().encode(code,BarcodeFormat.QR_CODE,720,720);int[] pixels=new int[720*720];for(int y=0;y<720;y++)for(int x=0;x<720;x++)pixels[y*720+x]=matrix.get(x,y)?Ui.DARK:Ui.WHITE;
        Bitmap bitmap=Bitmap.createBitmap(pixels,720,720,Bitmap.Config.ARGB_8888);LinearLayout box=column();box.setPadding(d(24),d(8),d(24),0);
        TextView name=text(box,cid==null?s.name:s.selectedName,20,Ui.INK);name.setGravity(Gravity.CENTER);name.setPadding(0,d(8),0,d(12));
        ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription(cid==null?"供朋友扫码添加我的二维码":"群聊或房间邀请二维码");image.setBackground(Ui.bg(Ui.WHITE,16));image.setClipToOutline(true);box.addView(image,new LinearLayout.LayoutParams(-1,d(260)));
        TextView hint=text(box,cid==null?"扫一扫，加我为好友\n好友申请需我确认 · 有效期 30 天":"扫一扫，加入这段对话\n仅分享给想邀请的朋友 · 有效期 7 天",13,Ui.MUTED);hint.setGravity(Gravity.CENTER);hint.setPadding(0,d(16),0,0);
        new AppDialog.Builder(this).setTitle(cid==null?"我的二维码":"邀请二维码").setView(box).setPositiveButton("关闭",null).setNeutralButton("复制邀请",(d,w)->{
            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Vibe Chat 邀请",code));Toast.makeText(this,"邀请已复制，也可粘贴到扫一扫中",Toast.LENGTH_SHORT).show();
        }).setNegativeDestructive().setNegativeButton("重置二维码",(d,w)->new AppDialog.Builder(this).setTitle("重置二维码？").setMessage("旧二维码和邀请内容会立即失效。").setNegativeButton("取消",null).setPositiveDestructive().setPositiveButton("重置",(x,y)->newQr(cid,pref)).show()).show();
    }catch(Exception e){a.notice("二维码生成失败");}}
    private void scan(){if(!s.loggedIn()){a.notice("请先登录");return;}new AppDialog.Builder(this).setTitle("扫一扫").setMessage("添加好友，或加入群聊和房间").setItemDetails(new String[]{"scan","photo","paste"},new String[]{"扫描朋友分享的二维码","识别保存到手机的二维码图片","使用朋友发来的邀请内容"}).setItems(new String[]{"相机扫一扫","从相册识别二维码","粘贴邀请内容"},(d,index)->{if(index==0)startActivityForResult(new Intent(this,ScanActivity.class),21);else if(index==1){try{startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),22);}catch(Exception e){a.notice("没有找到可用的相册应用");}}else edit("粘贴邀请内容","","粘贴朋友分享的邀请内容","继续",this::acceptCode);}).show();}
    private void acceptCode(String code){final String token;try{token=s.inviteToken(code);}catch(Exception e){a.notice(e.getMessage());return;}s.request("POST","/v1/invites/preview",CloudSession.obj("token",token),preview->{boolean friend=preview.optString("kind").equals("friend");JSONObject info=preview.optJSONObject(friend?"user":"chat");if(info==null)return;String name=info.optString("name");new AppDialog.Builder(this).setTitle(friend?"添加 "+name+" 为好友？":"加入「"+name+"」？").setMessage(friend?"发送好友申请，对方同意后即可聊天。":"加入后可接收文字与语音消息。").setNegativeButton("取消",null).setPositiveButton(friend?"发送申请":"加入",(d,w)->s.request("POST","/v1/invites/accept",CloudSession.obj("token",token),r->{if(friend){a.notice(r.optString("status").equals("friends")?"你们已经是好友":"好友申请已发送");s.refresh();}else{open(CloudSession.obj("id",r.optString("chat_id"),"name",name));s.refresh();}})).show();});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;if(request==23&&data.getData()!=null){prepareAvatar(data.getData());return;}if(request==21){String code=data.getStringExtra("code");if(code!=null)acceptCode(code);}else if(request==22&&data.getData()!=null){Uri uri=data.getData();new Thread(()->{try{BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;try(java.io.InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,size);}size.inSampleSize=Math.max(1,Math.max(size.outWidth,size.outHeight)/1600);size.inJustDecodeBounds=false;Bitmap bitmap;try(java.io.InputStream in=getContentResolver().openInputStream(uri)){bitmap=BitmapFactory.decodeStream(in,null,size);}if(bitmap==null)throw new Exception();int w=bitmap.getWidth(),h=bitmap.getHeight();int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);bitmap.recycle();String value=new MultiFormatReader().decode(new BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new RGBLuminanceSource(w,h,pixels)))).getText();a.main.post(()->acceptCode(value));}catch(Exception e){a.main.post(()->a.notice("未识别到二维码，请选择更清晰的图片"));}},"vibe-qr-image").start();}}
    private void chooseAvatar(){new AppDialog.Builder(this).setTitle("我的头像").setItemDetails(new String[]{"photo","me"},null).setItems(new String[]{"从相册选择","恢复默认头像"},(dialog,index)->{if(index==0){try{startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),23);}catch(Exception e){a.notice("没有找到可用的相册应用");}}else s.request("DELETE","/v1/profile/avatar",null,r->{s.refresh();a.notice("已恢复默认头像");});}).show();}
    private void prepareAvatar(Uri uri){
        String accountId=s.userId;new Thread(()->{try{
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;try(java.io.InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,options);}
            if(options.outWidth<=0||options.outHeight<=0)throw new Exception();options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1024)options.inSampleSize*=2;options.inJustDecodeBounds=false;
            Bitmap decoded;try(java.io.InputStream in=getContentResolver().openInputStream(uri)){decoded=BitmapFactory.decodeStream(in,null,options);}if(decoded==null)throw new Exception();
            int orientation=1;try(java.io.InputStream in=getContentResolver().openInputStream(uri)){orientation=new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);}catch(Exception ignored){}
            Matrix rotate=new Matrix();switch(orientation){case 2:rotate.setScale(-1,1);break;case 3:rotate.setRotate(180);break;case 4:rotate.setScale(1,-1);break;case 5:rotate.setRotate(90);rotate.postScale(-1,1);break;case 6:rotate.setRotate(90);break;case 7:rotate.setRotate(270);rotate.postScale(-1,1);break;case 8:rotate.setRotate(270);break;}
            Bitmap oriented=Bitmap.createBitmap(decoded,0,0,decoded.getWidth(),decoded.getHeight(),rotate,true);if(oriented!=decoded)decoded.recycle();int edge=Math.min(oriented.getWidth(),oriented.getHeight());Bitmap square=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(square);canvas.drawColor(Color.WHITE);canvas.drawBitmap(oriented,new Rect((oriented.getWidth()-edge)/2,(oriented.getHeight()-edge)/2,(oriented.getWidth()+edge)/2,(oriented.getHeight()+edge)/2),new Rect(0,0,256,256),new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));oriented.recycle();java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();square.compress(Bitmap.CompressFormat.JPEG,88,bytes);byte[] jpeg=bytes.toByteArray();
            a.main.post(()->{if(isFinishing()||isDestroyed()||!accountId.equals(s.userId))return;LinearLayout box=column();box.setPadding(d(24),d(12),d(24),d(12));ImageView preview=new ImageView(this);preview.setImageBitmap(square);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);box.addView(preview,new LinearLayout.LayoutParams(-1,d(220)));text(box,"头像会居中裁剪，并同步给好友。",12,Ui.MUTED);new AppDialog.Builder(this).setTitle("使用这张头像？").setView(box).setNegativeButton("取消",null).setPositiveButton("上传头像",(dialog,index)->{if(!accountId.equals(s.userId))return;s.request("PUT","/v1/profile/avatar",CloudSession.obj("image",android.util.Base64.encodeToString(jpeg,android.util.Base64.NO_WRAP)),r->{s.refresh();a.notice("头像已更新");});}).show();});
        }catch(Exception e){a.main.post(()->a.notice("无法读取这张图片，请选择其他照片"));}},"vibe-avatar").start();
    }
    private void startVoice(){ArrayList<String> permissions=new ArrayList<>();if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)permissions.add(Manifest.permission.RECORD_AUDIO);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permissions.add(Manifest.permission.POST_NOTIFICATIONS);if(!permissions.isEmpty()){requestPermissions(permissions.toArray(new String[0]),20);return;}launchVoice();}
    private void launchVoice(){stopManual();try{startForegroundService(new Intent(this,VoiceService.class).setAction(VoiceService.START).putExtra("mode","cloud"));}catch(Exception e){a.notice("请保持 App 在前台再开启对讲");}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==20){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)launchVoice();else a.notice("按住说话需要麦克风权限，可在应用设置中开启");}}
    private void stopManual(){manualRequest++;loadingVoice="";if(manualAudio!=null)manualAudio.stop();}
    private void play(JSONObject message){
        if(a.recording){a.notice("录音结束后再播放语音");return;}
        String id=s.voiceId(message);if(id.equals(loadingVoice)){stopManual();refresh();return;}
        if(id.equals(a.playingId)){stopManual();if(VoiceService.current!=null&&a.cloudMode){VibeApp.Entry e=a.find(id);if(e!=null)VoiceService.current.play(e,false);}else stopManual();refresh();return;}
        stopManual();int request=manualRequest;String accountId=s.userId,cid=s.selected;loadingVoice=id;refresh();long started=SystemClock.elapsedRealtime();
        s.voice(message,e->{if(request!=manualRequest||!s.foreground||!accountId.equals(s.userId)||!cid.equals(s.selected))return;loadingVoice="";if(e==null){refresh();return;}
            if(VoiceService.current!=null&&a.cloudMode){VoiceService.current.play(e,false);return;}
            a.read(e,pcm->{if(request!=manualRequest||!s.foreground||pcm==null)return;a.playing=true;a.playingId=e.id;a.changed();manualAudio.play(pcm,false,complete->{a.playing=false;a.playingId="";if(complete){e.heard=true;a.persist();}a.changed();});android.util.Log.d("VibePlayback","tap to AudioTrack.play: "+(SystemClock.elapsedRealtime()-started)+" ms");});
        });
    }
    private void overlay(boolean enabled){if(enabled&&!Settings.canDrawOverlays(this)){a.prefs.edit().putBoolean("overlay",true).apply();try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}catch(Exception e){a.notice("请在应用设置里允许显示在其他应用上层");}return;}a.prefs.edit().putBoolean("overlay",enabled).apply();if(VoiceService.current!=null)VoiceService.current.settingsChanged();else if(enabled)startVoice();}
}
