package chat.vibe.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import chat.vibe.app.core.*;

public final class VoiceService extends Service {
    public static VoiceService current;
    public static final String START="chat.vibe.START", STOP="chat.vibe.STOP";
    private VibeApp a; private AudioEngine audio; private RoomServer server; private RoomClient client;
    private int generation,playToken; private boolean destroyed; private long recordStart;
    private String recordTarget="";
    private String cloudReplyTarget="";
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final ArrayDeque<VibeApp.Entry> queue=new ArrayDeque<>();
    private final Set<String> received=new LinkedHashSet<>();
    private FloatingVoiceOverlay floating;
    private PowerManager.WakeLock voiceLock;
    private final BroadcastReceiver noisy=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){queue.clear();stopPlayback();}};
    @Override public void onCreate(){
        super.onCreate();a=VibeApp.app;current=this;
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("session","对讲状态",NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel("messages","收到语音",NotificationManager.IMPORTANCE_DEFAULT));
        audio=new AudioEngine(this,a.main,this::devicesChanged);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(noisy,new IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy,new IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        voiceLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"vibe:voice");voiceLock.setReferenceCounted(false);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null || STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        try {
            int types=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if(Build.VERSION.SDK_INT>=34)types|=ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if(Build.VERSION.SDK_INT>=30)startForeground(1,notification(),types);else startForeground(1,notification());
        }catch(Exception e){a.notice("无法开启对讲，请回到 App 授予麦克风权限后重试");stopSelf();return START_NOT_STICKY;}
        a.active=true;devicesChanged();showOverlay();
        String mode=intent.getStringExtra("mode");if(mode!=null)connect(mode,intent.getStringExtra("invite"));
        a.changed();return START_NOT_STICKY;
    }
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,a.cloudMode?CloudActivity.class:MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,VoiceService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"session").setSmallIcon(R.drawable.ic_notification).setContentTitle("Vibe Chat · "+a.room)
            .setContentText(a.recording?"正在录音，松开发送":a.connection+" · "+(a.auto()?(a.speakerAuto()?"自动播放 · 允许外放":"仅耳机自动播放"):"手动播放"))
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"结束对讲",stop).build()).build();
    }
    private void update(){
        if(destroyed)return;
        getSystemService(NotificationManager.class).notify(1,notification());
        a.changed();
    }
    private void devicesChanged(){boolean hadHeadset=a.hasHeadset;a.hasHeadset=audio.headset()!=null;a.headset=audio.headsetName();if((hadHeadset&&!a.hasHeadset)||!a.autoAllowed()){queue.clear();stopPlayback();}a.changed();}
    private void connect(String mode,String invite){
        endRecording(true);queue.clear();stopPlayback();int gen=++generation;
        if(client!=null){client.close();client=null;}if(server!=null){server.close();server=null;}
        a.connected=false;a.peers=0;a.invite="";a.demo=mode.equals("demo");a.cloudMode=mode.equals("cloud");
        if(a.cloudMode){cloudReplyTarget=a.cloud.selected;a.room=a.cloud.selectedName.isEmpty()?"选择一个会话":a.cloud.selectedName;a.connected=a.cloud.ready;a.connection=a.cloud.status;a.cloud.wake();update();return;}
        if(a.demo){a.room="体验房间";a.connected=true;a.connection="回声助手已就绪";update();return;}
        a.room=mode.equals("host")?"我的房间":"朋友的房间";a.connection="正在连接…";update();
        network.execute(()->{
            RoomServer created=null;
            try{
                String host;int port;String secret;String code;
                if(mode.equals("host")){
                    String local=localAddress();if(local==null)throw new IllegalArgumentException("请先连接 Wi-Fi 或开启手机热点，再创建房间");
                    secret=Wire.keyText();created=new RoomServer(Wire.PORT,Wire.key(secret));host="127.0.0.1";port=created.port();code=Wire.invitation(local,port,secret);
                }else{String[] v=Wire.parseInvitation(invite==null?"":invite);host=v[1];port=Integer.parseInt(v[2]);secret=v[3];code=invite.trim();}
                RoomServer ready=created;
                a.main.post(()->{
                    if(destroyed || gen!=generation){if(ready!=null)ready.close();return;}
                    server=ready;a.invite=code;
                    client=new RoomClient(host,port,Wire.key(secret),new RoomClient.Listener(){
                        public void connected(){post(()->{a.connected=true;a.connection="房间已连接";update();});}
                        public void presence(int count){post(()->{a.peers=count;a.connection=count<2?"等待朋友加入":"房间内 "+count+" 人";update();});}
                        public void message(Wire.Message m){post(()->receive(m));}
                        public void disconnected(){post(()->{a.connected=false;a.peers=0;a.connection="连接中断，正在重连…";endRecording(true);update();});}
                        private void post(Runnable r){a.main.post(()->{if(!destroyed && gen==generation)r.run();});}
                    });client.start();update();
                });
            }catch(Exception e){if(created!=null)created.close();String reason=e instanceof IllegalArgumentException?e.getMessage():"房间启动失败，请检查 Wi-Fi、邀请码和网络隔离设置";
                a.main.post(()->{if(!destroyed && gen==generation){a.connection="未连接";a.notice(reason);update();}});}
        });
    }
    public static String localAddress() throws SocketException {
        Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();String fallback=null;
        while(all.hasMoreElements()){
            NetworkInterface ni=all.nextElement();if(!ni.isUp()||ni.isLoopback()||ni.getName().startsWith("tun"))continue;
            Enumeration<InetAddress> addresses=ni.getInetAddresses();while(addresses.hasMoreElements()){
                InetAddress ip=addresses.nextElement();if(ip instanceof Inet4Address && ip.isSiteLocalAddress()){
                    if(ni.getName().startsWith("wlan")||ni.getName().startsWith("ap")||ni.getName().startsWith("swlan"))return ip.getHostAddress();fallback=ip.getHostAddress();
                }
            }
        }return fallback;
    }
    public void beginRecording(){
        beginRecording(false);
    }
    public void beginRecording(boolean useOpenChat){
        if(!a.active || a.recording)return;
        String target=useOpenChat?a.cloud.selected:cloudReplyTarget;
        if(a.cloudMode && (target.isEmpty()||!a.cloud.loggedIn()||a.cloud.chat(target)==null)){a.notice("请先登录并选择好友、群聊或房间");return;}
        if(!a.connected || (!a.cloudMode&&!a.demo && a.peers<2)){a.notice(a.connected?"朋友加入房间后就能发送语音；也可以先试试回声体验":"尚未连接，请稍后再试");return;}
        recordTarget=a.cloudMode?target:"";
        queue.clear();stopPlayback();a.recording=true;recordStart=SystemClock.elapsedRealtime();a.recordSeconds=0;voiceLock.acquire(35000);
        audio.begin(new AudioEngine.Capture(){
            public void finished(byte[] pcm,boolean cancelled){
                a.recording=false;a.main.removeCallbacks(recordTick);releaseLock();update();
                if(destroyed || cancelled){if(!destroyed)a.notice("已取消录音");drain();return;}
                if(pcm.length<16000){a.notice("说话时间太短，请按住至少半秒");drain();return;}
                send(pcm);drain();
            }
            public void failed(String reason){a.recording=false;a.main.removeCallbacks(recordTick);releaseLock();if(!destroyed){a.notice(reason);update();drain();}}
        });a.main.post(recordTick);update();
    }
    private final Runnable recordTick=new Runnable(){public void run(){
        if(!a.recording)return;a.recordSeconds=(int)((SystemClock.elapsedRealtime()-recordStart)/1000);update();
        if(a.recordSeconds>=30)endRecording(false);else a.main.postDelayed(this,250);
    }};
    public void endRecording(boolean cancelled){if(a.recording)audio.end(cancelled);}
    private void send(byte[] pcm){
        if(a.cloudMode){
            final String cid=recordTarget,account=a.cloud.userId;
            Wire.Message cloudMessage=new Wire.Message(UUID.randomUUID().toString(),account,a.cloud.name,System.currentTimeMillis(),pcm);
            a.add(cloudMessage,true,"发送中",entry->{entry.chat=cid;entry.account=account;a.persist();sendCloud(entry,pcm);});return;
        }
        final int sendGeneration=generation;final boolean wasDemo=a.demo;
        Wire.Message m=new Wire.Message(UUID.randomUUID().toString(),a.deviceId,a.name(),System.currentTimeMillis(),pcm);
        a.add(m,true,a.demo?"体验语音":"发送中",entry->{
            if(destroyed || sendGeneration!=generation){entry.status="未发送";a.persist();a.changed();return;}
            if(wasDemo){int gen=generation;a.main.postDelayed(()->{
                if(!destroyed && gen==generation)receive(new Wire.Message(UUID.randomUUID().toString(),"echo","回声助手",System.currentTimeMillis(),pcm));
            },650);}
            else{
                if(client==null || !client.send(m)){entry.status="发送失败";a.notice("网络已断开，语音保存在本机，点击消息可重发");a.persist();}
                else a.main.postDelayed(()->{if(entry.status.equals("发送中")){entry.status="未确认送达";a.persist();a.changed();}},12000);
            }
        });
    }
    public void retry(VibeApp.Entry e){
        if(!e.chat.isEmpty()){a.cloud.retryVoice(e);return;}
        if(a.demo || !a.connected || a.peers<2){a.notice("请先连接有朋友在线的房间");return;}
        int gen=generation;
        a.read(e,pcm->{if(pcm!=null && !destroyed && gen==generation && client!=null && client.send(new Wire.Message(e.id,a.deviceId,e.name,e.time,pcm))){e.status="发送中";a.persist();a.changed();
            a.main.postDelayed(()->{if(e.status.equals("发送中")){e.status="未确认送达";a.persist();a.changed();}},12000);
        }});
    }
    private void receive(Wire.Message m){
        if(m.sender.equals(a.deviceId)){VibeApp.Entry e=a.find(m.id);if(e!=null){e.status="已到达房间";a.persist();a.changed();}return;}
        if(received.contains(m.id)||a.find(m.id)!=null)return;received.add(m.id);if(received.size()>500)received.remove(received.iterator().next());
        int gen=generation;
        a.add(m,false,"未听",entry->{if(destroyed || gen!=generation)return;
            if(a.autoAllowed()){if(queue.size()<20)queue.add(entry);drain();}
            else messageNotification(entry);
        });
    }
    private void sendCloud(VibeApp.Entry entry,byte[] pcm){
        a.cloud.sendVoice(entry,pcm);
    }
    public void refreshNames(){org.json.JSONObject c=a.cloud.chat(cloudReplyTarget);if(c!=null&&!a.room.equals(c.optString("name"))){a.room=c.optString("name");update();}}
    public void cloudTargetChanged(){queue.clear();stopPlayback();cloudReplyTarget=a.cloud.selected;a.room=a.cloud.selectedName.isEmpty()?"选择一个会话":a.cloud.selectedName;update();}
    public void cloudIncoming(org.json.JSONObject m){
        if(!a.cloudMode||destroyed)return;
        if(m.optString("kind").equals("voice")&&a.autoAllowed()&&a.cloud.chat(m.optString("chat_id"))!=null){int gen=generation;
            a.cloud.voice(m,e->{if(e!=null&&!destroyed&&gen==generation&&a.autoAllowed()){if(queue.size()<20)queue.add(e);drain();}});
        }else{
            VibeApp.Entry e=new VibeApp.Entry();e.name=a.cloud.displayName(m.optString("sender"),m.optString("sender_name"));e.seconds=m.optInt("seconds");
            PendingIntent p=PendingIntent.getActivity(this,2,new Intent(this,CloudActivity.class).putExtra("chat_id",m.optString("chat_id")),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            getSystemService(NotificationManager.class).notify(2,new Notification.Builder(this,"messages").setSmallIcon(R.drawable.ic_notification).setContentTitle(e.name+" 发来"+(m.optString("kind").equals("voice")?"语音":"消息")).setContentText("打开 Vibe Chat 查看").setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(p).setAutoCancel(true).build());
        }
    }
    private void messageNotification(VibeApp.Entry e){
        PendingIntent p=PendingIntent.getActivity(this,2,new Intent(this,MainActivity.class).putExtra("tab",1),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,"messages").setSmallIcon(R.drawable.ic_notification).setContentTitle(e.name+" 发来语音")
            .setContentText(e.seconds+" 秒 · 点击收听").setContentIntent(p).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
        getSystemService(NotificationManager.class).notify(2,n);
    }
    private void drain(){
        if(destroyed||audio.busy()||a.playing||a.recording||queue.isEmpty())return;
        if(!a.autoAllowed()){queue.clear();return;}
        playEntry(queue.removeFirst(),a.hasHeadset||!a.speakerAuto(),true);
    }
    public void play(VibeApp.Entry e,boolean onlyHeadset){
        playEntry(e,onlyHeadset,false);
    }
    private void stopPlayback(){
        ++playToken;audio.stop();a.playing=false;a.playingId="";
    }
    public void clearHistory(){
        queue.clear();stopPlayback();a.clearHistory();
    }
    private void playEntry(VibeApp.Entry e,boolean onlyHeadset,boolean automatic){
        if(a.recording){a.notice("录音结束后再播放语音");return;}
        if(a.playingId.equals(e.id)){queue.clear();stopPlayback();a.changed();return;}
        if(!automatic)queue.clear();stopPlayback();
        if(a.cloudMode&&!e.chat.isEmpty()){cloudReplyTarget=e.chat;org.json.JSONObject chat=a.cloud.chat(e.chat);a.room=chat==null?e.name:chat.optString("name",e.name);update();}
        a.playing=true;a.playingId=e.id;a.changed();int token=playToken;
        a.read(e,pcm->{
            if(destroyed || token!=playToken)return;
            if(pcm==null || (onlyHeadset && !a.hasHeadset) || (automatic && !a.autoAllowed())){a.playing=false;a.playingId="";a.changed();return;}
            voiceLock.acquire(35000);
            if(automatic)android.util.Log.d("VibePlayback",onlyHeadset?"automatic: headset":"automatic: speaker allowed");
            audio.play(pcm,onlyHeadset,complete->{
                if(automatic)android.util.Log.d("VibePlayback","automatic finished: "+complete);
                releaseLock();a.playing=false;a.playingId="";
                if(complete){e.heard=true;if(!e.outgoing)e.status="已听";a.persist();}
                else if(!destroyed && onlyHeadset){queue.clear();a.notice("播放已暂停，语音保留在消息中");}
                a.changed();if(!destroyed)a.main.post(this::drain);
            });
        });
    }
    public void settingsChanged(){if(!a.autoAllowed()){queue.clear();stopPlayback();}showOverlay();update();}
    private void releaseLock(){if(voiceLock.isHeld())voiceLock.release();}
    private void showOverlay(){
        if(!a.overlay()||!Settings.canDrawOverlays(this)){hideOverlay();return;}
        if(floating==null){floating=new FloatingVoiceOverlay(this);if(!floating.show())floating=null;}else floating.update();
    }
    private void hideOverlay(){if(floating!=null){floating.close();floating=null;}}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);if(floating!=null)floating.configurationChanged();}
    @Override public void onDestroy(){
        destroyed=true;++generation;endRecording(true);queue.clear();audio.close();hideOverlay();unregisterReceiver(noisy);releaseLock();
        if(client!=null)client.close();if(server!=null)server.close();network.shutdownNow();a.main.removeCallbacks(recordTick);
        a.active=false;a.connected=false;a.recording=false;a.playing=false;a.playingId="";a.peers=0;a.connection="对讲已关闭";a.invite="";current=null;a.changed();
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
