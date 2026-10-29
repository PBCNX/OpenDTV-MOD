package ph.opendtv;


/**
 * What the build actually stamped, read by name so that a new {@code buildConfigField} shows up
 * without a second edit here.
 *
 * <p>The commit is a build input, not something a device can know. Native's versioning commit owns
 * {@code build.gradle}; until it adds {@code GIT_COMMIT} (or {@code BUILD_COMMIT}, {@code COMMIT},
 * {@code GIT_SHA}) {@link #commit()} is empty and callers say so rather than inventing a hash.
 */
public final class BuildStamp {

    private static final String[] COMMIT_FIELDS = {
            "GIT_COMMIT", "BUILD_COMMIT", "COMMIT", "GIT_SHA"};

    private BuildStamp() {
    }

    /** Short commit, or "" when the build carries none. */
    public static String commit() {
        for (String name : COMMIT_FIELDS) {
            try {
                Object value = BuildConfig.class.getField(name).get(null);
                String text = value == null ? "" : String.valueOf(value).trim();
                if (!text.isEmpty()) return text;
            } catch (NoSuchFieldException | IllegalAccessException ignored) {
                // absent in this build variant; try the next candidate field
            }
        }
        return "";
    }

    /** "2026-10-26" out of BUILD_TIME ("yyyy-MM-dd HH:mm:ss"), or "". */
    public static String date() {
        String built = BuildConfig.BUILD_TIME;
        return built != null && built.length() >= 10 ? built.substring(0, 10) : "";
    }

    /** "15:02" out of BUILD_TIME, or "". */
    public static String time() {
        String built = BuildConfig.BUILD_TIME;
        return built != null && built.length() >= 16 ? built.substring(11, 16) : "";
    }

    /**
     * "Version 2.1 build [b2365db]", or "Version 2.1" when the build carries no commit. Both the
     * About box and the Settings row under it read this, so the same fact cannot drift into two
     * shapes.
     */
    public static String versionLine(String version) {
        String commit = commit();
        return commit.isEmpty()
                ? "Version " + version
                : "Version " + version + " build [" + commit + "]";
    }
}
