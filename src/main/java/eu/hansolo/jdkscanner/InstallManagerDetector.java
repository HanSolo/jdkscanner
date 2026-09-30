package eu.hansolo.jdkscanner;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Figures out whether an install was likely put there by sdkman, Homebrew, or snap - and if so,
 * derives that tool's own identifier for it directly from the install path, so the update command
 * this offers is exact rather than guessed. This works regardless of which known root actually
 * found the install (JdkDetector's sdkman/Cellar/snap roots, JAVA_HOME, PATH, or --walk), since it
 * only looks at the resolved home path's structure.
 */
public final class InstallManagerDetector {

    private InstallManagerDetector() { }


    public record ManagerInstall(String manager, String identifier) {
        /**
         * @param newVersionDotted the new version, already stripped of build metadata (e.g."21.0.5", not "21.0.5+9" - see VersionComparator.toDottedString)
         * @return the command to run to get that version through this install's manager
         */
        public String updateCommand(final String newVersionDotted) {
            return switch (manager) {
                case "brew" -> "brew upgrade " + identifier;
                case "snap" -> "sudo snap refresh " + identifier;
                case "sdkman" -> {
                    // identifier is sdkman's OWN candidate id for the version currently installed,
                    // e.g. "21.0.4-tem" - sdkman ids are "<version>-<vendor suffix>", so swapping
                    // in the new version ahead of the existing vendor suffix is a good bet, but it
                    // is still a guess about the new id, unlike brew/snap above which name the
                    // package itself (unaffected by version). Flagged as such in the output.
                    final int    dash         = identifier.lastIndexOf('-');
                    final String vendorSuffix = dash >= 0 ? identifier.substring(dash) : "";
                    yield "sdk install java " + newVersionDotted + vendorSuffix + "   (best-effort candidate id - run `sdk list java` to confirm the exact identifier if this doesn't match)";
                }
                default -> null;
            };
        }
    }

    public static Optional<ManagerInstall> detect(final Path home) {
        final String path = home.toString();

        if (path.contains(File.separator + ".sdkman" + File.separator + "candidates" + File.separator + "java" + File.separator)) {
            // Each candidates/java/<id> directory IS the JDK home, so its own name is sdkman's id.
            final String candidateId = home.getFileName().toString();
            return Optional.of(new ManagerInstall("sdkman", candidateId));
        }

        final int cellarIndex = indexOfSegment(home, "Cellar");
        if (cellarIndex >= 0 && cellarIndex + 1 < home.getNameCount()) {
            final String formula = home.getName(cellarIndex + 1).toString();
            return Optional.of(new ManagerInstall("brew", formula));
        }

        final int snapIndex = indexOfSegment(home, "snap");
        if (snapIndex >= 0 && snapIndex + 1 < home.getNameCount()) {
            final String snapName = home.getName(snapIndex + 1).toString();
            return Optional.of(new ManagerInstall("snap", snapName));
        }

        return Optional.empty();
    }

    private static int indexOfSegment(final Path path, final String segment) {
        for (int i = 0; i < path.getNameCount(); i++) {
            if (path.getName(i).toString().equals(segment)) { return i; }
        }
        return -1;
    }
}