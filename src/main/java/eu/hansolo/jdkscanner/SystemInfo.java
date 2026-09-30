package eu.hansolo.jdkscanner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;


public enum SystemInfo {
    INSTANCE;


    /** disco-api's "operating_system" value for this machine, e.g. "macos", "linux", "windows". */
    public static String currentOperatingSystem() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) { return "macos"; }
        if (os.contains("win")) { return "windows"; }
        if (os.contains("linux")) { return "linux"; }
        return os; // best-effort fallback for anything unrecognized (BSD, Solaris, ...)
    }

    /** disco-api's "architecture" value for this machine, e.g. "aarch64", "x64". */
    public static String currentArchitecture() {
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64"       -> "aarch64";
            case "x86", "i386", "i686"    -> "x86";
            case "arm"                    -> "arm";
            default                       -> arch;
        };
    }

    public static Optional<String> currentLibcType() {
        if (!"linux".equals(currentOperatingSystem())) { return Optional.empty(); }

        for (final String muslLoader : List.of(
        "/lib/ld-musl-x86_64.so.1", "/lib/ld-musl-aarch64.so.1", "/lib/ld-musl-armhf.so.1")) {
            if (Files.exists(Path.of(muslLoader))) { return Optional.of("musl"); }
        }

        try {
            final Process process = new ProcessBuilder("ldd", "--version").redirectErrorStream(true).start();
            final String  output  = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            process.waitFor(2, TimeUnit.SECONDS);

            if (output.contains("musl")) { return Optional.of("musl"); }
            if (output.contains("glibc") || output.contains("gnu")) { return Optional.of("glibc"); }
        } catch (final Exception e) {
            // ldd missing, not executable, timed out, etc. - fall through to "inconclusive".
        }
        return Optional.empty();
    }
}