package chat.vibe.app;

import android.content.*;
import android.media.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;

/** 16 kHz mono PCM keeps the transport small and avoids codec dependencies. */
public final class AudioEngine {
    public interface Capture { void finished(byte[] pcm,boolean cancelled); void failed(String reason); }
    public interface Playback { void finished(boolean complete); }
    private final AudioManager manager; private final Handler main; private final Runnable devicesChanged;
    private volatile boolean capturing; private volatile boolean cancelled;
    private AudioTrack track; private Playback completion; private AudioFocusRequest focus;
    private boolean privatePlayback; private long playbackStart; private boolean audible;
    private final ExecutorService recordingWorker=Executors.newSingleThreadExecutor();
    private final AudioDeviceCallback devices=new AudioDeviceCallback(){
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] a){devicesChanged.run();}
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] a){if(privatePlayback)stop();devicesChanged.run();}
    };
    public AudioEngine(Context context, Handler main,Runnable changed){
        manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);this.main=main;devicesChanged=changed;
        manager.registerAudioDeviceCallback(devices,main);
    }
    public static boolean isHeadset(AudioDeviceInfo d) {
        if(d==null)return false;
        int t=d.getType();
        return t==AudioDeviceInfo.TYPE_WIRED_HEADSET || t==AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t==AudioDeviceInfo.TYPE_USB_HEADSET || t==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT>=31 && t==AudioDeviceInfo.TYPE_BLE_HEADSET);
    }
    public AudioDeviceInfo headset(){
        AudioDeviceInfo fallback=null;
        for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(isHeadset(d)){
            if(d.getType()!=AudioDeviceInfo.TYPE_BLUETOOTH_SCO)return d;fallback=d;
        }return fallback;
    }
    public String headsetName(){AudioDeviceInfo d=headset();return d==null?"未连接耳机":String.valueOf(d.getProductName());}
    public boolean busy(){return capturing || track!=null;}
    public boolean begin(Capture callback) {
        if(capturing)return false;stop();capturing=true;cancelled=false;
        recordingWorker.execute(()->{
            AudioRecord record=null;
            try {
                int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(min<0)throw new IOException();
                record=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,6400));
                if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException();
                ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] chunk=new byte[3200];record.startRecording();
                while(capturing && data.size()<960000){
                    int n=record.read(chunk,0,Math.min(chunk.length,960000-data.size()));if(n<0)throw new IOException();if(n>0)data.write(chunk,0,n);
                }
                byte[] pcm=data.toByteArray();boolean discard=cancelled;capturing=false;
                main.post(()->callback.finished(pcm,discard));
            } catch(Exception e){capturing=false;main.post(()->callback.failed("录音不可用，请检查麦克风权限或其他通话"));}
            finally { if(record!=null){try{record.stop();}catch(Exception ignored){}record.release();} }
        });return true;
    }
    public void end(boolean discard){cancelled=discard;capturing=false;}
    public void play(byte[] pcm,boolean headphonesOnly,Playback callback) {
        stop();if(capturing || (headphonesOnly && headset()==null)){callback.finished(false);return;}
        AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(change->{if(change<0)stop();},main).build();
        if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){manager.abandonAudioFocusRequest(focus);focus=null;callback.finished(false);return;}
        completion=callback;privatePlayback=headphonesOnly;audible=false;
        try {
            // Automatic playback verifies the sink during silent pre-roll. An explicit tap
            // follows the system media route immediately, without adding half a second.
            int preRoll=headphonesOnly?16000:0;
            byte[] buffered=new byte[pcm.length+preRoll];System.arraycopy(pcm,0,buffered,preRoll,pcm.length);
            track=new AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(buffered.length).setTransferMode(AudioTrack.MODE_STATIC).build();
            if(headphonesOnly && !track.setPreferredDevice(headset())){stop();return;}
            track.setVolume(headphonesOnly?0:1);
            track.addOnRoutingChangedListener(r->{if(privatePlayback && audible && !isHeadset(r.getRoutedDevice()))stop();},main);
            track.write(buffered,0,buffered.length);
            track.setNotificationMarkerPosition(buffered.length/2);
            track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener(){
                public void onMarkerReached(AudioTrack t){finish(true);}public void onPeriodicNotification(AudioTrack t){}
            },main);
            playbackStart=SystemClock.elapsedRealtime();track.play();main.post(checkRoute);
        }catch(Exception e){stop();}
    }
    private final Runnable checkRoute=new Runnable(){public void run(){
        if(track==null)return;
        if(privatePlayback){
            if(headset()==null){stop();return;}
            AudioDeviceInfo route=track.getRoutedDevice();
            if(isHeadset(route)){track.setVolume(1);audible=true;}
            else if(audible || SystemClock.elapsedRealtime()-playbackStart>400){stop();return;}
        }
        // Marker callbacks can be lost on vendor implementations; do not retain focus forever.
        if(SystemClock.elapsedRealtime()-playbackStart>33000){stop();return;}
        main.postDelayed(this,40);
    }};
    public void stop(){finish(false);}
    private void finish(boolean ok){
        main.removeCallbacks(checkRoute);
        AudioTrack old=track;track=null;if(old!=null){try{old.setVolume(0);old.stop();}catch(Exception ignored){}old.release();}
        if(focus!=null){manager.abandonAudioFocusRequest(focus);focus=null;}
        Playback cb=completion;completion=null;if(cb!=null)cb.finished(ok);
    }
    public void close(){end(true);stop();manager.unregisterAudioDeviceCallback(devices);recordingWorker.shutdown();}
}
