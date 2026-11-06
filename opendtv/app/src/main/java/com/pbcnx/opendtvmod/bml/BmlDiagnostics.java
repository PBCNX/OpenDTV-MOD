package com.pbcnx.opendtvmod.bml;

import android.webkit.WebView;

/**
 * The engine's own diagnostics snapshot: the stash of the newest answer a live page gave, the
 * controller that most recently opened a page, and the ask itself.
 *
 * <p>Split out of {@link BmlController} without a logic change. The stash exists because a page
 * cannot be asked from the Developers screen: that screen is another activity, so the one that owns
 * the page is stopped and its page is closed on the way. The watch screen therefore asks while the
 * page is still alive and stashes the answer, and the roster shows "as of HH:MM:SS" when there is
 * no page left to ask.
 */
public final class BmlDiagnostics {

    /** What the diagnostics need from the page: the WebView, and whether a page is alive. */
    interface Page {
        WebView web();

        boolean active();
    }

    interface DiagReady {
        void onDiagnostics(String json);
    }

    private final Page page;
    private final com.pbcnx.opendtvmod.Diagnostics state;

    BmlDiagnostics(Page page, com.pbcnx.opendtvmod.Diagnostics state) {
        this.page = page;
        this.state = state;
    }

    /** The controller that most recently opened a page; weak on purpose. */
    void remember(BmlController controller) {
        state.rememberController(controller);
    }

    /** Called with the engine's own JSON while the page can still answer; ignores the failures. */
    void stash(String json) {
        state.stash(json);
    }

    /**
     * Ask the page what the engine thinks: phase, what is pending and why, locks, tables, script and
     * stream errors, and the configuration in force. Never answers a bare null: "no-page" and
     * "no-engine" are answers too, and a caller that cannot tell them apart has to guess - which is
     * how "the page did not answer" used to be said about a page that was never asked.
     */
    void ask(final DiagReady cb) {
        final WebView w = page.web();
        if (w == null || !page.active()) {
            cb.onDiagnostics("no-page");
            return;
        }
        w.evaluateJavascript(
                "(function(){try{var f=window.OpenDtvBml&&OpenDtvBml.diagnostics;"
                        + "if(!f)return 'no-engine';var j=JSON.stringify(OpenDtvBml.diagnostics());"
                        + "return j||'no-engine';}catch(e){return 'no-engine';}})()",
                new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        String clean = unwrapJavascriptString(v);
                        if (clean == null || clean.isEmpty() || "null".equals(clean)) clean = "no-engine";
                        if (!"no-engine".equals(clean) && !"no-page".equals(clean)) stash(clean);
                        cb.onDiagnostics(clean);
                    }
                });
    }

    /**
     * evaluateJavascript hands its result back as a JSON-quoted string, so a JSON object arrives with
     * its quotes escaped twice. This is the one place that unwrapping happens.
     */
    private static String unwrapJavascriptString(String v) {
        if (v == null) return null;
        if (v.length() > 1 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\/", "/");
        }
        return v;
    }
}
