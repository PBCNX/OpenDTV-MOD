package com.pbcnx.opendtvmod;

import java.lang.ref.WeakReference;

import androidx.annotation.Nullable;

import com.pbcnx.opendtvmod.bml.BmlController;
import com.pbcnx.opendtvmod.ts.caption.CaptionProbe;

/**
 * Process-wide diagnostics state, owned by {@link App}: the newest BML controller, the last engine
 * snapshot it stashed, and the newest caption probe. The classes that produce these values write
 * here, and the Developers screen reads through this one object; App.onCreate resets it.
 */
public final class Diagnostics {

    private WeakReference<BmlController> lastController;
    private WeakReference<CaptionProbe> lastProbe;
    private String stashedSnapshot;
    private long stashedAtMs;

    /** The controller that most recently opened a page; weak on purpose. */
    public synchronized void rememberController(BmlController controller) {
        lastController = new WeakReference<>(controller);
    }

    @Nullable public synchronized BmlController lastController() {
        return lastController == null ? null : lastController.get();
    }

    /** The newest caption probe, weak so a dead filter is not kept alive. */
    public synchronized void rememberProbe(CaptionProbe probe) {
        lastProbe = new WeakReference<>(probe);
    }

    @Nullable public synchronized CaptionProbe lastProbe() {
        return lastProbe == null ? null : lastProbe.get();
    }

    /** Called with the engine's own JSON while the page can still answer; ignores failures. */
    public synchronized void stash(String json) {
        if (json == null || "null".equals(json) || "no-engine".equals(json) || "no-page".equals(json)) return;
        stashedSnapshot = json;
        stashedAtMs = System.currentTimeMillis();
    }

    @Nullable public synchronized String stashedSnapshot() {
        return stashedSnapshot;
    }

    public synchronized long stashedAtMs() {
        return stashedAtMs;
    }

    /** Clears every process-wide diagnostic reference; called from App.onCreate. */
    public synchronized void reset() {
        lastController = null;
        lastProbe = null;
        stashedSnapshot = null;
        stashedAtMs = 0;
    }
}
