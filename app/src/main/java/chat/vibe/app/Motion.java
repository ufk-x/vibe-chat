package chat.vibe.app;

import android.animation.*;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/** Short, interruptible motion; never gates recording or playback. */
final class Motion {
    static final Interpolator EASE=new PathInterpolator(.2f,.8f,.2f,1f);
    static final Interpolator EXIT=new PathInterpolator(.4f,0f,1f,1f);
    // A damped response with a small settle, rather than a repeating bounce.
    static final Interpolator SPRING=t->{if(t==0||t==1)return t;return (float)(1-Math.exp(-9*t)*(Math.cos(9*t)+Math.sin(9*t)));};
    static boolean enabled(){return ValueAnimator.areAnimatorsEnabled();}
    static void press(View view){
        StateListAnimator states=new StateListAnimator();
        states.addState(new int[]{android.R.attr.state_pressed,android.R.attr.state_enabled},scale(view,.97f,100,EASE));
        states.addState(new int[]{},scale(view,1f,280,SPRING));
        view.setStateListAnimator(states);
    }
    private static Animator scale(View view,float target,long duration,Interpolator curve){
        ObjectAnimator animator=ObjectAnimator.ofPropertyValuesHolder(view,PropertyValuesHolder.ofFloat(View.SCALE_X,target),PropertyValuesHolder.ofFloat(View.SCALE_Y,target));
        animator.setDuration(duration);animator.setInterpolator(curve);return animator;
    }
    static void enter(View view,int direction){
        if(!enabled())return;
        view.setAlpha(.65f);view.setTranslationX(Ui.dp(view.getContext(),direction*28));
        view.animate().alpha(1).translationX(0).setDuration(260).setInterpolator(EASE).start();
    }
    static void tab(View indicator,int distance){
        indicator.post(()->{if(!enabled()||!indicator.isAttachedToWindow())return;
            indicator.setTranslationX(distance*indicator.getWidth());
            indicator.animate().translationX(0).setDuration(360).setInterpolator(SPRING).start();
        });
    }
    static void reset(View view){view.animate().cancel();view.setAlpha(1);view.setTranslationX(0);view.setTranslationY(0);view.setScaleX(1);view.setScaleY(1);}
    /** Keep the departing page visible while the entire next page moves into place. */
    static final class PageHost extends FrameLayout {
        private View current;
        private ValueAnimator transition;
        private final Runnable settled;
        PageHost(Context context,Runnable settled){super(context);this.settled=settled;setBackgroundColor(Ui.BG);}
        boolean moving(){return transition!=null;}
        private void applyInsets(View page){if(getRootWindowInsets()!=null)page.dispatchApplyWindowInsets(getRootWindowInsets());requestApplyInsets();}
        void finish(){
            if(transition!=null){transition.removeAllListeners();transition.cancel();transition=null;}
            if(current!=null){if(current.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)current.getParent()).removeView(current);removeAllViews();reset(current);current.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);addView(current,new LayoutParams(-1,-1));}
        }
        void show(View next,int direction){
            finish();View previous=current;current=next;removeAllViews();setBackgroundColor(Ui.BG);
            if(previous==null||direction==0||!enabled()){addView(next,new LayoutParams(-1,-1));applyInsets(next);return;}
            FrameLayout departing=new FrameLayout(getContext());departing.addView(previous,new LayoutParams(-1,-1));
            previous.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            View touchShield=new View(getContext());touchShield.setClickable(true);departing.addView(touchShield,new LayoutParams(-1,-1));
            float width=getWidth()>0?getWidth():getResources().getDisplayMetrics().widthPixels;
            if(direction>0){addView(departing,new LayoutParams(-1,-1));addView(next,new LayoutParams(-1,-1));next.setTranslationX(width);}
            else{addView(next,new LayoutParams(-1,-1));addView(departing,new LayoutParams(-1,-1));next.setTranslationX(-width*.18f);next.setAlpha(.88f);}
            applyInsets(next);
            transition=ValueAnimator.ofFloat(0,1);transition.setDuration(300);transition.setInterpolator(EASE);
            transition.addUpdateListener(a->{float p=(float)a.getAnimatedValue();if(direction>0){departing.setTranslationX(-width*.18f*p);departing.setAlpha(1-.12f*p);next.setTranslationX(width*(1-p));}else{next.setTranslationX(-width*.18f*(1-p));next.setAlpha(.88f+.12f*p);departing.setTranslationX(width*p);}});
            transition.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator animation){transition=null;removeView(departing);reset(next);post(settled);}});transition.start();
        }
    }
    private Motion(){}
}
