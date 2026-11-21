package chat.vibe.app.core;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Small authenticated room host, shared by Android and the optional desktop relay. */
public final class RoomServer implements Closeable {
    private final byte[] key;
    private final ServerSocket server;
    private final CopyOnWriteArrayList<Peer> peers=new CopyOnWriteArrayList<>();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private volatile boolean closed;
    public RoomServer(int port, byte[] key) throws IOException {
        this.key=key.clone(); server=new ServerSocket(); server.setReuseAddress(true); server.bind(new InetSocketAddress(port));
        workers.execute(this::accept);
    }
    public int port() { return server.getLocalPort(); }
    private void accept() {
        while(!closed) try {
            Socket socket=server.accept();
            if(sockets.size()>=8) { socket.close(); continue; }
            sockets.add(socket); workers.execute(()->serve(socket));
        } catch(IOException e) { if(!closed) close(); }
    }
    private void serve(Socket s) {
        Peer peer=null;
        try {
            s.setSoTimeout(8000); s.setTcpNoDelay(true);
            DataInputStream in=new DataInputStream(s.getInputStream()); DataOutputStream out=new DataOutputStream(s.getOutputStream());
            byte[] nonce=Wire.random(32); Wire.write(out,nonce);
            if(!MessageDigest.isEqual(Wire.read(in),Wire.proof(key,nonce,"client"))) throw new IOException("Authentication failed");
            Wire.write(out,Wire.proof(key,nonce,"server"));
            s.setSoTimeout(45000); peer=new Peer(s,in,out); peers.add(peer); Peer writing=peer;workers.execute(writing::writeLoop);broadcastPresence();
            long previous=0;
            while(!closed) {
                byte[] plain=Wire.decrypt(key,Wire.read(in));
                if(plain.length==1 && plain[0]==Wire.PING) { peer.send(plain); continue; }
                Wire.parseVoice(plain);
                long now=System.nanoTime(); if(now-previous<250_000_000L) throw new IOException("Rate limit"); previous=now;
                broadcast(plain);
            }
        } catch(Exception ignored) {
        } finally {
            sockets.remove(s); try{s.close();}catch(IOException ignored){}
            if(peer!=null) { peer.finished=true; peers.remove(peer); broadcastPresence(); }
        }
    }
    private void broadcastPresence() { broadcast(new byte[]{Wire.PRESENCE,(byte)peers.size()}); }
    private synchronized void broadcast(byte[] plain) {
        if(closed) return;
        for(Peer p:peers)p.send(plain);
    }
    private class Peer {
        final Socket socket; final DataInputStream in; final DataOutputStream out;
        final BlockingQueue<byte[]> pending=new ArrayBlockingQueue<>(4);volatile boolean finished;
        Peer(Socket s,DataInputStream i,DataOutputStream o){socket=s;in=i;out=o;}
        void send(byte[] plain) { if(!pending.offer(plain))try{socket.close();}catch(IOException ignored){} }
        void writeLoop(){
            try{while(!closed&&!finished){byte[] plain=pending.poll(1,TimeUnit.SECONDS);if(plain!=null)Wire.write(out,Wire.encrypt(key,plain));}}
            catch(Exception e){try{socket.close();}catch(IOException ignored){}}
        }
    }
    @Override public void close() {
        closed=true; try{server.close();}catch(IOException ignored){}
        for(Socket s:sockets)try{s.close();}catch(IOException ignored){}
        workers.shutdownNow();
    }
}
