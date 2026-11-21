package chat.vibe.app;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.content.pm.ActivityInfo;import android.graphics.Rect;import java.lang.reflect.*;
public final class AndroidOverlayConfigCheck extends Instrumentation {
 MainActivity activity;VoiceService service;Object overlay;View surface;WindowManager.LayoutParams params;Throwable error;StringBuilder passed=new StringBuilder();
 interface Work{void run()throws Exception;}
 void ui(Work work){runOnMainSync(()->{try{work.run();}catch(Throwable e){error=e;}});if(error!=null)throw new AssertionError(error);}
 Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 void check(boolean c,String msg){if(!c)throw new AssertionError(msg);}
 void pass(String m){passed.append("PASS ").append(m).append('\n');}
 void shell(String command)throws Exception{try(ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);java.io.FileInputStream in=new java.io.FileInputStream(fd.getFileDescriptor())){byte[] bytes=new byte[1024];while(in.read(bytes)>0){}}SystemClock.sleep(1000);}
 void bounds()throws Exception{Rect safe=(Rect)field(overlay,"safe");check(params.x>=safe.left&&params.y>=safe.top&&params.x+params.width<=safe.right&&params.y+params.height<=safe.bottom,"window within safe area "+safe+" actual "+params);}
 void tap(View v){int[] xy=new int[2];v.getLocationOnScreen(xy);long t=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(t,t,0,xy[0]+v.getWidth()/2,xy[1]+v.getHeight()/2,0);sendPointerSync(down);MotionEvent up=MotionEvent.obtain(t,t+50,1,down.getRawX(),down.getRawY(),0);sendPointerSync(up);down.recycle();up.recycle();SystemClock.sleep(400);}
 void screenshot(String name)throws Exception{try(java.io.FileOutputStream out=new java.io.FileOutputStream("/sdcard/Download/"+name)){getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}}
 public void onCreate(Bundle b){super.onCreate(b);start();}
 public void onStart(){Bundle result=new Bundle();try{
  activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(500);
  ui(()->{VibeApp.app.prefs.edit().putBoolean("overlay",true).putBoolean("overlay_collapsed",false).putString("appearance","system").putInt("bubbleX",10000).putInt("bubbleY",400).commit();activity.startForegroundService(new Intent(activity,VoiceService.class).setAction(VoiceService.START).putExtra("mode","demo"));});SystemClock.sleep(600);
  ui(()->{service=VoiceService.current;overlay=field(service,"floating");surface=(View)field(overlay,"surface");params=(WindowManager.LayoutParams)field(overlay,"params");activity.moveTaskToBack(true);});shell("input keyevent KEYCODE_HOME");
  screenshot("v068-light-expanded.png");tap((View)field(overlay,"collapse"));screenshot("v068-light-compact.png");tap(surface);
  shell("cmd uimode night yes");check((Boolean)field(overlay,"dark"),"system dark while app in background");screenshot("v068-dark-expanded.png");tap((View)field(overlay,"collapse"));screenshot("v068-dark-compact.png");tap(surface);pass("system dark follows in background, both shapes");
  shell("settings put system font_scale 1.5");check((Float)field(overlay,"fontScale")>1.4f,"large type update");bounds();screenshot("v068-large-text.png");pass("1.5x text grows overlay and remains within screen");
  activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(500);shell("settings put system accelerometer_rotation 0");shell("settings put system user_rotation 1");bounds();check(service.getResources().getDisplayMetrics().widthPixels>service.getResources().getDisplayMetrics().heightPixels,"landscape applied");screenshot("v068-landscape.png");pass("landscape safe bounds");
  shell("settings put system user_rotation 0");shell("settings put system font_scale 1.0");shell("cmd uimode night no");
  ui(()->{VibeApp.app.demo=false;VibeApp.app.room="这是一条很长的好友备注和群聊名称，用来检查显示";VibeApp.app.changed();});SystemClock.sleep(350);android.widget.TextView title=(android.widget.TextView)field(overlay,"title");check(title.getLayout().getEllipsisCount(0)>0,"long name ellipsis");check(title.getTooltipText().toString().equals(VibeApp.app.room),"full name accessible");bounds();pass("long target ellipsized with full tooltip and accessibility label");
  shell("settings put global animator_duration_scale 0");tap((View)field(overlay,"collapse"));check((Boolean)field(overlay,"collapsed"),"collapse with zero animation");check(((View)field(overlay,"card")).getAlpha()==1,"no stale alpha");tap(surface);pass("system disabled animations render final shapes");shell("settings put global animator_duration_scale 1");
  ui(()->{VibeApp.app.demo=true;VibeApp.app.changed();});
  // Rotation must cancel an active capture even while the overlay owns the touch.
  View record=(View)field(overlay,"record");int[] xy=new int[2];record.getLocationOnScreen(xy);long t=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(t,t,0,xy[0]+record.getWidth()/2,xy[1]+record.getHeight()/2,0);sendPointerSync(down);SystemClock.sleep(600);check(VibeApp.app.recording,"capture before rotation");int before=VibeApp.app.messages.size();shell("settings put system user_rotation 1");MotionEvent cancel=MotionEvent.obtain(t,SystemClock.uptimeMillis(),3,down.getRawX(),down.getRawY(),0);sendPointerSync(cancel);down.recycle();cancel.recycle();SystemClock.sleep(600);check(!VibeApp.app.recording&&VibeApp.app.messages.size()==before,"rotation cancels no send");pass("rotation cancels capture without sending");shell("settings put system user_rotation 0");bounds();
  result.putString("stream",passed+"OVERLAY_CONFIG_OK\n");finish(-1,result);
 }catch(Throwable e){result.putString("stream",passed+"OVERLAY_CONFIG_FAIL "+android.util.Log.getStackTraceString(e)+"\n");finish(0,result);}}
}
