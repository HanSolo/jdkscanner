package eu.hansolo.jdkscanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One-shot CLI: finds JDK installations on this machine, checks each against disco-api's GA
 * catalog for its major version, and reports which ones have a newer build available.
 *
 * Every update lookup is filtered by THIS machine's operating system, architecture and each
 * install's package type (jdk/jre) - skipping any of those is how you end up offered a build that
 * can't even run here (e.g. a linux/musl/x64 build on an Apple-silicon Mac).
 *
 * Usage:
 *   java -jar jdk-update-scanner.jar [--base-url URL] [--walk DIR ...] [--json]
 *
 *   --base-url URL    disco-api base URL (default: http://hansolo.eu:8080
 *   --walk DIR        an extra directory to search recursively (shallow, max depth 4) for JDK
 *                     installs the known-location scan won't find, e.g. a custom tools folder. (Repeatable)
 *   --json            print one JSON document to stdout instead of the human-readable report,
 *                     see README.md for the schema. In this mode stdout carries ONLY that JSON
 *                     document (nothing else, so it stays parseable); anything unexpected that
 *                     would otherwise print alongside it goes to stderr instead.
 */
public class JdkUpdateScanner {

    public static void main(final String[] args) {
        final String            baseUrl         = argValue(args, "--base-url").orElse("http://hansolo.eu:8080");
        final List<Path>        walkRoots       = argValues(args, "--walk").stream().map(Paths::get).toList();
        final boolean           jsonOutput      = hasFlag(args, "--json");
        final JdkDetector       detector        = new JdkDetector();
        final DiscoApiClient    apiClient       = new DiscoApiClient(baseUrl);
        final String            operatingSystem = SystemInfo.INSTANCE.currentOperatingSystem();
        final String            architecture    = SystemInfo.INSTANCE.currentArchitecture();
        final Optional<String>  libcType        = SystemInfo.INSTANCE.currentLibcType();

        if (!jsonOutput) {
            System.out.println("This machine: operating_system=" + operatingSystem + ", architecture=" + architecture + ("linux".equals(operatingSystem) ? ", libc_type=" + libcType.orElse("undetermined - will check both glibc and musl") : ""));
        }

        final List<JdkInstallation> installations       = detector.detect(walkRoots);
        final JsonArray             installationReports = new JsonArray();

        for (final JdkInstallation install : installations) {
            installationReports.add(buildReport(install, apiClient, operatingSystem, architecture, libcType.orElse(null)));
        }

        if (jsonOutput) {
            printJson(operatingSystem, architecture, libcType, installationReports);
        } else {
            printHuman(installations, installationReports);
        }
    }

    private static JsonObject buildReport(final JdkInstallation install, final DiscoApiClient apiClient, final String operatingSystem, final String architecture, final String libcType) {
        final JsonObject report = new JsonObject();
        report.addProperty("home",           install.home().toString());
        report.addProperty("source",         install.source());
        putNullable(report, "java_version",     install.javaVersion());
        putNullable(report, "vendor",           install.implementorRaw());
        report.addProperty("package_type",   install.packageType());
        report.addProperty("javafx_bundled", install.javafxBundled());
        putNullable(report, "distribution",     install.discoDistribution());

        final Optional<InstallManagerDetector.ManagerInstall> managerInstall = InstallManagerDetector.detect(install.home());
        putNullable(report, "install_manager", managerInstall.map(InstallManagerDetector.ManagerInstall::manager).orElse(null));
        report.addProperty("request_successful", true);

        if (null == install.discoDistribution()) {
            report.addProperty("status", "skipped_unknown_vendor");
            report.addProperty("message", "vendor \"" + install.implementorRaw() + "\" isn't in this tool's implementor->distribution mapping (see VendorMapping.java)");
            return report;
        }
        if (null == install.javaVersion()) {
            report.addProperty("status", "skipped_no_version");
            report.addProperty("message", "no JAVA_VERSION in this install's release file");
            return report;
        }

        final int majorVersion = VersionComparator.INSTANCE.featureVersion(install.javaVersion());
        if (majorVersion <= 0) {
            report.addProperty("status", "skipped_unparseable_version");
            report.addProperty("message", "couldn't parse a major version from \"" + install.javaVersion() + "\"");
            return report;
        }

        final String releaseStatus = VersionComparator.INSTANCE.isEarlyAccess(install.javaVersion()) ? "ea" : "ga";
        report.addProperty("release_status", releaseStatus);

        try {
            final boolean   checkBothLibcTypes = "linux".equals(operatingSystem) && null == libcType;
            final JsonArray candidates         = apiClient.searchPackages(install.discoDistribution(), majorVersion, releaseStatus, operatingSystem, architecture, install.packageType(), install.javafxBundled(), checkBothLibcTypes ? null : libcType);

            if (candidates.isEmpty()) {
                report.addProperty("status", "no_matching_build");
                report.addProperty("message", "no " + releaseStatus.toUpperCase() + " build found on disco-api for distribution=\"" +
                                              install.discoDistribution() + "\", jdk_version=" + majorVersion +
                                              ", operating_system=" + operatingSystem + ", architecture=" + architecture +
                                              ", package_type=" + install.packageType() + ", javafx_bundled=" + install.javafxBundled());
                return report;
            }

            final JsonArray candidateReports   = new JsonArray();
            boolean         anyUpdateAvailable = false;
            if (checkBothLibcTypes) {
                for (final Map.Entry<String, JsonObject> entry : bestPerLibcType(candidates).entrySet()) {
                    final JsonObject candidateReport = describeCandidate(install, entry.getValue(), entry.getKey(), managerInstall);
                    anyUpdateAvailable |= candidateReport.get("update_available").getAsBoolean();
                    candidateReports.add(candidateReport);
                }
            } else {
                final JsonObject candidateReport = describeCandidate(install, bestOf(candidates), libcType, managerInstall);
                anyUpdateAvailable = candidateReport.get("update_available").getAsBoolean();
                candidateReports.add(candidateReport);
            }

            report.addProperty("status", anyUpdateAvailable ? "update_available" : "up_to_date");
            report.add("candidates", candidateReports);
        } catch (final Exception e) {
            report.addProperty("request_successful", false);
            report.addProperty("status", "error");
            report.addProperty("message", e.getMessage());
        }
        return report;
    }

    private static JsonObject describeCandidate(final JdkInstallation install, final JsonObject pkg, final String libcType, final Optional<InstallManagerDetector.ManagerInstall> managerInstall) {
        final String  latestVersion   = getString(pkg, "java_version");
        final String  downloadUri     = getString(pkg, "direct_download_uri");
        final boolean updateAvailable = null != latestVersion && VersionComparator.INSTANCE.isNewer(install.javaVersion(), latestVersion);

        final JsonObject candidateReport = new JsonObject();
        putNullable(candidateReport, "libc_type", libcType);
        putNullable(candidateReport, "latest_version", latestVersion);
        candidateReport.addProperty("update_available", updateAvailable);
        putNullable(candidateReport, "download_uri", downloadUri);

        if (updateAvailable && managerInstall.isPresent()) {
            final String newVersionDotted = VersionComparator.INSTANCE.toDottedString(latestVersion);
            putNullable(candidateReport, "update_command", managerInstall.get().updateCommand(newVersionDotted));
        }
        return candidateReport;
    }

    private static Map<String, JsonObject> bestPerLibcType(final JsonArray candidates) {
        final Map<String, JsonObject> bestPerLibc = new LinkedHashMap<>();
        for (final JsonElement el : candidates) {
            final JsonObject pkg     = el.getAsJsonObject();
            final String     libc    = orUnknown(getString(pkg, "libc_type"));
            final JsonObject current = bestPerLibc.get(libc);
            if (null == current || isBetter(pkg, current)) {
                bestPerLibc.put(libc, pkg);
            }
        }
        return bestPerLibc;
    }

    private static JsonObject bestOf(final JsonArray candidates) {
        JsonObject best = null;
        for (final JsonElement el : candidates) {
            final JsonObject pkg = el.getAsJsonObject();
            if (null == best || isBetter(pkg, best)) { best = pkg; }
        }
        return best;
    }

    private static boolean isBetter(final JsonObject candidate, final JsonObject currentBest) {
        return VersionComparator.INSTANCE.isNewer(getString(currentBest, "java_version"), getString(candidate, "java_version"));
    }

    // ---- Output ----

    private static void printJson(final String operatingSystem, final String architecture,
                                  final Optional<String> libcType, final JsonArray installationReports) {
        final JsonObject machine = new JsonObject();
        machine.addProperty("operating_system", operatingSystem);
        machine.addProperty("architecture", architecture);
        putNullable(machine, "libc_type", libcType.orElse(null));
        machine.addProperty("libc_type_determined", libcType.isPresent());

        final JsonObject root = new JsonObject();
        root.add("machine", machine);
        root.add("installations", installationReports);

        final Gson gson = new GsonBuilder().setPrettyPrinting().create();
        System.out.println(gson.toJson(root));
    }

    private static void printHuman(final List<JdkInstallation> installations, final JsonArray installationReports) {
        if (installations.isEmpty()) {
            System.out.println("No JDK installations found. Try --walk <dir> to search a custom install location.");
            return;
        }
        System.out.println("\nFound " + installations.size() + " JDK installation(s):\n");

        for (int i = 0; i < installations.size(); i++) {
            final JdkInstallation install = installations.get(i);
            final JsonObject      report  = installationReports.get(i).getAsJsonObject();

            System.out.println("- " + install.home());
            System.out.println("  Found via:    " + install.source());
            System.out.println("  Version:      " + orUnknown(install.javaVersion()));
            System.out.println("  Vendor:       " + orUnknown(install.implementorRaw()));
            System.out.println("  Package type: " + install.packageType());
            System.out.println("  JavaFX:       " + (install.javafxBundled() ? "bundled" : "not bundled"));
            final String installManager = getString(report, "install_manager");
            if (null != installManager) {
                System.out.println("  Installed by: " + installManager);
            }
            final String releaseStatus = getString(report, "release_status");
            if (null != releaseStatus) {
                System.out.println("  Channel:      " + ("ea".equals(releaseStatus) ? "early access" : "general availability"));
            }

            final String status = getString(report, "status");
            if (!status.equals("update_available") && !status.equals("up_to_date")) {
                System.out.println("  Update check: skipped - " + getString(report, "message"));
                System.out.println();
                continue;
            }

            for (final JsonElement el : report.getAsJsonArray("candidates")) {
                final JsonObject candidate = el.getAsJsonObject();
                final String     prefix    = report.getAsJsonArray("candidates").size() > 1 ? "  [" + orUnknown(getString(candidate, "libc_type")) + "] " : "  ";
                if (candidate.get("update_available").getAsBoolean()) {
                    System.out.println(prefix + "UPDATE AVAILABLE: " + getString(candidate, "latest_version") + " (installed: " + install.javaVersion() + ")");
                    final String downloadUri = getString(candidate, "download_uri");
                    System.out.println(prefix + "Download: " + (null != downloadUri ? downloadUri : "(no direct download link available for this build)"));
                    final String updateCommand = getString(candidate, "update_command");
                    if (null != updateCommand) {
                        System.out.println(prefix + "Update via: " + updateCommand);
                    }
                } else {
                    final String channel = "ea".equals(getString(report, "release_status")) ? "EA" : "GA";
                    System.out.println(prefix + "Up to date (latest known " + channel + ": " + orUnknown(getString(candidate, "latest_version")) + ")");
                }
            }
            System.out.println();
        }
    }

    // ---- Helpers ----

    private static void putNullable(final JsonObject obj, final String field, final String value) {
        if (null != value) { obj.addProperty(field, value); } else { obj.add(field, com.google.gson.JsonNull.INSTANCE); }
    }

    private static String getString(final JsonObject obj, final String field) {
        final var el = obj.get(field);
        return (null != el && !el.isJsonNull()) ? el.getAsString() : null;
    }

    private static String orUnknown(final String s) { return null == s ? "unknown" : s; }

    private static boolean hasFlag(final String[] args, final String flag) {
        for (final String arg : args) {
            if (arg.equals(flag)) { return true; }
        }
        return false;
    }

    private static Optional<String> argValue(final String[] args, final String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) { return Optional.of(args[i + 1]); }
        }
        return Optional.empty();
    }

    private static List<String> argValues(final String[] args, final String flag) {
        final List<String> values = new ArrayList<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) { values.add(args[i + 1]); }
        }
        return values;
    }
}