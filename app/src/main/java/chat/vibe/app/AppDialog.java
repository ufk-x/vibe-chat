package chat.vibe.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.HashMap;
import java.util.Map;

/** App-owned sheets and alerts. Permissions, pickers and the installer remain system UI. */
public final class AppDialog extends Dialog {
    private static final Map<Activity,AppDialog> OPEN=new HashMap<>();
    private final Builder spec;
    private int oldInk,oldMuted,oldBg;
    private LinearLayout panel;
    private View shade;
    private boolean closing;
    private AppDialog(Builder spec){
        super(spec.activity,Ui.dark?R.style.AppTheme_Dark:R.style.AppTheme);
        this.spec=spec;setOwnerActivity(spec.activity);requestWindowFeature(Window.FEATURE_NO_TITLE);
        setOnDismissListener(d->{if(OPEN.get(spec.activity)==this)OPEN.remove(spec.activity);});
    }
    public static void dismissFor(Activity activity){AppDialog dialog=OPEN.remove(activity);if(dialog!=null)dialog.dismiss();}
    public static void refresh(Activity activity){AppDialog dialog=OPEN.get(activity);if(dialog!=null&&dialog.isShowing()&&!dialog.closing)dialog.render();}
    private int dp(float n){return Ui.dp(getContext(),n);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(getContext());v.setOrientation(LinearLayout.VERTICAL);return v;}
    private boolean sheet(){return spec.items!=null||spec.content!=null||spec.sheet;}
    private void fire(DialogInterface.OnClickListener action,int which){if(closing)return;if(which==DialogInterface.BUTTON_POSITIVE&&spec.validate!=null&&!spec.validate.getAsBoolean())return;close(false,()->{if(action!=null)action.onClick(this,which);});}
    private void present(){
        if(!Motion.enabled())return;
        panel.setAlpha(0);shade.setAlpha(0);
        panel.post(()->{if(closing||!isShowing())return;
            if(!Motion.enabled()){Motion.reset(panel);shade.setAlpha(1);return;}
            if(sheet()){panel.setAlpha(1);panel.setTranslationY(panel.getHeight()+dp(24));panel.animate().translationY(0).setDuration(380).setInterpolator(Motion.SPRING).start();}
            else{panel.setScaleX(.94f);panel.setScaleY(.94f);panel.animate().alpha(1).scaleX(1).scaleY(1).setDuration(280).setInterpolator(Motion.EASE).start();}
            shade.animate().alpha(1).setDuration(220).setInterpolator(Motion.EASE).start();
        });
    }
    private void close(boolean cancelled,Runnable action){
        if(closing)return;closing=true;
        Runnable finish=()->{if(cancelled)super.cancel();else super.dismiss();if(action!=null&&!spec.activity.isFinishing()&&!spec.activity.isDestroyed())action.run();};
        if(panel==null||!isShowing()||!Motion.enabled()){finish.run();return;}
        panel.animate().cancel();shade.animate().cancel();shade.animate().alpha(0).setDuration(180).setInterpolator(Motion.EASE).start();
        android.view.ViewPropertyAnimator exit=panel.animate().setDuration(180).setInterpolator(Motion.EXIT);
        if(sheet())exit.translationY(panel.getHeight()+dp(24));else exit.alpha(0).scaleX(.96f).scaleY(.96f);
        exit.withEndAction(finish).start();
    }
    @Override public void cancel(){close(true,null);}
    @Override public void dismiss(){closing=true;if(panel!=null)panel.animate().cancel();if(shade!=null)shade.animate().cancel();super.dismiss();}
    private TextView label(String text,int size,int color,boolean bold){return bold?Ui.bold(getContext(),text,size,color):Ui.text(getContext(),text,size,color);}
    private void accessibleButton(View view,String description){
        Ui.touch(view);view.setContentDescription(description);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View v,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(v,info);info.setClassName(Button.class.getName());}});
    }
    private void feedback(View v,int color,int radius){v.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.dark?0x22ffffff:0x14000000),Ui.bg(color,radius),Ui.bg(Color.WHITE,radius)));}
    private TextView action(String text,int color,Runnable run){
        TextView v=label(text,17,color,true);v.setGravity(Gravity.CENTER);v.setPadding(dp(16),dp(13),dp(16),dp(13));
        accessibleButton(v,text);feedback(v,Color.TRANSPARENT,12);v.setOnClickListener(w->run.run());return v;
    }
    private void divider(LinearLayout parent,int inset){View line=new View(getContext());line.setBackgroundColor(Ui.LINE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.max(1,dp(.5f)));lp.setMargins(dp(inset),0,dp(inset),0);parent.addView(line,lp);}
    private void gap(LinearLayout parent,int size){parent.addView(new View(getContext()),new LinearLayout.LayoutParams(1,dp(size)));}
    private void swipeToClose(View view){
        // A fixed-height sheet has no resize grabber. Its header supports a downward dismissal.
        view.setOnTouchListener(new View.OnTouchListener(){float x,y;public boolean onTouch(View v,android.view.MotionEvent e){
            if(closing)return true;
            if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();panel.animate().cancel();v.getParent().requestDisallowInterceptTouchEvent(true);return true;}
            float dy=Math.max(0,e.getRawY()-y);
            if(e.getActionMasked()==android.view.MotionEvent.ACTION_MOVE&&Motion.enabled()){panel.setTranslationY(dy*.8f);shade.setAlpha(Math.max(.35f,1-dy/Math.max(1,panel.getHeight())));}
            if(e.getActionMasked()==android.view.MotionEvent.ACTION_UP){v.getParent().requestDisallowInterceptTouchEvent(false);if(dy>dp(56)&&Math.abs(e.getRawX()-x)<dp(48))cancel();else{settle();v.performClick();}return true;}
            if(e.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL){v.getParent().requestDisallowInterceptTouchEvent(false);settle();}
            return true;
        }});
    }
    private void settle(){if(!Motion.enabled()){Motion.reset(panel);shade.setAlpha(1);return;}panel.animate().translationY(0).setDuration(320).setInterpolator(Motion.SPRING).start();shade.animate().alpha(1).setDuration(180).setInterpolator(Motion.EASE).start();}
    private LinearLayout heading(boolean centered){
        LinearLayout box=column();box.setPadding(dp(24),dp(22),dp(24),dp(20));
        if(!spec.title.isEmpty()){TextView title=label(spec.title,centered?19:22,Ui.INK,true);title.setGravity(centered?Gravity.CENTER:Gravity.START);if(Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);box.addView(title);}
        if(!spec.message.isEmpty()){TextView detail=label(spec.message,centered?14:14,Ui.MUTED,false);detail.setLineSpacing(dp(4),1);detail.setPadding(0,dp(8),0,0);detail.setGravity(centered?Gravity.CENTER:Gravity.START);box.addView(detail);}
        if(sheet()&&spec.content==null)swipeToClose(box);return box;
    }
    private void repaintContent(View view){
        if(view instanceof TextView){TextView text=(TextView)view;int color=text.getCurrentTextColor();if(color==oldInk)text.setTextColor(Ui.INK);else if(color==oldMuted)text.setTextColor(Ui.MUTED);if(view instanceof EditText)((EditText)view).setHintTextColor(Ui.MUTED);}
        if(view.getBackground() instanceof GradientDrawable){GradientDrawable bg=(GradientDrawable)view.getBackground();ColorStateList color=bg.getColor();if(color!=null&&color.getDefaultColor()==oldBg)bg.setColor(Ui.BG);}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)repaintContent(((ViewGroup)view).getChildAt(i));
    }
    private void render(){
        if(panel!=null)panel.animate().cancel();if(shade!=null)shade.animate().cancel();
        View focus=getCurrentFocus();
        if(spec.content!=null){if(oldInk!=0)repaintContent(spec.content);if(spec.content.getParent() instanceof ViewGroup)((ViewGroup)spec.content.getParent()).removeView(spec.content);}
        oldInk=Ui.INK;oldMuted=Ui.MUTED;oldBg=Ui.BG;
        FrameLayout root=new FrameLayout(getContext());root.setClipToPadding(false);root.setOnClickListener(v->cancel());root.setFocusableInTouchMode(true);
        shade=new View(getContext());shade.setBackgroundColor(0x48000000);shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);root.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        // Backdrop taps must dismiss even when a field or a button currently owns focus.
        root.setOnTouchListener((v,e)->{if(e.getActionMasked()==android.view.MotionEvent.ACTION_UP)cancel();return true;});
        LinearLayout stack=column();panel=stack;stack.setClickable(true);
        LinearLayout card=column();card.setBackground(Ui.bg(Ui.SURFACE,sheet()?28:24));card.setClipToOutline(true);card.setClickable(true);
        if(spec.items!=null){
            ScrollView scroll=new ScrollView(getContext());scroll.setVerticalScrollBarEnabled(false);LinearLayout list=column();list.addView(heading(false));
            for(int i=0;i<spec.items.length;i++){
                final int index=i;boolean danger=i==spec.destructiveItem;int ink=danger?Ui.RED:Ui.INK;
                if(i>0)divider(list,spec.icons==null?24:64);
                LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(24),dp(16),dp(22),dp(16));
                if(spec.icons!=null){Ui.Icon icon=new Ui.Icon(getContext(),spec.icons[i],danger?Ui.RED:Ui.GREEN);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(24),dp(24));ip.setMarginEnd(dp(16));row.addView(icon,ip);}
                LinearLayout copy=column();TextView title=label(spec.items[i],17,ink,false);title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);copy.addView(title);
                String detail=spec.details==null?null:spec.details[i];if(detail!=null&&!detail.isEmpty()){TextView subtitle=label(detail,13,Ui.MUTED,false);subtitle.setPadding(0,dp(4),0,0);subtitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);copy.addView(subtitle);}
                row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));accessibleButton(row,spec.items[i]+(detail==null||detail.isEmpty()?"":"，"+detail));feedback(row,Color.TRANSPARENT,0);row.setOnClickListener(v->fire(spec.itemListener,index));list.addView(row,new LinearLayout.LayoutParams(-1,-2));
            }
            gap(list,8);scroll.addView(list);card.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            stack.addView(card,new LinearLayout.LayoutParams(-1,0,1));gap(stack,8);
            TextView cancel=action("取消",Ui.GREEN,this::cancel);feedback(cancel,Ui.SURFACE,20);stack.addView(cancel,new LinearLayout.LayoutParams(-1,-2));
        }else if(sheet()){
            boolean form=spec.content!=null&&spec.neutralText==null&&spec.negativeText!=null;
            if(form){
                LinearLayout toolbar=new LinearLayout(getContext());toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(8),dp(8),dp(8),0);
                toolbar.addView(action(spec.negativeText,Ui.GREEN,()->fire(spec.negative,DialogInterface.BUTTON_NEGATIVE)));
                toolbar.addView(new View(getContext()),new LinearLayout.LayoutParams(0,1,1));
                if(spec.positiveText!=null)toolbar.addView(action(spec.positiveText,Ui.GREEN,()->fire(spec.positive,DialogInterface.BUTTON_POSITIVE)));
                card.addView(toolbar);
            }else{
                LinearLayout toolbar=new LinearLayout(getContext());toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(24),dp(12),dp(10),0);
                TextView title=label(spec.title,22,Ui.INK,true);if(Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);toolbar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
                TextView close=action("×",Ui.MUTED,this::cancel);close.setTextSize(27);close.setContentDescription("关闭");toolbar.addView(close);card.addView(toolbar);swipeToClose(title);
            }
            ScrollView scroll=new ScrollView(getContext());scroll.setVerticalScrollBarEnabled(false);LinearLayout body=column();
            if(form)body.addView(heading(false));
            else if(!spec.message.isEmpty()){TextView message=label(spec.message,16,Ui.INK,false);message.setLineSpacing(dp(6),1);message.setPadding(dp(24),dp(16),dp(24),dp(20));body.addView(message);}
            if(spec.content!=null)body.addView(spec.content,new LinearLayout.LayoutParams(-1,-2));
            gap(body,20);scroll.addView(body);card.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            if(!form&&spec.neutralText!=null){
                LinearLayout buttons=column();buttons.setPadding(dp(24),0,dp(24),dp(12));TextView primary=action(spec.neutralText,Ui.WHITE,()->fire(spec.neutral,DialogInterface.BUTTON_NEUTRAL));feedback(primary,Ui.ACTION,16);buttons.addView(primary,new LinearLayout.LayoutParams(-1,-2));
                if(spec.negativeText!=null){gap(buttons,4);buttons.addView(action(spec.negativeText,spec.negativeDestructive?Ui.RED:Ui.GREEN,()->fire(spec.negative,DialogInterface.BUTTON_NEGATIVE)));}card.addView(buttons);
            }
            stack.addView(card,new LinearLayout.LayoutParams(-1,-2));
        }else{
            ScrollView scroll=new ScrollView(getContext());scroll.addView(heading(true));card.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            // Stack action labels at large text sizes instead of squeezing or truncating them.
            boolean vertical=getContext().getResources().getConfiguration().fontScale>1.25f||spec.neutralText!=null;
            LinearLayout buttons=new LinearLayout(getContext());buttons.setOrientation(vertical?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);divider(card,0);
            if(vertical){alertButton(buttons,spec.positiveText,spec.positive,DialogInterface.BUTTON_POSITIVE,spec.positiveDestructive?Ui.RED:Ui.GREEN,true);alertButton(buttons,spec.neutralText,spec.neutral,DialogInterface.BUTTON_NEUTRAL,Ui.GREEN,true);alertButton(buttons,spec.negativeText,spec.negative,DialogInterface.BUTTON_NEGATIVE,Ui.GREEN,true);}
            else{alertButton(buttons,spec.negativeText,spec.negative,DialogInterface.BUTTON_NEGATIVE,Ui.GREEN,false);alertButton(buttons,spec.positiveText,spec.positive,DialogInterface.BUTTON_POSITIVE,spec.positiveDestructive?Ui.RED:Ui.GREEN,false);}
            card.addView(buttons);stack.addView(card,new LinearLayout.LayoutParams(-1,-2));
        }
        FrameLayout.LayoutParams pos=new FrameLayout.LayoutParams(-1,-2,sheet()?Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL:Gravity.CENTER);root.addView(stack,pos);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int left,right,top,bottom;if(Build.VERSION.SDK_INT>=30){Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());left=safe.left;right=safe.right;top=safe.top;bottom=safe.bottom;}
            else{left=insets.getSystemWindowInsetLeft();right=insets.getSystemWindowInsetRight();top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}
            root.setPadding(left+dp(12),top+dp(16),right+dp(12),bottom+dp(12));FrameLayout.LayoutParams dim=(FrameLayout.LayoutParams)shade.getLayoutParams();dim.setMargins(-root.getPaddingLeft(),-root.getPaddingTop(),-root.getPaddingRight(),-root.getPaddingBottom());shade.setLayoutParams(dim);return insets;
        });
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{int width=Math.min(dp(sheet()?560:340),root.getWidth()-root.getPaddingLeft()-root.getPaddingRight());FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)stack.getLayoutParams();if(width>0&&lp.width!=width){lp.width=width;stack.setLayoutParams(lp);}});
        setContentView(root);Window window=getWindow();window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setWindowAnimations(0);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.setStatusBarColor(Color.TRANSPARENT);window.setNavigationBarColor(Color.TRANSPARENT);if(Build.VERSION.SDK_INT>=29)window.setNavigationBarContrastEnforced(false);
        int flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;if(!Ui.dark)flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;window.getDecorView().setSystemUiVisibility(flags);
        if(Build.VERSION.SDK_INT>=30){window.setDecorFitsSystemWindows(false);WindowInsetsController ctl=window.getInsetsController();if(ctl!=null){int mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;ctl.setSystemBarsAppearance(Ui.dark?0:mask,mask);}}
        window.setLayout(-1,-1);root.requestApplyInsets();if(focus!=null&&focus instanceof EditText)focus.requestFocus();
    }
    private void alertButton(LinearLayout row,String text,DialogInterface.OnClickListener listener,int which,int color,boolean vertical){
        if(text==null)return;if(row.getChildCount()>0){View line=new View(getContext());line.setBackgroundColor(Ui.LINE);row.addView(line,new LinearLayout.LayoutParams(vertical?-1:Math.max(1,dp(.5f)),vertical?Math.max(1,dp(.5f)):-1));}
        TextView button=action(text,color,()->fire(listener,which));row.addView(button,vertical?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));
    }
    public static final class Builder {
        final Activity activity;String title="",message="";View content;String[] items,icons,details;int destructiveItem=-1;
        String positiveText,negativeText,neutralText;DialogInterface.OnClickListener positive,negative,neutral,itemListener;DialogInterface.OnCancelListener cancelListener;java.util.function.BooleanSupplier validate;boolean sheet,positiveDestructive,negativeDestructive;
        public Builder(Activity activity){this.activity=activity;}
        public Builder setTitle(String value){title=value;return this;}
        public Builder setMessage(String value){message=value;return this;}
        public Builder setView(View value){content=value;return this;}
        public Builder asSheet(){sheet=true;return this;}
        public Builder setItems(String[] value,DialogInterface.OnClickListener listener){items=value;itemListener=listener;return this;}
        public Builder setItemDetails(String[] symbols,String[] subtitles){icons=symbols;details=subtitles;return this;}
        public Builder setDestructiveItem(int index){destructiveItem=index;return this;}
        public Builder setPositiveDestructive(){positiveDestructive=true;return this;}
        public Builder setNegativeDestructive(){negativeDestructive=true;return this;}
        public Builder setOnCancelListener(DialogInterface.OnCancelListener listener){cancelListener=listener;return this;}
        public Builder setBeforePositive(java.util.function.BooleanSupplier validation){validate=validation;return this;}
        public Builder setPositiveButton(String text,DialogInterface.OnClickListener listener){positiveText=text;positive=listener;return this;}
        public Builder setNegativeButton(String text,DialogInterface.OnClickListener listener){negativeText=text;negative=listener;return this;}
        public Builder setNeutralButton(String text,DialogInterface.OnClickListener listener){neutralText=text;neutral=listener;return this;}
        public AppDialog show(){
            AppDialog dialog=new AppDialog(this);if(activity.isFinishing()||activity.isDestroyed())return dialog;
            dismissFor(activity);OPEN.put(activity,dialog);dialog.setOnCancelListener(cancelListener);dialog.render();dialog.show();dialog.getWindow().setLayout(-1,-1);dialog.present();return dialog;
        }
    }
}
