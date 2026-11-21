package chat.vibe.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;

/** Camera frames remain in memory; only the decoded invitation is returned. */
@SuppressWarnings("deprecation")
public final class ScanActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView surface;private Camera camera;private boolean surfaceReady;private volatile boolean decoding,finished;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private TextView hint;
    @Override public void onCreate(Bundle b){setTheme(R.style.AppTheme_Dark);super.onCreate(b);getWindow().setStatusBarColor(Ui.DARK);getWindow().setNavigationBarColor(Ui.DARK);getWindow().getDecorView().setSystemUiVisibility(0);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(Ui.DARK);root.setPadding(0,Ui.dp(this,36),0,Ui.dp(this,24));hint=Ui.text(this,"将二维码放入取景框",18,Ui.WHITE);hint.setGravity(Gravity.CENTER);hint.setPadding(12,24,12,24);root.addView(hint);surface=new SurfaceView(this);root.addView(surface,new LinearLayout.LayoutParams(-1,0,1));surface.getHolder().addCallback(this);TextView back=Ui.text(this,"返回",17,Ui.ON_DARK_SECONDARY);back.setPadding(24,28,24,28);back.setGravity(Gravity.CENTER);back.setOnClickListener(v->finish());root.addView(back);setContentView(root);if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},1);}
    @Override protected void onResume(){super.onResume();open();}
    @Override protected void onPause(){close();super.onPause();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    public void surfaceCreated(SurfaceHolder h){surfaceReady=true;open();}public void surfaceChanged(SurfaceHolder h,int f,int w,int height){}public void surfaceDestroyed(SurfaceHolder h){surfaceReady=false;close();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)open();else hint.setText("未授予相机权限，可返回使用相册或粘贴邀请");}
    private void open(){if(camera!=null||!surfaceReady||finished||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;try{int cameraId=0;for(int i=0;i<Camera.getNumberOfCameras();i++){Camera.CameraInfo candidate=new Camera.CameraInfo();Camera.getCameraInfo(i,candidate);if(candidate.facing==Camera.CameraInfo.CAMERA_FACING_BACK){cameraId=i;break;}}camera=Camera.open(cameraId);Camera.Parameters p=camera.getParameters();Camera.Size best=null;for(Camera.Size size:p.getSupportedPreviewSizes())if(size.width<=1280&&(best==null||Math.abs(size.width-640)<Math.abs(best.width-640)))best=size;if(best!=null)p.setPreviewSize(best.width,best.height);List<String> modes=p.getSupportedFocusModes();if(modes!=null&&modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);camera.setParameters(p);Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(cameraId,info);int r=getWindowManager().getDefaultDisplay().getRotation();int degrees=r==Surface.ROTATION_90?90:r==Surface.ROTATION_180?180:r==Surface.ROTATION_270?270:0;camera.setDisplayOrientation((info.orientation-degrees+360)%360);camera.setPreviewDisplay(surface.getHolder());camera.setPreviewCallback((data,c)->decode(data,c.getParameters().getPreviewSize()));camera.startPreview();}catch(Exception e){close();hint.setText("相机暂不可用，请返回使用相册或粘贴邀请");}}
    private void decode(byte[] data,Camera.Size size){if(decoding||finished||worker.isShutdown())return;decoding=true;byte[] copy=data.clone();worker.execute(()->{try{PlanarYUVLuminanceSource source=new PlanarYUVLuminanceSource(copy,size.width,size.height,0,0,size.width,size.height,false);Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singletonList(BarcodeFormat.QR_CODE));String code=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source)),hints).getText();if(!finished){finished=true;runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("code",code));finish();});}}catch(Exception ignored){}finally{decoding=false;}});}
    private void close(){if(camera!=null){try{camera.setPreviewCallback(null);camera.stopPreview();camera.release();}catch(Exception ignored){}camera=null;}}
}
