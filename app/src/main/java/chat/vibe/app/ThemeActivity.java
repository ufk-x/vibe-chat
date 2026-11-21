package chat.vibe.app;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;

/** Repaints appearance without recreating an activity or interrupting microphone capture. */
public abstract class ThemeActivity extends Activity {
    private boolean appliedDark;
    private final Handler appearanceHandler=new Handler(android.os.Looper.getMainLooper());
    private final Runnable deferred=this::syncAppearance;
    @Override public void onCreate(Bundle state){
        appliedDark=Ui.wantsDark(this);Ui.palette(appliedDark);setTheme(appliedDark?R.style.AppTheme_Dark:R.style.AppTheme);
        super.onCreate(state);systemBars();
    }
    @Override protected void onResume(){super.onResume();syncAppearance();}
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);syncAppearance();}
    @Override protected void onPause(){appearanceHandler.removeCallbacks(deferred);super.onPause();}
    protected final void syncAppearance(){
        boolean next=Ui.wantsDark(this);if(next==appliedDark)return;
        // Replacing the pressed view would lose ACTION_UP; finish the gesture first.
        if(VibeApp.app.recording){appearanceHandler.removeCallbacks(deferred);appearanceHandler.postDelayed(deferred,150);return;}
        appliedDark=next;Ui.palette(next);setTheme(next?R.style.AppTheme_Dark:R.style.AppTheme);systemBars();onAppearanceChanged();AppDialog.refresh(this);
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)systemBars();}
    private void systemBars(){getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);int flags=getWindow().getDecorView().getSystemUiVisibility();int light=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;getWindow().getDecorView().setSystemUiVisibility(appliedDark?flags&~light:flags|light);
        if(android.os.Build.VERSION.SDK_INT>=30){android.view.WindowInsetsController controller=getWindow().getInsetsController();if(controller!=null){int mask=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;controller.setSystemBarsAppearance(appliedDark?0:mask,mask);}}
        if(android.os.Build.VERSION.SDK_INT>=29)getWindow().setNavigationBarContrastEnforced(false);
    }
    protected abstract void onAppearanceChanged();
    @Override protected void onDestroy(){AppDialog.dismissFor(this);super.onDestroy();}
}
