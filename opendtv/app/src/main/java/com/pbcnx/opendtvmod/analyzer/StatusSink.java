package com.pbcnx.opendtvmod.analyzer;

/**
 * The one analyzer status boundary: every panel and controller writes status through this instead of
 * reaching into AnalyzerState for a LogsPanel reference.
 */
public interface StatusSink {
    void setStatus(String text);

    /** True while the caption probe should be logged by the ring (the Logs tab / services view). */
    void logCaptionProbe(boolean enabled);
}
