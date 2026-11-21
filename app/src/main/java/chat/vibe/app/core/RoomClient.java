package chat.vibe.app.core;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.concurrent.*;

public final class RoomClient implements Closeable {
    public interface Listener { void connected(); void presence(int count); void message(Wire.Message m); void disconnected(); }
    private final byte[] key; private final String host; private final int port; private final Listener listener;
    private volatile Socket socket; private volatile DataOutputStream output; private volatile boolean closed;
    private Thread reader;
    private final ExecutorService writer=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    public RoomClient(String host,int port,byte[] key,Listener listener){this.host=host;this.port=port;this.key=key;this.listener=listener;}
    public void start() {
        reader=new Thread(this::loop,"vibe-connection");reader.start();
        timer.scheduleAtFixedRate(()->sendPacket(new byte[]{Wire.PING}),12,12,TimeUnit.SECONDS);
    }
    private void loop() {
        int delay=1000;
        while(!closed) {
            Socket current=new Socket(); socket=current;
            try {
                current.connect(new InetSocketAddress(host,port),6000); current.setTcpNoDelay(true); current.setSoTimeout(8000);
                DataInputStream in=new DataInputStream(current.getInputStream()); DataOutputStream out=new DataOutputStream(current.getOutputStream());
                byte[] nonce=Wire.read(in); if(nonce.length!=32) throw new IOException("Invalid challenge");
                Wire.write(out,Wire.proof(key,nonce,"client"));
                if(!MessageDigest.isEqual(Wire.read(in),Wire.proof(key,nonce,"server"))) throw new IOException("Wrong room key");
                current.setSoTimeout(40000); output=out; delay=1000; if(closed)break; listener.connected();
                while(!closed) {
                    byte[] p=Wire.decrypt(key,Wire.read(in));
                    if(p.length==2 && p[0]==Wire.PRESENCE) listener.presence(p[1]&255);
                    else if(p.length==1 && p[0]==Wire.PING) { /* heartbeat */ }
                    else listener.message(Wire.parseVoice(p));
                }
            } catch(Exception ignored) {
            } finally { output=null; try{current.close();}catch(IOException ignored){} }
            if(!closed) {
                listener.disconnected();
                try{Thread.sleep(delay);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                delay=Math.min(10000,delay*2);
            }
        }
    }
    public boolean send(Wire.Message m) {
        try { return sendPacket(Wire.voice(m)); } catch(IOException e) { return false; }
    }
    private boolean sendPacket(byte[] p) {
        DataOutputStream out=output; if(out==null || closed)return false;
        try { writer.execute(()->{
            try{Wire.write(out,Wire.encrypt(key,p));}catch(Exception e){Socket s=socket;if(s!=null)try{s.close();}catch(IOException ignored){}}
        });return true;} catch(RejectedExecutionException e){return false;}
    }
    @Override public void close() {
        closed=true; output=null; Socket s=socket;if(s!=null)try{s.close();}catch(IOException ignored){}
        timer.shutdownNow();writer.shutdownNow();if(reader!=null)reader.interrupt();
    }
}
