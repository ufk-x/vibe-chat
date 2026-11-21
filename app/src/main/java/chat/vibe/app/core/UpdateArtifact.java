package chat.vibe.app.core;

/** A different release must never reuse an installer's file identity. */
public final class UpdateArtifact {
    private UpdateArtifact() {}
    public static String name(long version, String sha256) {
        if (version <= 0 || sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid release");
        return "VibeChat-" + version + "-" + sha256 + ".apk";
    }
    public static boolean validName(String name) {
        return name != null && name.matches("VibeChat-[1-9][0-9]{0,18}-[a-f0-9]{64}\\.apk");
    }
}
