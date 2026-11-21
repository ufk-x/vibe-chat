package chat.vibe.app;

import android.content.*;
import android.content.pm.*;
import android.os.Build;
import android.net.Uri;
import chat.vibe.app.core.UpdateArtifact;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Public HTTPS releases; installed package signature remains the final trust anchor. */
public final class AppUpdater {
    private static AppUpdater instance;
    public static synchronized AppUpdater get(){if(instance==null)instance=new AppUpdater(VibeApp.app);return instance;}
    private final VibeApp a;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    public final ArrayList<Runnable> listeners=new ArrayList<>();
    public JSONObject release;
    public boolean busy,ready;
    public int progress;
    public String status="点击检查新版本";
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;
    private static final String CACHED_RELEASE="update_release";
    private static final long CHECK_INTERVAL=30*60*1000L,RETRY_INTERVAL=5*60*1000L;
    private boolean foreground;
    private long nextAutomaticCheck;
    private final Runnable automaticTick=()->{if(!foreground)return;if(!busy&&android.os.SystemClock.elapsedRealtime()>=nextAutomaticCheck)check(true);scheduleAutomatic();};
    private AppUpdater(VibeApp app){a=app;discardLegacy();restoreAnnouncement();}
    private void restoreAnnouncement(){
        try{JSONObject cached=new JSONObject(a.prefs.getString(CACHED_RELEASE,""));validateRelease(cached);release=cached;status=releaseStatus(cached,false);}
        catch(Exception ignored){a.prefs.edit().remove(CACHED_RELEASE).apply();}
    }
    /** Called only by the application lifecycle, not by individual tab switches. */
    public void foregroundChanged(boolean visible){foreground=visible;a.main.removeCallbacks(automaticTick);if(visible)automaticTick.run();}
    private void scheduleAutomatic(){a.main.removeCallbacks(automaticTick);if(foreground)a.main.postDelayed(automaticTick,busy?60000:Math.max(1000,nextAutomaticCheck-android.os.SystemClock.elapsedRealtime()));}
    private String releaseStatus(JSONObject r,boolean cached){return r.optLong("versionCode")<=installedCode()?"已经是最新版本":r.optInt("minSdk",26)>Build.VERSION.SDK_INT?"此版本需要更新的安卓系统":cached?"安装包已就绪":"发现新版本 "+r.optString("versionName");}
    private void validateRelease(JSONObject r)throws Exception{
        if(r.getLong("versionCode")<=0||r.getString("versionName").isEmpty()||!r.getString("sha256").matches("[a-f0-9]{64}")||r.getLong("size")<10000||r.getLong("size")>64*1024*1024||r.optInt("minSdk",26)<1)throw new IOException("版本信息无效");
        URI url=new URI(r.getString("url")),trusted=new URI(CloudSession.defaultOrigin());
        if(!"https".equals(url.getScheme())||!trusted.getHost().equals(url.getHost())||url.getPort()!=trusted.getPort()||url.getUserInfo()!=null||url.getFragment()!=null||url.getQuery()!=null||!url.getPath().startsWith("/downloads/")||!url.getPath().endsWith(".apk"))throw new IOException("更新地址不可信");
    }
    public long installedCode(){try{return code(a.getPackageManager().getPackageInfo(a.getPackageName(),0));}catch(Exception e){return 0;}}
    public String installedName(){try{return a.getPackageManager().getPackageInfo(a.getPackageName(),0).versionName;}catch(Exception e){return "未知";}}
    private static long code(PackageInfo p){return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
    private void changed(){for(Runnable r:new ArrayList<>(listeners))r.run();}
    private File directory(){return new File(a.getFilesDir(),"updates");}
    private File apk(JSONObject r)throws JSONException{return new File(directory(),UpdateArtifact.name(r.getLong("versionCode"),r.getString("sha256")));}
    private void discardLegacy(){
        a.revokeUriPermission(Uri.parse("content://chat.vibe.app.updates/release.apk"),Intent.FLAG_GRANT_READ_URI_PERMISSION);
        for(String name:new String[]{"release.apk","release.json","release.part"})new File(directory(),name).delete();
    }
    private void prune(File keep){File[] files=directory().listFiles();if(files!=null)for(File f:files)if(!f.equals(keep)&&(UpdateArtifact.validName(f.getName())||f.getName().endsWith(".part")))f.delete();}
    private HttpURLConnection connect(String url)throws Exception{
        URI u=new URI(url);URI trusted=new URI(CloudSession.defaultOrigin());
        if(!"https".equals(u.getScheme())||!trusted.getHost().equals(u.getHost())||u.getPort()!=trusted.getPort()||u.getUserInfo()!=null||u.getFragment()!=null)throw new IOException("更新地址不可信");
        HttpURLConnection c=(HttpURLConnection)u.toURL().openConnection();connection=c;c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("Cache-Control","no-cache");
        if(c.getResponseCode()!=200){c.disconnect();throw new IOException("更新服务暂时不可用，请稍后重试");}return c;
    }
    public void check(){check(false);}
    private void check(boolean automatic){if(busy)return;busy=true;ready=false;cancelled=false;status="正在检查更新…";progress=0;nextAutomaticCheck=android.os.SystemClock.elapsedRealtime()+CHECK_INTERVAL;changed();
        android.util.Log.d("VibeUpdate",automatic?"automatic release check":"manual release check");
        io.execute(()->{try{
            HttpURLConnection c=connect(CloudSession.defaultOrigin()+"/downloads/latest.json");byte[] data;
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>16384)throw new IOException("版本信息无效");out.write(b,0,n);}data=out.toByteArray();}finally{c.disconnect();connection=null;}
            JSONObject r=new JSONObject(new String(data,StandardCharsets.UTF_8));
            if(cancelled)throw new IOException("检查已取消");validateRelease(r);
            File target=apk(r);prune(target);boolean cached=false;
            if(r.getLong("versionCode")>installedCode()&&target.exists())try{verify(target,r);cached=true;}catch(Exception ignored){target.delete();}
            final boolean found=cached;a.main.post(()->{release=r;ready=found;busy=false;status=releaseStatus(r,found);a.prefs.edit().putString(CACHED_RELEASE,r.toString()).apply();nextAutomaticCheck=android.os.SystemClock.elapsedRealtime()+CHECK_INTERVAL;changed();scheduleAutomatic();});
        }catch(Exception e){a.main.post(()->{busy=false;status=cancelled?"检查已取消":message(e);nextAutomaticCheck=android.os.SystemClock.elapsedRealtime()+RETRY_INTERVAL;changed();scheduleAutomatic();});}});
    }
    public boolean available(){return release!=null&&release.optLong("versionCode")>installedCode()&&release.optInt("minSdk",26)<=Build.VERSION.SDK_INT;}
    public void cancel(){if(!busy)return;cancelled=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    public void download(){if(busy||!available())return;JSONObject r=release;busy=true;ready=false;cancelled=false;progress=0;status="正在下载安装包…";changed();
        io.execute(()->{File part=null;try{
            File target=apk(r);part=new File(directory(),target.getName()+".part");directory().mkdirs();HttpURLConnection c=connect(r.getString("url"));long expected=r.getLong("size"),total=0;int percent=-1;
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(part)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(cancelled)throw new IOException("下载已取消");total+=n;if(total>expected)throw new IOException("安装包大小不匹配");out.write(b,0,n);int next=(int)(total*100/expected);if(next!=percent){percent=next;int value=next;a.main.post(()->{progress=value;changed();});}}}finally{c.disconnect();connection=null;}
            if(cancelled)throw new IOException("下载已取消");verify(part,r);Files.move(part.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);prune(target);discardLegacy();
            a.main.post(()->{busy=false;ready=true;status="下载完成，准备安装";progress=100;changed();});
        }catch(Exception e){if(part!=null)part.delete();a.main.post(()->{busy=false;ready=false;status=cancelled?"下载已取消，可重新下载":message(e);changed();});}});
    }
    /** Recheck the bytes and manifest immediately before handing them to the installer. */
    public void prepareInstall(java.util.function.Consumer<Uri> done){
        if(busy||!ready){done.accept(null);return;}
        if(!available()){ready=false;status="已经是最新版本";changed();done.accept(null);return;}
        JSONObject r=release;busy=true;ready=false;cancelled=false;progress=0;status="正在校验安装包…";changed();
        io.execute(()->{try{
            File target=apk(r);verify(target,r);Uri uri=new Uri.Builder().scheme("content").authority("chat.vibe.app.updates").appendPath(target.getName()).build();
            a.main.post(()->{busy=false;ready=true;status=cancelled?"已取消安装，可再次安装":"安装包已确认："+r.optString("versionName");changed();done.accept(cancelled?null:uri);});
        }catch(Exception e){try{apk(r).delete();}catch(Exception ignored){}a.main.post(()->{busy=false;ready=false;status="安装包已失效，请重新下载";changed();done.accept(null);});}});
    }
    private void verify(File file,JSONObject r)throws Exception{
        if(file.length()!=r.getLong("size"))throw new IOException("安装包不完整，请重新下载");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));if(!hash.toString().equals(r.getString("sha256")))throw new IOException("安装包校验失败，请重新下载");
        PackageManager pm=a.getPackageManager();PackageInfo incoming=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNATURES),current=pm.getPackageInfo(a.getPackageName(),PackageManager.GET_SIGNATURES);
        if(incoming==null||!a.getPackageName().equals(incoming.packageName)||code(incoming)!=r.getLong("versionCode")||!r.getString("versionName").equals(incoming.versionName)||code(incoming)<=code(current)||!Arrays.equals(incoming.signatures,current.signatures))throw new IOException("安装包版本或签名不匹配");
    }
    private static String message(Exception e){return e instanceof IOException&&e.getMessage()!=null&&!e.getMessage().contains("http")?e.getMessage():"更新失败，请检查网络后重试";}
}
