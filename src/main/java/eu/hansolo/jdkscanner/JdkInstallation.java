package eu.hansolo.jdkscanner;

import java.nio.file.Path;

/**
 * One JDK/JRE installation found on disk, as read from its {@code release} file (or, if that file
 * is missing, from a best-effort fallback - see JdkDetector).
 *
 * @param home              the JDK home directory (what JAVA_HOME would point to for this install)
 * @param javaVersion       e.g. "21.0.4+7" - from the release file's JAVA_VERSION
 * @param implementorRaw    the release file's IMPLEMENTOR value as-is, e.g. "Eclipse Adoptium",
 *                          "Azul Systems, Inc.", "SAP SE" - null if the release file had none
 * @param discoDistribution best-effort mapping of implementorRaw to a disco-api distribution key
 *                          (e.g. "temurin", "zulu", "sap_machine") - null if it couldn't be mapped,
 *                          in which case this install is reported but not checked for updates
 * @param packageType       "jdk" if this install has a compiler (bin/javac(.exe)), "jre" otherwise -
 *                          matters because disco-api's package_type filter must match, or you get
 *                          offered the wrong kind of build for what's actually installed
 * @param javafxBundled     whether this runtime image includes the javafx.* modules - matters
 *                          because disco-api's javafx_bundled filter must match the same way
 *                          package_type does, or the update offered may lack JavaFX the install
 *                          actually has (or vice versa)
 * @param source            how this install was found, for the report (e.g. "JAVA_HOME", a known
 *                          install directory, or a filesystem-walk root)
 */
public record JdkInstallation(Path home, String javaVersion, String implementorRaw, String discoDistribution, String packageType, boolean javafxBundled, String source) { }