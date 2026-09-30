package eu.hansolo.jdkscanner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Best-effort mapping from a JDK's {@code release} file IMPLEMENTOR string to the distribution key
 * disco-api uses (the same keys SearchJdkPackagesTool/GetDistributionInfoTool validate against via
 * /disco/v4.0/parameters). This is NOT authoritative - vendors are free to put whatever string they
 * like in IMPLEMENTOR, and it has changed wording between releases of the same vendor's builds. Add
 * entries here as you find installs this doesn't yet recognize; an unrecognized IMPLEMENTOR is
 * reported in the scan output rather than silently dropped, so gaps are visible.
 */
public final class VendorMapping {

    private static final Map<String, String> IMPLEMENTOR_TO_DISTRIBUTION = new LinkedHashMap<>();
    static {
        IMPLEMENTOR_TO_DISTRIBUTION.put("eclipse adoptium", "temurin");
        IMPLEMENTOR_TO_DISTRIBUTION.put("eclipse foundation", "temurin");
        IMPLEMENTOR_TO_DISTRIBUTION.put("adoptopenjdk", "aoj");
        IMPLEMENTOR_TO_DISTRIBUTION.put("azul systems, inc.", "zulu");
        IMPLEMENTOR_TO_DISTRIBUTION.put("azul systems", "zulu");
        IMPLEMENTOR_TO_DISTRIBUTION.put("amazon.com inc.", "corretto");
        IMPLEMENTOR_TO_DISTRIBUTION.put("amazon.com", "corretto");
        IMPLEMENTOR_TO_DISTRIBUTION.put("sap se", "sap_machine");
        IMPLEMENTOR_TO_DISTRIBUTION.put("oracle corporation", "oracle");
        IMPLEMENTOR_TO_DISTRIBUTION.put("bellsoft", "liberica");
        IMPLEMENTOR_TO_DISTRIBUTION.put("microsoft", "microsoft");
        IMPLEMENTOR_TO_DISTRIBUTION.put("ibm corporation", "semeru");
        IMPLEMENTOR_TO_DISTRIBUTION.put("ibm", "semeru");
        IMPLEMENTOR_TO_DISTRIBUTION.put("graalvm community", "graalvm_community");
        IMPLEMENTOR_TO_DISTRIBUTION.put("oracle graalvm", "graalvm");
        IMPLEMENTOR_TO_DISTRIBUTION.put("openlogic", "openlogic");
        IMPLEMENTOR_TO_DISTRIBUTION.put("jetbrains s.r.o.", "jetbrains");
        IMPLEMENTOR_TO_DISTRIBUTION.put("alibaba", "dragonwell");
        IMPLEMENTOR_TO_DISTRIBUTION.put("tencent", "kona");
        IMPLEMENTOR_TO_DISTRIBUTION.put("bisheng", "bisheng");
        IMPLEMENTOR_TO_DISTRIBUTION.put("mandrel", "mandrel");
        IMPLEMENTOR_TO_DISTRIBUTION.put("ojdkbuild", "ojdk_build");
        IMPLEMENTOR_TO_DISTRIBUTION.put("huawei", "bisheng");
        IMPLEMENTOR_TO_DISTRIBUTION.put("red hat, inc.", "temurin");
    }

    private VendorMapping() { }

    /** @return the disco-api distribution key for this IMPLEMENTOR string, if recognized. */
    public static Optional<String> toDistribution(final String implementorRaw) {
        if (null == implementorRaw) { return Optional.empty(); }
        return Optional.ofNullable(IMPLEMENTOR_TO_DISTRIBUTION.get(implementorRaw.trim().toLowerCase()));
    }
}
