import chat.vibe.app.core.*;

/** Optional single-room relay. No accounts, offline queue, or managed hosting. */
public final class RelayMain {
    public static void main(String[] args)throws Exception {
        String host=args.length>0?args[0]:"127.0.0.1";
        int port=args.length>1?Integer.parseInt(args[1]):Wire.PORT;
        String key=System.getenv("VIBE_ROOM_KEY");
        if(key==null||key.isEmpty())key=Wire.keyText();
        RoomServer server=new RoomServer(port,Wire.key(key));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("Vibe Chat room relay is listening on port "+server.port());
        System.out.println("Private invitation (share only with intended members):");
        System.out.println(Wire.invitation(host,server.port(),key));
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
