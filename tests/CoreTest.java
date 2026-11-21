import chat.vibe.app.core.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class CoreTest {
    static int checks;
    static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    static final class Probe implements RoomClient.Listener {
        CountDownLatch online=new CountDownLatch(1),two=new CountDownLatch(1),gone=new CountDownLatch(1);
        BlockingQueue<Wire.Message> inbox=new LinkedBlockingQueue<>();AtomicInteger count=new AtomicInteger();
        public void connected(){online.countDown();}public void presence(int n){count.set(n);if(n>=2)two.countDown();}
        public void disconnected(){gone.countDown();}public void message(Wire.Message m){inbox.offer(m);}
    }
    public static void main(String[] args)throws Exception{
        byte[] key=Wire.key(Wire.keyText()),pcm=new byte[64000];new Random(5).nextBytes(pcm);
        Wire.Message m=new Wire.Message(UUID.randomUUID().toString(),"alice","小林",123456,pcm);
        byte[] frame=Wire.encrypt(key,Wire.voice(m));Wire.Message copy=Wire.parseVoice(Wire.decrypt(key,frame));
        check(Arrays.equals(pcm,copy.pcm)&&copy.name.equals("小林")&&copy.seconds()==2,"voice roundtrip preserves PCM, name and duration");
        frame[frame.length-1]^=1;boolean tamper=false;try{Wire.decrypt(key,frame);}catch(GeneralSecurityException e){tamper=true;}
        check(tamper,"modified ciphertext rejected");
        boolean invalid=false;try{Wire.parseInvitation("vibe1|host|70000|"+Wire.keyText());}catch(IllegalArgumentException e){invalid=true;}
        check(invalid,"out-of-range invitation port rejected");
        invalid=false;try{Wire.parseInvitation("vibe1|host|4000|x");}catch(IllegalArgumentException e){invalid=true;}
        check(invalid,"invalid invite key rejected");
        invalid=false;try{Wire.read(new DataInputStream(new ByteArrayInputStream(new byte[]{127,-1,-1,-1})));}catch(IOException e){invalid=true;}
        check(invalid,"oversized frame rejected before allocation");
        invalid=false;try{Wire.voice(new Wire.Message("x","a","b",0,new byte[960002]));}catch(IOException e){invalid=true;}
        check(invalid,"audio longer than 30 seconds rejected");
        try(RoomServer server=new RoomServer(0,key)){
            Probe p1=new Probe(),p2=new Probe(),wrong=new Probe();
            try(RoomClient c1=new RoomClient("127.0.0.1",server.port(),key,p1);
                RoomClient c2=new RoomClient("127.0.0.1",server.port(),key,p2);
                RoomClient bad=new RoomClient("127.0.0.1",server.port(),Wire.random(32),wrong)){
                c1.start();c2.start();check(p1.two.await(5,TimeUnit.SECONDS)&&p2.two.await(5,TimeUnit.SECONDS),"two authenticated clients join one room");
                bad.start();check(wrong.gone.await(5,TimeUnit.SECONDS)&&wrong.online.getCount()==1,"wrong key cannot join room");
                check(c1.send(m),"connected client accepts voice for transmission");
                Wire.Message got=p2.inbox.poll(5,TimeUnit.SECONDS),echo=p1.inbox.poll(5,TimeUnit.SECONDS);
                check(got!=null&&Arrays.equals(got.pcm,pcm)&&got.id.equals(m.id),"peer receives exact encrypted voice");
                check(echo!=null&&echo.id.equals(m.id),"sender receives room acknowledgment");
                Thread.sleep(300);
                Wire.Message reply=new Wire.Message(UUID.randomUUID().toString(),"bob","朋友",123457,new byte[32000]);
                c2.send(reply);got=p1.inbox.poll(5,TimeUnit.SECONDS);
                check(got!=null&&got.sender.equals("bob"),"reply delivered in reverse direction");
                c2.close();long until=System.currentTimeMillis()+3000;while(p1.count.get()!=1&&System.currentTimeMillis()<until)Thread.sleep(25);
                check(p1.count.get()==1,"disconnect updates room presence");
                server.close();check(p1.gone.await(5,TimeUnit.SECONDS),"host shutdown reaches client");
            }
        }
        check(Wire.parseInvitation(Wire.invitation("192.168.1.20",45735,Wire.keyText()))[1].equals("192.168.1.20"),"shareable invitation roundtrip");
        System.out.println(checks+" checks passed");
    }
}
