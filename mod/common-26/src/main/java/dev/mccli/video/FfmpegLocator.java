package dev.mccli.video;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Finds ffmpeg. Besides MCCLI_FFMPEG and PATH it looks where installers put it, because a game started from a
 * launcher often has an older PATH than the user's shell.
 */
public final class FfmpegLocator {
    public static final String ENV = "MCCLI_FFMPEG";
    private static volatile Path known;

    private FfmpegLocator() {}

    public static Path require() {
        Path k = known;
        if (k != null && Files.isRegularFile(k)) return k;
        Path found = find().orElseThrow(FfmpegLocator::notFound);
        known = found;
        return found;
    }

    private static Optional<Path> find() {
        String custom = System.getenv(ENV);
        if (custom != null && !custom.isBlank()) {
            Path p = Path.of(custom);
            return Files.isRegularFile(p) && runs(p) ? Optional.of(p) : Optional.empty();
        }
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String exe = windows ? "ffmpeg.exe" : "ffmpeg";
        List<Path> candidates = new ArrayList<>();
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!dir.isBlank()) candidates.add(Path.of(dir.trim()).resolve(exe));
        }
        if (windows) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) candidates.add(Path.of(local, "Microsoft", "WinGet", "Links", exe));
            candidates.add(Path.of("C:\\ffmpeg\\bin\\ffmpeg.exe"));
        } else {
            for (String p : List.of("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg", "/snap/bin/ffmpeg")) candidates.add(Path.of(p));
        }
        for (Path p : candidates) {
            if (Files.isRegularFile(p) && runs(p)) return Optional.of(p);
        }
        return Optional.empty();
    }

    private static boolean runs(Path ffmpeg) {
        try {
            Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static IllegalStateException notFound() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String install = os.contains("win") ? "winget install Gyan.FFmpeg" : os.contains("mac") ? "brew install ffmpeg" : "sudo apt install ffmpeg";
        return new IllegalStateException("FFMPEG_NOT_FOUND: ffmpeg is not installed or not on PATH. Install it (" + install
                + ") or set " + ENV + " to the executable before starting the game.");
    }
}
