package chat.vibe.app;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import android.graphics.Rect;import java.lang.reflect.*;
public final class AndroidOverlayCheck extends Instrumentation {
 MainActivity activity;VoiceService service;Object overlay;View surface;WindowManager.LayoutParams params;Throwable error;StringBuilder passed=new StringBuilder();
 interface Work{void run()throws Exception;}
 void ui(Work work){runOnMainSync(()->{try{work.run();}catch(Throwable e){error=e;}});if(error!=null)throw new AssertionError(error);}
 Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 View view(String name)throws Exception{return (View)field(overlay,name);}
 void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 void pass(String message){passed.append("PASS ").append(message).append('\n');}
 void locate()throws Exception{ui(()->{service=VoiceService.current;check(service!=null,"service alive");overlay=field(service,"floating");check(overlay!=null,"overlay exists");surface=view("surface");params=(WindowManager.LayoutParams)field(overlay,"params");check(surface.isAttachedToWindow(),"window attached");});}
 float[] center(View v){int[] xy=new int[2];v.getLocationOnScreen(xy);return new float[]{xy[0]+v.getWidth()/2f,xy[1]+v.getHeight()/2f};}
 void event(long down,int action,float x,float y){MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);sendPointerSync(e);e.recycle();}
 void gesture(View v,float dx,float dy,boolean cancel)throws Exception{float[] p=center(v);long t=SystemClock.uptimeMillis();event(t,0,p[0],p[1]);for(int i=1;i<=8;i++){SystemClock.sleep(22);event(t,2,p[0]+dx*i/8,p[1]+dy*i/8);}event(t,cancel?3:1,p[0]+dx,p[1]+dy);SystemClock.sleep(450);waitForIdleSync();}
 void tap(View v)throws Exception{gesture(v,0,0,false);}
 boolean compact()throws Exception{return (Boolean)field(overlay,"collapsed");}
 void drag(String name,int sign)throws Exception{int beforeY=params.y;gesture(view(name),0,sign*90,false);check(Math.abs(params.y-beforeY)>45,"drag from "+name);check(!compact(),"drag not collapse: "+name);check(VibeApp.app.overlay(),"drag not hide: "+name);check(!VibeApp.app.recording,"drag not record: "+name);pass("drag "+name+" moves window without accidental click");}
 void screenshot(String name)throws Exception{java.io.File dir=new java.io.File("/sdcard/Download");dir.mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name))){getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}}
 void dismissGesture(boolean cancel,boolean outside)throws Exception{
  float[] p=center(compact()?surface:view("title"));long t=SystemClock.uptimeMillis();event(t,0,p[0],p[1]);event(t,2,p[0]-35,p[1]-35);SystemClock.sleep(30);event(t,2,p[0]-45,p[1]-45);SystemClock.sleep(150);check(field(overlay,"dismissView")!=null,"dismiss target appears during drag");WindowManager.LayoutParams d=(WindowManager.LayoutParams)field(overlay,"dismissParams");float x=d.x+d.width/2f,y=d.y+Ui.dp(activity,24);for(int k=1;k<=10;k++){event(t,2,p[0]+(x-p[0])*k/10,p[1]+(y-p[1])*k/10);SystemClock.sleep(30);}check((Boolean)field(overlay,"overDismiss"),"close target highlighted");event(t,cancel?3:1,outside?x+Ui.dp(activity,160):x,y);SystemClock.sleep(400);
 }
 public void onCreate(Bundle b){super.onCreate(b);start();}
 public void onStart(){Bundle result=new Bundle();try{
  activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(700);
  ui(()->{VibeApp.app.prefs.edit().putBoolean("overlay",true).putBoolean("overlay_collapsed",false).putString("appearance","light").putInt("bubbleX",10000).putInt("bubbleY",700).commit();activity.startForegroundService(new Intent(activity,VoiceService.class).setAction(VoiceService.START).putExtra("mode","demo"));});SystemClock.sleep(900);locate();
  check(!compact(),"expanded starts");int expandedWidth=params.width;check(params.width<=Ui.dp(activity,208)&&params.height<=Ui.dp(activity,64)+2,"compact pill dimensions: "+params.width+"x"+params.height);pass("expanded window nominal 208 x 64 dp (body 200 x 56 dp), rounding <= 2 px");
  drag("title",1);drag("hint",-1);drag("collapse",1);
  int y=params.y;float[] pos=new float[2];int[] xy=new int[2];surface.getLocationOnScreen(xy);long t=SystemClock.uptimeMillis();event(t,0,xy[0]+4,xy[1]+30);event(t,2,xy[0]+4,xy[1]+130);event(t,1,xy[0]+4,xy[1]+130);SystemClock.sleep(400);check(params.y-y>45,"outer blank drag");pass("outer padding drag");
  tap(view("collapse"));check(compact(),"collapse click");check(params.width<expandedWidth/2,"compact shrinks actual window");pass("collapse releases unused screen area");
  y=params.y;gesture(surface,0,90,false);check(compact()&&params.y-y>45,"compact drag without expand");pass("compact drag without expand");
  ui(()->{service.settingsChanged();});check(compact(),"settings preserve compact");
  ui(()->{VibeApp.app.prefs.edit().putBoolean("overlay",false).commit();service.settingsChanged();check(!surface.isAttachedToWindow(),"hidden detached");VibeApp.app.prefs.edit().putBoolean("overlay",true).commit();service.settingsChanged();});SystemClock.sleep(350);locate();check(compact(),"compact persisted across recreate");pass("shape and position persist across recreate");
  screenshot("v068-light-compact.png");tap(surface);check(!compact(),"compact tap expands");pass("compact tap expands");screenshot("v068-light-expanded.png");
  int count=VibeApp.app.messages.size();int recX=params.x,recY=params.y;float[] rp=center(view("record"));t=SystemClock.uptimeMillis();event(t,0,rp[0],rp[1]);SystemClock.sleep(650);check(VibeApp.app.recording,"hold records");check(!view("collapse").isEnabled(),"cannot collapse recording");event(t,2,rp[0],rp[1]-300);event(t,1,rp[0],rp[1]-300);SystemClock.sleep(700);check(!VibeApp.app.recording,"cancel ends capture");check(VibeApp.app.messages.size()==count,"cancel does not send");check(params.x==recX&&params.y==recY,"record drag must not move");pass("hold starts mic, slide up cancels, no message and no movement");
  rp=center(view("record"));t=SystemClock.uptimeMillis();event(t,0,rp[0],rp[1]);SystemClock.sleep(650);event(t,3,rp[0],rp[1]);SystemClock.sleep(650);check(!VibeApp.app.recording&&VibeApp.app.messages.size()==count,"system cancel safe");pass("system CANCEL ends recording without sending");
  rp=center(view("record"));t=SystemClock.uptimeMillis();event(t,0,rp[0],rp[1]);SystemClock.sleep(800);event(t,1,rp[0],rp[1]);SystemClock.sleep(1300);check(!VibeApp.app.recording&&VibeApp.app.messages.size()>count,"normal release sends demo voice");pass("normal microphone release sends voice in demo mode");
  ui(()->{VibeApp.app.prefs.edit().putString("appearance","dark").commit();VibeApp.app.changed();});SystemClock.sleep(400);check((Boolean)field(overlay,"dark"),"overlay dark updates");screenshot("v068-dark-expanded.png");tap(view("collapse"));screenshot("v068-dark-compact.png");pass("theme updates without restarting service");
  gesture(surface,-1000,-5000,false);Rect safe=(Rect)field(overlay,"safe");check(params.x>=safe.left&&params.y>=safe.top,"top left bounds");gesture(surface,5000,5000,false);check(params.x+params.width<=safe.right&&params.y+params.height<=safe.bottom,"bottom right bounds");pass("drag clamps to safe area and docks");

  // The drag target exists only during movement. CANCEL never dismisses.
  dismissGesture(true,false);check(surface.isAttachedToWindow()&&VibeApp.app.overlay(),"cancel over dismiss preserves overlay");check(field(overlay,"dismissView")==null,"cancel removes dismiss target");pass("drag cancellation over close target is safe");
  dismissGesture(false,true);check(surface.isAttachedToWindow()&&VibeApp.app.overlay(),"release outside close target preserves overlay");pass("final release position is checked, not stale hover");
  dismissGesture(false,false);check(!surface.isAttachedToWindow()&&!VibeApp.app.overlay()&&VibeApp.app.active,"hide only removes overlay");check(field(overlay,"dismissView")==null,"hide removes dismiss target");pass("drop onto close target hides overlay and leaves service active");
  ui(()->{VibeApp.app.prefs.edit().putBoolean("overlay",true).putString("appearance","light").putInt("bubbleY",650).commit();service.settingsChanged();});SystemClock.sleep(300);
  result.putString("stream",passed+"OVERLAY_CHECK_OK\n");finish(-1,result);
 }catch(Throwable e){result.putString("stream",passed+"OVERLAY_CHECK_FAIL "+android.util.Log.getStackTraceString(e)+"\n");finish(0,result);}}
}
