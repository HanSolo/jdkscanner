package eu.hansolo.jdkscanner;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


public enum VersionComparator {
    INSTANCE;

    private static final int     MAX_COMPONENTS   = 4; // FEATURE.INTERIM.UPDATE.PATCH
    private static final Pattern LEGACY_PREFIX    = Pattern.compile("^1\\.\\d+([._].*)?$");
    private static final Pattern NUMERIC_PREFIX   = Pattern.compile("^[0-9.]+");
    private static final Pattern BUILD_NUMBER     = Pattern.compile("\\+(\\d+)");
    private static final Pattern EARLY_ACCESS_TAG = Pattern.compile("-ea\\b", Pattern.CASE_INSENSITIVE);


    /** @return true if {@code candidate} looks newer than {@code installed}. */
    public static boolean isNewer(final String installed, final String candidate) {
        if (null == installed || null == candidate) { return false; }
        final int[] a   = normalize(installed);
        final int[] b   = normalize(candidate);
        final int   len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            final int ai = i < a.length ? a[i] : 0;
            final int bi = i < b.length ? b[i] : 0;
            if (bi != ai) { return bi > ai; }
        }
        final int buildA = buildNumberOf(installed);
        final int buildB = buildNumberOf(candidate);
        return buildA >= 0 && buildB >= 0 && buildB > buildA;
    }

    /** @return true if this version string carries an early-access tag (e.g. "26-ea+15"). */
    public static boolean isEarlyAccess(final String rawVersion) {
        return null != rawVersion && EARLY_ACCESS_TAG.matcher(rawVersion).find();
    }

    private static int buildNumberOf(final String rawVersion) {
        if (null == rawVersion) { return -1; }
        final Matcher m = BUILD_NUMBER.matcher(rawVersion);
        if (!m.find()) { return -1; }
        try {
            return Integer.parseInt(m.group(1));
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    public static int featureVersion(final String rawVersion) {
        final int[] components = normalize(rawVersion);
        return components.length > 0 ? components[0] : -1;
    }

    public static String toDottedString(final String rawVersion) {
        final int[] components = normalize(rawVersion);
        if (0 == components.length) { return rawVersion; }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < components.length; i++) {
            if (i > 0) { sb.append('.'); }
            sb.append(components[i]);
        }
        return sb.toString();
    }

    static int[] normalize(final String rawVersion) {
        if (null == rawVersion) { return new int[0]; }
        String v = rawVersion.trim();

        // "1.8.0_252" -> "8.0_252" (Disco reports this release as "8.0.252", major version 8, not 1).
        if (LEGACY_PREFIX.matcher(v).matches()) {
            v = v.substring(2);
        }
        // The legacy scheme's update separator; by this point any remaining "_" is one of these.
        v = v.replace('_', '.');

        // Keep only the leading digits-and-dots run - drops a trailing "+<build>" and/or "-ea"
        // pre-release tag, whichever comes first.
        final Matcher m = NUMERIC_PREFIX.matcher(v);
        if (!m.find()) { return new int[0]; }

        final String[] parts = m.group().split("\\.");
        final int[] components = new int[Math.min(parts.length, MAX_COMPONENTS)];
        for (int i = 0; i < components.length; i++) {
            try {
                components[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (final NumberFormatException e) {
                components[i] = 0;
            }
        }
        return components;
    }
}