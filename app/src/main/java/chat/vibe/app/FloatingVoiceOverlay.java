package chat.vibe.app;

import android.animation.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

/** A bounded application-overlay window; movement never steals a recording gesture. */
final class FloatingVoiceOverlay {
    private final VoiceService service;
    private final VibeApp app;
    private final WindowManager windows;
    private final WindowManager.LayoutParams params;
    private final Runnable observer=this::update;
    private DragSurface surface;
    private View card,collapse,dot,record,recordFill;
    private TextView title,hint;
    private LinearLayout dismissView;private TextView dismissLabel;private View dismissCircle;
    private WindowManager.LayoutParams dismissParams;private boolean overDismiss;
    private boolean attached,collapsed,dark,configPending;
    private float fontScale;
    private ValueAnimator snap;
    private final Rect safe=new Rect();
    FloatingVoiceOverlay(VoiceService service){
        this.service=service;app=VibeApp.app;windows=(WindowManager)service.getSystemService(Context.WINDOW_SERVICE);
        params=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;params.setTitle("Vibe Chat 对讲悬浮窗");
        collapsed=app.prefs.getBoolean("overlay_collapsed",false);
        params.x=app.prefs.getInt("bubbleX",service.getResources().getDisplayMetrics().widthPixels);params.y=app.prefs.getInt("bubbleY",dp(220));
    }
    private int dp(float n){return Ui.dp(service,n);}
    private void bounds(){
        if(Build.VERSION.SDK_INT>=30){WindowMetrics metrics=windows.getCurrentWindowMetrics();Rect b=metrics.getBounds();Insets in=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());safe.set(in.left,in.top,b.width()-in.right,b.height()-in.bottom);}
        else{android.util.DisplayMetrics m=new android.util.DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(m);safe.set(0,systemSize("status_bar_height"),m.widthPixels,m.heightPixels-systemSize("navigation_bar_height"));}
        safe.inset(dp(4),dp(4));
    }
    private int systemSize(String name){int id=service.getResources().getIdentifier(name,"dimen","android");return id==0?0:service.getResources().getDimensionPixelSize(id);}
    private void constrain(){params.x=Math.max(safe.left,Math.min(params.x,safe.right-params.width));params.y=Math.max(safe.top,Math.min(params.y,safe.bottom-params.height));}
    private void apply(){constrain();if(attached)try{windows.updateViewLayout(surface,params);}catch(IllegalArgumentException ignored){}}
    private void save(){app.prefs.edit().putInt("bubbleX",params.x).putInt("bubbleY",params.y).putBoolean("overlay_collapsed",collapsed).apply();}
    boolean show(){
        render(false);try{windows.addView(surface,params);attached=true;app.listeners.add(observer);update();return true;}catch(Exception e){close();app.notice("悬浮窗未能显示，请在系统设置中允许显示在其他应用上层");return false;}
    }
    void close(){
        stopSnap();removeDismiss();app.listeners.remove(observer);if(surface!=null)surface.animate().cancel();if(attached)try{windows.removeViewImmediate(surface);}catch(IllegalArgumentException ignored){}attached=false;
    }
    void configurationChanged(){removeDismiss();configPending=true;if(app.recording)service.endRecording(true);update();}
    private void mode(boolean compact){if(app.recording||surface.touching||compact==collapsed)return;collapsed=compact;render(true);save();}
    private TextView label(String value,int size,int color,boolean bold){return bold?Ui.bold(service,value,size,color):Ui.text(service,value,size,color);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(service);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private View control(String icon,String description,int color,Runnable click){
        FrameLayout target=new FrameLayout(service);target.addView(new Ui.Icon(service,icon,color),new FrameLayout.LayoutParams(dp(18),dp(18),Gravity.CENTER));
        target.setContentDescription(description);target.setTooltipText(description);Ui.touch(target);target.setOnClickListener(w->click.run());target.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));return target;
    }
    private void render(boolean animate){
        stopSnap();removeDismiss();bounds();boolean right=params.x+params.width/2>=safe.centerX();int centerY=params.y+params.height/2;
        dark=Ui.wantsDark(service);fontScale=service.getResources().getConfiguration().fontScale;configPending=false;
        int ink=dark?0xfff5f5f7:0xff1c1c1e,muted=dark?0xffa9afba:0xff666a73;
        if(surface==null)surface=new DragSurface();else surface.removeAllViews();surface.setPadding(dp(4),dp(4),dp(4),dp(4));
        title=null;record=null;recordFill=null;hint=null;collapse=null;dot=null;
        GradientDrawable material=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,dark?new int[]{0xfc2c3038,0xfa23262e}:new int[]{0xfdffffff,0xfaf6f7fa});material.setCornerRadius(dp(28));material.setStroke(dp(1),dark?0xff424650:0xffffffff);
        if(collapsed){
            FrameLayout compact=new FrameLayout(service);FrameLayout circle=new FrameLayout(service);circle.setBackground(material);circle.setElevation(dp(2));circle.addView(new Ui.Icon(service,"mic",dark?0xffa9ceff:Ui.ACTION),new FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER));compact.addView(circle,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));
            dot=new View(service);FrameLayout.LayoutParams status=new FrameLayout.LayoutParams(dp(5),dp(5),Gravity.TOP|Gravity.RIGHT);status.setMargins(0,dp(5),dp(5),0);circle.addView(dot,status);
            surface.addView(compact,new FrameLayout.LayoutParams(dp(48),dp(48)));card=compact;
            surface.setContentDescription("展开对讲浮条");surface.setOnClickListener(v->mode(false));
        }else{
            LinearLayout pill=new LinearLayout(service);pill.setGravity(Gravity.CENTER_VERTICAL);pill.setPadding(dp(4),dp(4),dp(4),dp(4));pill.setBackground(material);
            FrameLayout mic=new FrameLayout(service);FrameLayout fill=new FrameLayout(service);fill.setBackground(Ui.bg(Ui.ACTION,20));fill.addView(new Ui.Icon(service,"mic",Ui.WHITE),new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));mic.addView(fill,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));Ui.touch(mic);pill.addView(mic,new LinearLayout.LayoutParams(dp(48),dp(48)));record=mic;recordFill=fill;
            Ui.attachTalk(record,()->service.beginRecording(),cancel->service.endRecording(cancel));
            LinearLayout names=column();names.setGravity(Gravity.CENTER_VERTICAL);names.setPadding(dp(7),0,0,0);title=label("",14,ink,true);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);names.addView(title);hint=label("按住麦克风",11,muted,false);hint.setSingleLine(true);hint.setEllipsize(TextUtils.TruncateAt.END);names.addView(hint);pill.addView(names,new LinearLayout.LayoutParams(0,-2,1));
            collapse=control("minimize","收起悬浮窗",muted,()->mode(true));pill.addView(collapse);pill.setElevation(dp(3));
            surface.addView(pill,new FrameLayout.LayoutParams(-1,-2));card=pill;surface.setContentDescription(null);surface.setOnClickListener(null);
        }
        // Dismiss remains available to accessibility users without a drag gesture.
        surface.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS,"隐藏悬浮窗"));}
            @Override public boolean performAccessibilityAction(View host,int action,android.os.Bundle args){if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS&&!app.recording){hideOverlay();return true;}return super.performAccessibilityAction(host,action,args);}
        });
        surface.setClipChildren(false);surface.setClipToPadding(false);
        params.width=Math.min(dp(collapsed?56:208),safe.width());surface.measure(View.MeasureSpec.makeMeasureSpec(params.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(safe.height(),View.MeasureSpec.AT_MOST));params.height=surface.getMeasuredHeight();
        if(attached){params.x=right?safe.right-params.width:safe.left;params.y=centerY-params.height/2;}apply();updateLabels();
        if(animate&&Motion.enabled()){card.setPivotX(right?card.getMeasuredWidth():0);card.setScaleX(.94f);card.setScaleY(.94f);card.setAlpha(.7f);card.animate().scaleX(1).scaleY(1).alpha(1).setDuration(220).setInterpolator(Motion.EASE).start();}
    }
    void update(){if(!attached)return;if(!app.recording&&!surface.touching&&(configPending||dark!=Ui.wantsDark(service)||fontScale!=service.getResources().getConfiguration().fontScale))render(false);else updateLabels();}
    private void updateLabels(){
        String target=app.demo?"回声体验":app.room;
        if(title!=null){title.setText(target);title.setContentDescription("当前回复："+target);title.setTooltipText(target);}
        if(record!=null){recordFill.setBackground(Ui.bg(app.recording?0xffb92532:Ui.ACTION,20));hint.setText(app.recording?app.recordSeconds+"秒 · ↑取消":"按住麦克风");record.setContentDescription(app.recording?"录音 "+app.recordSeconds+" 秒，松开发送，上滑取消":"按住录音，松开发送，向上滑动取消");collapse.setEnabled(!app.recording);collapse.setAlpha(app.recording?.4f:1);}
        if(collapsed){surface.setContentDescription("展开对讲面板，当前回复："+target+(app.playing?"，正在播放语音":""));dot.setVisibility(app.playing?View.VISIBLE:View.GONE);dot.setBackground(Ui.bg(0xff30b969,3));}
    }
    private void hideOverlay(){if(app.recording)return;app.prefs.edit().putBoolean("overlay",false).apply();service.settingsChanged();}
    private void showDismiss(){
        if(dismissView!=null)return;
        dismissView=column();dismissView.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);dismissCircle=new FrameLayout(service);dismissCircle.setBackground(Ui.bg(0xff30343c,24));((FrameLayout)dismissCircle).addView(new Ui.Icon(service,"close",Ui.WHITE),new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER));dismissView.addView(dismissCircle,new LinearLayout.LayoutParams(dp(48),dp(48)));
        dismissLabel=label("拖到这里隐藏",11,Ui.WHITE,false);dismissLabel.setGravity(Gravity.CENTER);dismissLabel.setPadding(dp(8),dp(4),dp(8),dp(4));dismissLabel.setBackground(Ui.bg(0xff30343c,12));LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(-2,-2);labelParams.topMargin=dp(6);dismissView.addView(dismissLabel,labelParams);
        dismissView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        dismissParams=new WindowManager.LayoutParams(dp(128),dp(88),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        dismissParams.gravity=Gravity.TOP|Gravity.LEFT;dismissParams.alpha=.8f;dismissParams.x=safe.centerX()-dismissParams.width/2;dismissParams.y=safe.bottom-dismissParams.height-dp(12);dismissParams.setTitle("Vibe Chat 隐藏目标");
        try{windows.addView(dismissView,dismissParams);}catch(Exception e){dismissView=null;dismissCircle=null;dismissLabel=null;dismissParams=null;}
    }
    private boolean inDismiss(float x,float y){return dismissParams!=null&&Math.hypot(x-(dismissParams.x+dismissParams.width/2f),y-(dismissParams.y+dp(24)))<dp(48);}
    private void trackDismiss(float x,float y){
        showDismiss();if(dismissView==null)return;
        boolean over=inDismiss(x,y);
        if(over!=overDismiss){overDismiss=over;dismissCircle.setBackground(Ui.bg(over?0xffb92532:0xff30343c,24));dismissLabel.setText(over?"松手隐藏":"拖到这里隐藏");if(over)surface.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);}
    }
    private void removeDismiss(){if(dismissView!=null){try{windows.removeViewImmediate(dismissView);}catch(IllegalArgumentException ignored){}dismissView=null;dismissCircle=null;dismissLabel=null;dismissParams=null;}overDismiss=false;}
    private void stopSnap(){if(snap!=null){snap.cancel();snap=null;}}
    private void dock(){
        int from=params.x,to=from+params.width/2>=safe.centerX()?safe.right-params.width:safe.left;
        if(!Motion.enabled()||from==to){params.x=to;apply();save();return;}
        snap=ValueAnimator.ofInt(from,to);snap.setDuration(220);snap.setInterpolator(Motion.EASE);snap.addUpdateListener(a->{params.x=(int)a.getAnimatedValue();apply();});snap.addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){snap=null;save();}});snap.start();
    }
    private final class DragSurface extends FrameLayout {
        private final int slop=ViewConfiguration.get(service).getScaledTouchSlop();
        private float startX,startY;private int originX,originY,pointer;private boolean drag,recordGesture,touching;
        DragSurface(){super(service);setFocusable(true);}
        @Override public boolean dispatchTouchEvent(MotionEvent e){
            int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){touching=true;stopSnap();startX=e.getRawX();startY=e.getRawY();originX=params.x;originY=params.y;pointer=e.getPointerId(0);drag=false;recordGesture=app.recording;
                if(record!=null){int[] xy=new int[2];record.getLocationOnScreen(xy);recordGesture|=startX>=xy[0]&&startX<xy[0]+record.getWidth()&&startY>=xy[1]&&startY<xy[1]+record.getHeight();}}
            // Click listeners run on UP, and are allowed to change the shape after this gesture.
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)touching=false;
            boolean handled=super.dispatchTouchEvent(e);
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)post(FloatingVoiceOverlay.this::update);
            return handled;
        }
        private boolean moved(MotionEvent e){return !recordGesture&&!app.recording&&e.getPointerId(0)==pointer&&Math.hypot(e.getRawX()-startX,e.getRawY()-startY)>slop;}
        @Override public boolean onInterceptTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&moved(e)){drag=true;return true;}return false;}
        @Override public boolean onTouchEvent(MotionEvent e){
            if(recordGesture)return true;
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&(drag||moved(e))){drag=true;params.x=originX+Math.round(e.getRawX()-startX);params.y=originY+Math.round(e.getRawY()-startY);apply();trackDismiss(e.getRawX(),e.getRawY());return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP){boolean dismiss=drag&&inDismiss(e.getRawX(),e.getRawY());removeDismiss();if(dismiss)hideOverlay();else if(drag)dock();else performClick();return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){removeDismiss();if(drag)dock();return true;}return true;
        }
        @Override public boolean performClick(){return super.performClick();}
    }
}
