package dev.mccli.control;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The secret a tool sends with every request. MCCLI_TOKEN wins; otherwise config/mccli-token is read, or created
 * once (owner-only on POSIX), so a tool on the same computer pairs by reading the file.
 */
public final class AccessToken {
    public static final String FILE = "mccli-token";
    private final String value;
    private final Path file;

    private AccessToken(String value, Path file) {
        this.value = value;
        this.file = file;
    }

    public static AccessToken load(Path configDir) throws IOException {
        String env = System.getenv("MCCLI_TOKEN");
        Path file = configDir.resolve(FILE);
        if (env != null && !env.isBlank()) {
            return new AccessToken(env.trim(), null);
        }
        if (Files.isRegularFile(file)) {
            String stored = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (stored.length() >= 32) {
                ownerOnly(file);
                return new AccessToken(stored, file);
            }
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String created = HexFormat.of().formatHex(bytes);
        Files.createDirectories(configDir);
        Files.writeString(file, created + "\n", StandardCharsets.UTF_8);
        ownerOnly(file);
        return new AccessToken(created, file);
    }

    private static void ownerOnly(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows: the file stays in the player's own profile folder.
        }
    }

    public boolean matches(String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    /** Where the token is stored, or null when it came from MCCLI_TOKEN. */
    public Path file() {
        return file;
    }

    public String value() {
        return value;
    }
}
