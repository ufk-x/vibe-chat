import chat.vibe.app.core.VoiceDelivery;

public final class VoiceDeliveryTest {
    static int checks;
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);checks++;}
    public static void main(String[] args){
        String local="10a203d4-1111-4222-8333-123456789abc",cache="cloud:alice:42";
        for(String stale:new String[]{"未听","发送失败","发送中","未确认送达"}){
            VoiceDelivery d=VoiceDelivery.restore("",cache,true,"chat","alice",stale);
            check(d==VoiceDelivery.SENT&&!d.pending(),"server cache must never become an unsent message: "+stale);
            check(!VoiceDelivery.local(cache,true,"chat","alice"),"server identifiers must not be uploaded as client ids");
        }
        check(VoiceDelivery.restore("",cache,false,"chat","alice","未听")==VoiceDelivery.NONE,"incoming playback state is not upload state");
        check(VoiceDelivery.restore("",local,true,"chat","alice","已送达服务器")==VoiceDelivery.SENT,"legacy successful upload stays delivered");
        check(VoiceDelivery.restore("SENT",local,true,"chat","alice","未听")==VoiceDelivery.SENT,"hearing state cannot undo delivery");
        check(VoiceDelivery.restore("SENDING",local,true,"chat","alice","发送中")==VoiceDelivery.RETRY,"process death preserves an uncertain upload for reconciliation");
        check(VoiceDelivery.restore("",local,true,"chat","alice","发送失败")==VoiceDelivery.RETRY,"a real local failure remains recoverable");
        check(VoiceDelivery.local(local,true,"chat","alice"),"original client id is retained for idempotent retry");
        check(!VoiceDelivery.local(local,false,"chat","alice")&&!VoiceDelivery.local(local,true,"","alice")&&!VoiceDelivery.local(local,true,"chat",""),"other-account/incoming/LAN data is not a cloud outbox item");
        check(VoiceDelivery.restore("",local,true,"","","未确认送达")==VoiceDelivery.NONE,"LAN entries are left to LAN transport");
        System.out.println(checks+" voice delivery regression checks passed");
    }
}
