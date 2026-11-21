import chat.vibe.app.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Manual emulator integration fixture; synthetic tone, never bundled in APK. */
public final class AndroidPeer {
    public static void main(String[] args)throws Exception{
        String secret=Wire.keyText();RoomServer host=new RoomServer(50123,Wire.key(secret));
        Files.write(Paths.get(args[0]),Wire.invitation("10.0.2.2",50123,secret).getBytes("UTF-8"));
        CountDownLatch two=new CountDownLatch(1),received=new CountDownLatch(1);
        RoomClient peer=new RoomClient("127.0.0.1",50123,Wire.key(secret),new RoomClient.Listener(){
            public void connected(){System.out.println("TEST_PEER_CONNECTED");}
            public void presence(int n){System.out.println("PRESENCE "+n);if(n>=2)two.countDown();}
            public void disconnected(){System.out.println("DISCONNECTED");}
            public void message(Wire.Message m){if(!m.sender.equals("test-peer")){System.out.println("ANDROID_VOICE_RECEIVED bytes="+m.pcm.length+" name="+m.name);received.countDown();}}
        });
        Runtime.getRuntime().addShutdownHook(new Thread(()->{peer.close();host.close();}));
        peer.start();
        if(!two.await(180,TimeUnit.SECONDS))throw new AssertionError("Android did not join");
        byte[] pcm=new byte[64000];
        for(int i=0;i<pcm.length/2;i++){short sample=(short)(Math.sin(i*2*Math.PI*440/16000)*1800);pcm[2*i]=(byte)sample;pcm[2*i+1]=(byte)(sample>>8);}
        peer.send(new Wire.Message(UUID.randomUUID().toString(),"test-peer","联调测试端",System.currentTimeMillis(),pcm));
        System.out.println("TEST_TONE_SENT");
        if(!received.await(180,TimeUnit.SECONDS))throw new AssertionError("Android did not reply");
        System.out.println("ANDROID_DUPLEX_PASS");
        new CountDownLatch(1).await();
    }
}
