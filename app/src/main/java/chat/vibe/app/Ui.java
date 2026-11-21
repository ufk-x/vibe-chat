package chat.vibe.app;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.util.function.Consumer;

public final class Ui {
    // Semantic surfaces and labels adapt together. WHITE is always foreground on a filled control.
    public static final int WHITE=0xffffffff,DARK=0xff182333,ON_DARK_SECONDARY=0xffc0d2e7,BADGE=0xffff3b30;
    public static int BG,SURFACE,RAISED,GREEN,ACTION,LIME,INK,MUTED,LINE,RED,SWITCH_OFF;
    public static boolean dark;
    static { palette(false); }
    public static boolean wantsDark(Context c){
        String mode=c.getSharedPreferences("vibe",Context.MODE_PRIVATE).getString("appearance","system");
        return mode.equals("dark") || (!mode.equals("light") && (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES);
    }
    public static String appearanceName(Context c){String mode=c.getSharedPreferences("vibe",Context.MODE_PRIVATE).getString("appearance","system");return mode.equals("dark")?"深色":mode.equals("light")?"浅色":"跟随系统";}
    public static void palette(boolean night){
        dark=night;
        BG=night?0xff0c0e12:0xfff2f2f7;
        SURFACE=night?0xff1c1f26:0xffffffff;
        RAISED=night?0xff2b3039:0xffe9edf4;
        INK=night?0xfff5f5f7:0xff1c1c1e;
        MUTED=night?0xffa9afba:0xff666a73;
        GREEN=night?0xff78b6ff:0xff005fc9;
        ACTION=0xff0064d8;
        LIME=night?0xff172b44:0xffe7f0ff;
        LINE=night?0xff353a44:0xffe3e5eb;
        RED=night?0xffff8a90:0xffb92532;
        SWITCH_OFF=night?0xff555b65:0xffb4b9c2;
    }
    public static int dp(Context c,float value){return (int)(value*c.getResources().getDisplayMetrics().density+.5f);}
    public static GradientDrawable bg(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius*android.content.res.Resources.getSystem().getDisplayMetrics().density);return d;}
    public static GradientDrawable glass(){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,dark?new int[]{0xfa2b3039,0xf522252d}:new int[]{0xfcffffff,0xf5f5f7fc});d.setCornerRadius(dp(VibeApp.app,36));d.setStroke(dp(VibeApp.app,1),dark?0xff454a54:WHITE);return d;}
    public static void touch(View v){v.setMinimumHeight(dp(v.getContext(),48));v.setFocusable(true);Motion.press(v);}
    public static TextView text(Context c,String value,float size,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("kern");return t;}
    public static TextView bold(Context c,String value,float size,int color){TextView t=text(c,value,size,color);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    public static void attachTalk(View v,Runnable start,Consumer<Boolean> finish){
        v.setContentDescription("按住录音，松开发送，向上滑动取消");v.setOnTouchListener(new View.OnTouchListener(){float downY;boolean down,cancel;
            public boolean onTouch(View view,MotionEvent e){
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:down=true;cancel=false;view.setPressed(true);downY=e.getRawY();view.getParent().requestDisallowInterceptTouchEvent(true);view.setAlpha(.78f);view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);start.run();return true;
                    case MotionEvent.ACTION_MOVE:cancel=downY-e.getRawY()>dp(view.getContext(),72);view.setAlpha(cancel?.42f:.78f);return true;
                    case MotionEvent.ACTION_UP:if(down){down=false;view.setPressed(false);view.setAlpha(1);finish.accept(cancel);view.getParent().requestDisallowInterceptTouchEvent(false);view.performClick();}return true;
                    case MotionEvent.ACTION_CANCEL:if(down){down=false;view.setPressed(false);view.setAlpha(1);finish.accept(true);view.getParent().requestDisallowInterceptTouchEvent(false);}return true;
                    default:return true;
                }
            }
        });
    }
    public static TextView avatar(Context c,String name,String kind,int size){
        int[] fills=dark?new int[]{0xff302b48,0xff203e36,0xff463425,0xff24374f,0xff442b3b}:new int[]{0xffebe8ff,0xffdff4ee,0xffffeddf,0xffe2edff,0xfff8e3ee};
        int[] inks=dark?new int[]{0xffc3b5ff,0xffa0d6c4,0xfff1c59d,0xffa8caff,0xffedb3d5}:new int[]{0xff6550a8,0xff286c5c,0xff87552e,0xff38649b,0xff924b72};
        int index=Math.floorMod(name.hashCode(),fills.length);
        String value=kind.equals("room")?"#":kind.equals("group")?"群":(name.isEmpty()?"V":name.substring(0,name.offsetByCodePoints(0,1)));
        TextView t=bold(c,value,size*.36f,inks[index]);t.setGravity(Gravity.CENTER);t.setBackground(bg(fills[index],size*.5f));t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return t;
    }
    public static final class Icon extends View {
        private final String kind;private final int color;private final Paint p=new Paint(3);
        public Icon(Context c,String kind,int color){super(c);this.kind=kind;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float scale=Math.min(getWidth(),getHeight())/24f;c.save();c.translate((getWidth()-24*scale)/2,(getHeight()-24*scale)/2);c.scale(scale,scale);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            if(kind.equals("chat")){c.drawRoundRect(3,4,21,18,5,5,p);c.drawLine(7,18,6,21,p);c.drawLine(6,21,12,18,p);c.drawLine(8,10,16,10,p);c.drawLine(8,13,13,13,p);}
            else if(kind.equals("friends")){c.drawCircle(9,8,3,p);c.drawArc(3,13,15,24,180,180,false,p);c.drawArc(14,5,20,11,270,170,false,p);c.drawArc(14,14,22,24,260,100,false,p);}
            else if(kind.equals("mic")){c.drawRoundRect(9,3,15,14,3,3,p);c.drawArc(6,8,18,19,0,180,false,p);c.drawLine(12,19,12,22,p);c.drawLine(9,22,15,22,p);}
            else if(kind.equals("me")){c.drawCircle(12,8,3.5f,p);c.drawArc(4,14,20,28,180,180,false,p);}
            else if(kind.equals("scan")){for(int i=0;i<4;i++){c.save();c.rotate(i*90,12,12);c.drawLine(3,8,3,3,p);c.drawLine(3,3,8,3,p);c.restore();}c.drawLine(7,12,17,12,p);}
            else if(kind.equals("qr")){p.setStrokeWidth(1.6f);c.drawRoundRect(3,3,9,9,1,1,p);c.drawRoundRect(15,3,21,9,1,1,p);c.drawRoundRect(3,15,9,21,1,1,p);p.setStyle(Paint.Style.FILL);c.drawRect(5,5,7,7,p);c.drawRect(17,5,19,7,p);c.drawRect(5,17,7,19,p);c.drawRect(13,13,16,16,p);c.drawRect(18,13,21,16,p);c.drawRect(15,16,18,19,p);c.drawRect(19,19,22,22,p);c.drawRect(12,20,15,23,p);}
            else if(kind.equals("paste")){c.drawRoundRect(5,5,19,22,2,2,p);c.drawRoundRect(8,2,16,7,1.5f,1.5f,p);c.drawLine(9,12,15,12,p);c.drawLine(9,16,14,16,p);}
            else if(kind.equals("edit")){c.drawLine(5,16,16,5,p);c.drawLine(16,5,20,9,p);c.drawLine(20,9,9,20,p);c.drawLine(9,20,4,21,p);c.drawLine(4,21,5,16,p);c.drawLine(13,8,17,12,p);}
            else if(kind.equals("exit")){c.drawLine(9,4,4,4,p);c.drawLine(4,4,4,20,p);c.drawLine(4,20,9,20,p);c.drawLine(10,12,22,12,p);c.drawLine(18,8,22,12,p);c.drawLine(22,12,18,16,p);}
            else if(kind.equals("trash")){c.drawLine(4,6,20,6,p);c.drawLine(9,3,15,3,p);c.drawLine(6,6,7,21,p);c.drawLine(7,21,17,21,p);c.drawLine(17,21,18,6,p);c.drawLine(10,10,10,17,p);c.drawLine(14,10,14,17,p);}
            else if(kind.equals("minimize")){c.drawLine(5,12,19,12,p);}
            else if(kind.equals("close")){c.drawLine(6,6,18,18,p);c.drawLine(18,6,6,18,p);}
            else if(kind.equals("plus")){c.drawLine(5,12,19,12,p);c.drawLine(12,5,12,19,p);}
            else if(kind.equals("back")){c.drawLine(15,5,8,12,p);c.drawLine(8,12,15,19,p);}
            else if(kind.equals("more")){p.setStyle(Paint.Style.FILL);for(int i=0;i<3;i++)c.drawCircle(5+i*7,12,1.5f,p);}
            else if(kind.equals("headset")){c.drawArc(4,3,20,21,180,180,false,p);c.drawRoundRect(3,11,7,19,2,2,p);c.drawRoundRect(17,11,21,19,2,2,p);}
            else if(kind.equals("appearance")){c.drawCircle(12,12,8,p);p.setStyle(Paint.Style.FILL);c.drawArc(4,4,20,20,90,180,true,p);}
            else if(kind.equals("lock")){c.drawRoundRect(5,10,19,21,3,3,p);c.drawArc(8,3,16,15,180,180,false,p);c.drawLine(12,14,12,17,p);}
            else if(kind.equals("photo")){c.drawRoundRect(3,4,21,20,3,3,p);c.drawCircle(8,9,1.5f,p);c.drawLine(4,18,10,12,p);c.drawLine(10,12,14,16,p);c.drawLine(14,16,17,13,p);c.drawLine(17,13,20,16,p);}
            else if(kind.equals("update")){c.drawLine(12,3,12,16,p);c.drawLine(7,11,12,16,p);c.drawLine(12,16,17,11,p);c.drawLine(4,18,4,21,p);c.drawLine(4,21,20,21,p);c.drawLine(20,21,20,18,p);}
            c.restore();
        }
    }
    /** A playback indicator, not a measurement of recorded amplitude. */
    public static final class Wave extends View {
        private final Paint p=new Paint(3);private final boolean hero;private final int color;
        private final Rect visibleArea=new Rect();
        private android.animation.ValueAnimator animator;
        private boolean playing,aggregatedVisible;private float phase;
        public Wave(Context c,int color,boolean hero){super(c);this.color=color;this.hero=hero;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        public void setPlaying(boolean value){if(playing==value)return;playing=value;syncAnimation();invalidate();}
        private void syncAnimation(){
            boolean run=playing&&isAttachedToWindow()&&aggregatedVisible&&getWindowVisibility()==VISIBLE&&android.animation.ValueAnimator.areAnimatorsEnabled();
            if(run&&animator==null){
                animator=android.animation.ValueAnimator.ofFloat(0,1);animator.setDuration(1200);animator.setRepeatCount(android.animation.ValueAnimator.INFINITE);animator.setInterpolator(new android.view.animation.LinearInterpolator());
                animator.addUpdateListener(value->{if(!android.animation.ValueAnimator.areAnimatorsEnabled()){stopAnimation();invalidate();return;}phase=(float)value.getAnimatedValue();if(getLocalVisibleRect(visibleArea))invalidate();});animator.start();
            }else if(!run)stopAnimation();
        }
        private void stopAnimation(){if(animator!=null){android.animation.ValueAnimator old=animator;animator=null;old.cancel();old.removeAllUpdateListeners();}phase=0;}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();syncAnimation();}
        @Override protected void onDetachedFromWindow(){stopAnimation();super.onDetachedFromWindow();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);syncAnimation();}
        @Override public void onVisibilityAggregated(boolean visible){super.onVisibilityAggregated(visible);aggregatedVisible=visible;syncAnimation();}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);p.setColor(color);p.setStrokeCap(Paint.Cap.ROUND);
            float w=getWidth(),h=getHeight();int n=hero?17:19;p.setStrokeWidth(w/(n*2.8f));
            for(int i=0;i<n;i++){
                float rest=.16f+.72f*(float)Math.abs(Math.sin(i*1.8)*Math.cos(i*.4));
                float level=animator==null?rest:.18f+.64f*(.5f+.5f*(float)Math.sin(phase*Math.PI*2+i*.85))* (.65f+.35f*(float)Math.cos(i*.4)* (float)Math.cos(i*.4));
                float x=(i+.5f)*w/n;canvas.drawLine(x,h/2-h*level/2,x,h/2+h*level/2,p);
            }
        }
    }
}
