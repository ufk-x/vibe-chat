package chat.vibe.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;
import chat.vibe.app.core.Wire;
import chat.vibe.app.core.VoiceDelivery;

/** HTTPS account transport. All UI state and callbacks belong to the main thread. */
public final class CloudSession {
    public static String defaultOrigin(){return VibeApp.app.getString(R.string.vibe_origin);}
    private final VibeApp a;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService historyIo=Executors.newFixedThreadPool(2);
    private final ExecutorService voiceIo=Executors.newFixedThreadPool(2);
    private final ExecutorService avatarsIo=Executors.newFixedThreadPool(2);
    private final android.util.LruCache<String,android.graphics.Bitmap> avatars=new android.util.LruCache<String,android.graphics.Bitmap>(8*1024*1024){protected int sizeOf(String k,android.graphics.Bitmap b){return b.getAllocationByteCount();}};
    private final Map<String,ArrayList<Consumer<android.graphics.Bitmap>>> avatarWaiters=new HashMap<>();
    private final Map<String,Long> avatarFailures=new HashMap<>();
    private final ExecutorService prefetchIo=Executors.newFixedThreadPool(2);
    private final Set<String> prefetching=new HashSet<>();
    private final Map<String,ArrayList<Consumer<VibeApp.Entry>>> voiceWaiters=new HashMap<>();
    private volatile String origin,token="";
    public String userId="",name="",username="",selected="",selectedName="",status="未登录";
    public JSONObject state=new JSONObject();
    public JSONArray history=new JSONArray();
    public boolean foreground,viewingChat,ready,busy;
    public int revision;
    public boolean historyLoaded,historyLoading;
    public String historyError="";
    private static final class HistorySnapshot {final JSONArray rows;final String wire;HistorySnapshot(JSONArray rows,String wire){this.rows=rows;this.wire=wire;}}
    private final android.util.LruCache<String,HistorySnapshot> recentHistory=new android.util.LruCache<>(6);
    private int historyRequest;
    private String loadingChat="";
    private boolean historyAgain;
    private void clearHistory(){historyRequest++;recentHistory.evictAll();history=new JSONArray();historyLoaded=false;historyLoading=false;historyError="";loadingChat="";historyAgain=false;}

    private long cursor,lastState;
    private int generation,refreshVersion;
    private boolean inFlight;
    public CloudSession(VibeApp app){a=app;origin=a.prefs.getString("cloud_origin",defaultOrigin());
        try{JSONObject session=new JSONObject(decrypt(a.prefs.getString("cloud_session","")));token=session.getString("token");userId=session.getString("id");name=session.getString("name");username=session.getString("username");}catch(Exception ignored){}
    }
    public boolean loggedIn(){return !token.isEmpty();}
    public String origin(){return origin;}
    public static String validOrigin(String value)throws Exception{
        URI u=new URI(value.trim());if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!(u.getPath()==null||u.getPath().isEmpty()||u.getPath().equals("/")))throw new Exception("服务地址必须是完整的 HTTPS 域名");
        return "https://"+u.getHost().toLowerCase(Locale.ROOT)+(u.getPort()==-1?"":":"+u.getPort());
    }
    private SecretKey secret()throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias("vibe-session")){KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder("vibe-session",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}
        return (SecretKey)ks.getKey("vibe-session",null);
    }
    private String encrypt(String text)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,secret());return Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(text.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);}
    private String decrypt(String value)throws Exception{String[] p=value.split(":");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,secret(),new GCMParameterSpec(128,Base64.decode(p[0],Base64.NO_WRAP)));return new String(c.doFinal(Base64.decode(p[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);}
    private byte[] exchange(String method,String path,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(origin+path).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);c.setRequestMethod(method);c.setRequestProperty("Accept","application/json");
        String auth=token;if(!auth.isEmpty())c.setRequestProperty("Authorization","Bearer "+auth);
        try{
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
            int code=c.getResponseCode();InputStream stream=code>=200&&code<300?c.getInputStream():c.getErrorStream();ByteArrayOutputStream out=new ByteArrayOutputStream();
            if(stream!=null)try(InputStream in=stream){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>2100000)throw new IOException("响应过大");out.write(buffer,0,n);}}
            byte[] result=out.toByteArray();if(code<200||code>=300){String message="服务连接失败（"+code+"）";try{message=new JSONObject(new String(result,StandardCharsets.UTF_8)).optString("error",message);}catch(Exception ignored){}throw new ApiError(code,message);}return result;
        }finally{c.disconnect();}
    }
    private JSONObject call(String method,String path,JSONObject body)throws Exception{return new JSONObject(new String(exchange(method,path,body),StandardCharsets.UTF_8));}
    public static JSONObject obj(Object... pairs){JSONObject j=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)j.put((String)pairs[i],pairs[i+1]);}catch(Exception e){throw new IllegalArgumentException(e);}return j;}
    public void request(String method,String path,JSONObject body,Consumer<JSONObject> done){int gen=generation;io.execute(()->{try{JSONObject result=call(method,path,body);a.main.post(()->{if(gen!=generation)return;if(done!=null)done.accept(result);});}catch(Exception e){a.main.post(()->{if(gen==generation)error(e);});}});}
    private void error(Exception e){if(e instanceof ApiError && ((ApiError)e).code==401){logoutLocal();a.notice("登录已失效，请重新登录");return;}status=e instanceof ApiError?e.getMessage():"网络暂时无法连接，正在重试";a.notice(status);}
    public void signIn(String server,String account,String password,String nickname,boolean register){
        if(busy)return;try{origin=validOrigin(server);}catch(Exception e){a.notice(e.getMessage());return;}
        busy=true;status="正在连接…";a.changed();int gen=++generation;
        io.execute(()->{try{
            JSONObject result=call("POST",register?"/v1/register":"/v1/login",obj("username",account,"password",password,"name",nickname));JSONObject u=result.getJSONObject("user");
            String saved=encrypt(obj("token",result.getString("token"),"id",u.getString("id"),"name",u.getString("name"),"username",u.getString("username")).toString());
            a.main.post(()->{if(gen!=generation)return;clearHistory();token=result.optString("token");userId=u.optString("id");name=u.optString("name");username=u.optString("username");a.prefs.edit().putString("cloud_origin",origin).putString("cloud_session",saved).apply();busy=false;ready=false;status="正在同步…";a.notice="";revision++;a.changed();wake();});
        }catch(Exception e){a.main.post(()->{if(gen!=generation)return;busy=false;error(e);a.changed();});}});
    }
    public void logout(){int gen=generation;io.execute(()->{try{call("POST","/v1/logout",obj());}catch(Exception ignored){}a.main.post(()->{if(gen==generation)logoutLocal();});});}
    private void logoutLocal(){generation++;token="";userId="";ready=false;selected="";selectedName="";clearHistory();state=new JSONObject();voiceWaiters.clear();prefetching.clear();avatars.evictAll();avatarWaiters.clear();avatarFailures.clear();status="未登录";a.prefs.edit().remove("cloud_session").apply();if(VoiceService.current!=null&&a.cloudMode)a.stopService(new android.content.Intent(a,VoiceService.class));revision++;a.changed();}
    public void wake(){a.main.removeCallbacks(tick);a.main.post(tick);}
    private boolean shouldPoll(){return loggedIn()&&(foreground||(a.active&&a.cloudMode));}
    private final Runnable tick=new Runnable(){public void run(){
        if(!shouldPoll())return;if(inFlight){a.main.postDelayed(this,2000);return;}inFlight=true;int gen=generation,version=refreshVersion;boolean initial=!ready;long after=cursor;
        io.execute(()->{try{
            JSONObject snapshot=initial||System.currentTimeMillis()-lastState>15000?call("GET","/v1/state",null):null;
            JSONObject events=initial?null:call("GET","/v1/events?after="+after,null);
            a.main.post(()->{inFlight=false;if(gen!=generation)return;
                if(snapshot!=null){if(!state.toString().equals(snapshot.toString()))revision++;state=snapshot;lastState=version==refreshVersion?System.currentTimeMillis():0;JSONObject u=snapshot.optJSONObject("user");if(u!=null)name=u.optString("name",name);if(initial)cursor=snapshot.optLong("cursor");}
                ready=true;status="已连接";if(a.cloudMode&&a.active){a.connected=true;a.connection="公网服务已连接";}
                if(events!=null){cursor=events.optLong("cursor",cursor);JSONArray rows=events.optJSONArray("events");boolean reload=false;for(int i=0;rows!=null&&i<rows.length();i++){JSONObject e=rows.optJSONObject(i);if(e==null)continue;
                    if(e.optString("kind").equals("state")){lastState=0;if(foreground&&viewingChat)reload=true;continue;}if(e.optString("kind").equals("transcript")){lastState=0;if(e.optString("chat_id").equals(selected))reload=true;continue;}JSONObject m=e.optJSONObject("message");if(m==null)continue;if(confirmVoice(m)){a.persist();revision++;}if(m.optString("chat_id").equals(selected))reload=true;
                    if(VoiceService.current!=null&&a.cloudMode&&!m.optString("sender").equals(userId)&&System.currentTimeMillis()-m.optLong("created")<60000)VoiceService.current.cloudIncoming(m);
                }if(reload)loadHistory();}
                if(!selected.isEmpty()&&snapshot!=null){JSONObject found=chat(selected);if(found==null){select("","");}else selectedName=found.optString("name");}
                if(snapshot!=null)for(String cid:recentHistory.snapshot().keySet())if(chat(cid)==null)recentHistory.remove(cid);
                if(snapshot!=null&&VoiceService.current!=null&&a.cloudMode)VoiceService.current.refreshNames();
                a.changed();if(shouldPoll())a.main.postDelayed(tick,2000);
            });
        }catch(Exception e){a.main.post(()->{inFlight=false;if(gen!=generation)return;status="连接中断，正在重试…";if(a.cloudMode&&a.active){a.connected=false;a.connection=status;}if(e instanceof ApiError&&((ApiError)e).code==401)error(e);a.changed();if(shouldPoll())a.main.postDelayed(tick,5000);});}});
    }};
    public JSONObject chat(String id){JSONArray list=state.optJSONArray("chats");for(int i=0;list!=null&&i<list.length();i++){JSONObject c=list.optJSONObject(i);if(c!=null&&c.optString("id").equals(id))return c;}return null;}
    public JSONObject friend(String id){JSONArray rows=state.optJSONArray("friends");for(int i=0;rows!=null&&i<rows.length();i++){JSONObject f=rows.optJSONObject(i);if(f!=null&&id.equals(f.optString("id")))return f;}return null;}
    public String displayName(String id,String fallback){JSONObject f=friend(id);return f!=null&&!f.optString("remark").isEmpty()?f.optString("remark"):fallback;}
    public String avatarVersion(String id,String fallback){JSONObject u=id.equals(userId)?state.optJSONObject("user"):friend(id);return u==null?fallback:u.optString("avatar_version");}
    public void avatar(String id,String version,Consumer<android.graphics.Bitmap> done){
        if(!id.matches("[a-f0-9-]{36}")||!version.matches("[a-f0-9]{24}"))return;
        String key=generation+":"+userId+":"+id+":"+version;android.graphics.Bitmap cached=avatars.get(key);if(cached!=null){done.accept(cached);return;}
        if(avatarFailures.containsKey(key)&&System.currentTimeMillis()-avatarFailures.get(key)<30000)return;
        ArrayList<Consumer<android.graphics.Bitmap>> waiting=avatarWaiters.get(key);if(waiting!=null){waiting.add(done);return;}waiting=new ArrayList<>();waiting.add(done);avatarWaiters.put(key,waiting);int gen=generation;
        avatarsIo.execute(()->{android.graphics.Bitmap result=null;try{byte[] bytes=exchange("GET","/v1/users/"+id+"/avatar?v="+version,null);android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);if(bytes.length<=200000&&bounds.outWidth>0&&bounds.outWidth<=512&&bounds.outHeight>0&&bounds.outHeight<=512)result=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);}catch(Exception ignored){}
            android.graphics.Bitmap image=result;a.main.post(()->{ArrayList<Consumer<android.graphics.Bitmap>> callbacks=avatarWaiters.remove(key);if(gen!=generation)return;if(image!=null){avatars.put(key,image);if(callbacks!=null)for(Consumer<android.graphics.Bitmap> cb:callbacks)cb.accept(image);}else{if(avatarFailures.size()>200)avatarFailures.clear();avatarFailures.put(key,System.currentTimeMillis());}});
        });
    }
    public void refresh(){refreshVersion++;lastState=0;wake();}
    public void select(String id,String title){
        if(a.recording){a.notice("请先结束当前录音");return;}
        historyRequest++;loadingChat="";historyAgain=false;selected=id;selectedName=title;
        HistorySnapshot cached=recentHistory.get(id);history=cached==null?new JSONArray():cached.rows;
        historyLoaded=cached!=null;historyLoading=false;historyError="";revision++;
        if(a.cloudMode&&VoiceService.current!=null)VoiceService.current.cloudTargetChanged();
        if(!id.isEmpty())loadHistory();a.changed();
    }
    public void loadHistory(){
        String cid=selected;if(cid.isEmpty())return;
        if(cid.equals(loadingChat)){historyAgain=true;return;}
        boolean statusChanged=!historyError.isEmpty()||!historyLoaded;loadingChat=cid;historyLoading=true;historyError="";int gen=generation,ticket=++historyRequest;
        if(statusChanged){revision++;a.changed();}
        // Opening a conversation must not queue behind polling, uploads or read receipts.
        historyIo.execute(()->{try{
            JSONObject response=call("GET","/v1/chats/"+cid+"/messages",null);JSONArray rows=response.optJSONArray("messages");
            final JSONArray data=rows==null?new JSONArray():rows;final String wire=data.toString();
            a.main.post(()->{
                if(gen!=generation||ticket!=historyRequest||!cid.equals(selected))return;
                boolean again=historyAgain;historyAgain=false;loadingChat="";historyLoading=false;
                HistorySnapshot old=recentHistory.get(cid);boolean changed=old==null||!wire.equals(old.wire);
                if(changed){history=data;recentHistory.put(cid,new HistorySnapshot(data,wire));}
                boolean first=!historyLoaded;historyLoaded=true;historyError="";
                if(changed||first){reconcileVoices(history);prefetchVoices();revision++;a.changed();}
                if(foreground&&viewingChat&&history.length()>0){long id=history.optJSONObject(history.length()-1).optLong("id");request("POST","/v1/chats/"+cid+"/read",obj("message_id",id),ignored->{});}
                if(again)loadHistory();
            });
        }catch(Exception e){a.main.post(()->{
            if(gen!=generation||ticket!=historyRequest||!cid.equals(selected))return;
            loadingChat="";historyLoading=false;historyAgain=false;historyError="加载失败，轻点重试";
            if(e instanceof ApiError&&(((ApiError)e).code==403||((ApiError)e).code==404)){recentHistory.remove(cid);history=new JSONArray();historyLoaded=false;}
            revision++;error(e);a.changed();
        });}});
    }
    public void sendText(String text,Runnable sent){String cid=selected;if(cid.isEmpty())return;request("POST","/v1/chats/"+cid+"/messages",obj("client_id",UUID.randomUUID().toString(),"kind","text","text",text),r->{if(sent!=null)sent.run();if(selected.equals(cid))loadHistory();refresh();});}
    private boolean confirmVoice(JSONObject m){
        if(!m.optString("kind").equals("voice")||!m.optString("sender").equals(userId))return false;
        VibeApp.Entry e=a.find(m.optString("client_id"));
        if(e==null||!VoiceDelivery.local(e.id,e.outgoing,e.chat,e.account)||!e.account.equals(userId)||!e.chat.equals(m.optString("chat_id"))||e.delivery==VoiceDelivery.SENT)return false;
        e.delivery=VoiceDelivery.SENT;e.status="已送达服务器";return true;
    }
    public void reconcileVoices(JSONArray rows){
        boolean changed=false;for(int i=0;rows!=null&&i<rows.length();i++){JSONObject m=rows.optJSONObject(i);if(m!=null)changed|=confirmVoice(m);}
        if(changed){a.persist();revision++;}
    }
    private boolean canSend(VibeApp.Entry e){return loggedIn()&&e.account.equals(userId)&&VoiceDelivery.local(e.id,e.outgoing,e.chat,e.account)&&e.delivery!=VoiceDelivery.SENT;}
    private void deliveryChanged(VibeApp.Entry e,VoiceDelivery state){e.delivery=state;e.status=state==VoiceDelivery.SENT?"已送达服务器":state==VoiceDelivery.SENDING?"发送中":"发送失败";a.persist();revision++;a.changed();}
    public void retryVoice(VibeApp.Entry e){
        if(!canSend(e)||e.delivery!=VoiceDelivery.RETRY)return;
        int gen=generation;deliveryChanged(e,VoiceDelivery.SENDING);
        a.read(e,pcm->{if(gen!=generation)return;if(pcm==null){deliveryChanged(e,VoiceDelivery.RETRY);return;}if(canSend(e))sendVoice(e,pcm);});
    }
    public void sendVoice(VibeApp.Entry entry,byte[] pcm){
        if(!canSend(entry))return;
        int gen=generation;String cid=entry.chat;deliveryChanged(entry,VoiceDelivery.SENDING);
        io.execute(()->{try{
            JSONObject result=call("POST","/v1/chats/"+cid+"/messages",obj("client_id",entry.id,"kind","voice","audio",Base64.encodeToString(pcm,Base64.NO_WRAP)));
            JSONObject m=result.getJSONObject("message");
            if(!entry.id.equals(m.optString("client_id"))||!cid.equals(m.optString("chat_id"))||!entry.account.equals(m.optString("sender")))throw new IOException("消息确认不匹配");
            a.main.post(()->{if(gen!=generation)return;deliveryChanged(entry,VoiceDelivery.SENT);if(cid.equals(selected))loadHistory();refresh();});
        }catch(Exception e){a.main.post(()->{if(gen!=generation||entry.delivery==VoiceDelivery.SENT)return;deliveryChanged(entry,VoiceDelivery.RETRY);if(e instanceof ApiError)error(e);else a.notice("网络暂时无法连接，语音已保留，可点击重试");if(cid.equals(selected))loadHistory();});}});
    }
    public void retryTranscript(JSONObject message){String cid=message.optString("chat_id");request("POST","/v1/messages/"+message.optLong("id")+"/transcription",obj(),r->{if(cid.equals(selected))loadHistory();refresh();});}
    public String voiceId(JSONObject message){return "cloud:"+userId+":"+message.optLong("id");}
    public void voice(JSONObject message,Consumer<VibeApp.Entry> done){fetchVoice(message,done,false);}
    private void prefetchVoices(){
        int count=0;for(int i=history.length()-1;i>=0&&count<4;i--){JSONObject m=history.optJSONObject(i);if(m!=null&&m.optString("kind").equals("voice")){count++;String id=voiceId(m);VibeApp.Entry e=a.find(id);if(e!=null){a.read(e,bytes->{});continue;}if(prefetching.size()<4&&!prefetching.contains(id)){prefetching.add(id);fetchVoice(m,null,true);}}}
    }
    private void fetchVoice(JSONObject message,Consumer<VibeApp.Entry> done,boolean prefetch){
        String id=voiceId(message);VibeApp.Entry cached=a.find(id);if(cached!=null){prefetching.remove(id);if(done!=null)done.accept(cached);return;}
        // A foreground tap never waits behind speculative downloads or account polling.
        if(!prefetch){ArrayList<Consumer<VibeApp.Entry>> pending=voiceWaiters.get(id);if(pending!=null){pending.add(done);return;}pending=new ArrayList<>();pending.add(done);voiceWaiters.put(id,pending);}
        int gen=generation;String account=userId;
        (prefetch?prefetchIo:voiceIo).execute(()->{try{
            byte[] pcm=exchange("GET","/v1/audio/"+message.optLong("id"),null);if(pcm.length<16000||pcm.length>960000||pcm.length%2!=0)throw new IOException("语音格式不正确");
            a.main.post(()->{prefetching.remove(id);if(gen!=generation){voiceWaiters.remove(id);return;}
                VibeApp.Entry existing=a.find(id);if(existing!=null){finishVoice(id,existing);return;}
                a.add(new Wire.Message(id,message.optString("sender"),message.optString("sender_name"),message.optLong("created"),pcm),message.optString("sender").equals(account),message.optString("sender").equals(account)?"已送达服务器":"未听",entry->{entry.chat=message.optString("chat_id");entry.account=account;entry.delivery=entry.outgoing?VoiceDelivery.SENT:VoiceDelivery.NONE;a.persist();if(gen==generation)finishVoice(id,entry);});
            });
        }catch(Exception e){a.main.post(()->{prefetching.remove(id);if(gen==generation&&!prefetch){error(e);finishVoice(id,null);}});}});
    }
    private void finishVoice(String id,VibeApp.Entry entry){ArrayList<Consumer<VibeApp.Entry>> waiters=voiceWaiters.remove(id);if(waiters!=null)for(Consumer<VibeApp.Entry> waiter:waiters)if(waiter!=null)waiter.accept(entry);}
    public String inviteToken(String code)throws Exception{String[] p=code.trim().split("\\|",-1);if(p.length!=3||!p[0].equals("vibe2")||!p[2].matches("[A-Za-z0-9_-]{43}"))throw new Exception("这不是 Vibe Chat 二维码");if(!validOrigin(p[1]).equals(origin))throw new Exception("二维码属于另一个服务，不能添加到当前账号");return p[2];}
    private static final class ApiError extends Exception{final int code;ApiError(int c,String message){super(message);code=c;}}
}
