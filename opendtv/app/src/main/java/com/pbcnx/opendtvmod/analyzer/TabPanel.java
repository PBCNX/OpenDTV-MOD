package com.pbcnx.opendtvmod.analyzer;

/**
 * One tab of the analyzer. Each panel owns its views, binds them once and repaints from the shared
 * {@link AnalyzerState} on the activity's tick. Before this, twelve sibling panels were built the
 * same way but exposed their per-tick method in four different shapes (with the demux, with the
 * services, with nothing, with a service), so the activity had to know each one's arguments.
 */
public interface TabPanel {

    /** The root view this panel owns, or null for controls that only bind/update. */
    default android.view.View view() {
        return null;
    }

    /** Binds the views this panel owns; called once from onCreate. */
    default void bindViews() {
        // intentionally empty
    }

    /** Repaints from the shared state. Cheap enough for every tick; skips when its surface is hidden. */
    default void update() {
        // intentionally empty
    }
}
