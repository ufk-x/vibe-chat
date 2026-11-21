package chat.vibe.app.core;

/** Upload state is independent of playback/heard state. */
public enum VoiceDelivery {
    NONE, SENDING, RETRY, SENT;

    public boolean pending(){return this==SENDING||this==RETRY;}
    public static boolean local(String id,boolean outgoing,String chat,String account){
        return outgoing&&!chat.isEmpty()&&!account.isEmpty()&&id.matches("[a-f0-9-]{36}");
    }
    public static VoiceDelivery restore(String saved,String id,boolean outgoing,String chat,String account,String legacy){
        // cloud:<account>:<server id> is a downloaded, already-delivered message,
        // including old cached copies accidentally marked as failed by 0.4.0.
        if(id.startsWith("cloud:"))return outgoing?SENT:NONE;
        if(!local(id,outgoing,chat,account))return NONE;
        if("SENT".equals(saved)||"已送达服务器".equals(legacy))return SENT;
        // An interrupted upload may have reached the server. Keep its original
        // client id so history reconciliation or an idempotent retry can confirm it.
        return RETRY;
    }
}
