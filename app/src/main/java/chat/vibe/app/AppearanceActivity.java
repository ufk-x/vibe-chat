package chat.vibe.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;

/** Appearance is local to this device and survives sign-out and upgrades. */
public final class AppearanceActivity extends ThemeActivity {
    private int d(float value){return Ui.dp(this,value);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(1);return v;}
    @Override public void onCreate(Bundle state){super.onCreate(state);build();}
    @Override protected void onAppearanceChanged(){build();}
    private void choose(String mode){VibeApp.app.prefs.edit().putString("appearance",mode).apply();syncAppearance();build();}
    private void build(){
        LinearLayout root=column();root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        TextView back=Ui.text(this,"‹  返回",16,Ui.GREEN);back.setPadding(d(24),d(12),d(24),d(12));Ui.touch(back);back.setOnClickListener(v->finish());root.addView(back);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);LinearLayout body=column();body.setPadding(d(24),d(14),d(24),d(32));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView title=Ui.bold(this,"外观",32,Ui.INK);body.addView(title);TextView subtitle=Ui.text(this,"让每一次对话，都看着舒服。",14,Ui.MUTED);subtitle.setPadding(0,d(8),0,d(28));body.addView(subtitle);
        LinearLayout previews=new LinearLayout(this);String mode=VibeApp.app.prefs.getString("appearance","system");
        for(int i=0;i<2;i++){final boolean night=i==1;String value=night?"dark":"light";LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setContentDescription(night?"预览并选择深色":"预览并选择浅色");item.setSelected(value.equals(mode));item.setOnClickListener(v->choose(value));Ui.touch(item);
            Preview preview=new Preview(this,night);item.addView(preview,new LinearLayout.LayoutParams(-1,d(208)));TextView label=Ui.bold(this,night?"深色":"浅色",15,Ui.INK);label.setPadding(0,d(14),0,d(18));item.addView(label);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i==0)lp.setMarginEnd(d(18));previews.addView(item,lp);
        }body.addView(previews);
        LinearLayout group=column();group.setBackground(Ui.bg(Ui.SURFACE,20));body.addView(group);
        String[] values={"system","light","dark"},labels={"跟随系统","浅色","深色"};
        for(int i=0;i<3;i++){final String value=values[i];RadioButton option=new RadioButton(this);option.setText(labels[i]);option.setTextColor(Ui.INK);option.setTextSize(16);option.setPadding(d(16),d(12),d(16),d(12));option.setMinHeight(d(58));option.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));option.setChecked(mode.equals(value));option.setBackgroundColor(android.graphics.Color.TRANSPARENT);option.setOnClickListener(v->choose(value));group.addView(option,new LinearLayout.LayoutParams(-1,-2));if(i<2){View line=new View(this);line.setBackgroundColor(Ui.LINE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.max(1,d(.5f)));lp.setMargins(d(16),0,d(16),0);group.addView(line,lp);}}
        TextView hint=Ui.text(this,"跟随系统会根据手机的深色模式设置自动切换。外观设置只影响这台设备。",13,Ui.MUTED);hint.setLineSpacing(d(4),1);hint.setPadding(d(4),d(16),d(4),0);body.addView(hint);setContentView(root);
    }
    /** Small schematic previews use their own fixed palettes, independent of the active theme. */
    private static final class Preview extends View {
        private final boolean night;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Preview(Context context,boolean dark){super(context);night=dark;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        private void rect(Canvas c,float l,float t,float r,float b,float radius,int color){paint.setColor(color);c.drawRoundRect(l,t,r,b,radius,radius,paint);}
        @Override protected void onDraw(Canvas c){float w=getWidth(),h=getHeight(),scale=Math.min(w/144f,h/208f);c.save();c.translate((w-144*scale)/2,(h-208*scale)/2);c.scale(scale,scale);
            int bg=night?0xff0c0e12:0xfff2f2f7,surface=night?0xff252933:0xffffffff,ink=night?0xfff5f5f7:0xff1c1c1e,line=night?0xff606b7b:0xffb0b9c9;
            rect(c,0,0,144,208,21,night?0xff454b57:0xffd4d8e2);rect(c,1.5f,1.5f,142.5f,206.5f,20,bg);
            rect(c,54,9,90,13,2,line);rect(c,14,30,63,37,3,ink);rect(c,14,43,85,47,2,line);
            rect(c,14,62,108,96,11,surface);rect(c,23,72,73,76,2,line);rect(c,23,82,91,85,1.5f,line);
            rect(c,45,107,130,143,11,0xff0064d8);for(int i=0;i<12;i++){float height=6+(i*7%17);rect(c,57+i*5,125-height/2,59+i*5,125+height/2,1,0xffffffff);}
            rect(c,45,149,130,167,7,night?0xff172b44:0xffe7f0ff);rect(c,53,156,119,159,1.5f,night?0xffa9afba:0xff666a73);
            rect(c,12,181,132,198,8,surface);rect(c,17,185,52,194,4,night?0xff3e4c60:0xffe7f0ff);for(int i=0;i<3;i++)rect(c,66+i*23,186,73+i*23,192,3,line);c.restore();
        }
    }
}
