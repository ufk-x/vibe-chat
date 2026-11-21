package chat.vibe.app;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import chat.vibe.app.core.UpdateArtifact;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Signed instrumentation fixture; never shipped in the application APK. */
public final class AndroidUpdateTest extends Instrumentation {
    private int checks;
    private void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
    private byte[] asset(String name)throws Exception{try(InputStream in=getContext().getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    private String hash(byte[] bytes)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private Uri prepare(AppUpdater updater,JSONObject release)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Uri> result=new AtomicReference<>();
        runOnMainSync(()->{updater.release=release;updater.ready=true;updater.prepareInstall(uri->{result.set(uri);done.countDown();});});
        check(done.await(15,TimeUnit.SECONDS),"install preparation completes");return result.get();
    }
    private void denied(Uri uri)throws Exception{try{getTargetContext().getContentResolver().openInputStream(uri).close();throw new AssertionError("invalid URI was accepted");}catch(SecurityException|FileNotFoundException expected){checks++;}}
    private AppUpdater restored(JSONObject value)throws Exception{
        VibeApp.app.prefs.edit().putString("update_release",value.toString()).commit();
        java.lang.reflect.Constructor<AppUpdater> constructor=AppUpdater.class.getDeclaredConstructor(VibeApp.class);constructor.setAccessible(true);return constructor.newInstance(VibeApp.app);
    }
    private void announcements(JSONObject release)throws Exception{
        String previous=VibeApp.app.prefs.getString("update_release",null);
        try{
            AppUpdater cached=restored(release);check(cached.available()&&!cached.ready&&!cached.busy,"cached future release restores badge without trusting or downloading APK");
            JSONObject installed=new JSONObject(release.toString());installed.put("versionCode",cached.installedCode());check(!restored(installed).available(),"badge disappears when the announced version is installed");
            JSONObject incompatible=new JSONObject(release.toString());incompatible.put("minSdk",android.os.Build.VERSION.SDK_INT+1);check(!restored(incompatible).available(),"unsupported Android release has no actionable badge");
            JSONObject invalid=new JSONObject(release.toString());invalid.put("sha256","invalid");check(restored(invalid).release==null,"invalid cached release is rejected");
            JSONObject foreign=new JSONObject(release.toString());foreign.put("url","https://example.com/downloads/app.apk");check(restored(foreign).release==null,"foreign cached download origin is rejected");
            JSONObject credentials=new JSONObject(release.toString());credentials.put("url",release.getString("url")+"?token=bad");check(restored(credentials).release==null,"cached URL with query is rejected");
            java.lang.reflect.Field due=AppUpdater.class.getDeclaredField("nextAutomaticCheck"),tick=AppUpdater.class.getDeclaredField("automaticTick");due.setAccessible(true);tick.setAccessible(true);due.setLong(cached,android.os.SystemClock.elapsedRealtime()+1800000);
            Runnable callback=(Runnable)tick.get(cached);
            runOnMainSync(()->{cached.foregroundChanged(true);check(!cached.busy,"returning to foreground within interval does not recheck");check(VibeApp.app.main.hasCallbacks(callback),"foreground schedules the next check");cached.foregroundChanged(false);check(!VibeApp.app.main.hasCallbacks(callback),"background removes the periodic callback");});
            due.setLong(cached,0);runOnMainSync(()->{cached.busy=true;cached.foregroundChanged(true);check(cached.status.startsWith("发现新版本"),"ongoing download or check is not replaced by another automatic check");cached.foregroundChanged(false);cached.busy=false;});
        }finally{if(previous==null)VibeApp.app.prefs.edit().remove("update_release").commit();else VibeApp.app.prefs.edit().putString("update_release",previous).commit();}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context context=getTargetContext();File dir=new File(context.getFilesDir(),"updates");dir.mkdirs();byte[] current=asset("new.apk"),old=asset("old.apk");
        JSONObject release=new JSONObject(new String(asset("release.json"),"UTF-8"));String name=UpdateArtifact.name(release.getLong("versionCode"),hash(current));File target=new File(dir,name);
        // Simulate leftover 0.4.0 files from an earlier installation.
        Files.write(new File(dir,"release.apk").toPath(),old);Files.write(new File(dir,"release.json").toPath(),"{}".getBytes("UTF-8"));
        AtomicReference<AppUpdater> ref=new AtomicReference<>();runOnMainSync(()->ref.set(AppUpdater.get()));AppUpdater updater=ref.get();
        check(!new File(dir,"release.apk").exists()&&!new File(dir,"release.json").exists(),"legacy install cache removed on first use");
        Files.write(target.toPath(),current);Uri uri=prepare(updater,release);check(uri!=null&&name.equals(uri.getLastPathSegment()),"install URI identifies actual release");
        check(!uri.toString().endsWith("/release.apk"),"legacy URI never reused");
        try(InputStream in=context.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);check(hash(out.toByteArray()).equals(release.getString("sha256")),"installer reads exactly verified new APK bytes");}
        try(Cursor c=context.getContentResolver().query(uri,null,null,null,null)){check(c.moveToFirst()&&name.equals(c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))&&c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))==current.length,"installer metadata identifies new file");}
        denied(Uri.parse("content://chat.vibe.app.updates/release.apk"));denied(Uri.parse("content://chat.vibe.app.updates/../release.apk"));denied(Uri.parse("content://chat.vibe.app.updates/%2e%2e%2frelease.apk"));denied(uri.buildUpon().appendQueryParameter("old","1").build());
        try{context.getContentResolver().openFileDescriptor(uri,"rw").close();throw new AssertionError("write accepted");}catch(SecurityException expected){checks++;}
        Files.write(target.toPath(),old);check(prepare(updater,release)==null&&!updater.ready&&!target.exists(),"replaced/stale old APK rejected at install time and discarded");
        Files.write(target.toPath(),current);JSONObject wrongName=new JSONObject(release.toString());wrongName.put("versionName","0.4.0");check(prepare(updater,wrongName)==null,"internal versionName must match release manifest");
        JSONObject wrongCode=new JSONObject(release.toString());wrongCode.put("versionCode",release.getLong("versionCode")+1);File wrong=new File(dir,UpdateArtifact.name(wrongCode.getLong("versionCode"),hash(current)));Files.write(wrong.toPath(),current);check(prepare(updater,wrongCode)==null,"internal versionCode must match release manifest");
        check(!name.equals(UpdateArtifact.name(release.getLong("versionCode")+1,hash(current))),"next release gets distinct identity");check(!name.equals(UpdateArtifact.name(release.getLong("versionCode"),hash(old))),"different bytes get distinct identity");
        announcements(release);
        // Leave a valid future release for the subsequent UI/system installer check.
        Files.write(target.toPath(),current);check(prepare(updater,release)!=null,"valid downloaded package can be retried");
        result.putString("stream","UPDATE_REGRESSION_PASS "+checks+" checks\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","UPDATE_REGRESSION_FAIL "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
