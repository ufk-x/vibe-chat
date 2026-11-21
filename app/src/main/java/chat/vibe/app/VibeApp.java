package chat.vibe.app;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import chat.vibe.app.core.Wire;
import chat.vibe.app.core.VoiceDelivery;

public final class VibeApp extends Application {
    public static VibeApp app;
    public final Handler main=new Handler(Looper.getMainLooper());
    public final ArrayList<Runnable> listeners=new ArrayList<>();
    public final ArrayList<Entry> messages=new ArrayList<>();
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private final android.util.LruCache<String,byte[]> audioMemory=new android.util.LruCache<String,byte[]>(8*1024*1024){
        @Override protected int sizeOf(String key,byte[] value){return value.length;}
    };
    public SharedPreferences prefs;
    public CloudSession cloud;
    public boolean cloudMode;
    public String deviceId, connection="尚未开启", headset="未连接耳机", room="体验房间", invite="", notice="";
    public boolean active, connected, hasHeadset, recording, playing, demo=true;
    public int peers, recordSeconds;
    public String playingId="";
    public static final class Entry {
        public String id,name,file,status,chat="",account=""; public boolean outgoing,heard; public long time; public int seconds;
        public VoiceDelivery delivery=VoiceDelivery.NONE;
        public boolean cloudPending(){return VoiceDelivery.local(id,outgoing,chat,account)&&delivery.pending();}
        JSONObject json() throws JSONException {
            JSONObject j=new JSONObject();j.put("id",id);j.put("name",name);j.put("file",file);j.put("status",status);
            j.put("outgoing",outgoing);j.put("heard",heard);j.put("time",time);j.put("seconds",seconds);j.put("chat",chat);j.put("account",account);j.put("delivery",delivery.name());return j;
        }
    }
    @Override public void onCreate() {
        super.onCreate();app=this;prefs=getSharedPreferences("vibe",MODE_PRIVATE);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
            private int started;
            public void onActivityStarted(android.app.Activity activity){if(started++==0)AppUpdater.get().foregroundChanged(true);}
            public void onActivityStopped(android.app.Activity activity){if(--started==0)AppUpdater.get().foregroundChanged(false);}
            public void onActivityCreated(android.app.Activity activity,android.os.Bundle state){}
            public void onActivityResumed(android.app.Activity activity){}
            public void onActivityPaused(android.app.Activity activity){}
            public void onActivitySaveInstanceState(android.app.Activity activity,android.os.Bundle state){}
            public void onActivityDestroyed(android.app.Activity activity){}
        });
        cloud=new CloudSession(this);
        deviceId=prefs.getString("device",null);if(deviceId==null){deviceId=UUID.randomUUID().toString();prefs.edit().putString("device",deviceId).apply();}
        new File(getFilesDir(),"voices").mkdirs();
        disk.execute(()->{
            ArrayList<Entry> restored=new ArrayList<>();
            try {
                File file=new File(getFilesDir(),"history.json");
                if(file.exists()) {
                    JSONArray rows=new JSONArray(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
                    for(int i=0;i<rows.length();i++) {
                        JSONObject j=rows.getJSONObject(i); Entry e=new Entry();e.id=j.getString("id");e.name=j.getString("name");e.file=j.getString("file");
                        if(!e.file.matches("[a-f0-9-]+\\.pcm"))continue;
                        e.status=j.getString("status");if(e.status.equals("发送中"))e.status="未确认送达";
                        e.chat=j.optString("chat");e.account=j.optString("account");
                        e.outgoing=j.getBoolean("outgoing");e.heard=j.optBoolean("heard");e.time=j.getLong("time");e.seconds=j.getInt("seconds");
                        e.delivery=VoiceDelivery.restore(j.optString("delivery"),e.id,e.outgoing,e.chat,e.account,e.status);
                        if(e.delivery==VoiceDelivery.SENT)e.status="已送达服务器";
                        if(new File(getFilesDir(),"voices/"+e.file).exists())restored.add(e);
                    }
                }
            }catch(Exception ignored){}
            main.post(()->{messages.addAll(0,restored);cloud.reconcileVoices(cloud.history);persist();cloud.revision++;changed();});
        });
    }
    public String name(){return prefs.getString("name","Vibe 朋友");}
    public boolean auto(){return prefs.getBoolean("auto",true);}
    public boolean speakerAuto(){return prefs.getBoolean("auto_speaker",false);}
    public boolean autoAllowed(){return auto()&&(hasHeadset||speakerAuto());}
    public boolean overlay(){return prefs.getBoolean("overlay",false);}
    public void changed(){for(Runnable r:new ArrayList<>(listeners))r.run();}
    public void notice(String s){notice=s;changed();}
    public Entry find(String id){for(Entry e:messages)if(e.id.equals(id))return e;return null;}
    public File audioFile(Entry e){return new File(getFilesDir(),"voices/"+e.file);}
    public void add(Wire.Message m,boolean outgoing,String status,java.util.function.Consumer<Entry> done) {
        audioMemory.put(m.id,m.pcm);
        Entry old=find(m.id);if(old!=null){if(outgoing){old.status=status;persist();changed();}if(done!=null)done.accept(old);return;}
        Entry e=new Entry();e.id=m.id;e.name=m.name;e.time=m.time;e.seconds=m.seconds();e.file=UUID.randomUUID()+".pcm";e.outgoing=outgoing;e.status=status;
        disk.execute(()->{
            try {
                Files.write(audioFile(e).toPath(),m.pcm);
                main.post(()->{Entry duplicate=find(e.id);if(duplicate!=null){disk.execute(()->audioFile(e).delete());if(done!=null)done.accept(duplicate);return;}messages.add(e);while(messages.size()>60){Entry removed=messages.remove(0);disk.execute(()->audioFile(removed).delete());}persist();changed();if(done!=null)done.accept(e);});
            }catch(IOException ex){main.post(()->notice("语音保存失败，请检查可用存储空间"));}
        });
    }
    public void read(Entry e,java.util.function.Consumer<byte[]> done) {
        byte[] cached=audioMemory.get(e.id);if(cached!=null){done.accept(cached);return;}
        disk.execute(()->{try{byte[] bytes=Files.readAllBytes(audioFile(e).toPath());main.post(()->{audioMemory.put(e.id,bytes);done.accept(bytes);});}
            catch(IOException ex){main.post(()->{notice("这条语音文件已不可用");done.accept(null);});}});
    }
    public void persist() {
        JSONArray rows=new JSONArray();try{for(Entry e:messages)rows.put(e.json());}catch(JSONException ignored){}
        byte[] data=rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        disk.execute(()->{try{
            File temp=new File(getFilesDir(),"history.tmp");Files.write(temp.toPath(),data);
            Files.move(temp.toPath(),new File(getFilesDir(),"history.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }catch(IOException ignored){}});
    }
    public void clearHistory() {
        audioMemory.evictAll();
        ArrayList<Entry> old=new ArrayList<>(messages);messages.clear();persist();changed();disk.execute(()->{for(Entry e:old)audioFile(e).delete();});
    }
}
