package chat.vibe.app.core;

import java.io.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Bounded, authenticated room frames. No Java object deserialization. */
public final class Wire {
    public static final int PORT = 45735, MAX_AUDIO = 960000, MAX_FRAME = 970000;
    public static final int VOICE = 1, PRESENCE = 2, PING = 3;
    private static final SecureRandom RANDOM = new SecureRandom();
    public static byte[] random(int n) { byte[] b = new byte[n]; RANDOM.nextBytes(b); return b; }
    public static String keyText() { return Base64.getUrlEncoder().withoutPadding().encodeToString(random(32)); }
    public static byte[] key(String text) {
        byte[] b = Base64.getUrlDecoder().decode(text);
        if (b.length != 32) throw new IllegalArgumentException("邀请密钥格式不正确");
        return b;
    }
    public static byte[] proof(byte[] key, byte[] nonce, String role) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(role.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return mac.doFinal(nonce);
    }
    public static byte[] encrypt(byte[] key, byte[] plain) throws GeneralSecurityException {
        byte[] iv = random(12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(new byte[]{'V','I','B','E',1});
        byte[] body = c.doFinal(plain), out = new byte[iv.length + body.length];
        System.arraycopy(iv,0,out,0,iv.length); System.arraycopy(body,0,out,iv.length,body.length); return out;
    }
    public static byte[] decrypt(byte[] key, byte[] frame) throws GeneralSecurityException {
        if (frame.length < 29) throw new GeneralSecurityException("Invalid frame");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,frame,0,12));
        c.updateAAD(new byte[]{'V','I','B','E',1}); return c.doFinal(frame,12,frame.length-12);
    }
    public static byte[] read(DataInputStream in) throws IOException {
        int n=in.readInt(); if(n<1 || n>MAX_FRAME) throw new IOException("Frame size limit");
        byte[] b=new byte[n]; in.readFully(b); return b;
    }
    public static void write(DataOutputStream out, byte[] b) throws IOException {
        if(b.length<1 || b.length>MAX_FRAME) throw new IOException("Frame size limit");
        out.writeInt(b.length); out.write(b); out.flush();
    }
    public static class Message {
        public String id, sender, name; public long time; public byte[] pcm;
        public Message(String id, String sender, String name, long time, byte[] pcm) {
            this.id=id; this.sender=sender; this.name=name; this.time=time; this.pcm=pcm;
        }
        public int seconds() { return Math.max(1,(pcm.length+31999)/32000); }
    }
    public static byte[] voice(Message m) throws IOException {
        if(m.pcm.length<16000 || m.pcm.length>MAX_AUDIO || m.pcm.length%2!=0) throw new IOException("Audio size limit");
        ByteArrayOutputStream b=new ByteArrayOutputStream(); DataOutputStream o=new DataOutputStream(b);
        o.writeByte(VOICE); o.writeUTF(m.id); o.writeUTF(m.sender); o.writeUTF(m.name);
        o.writeLong(m.time); o.writeInt(m.pcm.length); o.write(m.pcm); return b.toByteArray();
    }
    public static Message parseVoice(byte[] packet) throws IOException {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(packet));
        if(in.readByte()!=VOICE) throw new IOException("Not voice");
        String id=in.readUTF(), sender=in.readUTF(), name=in.readUTF();
        long time=in.readLong(); int n=in.readInt();
        if(id.length()>64 || sender.length()>64 || name.length()>40 || n<16000 || n>MAX_AUDIO || n%2!=0 || n!=in.available()) throw new IOException("Invalid voice");
        byte[] pcm=new byte[n]; in.readFully(pcm); return new Message(id,sender,name,time,pcm);
    }
    public static String invitation(String host, int port, String secret) { return "vibe1|"+host+"|"+port+"|"+secret; }
    public static String[] parseInvitation(String invite) {
        String[] v=invite.trim().split("\\|",-1);
        if(v.length!=4 || !v[0].equals("vibe1") || v[1].isEmpty() || v[1].length()>253 || v[1].contains("/") || v[1].contains(" ")) throw new IllegalArgumentException("请粘贴完整的房间邀请码");
        int p=Integer.parseInt(v[2]); if(p<1 || p>65535) throw new IllegalArgumentException("端口不正确");
        key(v[3]); return v;
    }
}
