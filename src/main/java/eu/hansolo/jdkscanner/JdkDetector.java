package eu.hansolo.jdkscanner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;


public class JdkDetector {

    private static       String       userHome() { return System.getProperty("user.home", ""); }
    private static       String       sdkmanJavaCandidatesRoot() { return userHome() + "/.sdkman/candidates/java"; }
    private static       List<String> knownLinuxRoots() { return List.of("/usr/lib/jvm", "/opt", "/usr/java", sdkmanJavaCandidatesRoot(), "/home/linuxbrew/.linuxbrew/Cellar", "/snap"); }
    private static       List<String> knownMacosRoots() { return List.of("/Library/Java/JavaVirtualMachines", sdkmanJavaCandidatesRoot(), "/opt/homebrew/Cellar", "/usr/local/Cellar"); }
    private static final List<String> KNOWN_WINDOWS_ROOTS = List.of("C:\\Program Files\\Java", "C:\\Program Files\\Eclipse Adoptium", "C:\\Program Files\\Zulu", "C:\\Program Files (x86)\\Java");
    private static final int          WALK_MAX_DEPTH      = 4;


    public List<JdkInstallation> detect(final List<Path> extraWalkRoots) {
        final Map<Path, JdkInstallation> byRealPath = new LinkedHashMap<>();

        final String javaHome = System.getenv("JAVA_HOME");
        if (null != javaHome && !javaHome.isBlank()) {
            addIfJdkHome(byRealPath, Paths.get(javaHome), "JAVA_HOME");
        }

        final String javaOnPath = findJavaOnPath();
        if (null != javaOnPath) {
            // bin/java(.exe) -> its parent's parent is the JDK home.
            final Path home = Paths.get(javaOnPath).getParent().getParent();
            addIfJdkHome(byRealPath, home, "PATH");
        }

        for (final String root : knownRootsForThisOs()) {
            final Path rootPath = Paths.get(root);
            if (!Files.isDirectory(rootPath)) { continue; }

            if (root.endsWith("Cellar")) {
                scanHomebrewCellar(rootPath, byRealPath);
                continue;
            }
            if (root.equals("/snap")) {
                scanSnap(rootPath, byRealPath);
                continue;
            }

            try (Stream<Path> children = Files.list(rootPath)) {
                for (final Path child : children.toList()) {
                    final Path macHome = child.resolve("Contents/Home");
                    if (Files.isDirectory(macHome)) {
                        addIfJdkHome(byRealPath, macHome, "known install dir (" + root + ")");
                    } else {
                        addIfJdkHome(byRealPath, child, "known install dir (" + root + ")");
                    }
                }
            } catch (final IOException e) {
                // Not fatal, just means this one known root couldn't be listed (permissions, etc).
            }
        }

        for (final Path extraRoot : extraWalkRoots) {
            walkForJdkHomes(extraRoot, byRealPath);
        }

        return new ArrayList<>(byRealPath.values());
    }

    private void scanHomebrewCellar(final Path cellarRoot, final Map<Path, JdkInstallation> byRealPath) {
        try (Stream<Path> formulas = Files.list(cellarRoot)) {
            for (final Path formula : formulas.toList()) {
                if (!Files.isDirectory(formula)) { continue; }
                try (Stream<Path> versions = Files.list(formula)) {
                    for (final Path version : versions.toList()) {
                        final Path bottleHome = version.resolve("libexec/openjdk.jdk/Contents/Home");
                        final Path caskHome   = version.resolve("Contents/Home");
                        if (Files.isDirectory(bottleHome)) {
                            addIfJdkHome(byRealPath, bottleHome, "brew (" + cellarRoot + ")");
                        } else if (Files.isDirectory(caskHome)) {
                            addIfJdkHome(byRealPath, caskHome, "brew (" + cellarRoot + ")");
                        } else {
                            addIfJdkHome(byRealPath, version, "brew (" + cellarRoot + ")");
                        }
                    }
                } catch (final IOException e) {
                    // Not fatal, this one formula's versions couldn't be listed; others still scanned.
                }
            }
        } catch (final IOException e) {
            // Not fatal, Cellar itself couldn't be listed (permissions, etc).
        }
    }

    private void scanSnap(final Path snapRoot, final Map<Path, JdkInstallation> byRealPath) {
        try (Stream<Path> names = Files.list(snapRoot)) {
            for (final Path name : names.toList()) {
                final Path current    = name.resolve("current");
                final Path nestedJdk  = current.resolve("jdk");
                if (Files.isDirectory(nestedJdk)) {
                    addIfJdkHome(byRealPath, nestedJdk, "snap (" + snapRoot + ")");
                } else if (Files.isDirectory(current)) {
                    addIfJdkHome(byRealPath, current, "snap (" + snapRoot + ")");
                }
            }
        } catch (final IOException e) {
            // Not fatal, /snap itself couldn't be listed (permissions, etc).
        }
    }

    private List<String> knownRootsForThisOs() {
        final String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) { return knownMacosRoots(); }
        if (os.contains("win")) { return KNOWN_WINDOWS_ROOTS; }
        return knownLinuxRoots();
    }

    private String findJavaOnPath() {
        final String path = System.getenv("PATH");
        if (null == path) { return null; }
        final boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        final String  exeName = windows ? "java.exe" : "java";
        for (final String dir : path.split(java.io.File.pathSeparator)) {
            final Path candidate = Paths.get(dir, exeName);
            if (Files.isExecutable(candidate)) { return candidate.toString(); }
        }
        return null;
    }

    private void walkForJdkHomes(final Path root, final Map<Path, JdkInstallation> byRealPath) {
        if (!Files.isDirectory(root)) { return; }
        try (Stream<Path> paths = Files.walk(root, WALK_MAX_DEPTH)) {
            paths.filter(p -> p.getFileName() != null && "release".equals(p.getFileName().toString()))
                 .forEach(releaseFile -> addIfJdkHome(byRealPath, releaseFile.getParent(), "filesystem walk (" + root + ")"));
        } catch (final IOException | UncheckedIOException e) {
            // Not fatal, just means this one extra root couldn't be fully walked (permissions, a broken symlink, etc). Other roots and already-found installs are unaffected.
        }
    }

    private void addIfJdkHome(final Map<Path, JdkInstallation> byRealPath, final Path candidateHome, final String source) {
        if (null == candidateHome || !Files.isDirectory(candidateHome)) { return; }
        final Path releaseFile = candidateHome.resolve("release");
        if (!Files.isRegularFile(releaseFile)) { return; }

        final Path realPath;
        try {
            realPath = candidateHome.toRealPath();
        } catch (final IOException e) {
            return;
        }
        if (byRealPath.containsKey(realPath)) { return; }

        final Properties release = readReleaseFile(releaseFile);
        if (null == release) { return; }

        final String  javaVersion   = unquote(release.getProperty("JAVA_VERSION"));
        final String  implementor   = unquote(release.getProperty("IMPLEMENTOR"));
        final String  distribution  = VendorMapping.toDistribution(implementor).orElse(null);
        final String  packageType   = packageTypeOf(realPath);
        final boolean javafxBundled = javafxBundled(release, realPath);

        byRealPath.put(realPath, new JdkInstallation(realPath, javaVersion, implementor, distribution, packageType, javafxBundled, source));
    }

    private String packageTypeOf(final Path home) {
        final boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        final Path    javac   = home.resolve(windows ? "bin/javac.exe" : "bin/javac");
        return Files.isRegularFile(javac) ? "jdk" : "jre";
    }

    private boolean javafxBundled(final Properties release, final Path home) {
        final String modules = unquote(release.getProperty("MODULES"));
        if (null != modules) {
            for (final String module : modules.split("\\s+")) {
                if (module.startsWith("javafx.")) { return true; }
            }
            return false;
        }

        if (Files.isRegularFile(home.resolve("jre/lib/ext/jfxrt.jar")) || Files.isRegularFile(home.resolve("lib/ext/jfxrt.jar"))) {
            return true;
        }

        return javafxBundledViaListModules(home);
    }

    private boolean javafxBundledViaListModules(final Path home) {
        final boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        final Path    javaExe = home.resolve(windows ? "bin/java.exe" : "bin/java");
        if (!Files.isExecutable(javaExe)) { return false; }

        try {
            final Process process = new ProcessBuilder(javaExe.toString(), "--list-modules").redirectErrorStream(true).start();
            final String  output  = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return output.lines().anyMatch(line -> line.trim().startsWith("javafx."));
        } catch (final Exception e) {
            // Not fatal, falls through to "not bundled", consistent with every other best-effort check in this class. Covers a missing/broken java executable, a timeout, etc.
            return false;
        }
    }

    private Properties readReleaseFile(final Path releaseFile) {
        final Properties props = new Properties();
        try (var reader = Files.newBufferedReader(releaseFile)) {
            props.load(reader);
            return props;
        } catch (final IOException e) {
            return null;
        }
    }

    private String unquote(final String value) {
        if (null == value) { return null; }
        final String trimmed = value.trim();
        return (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) ? trimmed.substring(1, trimmed.length() - 1) : trimmed;
    }
}