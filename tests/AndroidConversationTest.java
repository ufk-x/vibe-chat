package chat.vibe.app;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import org.json.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;
public class AndroidConversationTest extends Instrumentation {
 CloudActivity activity;Throwable error;JSONObject direct,group;StringBuilder report=new StringBuilder();CountDownLatch release=new CountDownLatch(1);
 interface Work{void run()throws Exception;}
 void ui(Work work){runOnMainSync(()->{try{work.run();}catch(Throwable e){error=e;}});if(error!=null)throw new AssertionError(error);}
 Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 void call(Object o,String n,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);m.invoke(o,args);}
 void check(boolean value,String text){if(!value)throw new AssertionError(text);}
 void open(JSONObject c)throws Exception{call(activity,"open",new Class[]{JSONObject.class},c);}
 void back()throws Exception{call(activity,"closeConversation",new Class[]{});}
 void pause(long ms){SystemClock.sleep(ms);}
 ListView list()throws Exception{return (ListView)field(activity,"messageList");}
 void loaded(String id){for(int i=0;i<60;i++){boolean[] ready={false};ui(()->ready[0]=VibeApp.app.cloud.selected.equals(id)&&VibeApp.app.cloud.historyLoaded&&!VibeApp.app.cloud.historyLoading);if(ready[0])return;pause(100);}throw new AssertionError("History timeout");}
 void pass(String text){report.append("PASS ").append(text).append('\n');}
 public void onCreate(Bundle args){super.onCreate(args);start();}
 public void onStart(){Bundle result=new Bundle();try{
  activity=(CloudActivity)startActivitySync(new Intent(getTargetContext(),CloudActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));pause(1200);
  ui(()->{check(VibeApp.app.cloud.username.startsWith("qa66a_"),"isolated QA account required");JSONArray chats=VibeApp.app.cloud.state.getJSONArray("chats");for(int i=0;i<chats.length();i++){JSONObject c=chats.getJSONObject(i);if(c.optString("kind").equals("direct"))direct=c;if(c.optString("kind").equals("group"))group=c;}check(direct!=null&&group!=null,"QA chats present");});
  if(!Motion.enabled()){
   ui(()->{open(direct);check(!((Motion.PageHost)field(activity,"pageHost")).moving(),"reduced entry immediate");check(((View)field(activity,"root")).getTranslationX()==0,"reduced entry transform");back();check(!((Motion.PageHost)field(activity,"pageHost")).moving(),"reduced back immediate");});
   result.putString("stream","NAVIGATION_REDUCED_PASS\n");finish(-1,result);return;
  }
  ui(()->{call(VibeApp.app.cloud,"clearHistory",new Class[]{});((ExecutorService)field(VibeApp.app.cloud,"io")).execute(()->{try{release.await(7,TimeUnit.SECONDS);}catch(Exception ignored){}});open(direct);check(!VibeApp.app.cloud.historyLoaded,"cold loading");check(((TextView)field(activity,"historyHint")).getText().toString().contains("加载"),"cold is loading not empty chat");});
  pause(70);ui(()->{View root=(View)field(activity,"root");check(root.getTranslationX()>0,"whole page slides");check(((Motion.PageHost)field(activity,"pageHost")).getChildCount()==2,"previous page remains visible");});
  long start=SystemClock.elapsedRealtime();loaded(direct.optString("id"));check(SystemClock.elapsedRealtime()-start<3500,"history not blocked by normal IO");release.countDown();pause(600);
  ui(()->{check(((Motion.PageHost)field(activity,"pageHost")).getChildCount()==1,"old page released");View root=(View)field(activity,"root");android.graphics.Insets safe=root.getRootWindowInsets().getInsets(WindowInsets.Type.systemBars());check(root.getPaddingTop()>=safe.top&&root.getPaddingBottom()>=safe.bottom,"whole-page transition preserves safe insets");});pass("Cold loading is explicit; history bypasses blocked polling/upload queue; whole page transition releases old page");
  ui(()->{JSONArray cached=VibeApp.app.cloud.history;back();open(direct);check(VibeApp.app.cloud.history==cached&&list().getCount()>0,"warm reopen immediately displays cached records");});pause(450);loaded(direct.optString("id"));pass("Warm reopen shows existing messages before network response");
  ui(()->{back();open(direct);back();open(group);});loaded(group.optString("id"));pause(450);
  ui(()->{for(int i=0;i<VibeApp.app.cloud.history.length();i++)check(group.optString("id").equals(VibeApp.app.cloud.history.getJSONObject(i).optString("chat_id")),"late response cannot mix conversations");check(((Motion.PageHost)field(activity,"pageHost")).getChildCount()==1,"rapid navigation leaves one page");});pass("Interrupted push/back and rapid A-to-B switching keep the right conversation");
  ui(()->open(direct));loaded(direct.optString("id"));pause(450);
  ui(()->{View composer=(View)field(activity,"composer");((EditText)composer).setText("draft stays");int bound=(int)field(field(activity,"messageAdapter"),"boundRows");VibeApp.app.cloud.revision++;call(activity,"refresh",new Class[]{});check((int)field(field(activity,"messageAdapter"),"boundRows")==bound,"unchanged revision does not rebuild rows");check(field(activity,"composer")==composer&&((EditText)composer).getText().toString().equals("draft stays"),"draft preserved");((EditText)composer).setText("");});pass("Unchanged state updates retain rows, composer and draft");
  ui(()->{VibeApp.app.listeners.remove((Runnable)field(activity,"observer"));CloudSession s=VibeApp.app.cloud;JSONArray data=new JSONArray();for(int i=0;i<50;i++)data.put(CloudSession.obj("id",800000+i,"sender",i%2==0?s.userId:direct.optString("peer_id"),"sender_name","林间","chat_id",s.selected,"kind","text","text","消息 "+i+"：散步的时候也能聊聊天。","created",System.currentTimeMillis()));s.history=data;s.revision++;call(activity,"refresh",new Class[]{});});pause(250);
  ui(()->{check(list().getCount()==50&&list().getChildCount()<15,"only visible rows attached");list().setSelectionFromTop(10,12);});pause(200);
  int[] position={0,0};long[] anchor={0};
  ui(()->{position[0]=list().getFirstVisiblePosition();position[1]=list().getChildAt(0).getTop();anchor[0]=list().getItemIdAtPosition(position[0]);CloudSession s=VibeApp.app.cloud;JSONArray data=new JSONArray(s.history.toString());data.getJSONObject(49).put("text","远处一条消息更新，不该把我拉到底部。");s.history=data;s.revision++;call(activity,"refresh",new Class[]{});});pause(250);
  ui(()->{check(list().getItemIdAtPosition(list().getFirstVisiblePosition())==anchor[0],"update preserves history anchor");check(Math.abs(list().getChildAt(0).getTop()-position[1])<3,"update preserves pixel offset");});pass("50-message list stays bounded; offscreen update preserves reading position");
  ui(()->{call(VibeApp.app.cloud,"logoutLocal",new Class[]{});check(!VibeApp.app.cloud.historyLoaded&&VibeApp.app.cloud.history.length()==0,"logout clears active records");check(((android.util.LruCache)field(VibeApp.app.cloud,"recentHistory")).size()==0,"logout clears cache");});pass("Logout clears account-scoped history cache");
  result.putString("stream",report+"NAVIGATION_CHECK_PASS\n");finish(-1,result);
 }catch(Throwable e){result.putString("stream",report+"NAVIGATION_CHECK_FAIL "+e+"\n");finish(0,result);}finally{release.countDown();}}
}
