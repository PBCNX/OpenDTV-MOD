package ph.opendtv.source;

/**
 * Marker for a source that is a live USB tuner rather than a file or a test feed.
 *
 * It exists so callers can ask "is this a tuner?" without naming the individual drivers:
 * the public tree drops the private ones, and an {@code instanceof} against a class that
 * is not in the build would not compile.
 */
public interface TunerSource extends TsSource {
}
