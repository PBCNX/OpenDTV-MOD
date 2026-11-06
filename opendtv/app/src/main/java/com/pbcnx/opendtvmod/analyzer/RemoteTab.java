package com.pbcnx.opendtvmod.analyzer;

import androidx.annotation.Nullable;

import android.util.Log;
import android.view.View;

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.bml.BmlController;
import com.pbcnx.opendtvmod.bml.RemotePanel;
import com.pbcnx.opendtvmod.ts.ServiceInfo;


/** The data-broadcast viewer and the on-screen remote: video placement and key routing. */
public final class RemoteTab {
    private android.widget.FrameLayout playerFrame;
    private float[] videoFrac = null;


    private final AnalyzerActivity a;
    private final AnalyzerState state;
    private final StatusSink status;

    public RemoteTab(AnalyzerActivity activity, AnalyzerState state, StatusSink status) {
        this.a = activity;
        this.state = state;
        this.status = status;
    }

    // ------------------------------------------------------------------ data broadcast + remote

    public void setupRemote() {
        playerFrame = (android.widget.FrameLayout) a.findViewById(R.id.player_frame);
        state.bml = new BmlController(a, playerFrame, new BmlController.Host() {
            @Override
            public void onVideoRect(float l, float t, float r, float b, boolean invisible) {
                videoFrac = (invisible || (l <= 0.001f && t <= 0.001f && r >= 0.999f && b >= 0.999f))
                        ? null : new float[] {l, t, r, b};
                applyVideoRect();
            }

            @Override
            public void onStatus(String text) { status.setStatus(text); }

            @Override
            public void onFailed(String reason) { status.setStatus("BML: " + reason); }

            @Override
            public void onClosed() {
                videoFrac = null;
                applyVideoRect();
            }
        });
        playerFrame.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (videoFrac != null && (r - l != or - ol || b - t != ob - ot)) applyVideoRect();
            }
        });

        state.remotePanel = new RemotePanel(a, new RemotePanel.Listener() {
            @Override
            public void onKey(int code) { remoteKey(code); }

            @Override
            public void onExit() { state.bml.close(); }
        });
        state.remotePanel.setVisibility(View.GONE);
        android.view.ViewGroup holder = (android.view.ViewGroup) a.findViewById(R.id.content_frame);
        // Last child = top of the z-order, full size: the panel divides its height by its nine rows.
        holder.addView(state.remotePanel, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void remoteKey(int code) {
        if (!state.bml.isActive()) {
            if (code != BmlController.DATA) {
                status.setStatus("Press DATA first to open the data broadcast.");
                return;
            }
            ServiceInfo svc = AnalyzerUtil.findService(state.demux.getServices(), state.selectedServiceId);
            if (svc == null || !svc.pmtSeen) {
                status.setStatus("No service selected yet.");
                return;
            }
            boolean hasData = false;
            for (com.pbcnx.opendtvmod.ts.StreamInfo st : svc.streams) hasData |= st.isDatacast();
            if (!hasData) {
                status.setStatus("This service has no data broadcast.");
                return;
            }
            state.bmlServiceId = svc.serviceId;
            state.bml.open(state.demux, svc);
            // The page takes the keys now; on the Remote tab the pad keeps the D-pad, so
            // put focus on it rather than leaving the remote off-screen for the arrows.
            if (state.remotePanel != null && state.currentTab == AnalyzerState.TAB_REMOTE) state.remotePanel.focusFirst();
        }
        state.bml.tap(code);
    }

    /** BML moves/shrinks the video: place the PlayerView inside the frame accordingly. */
    private void applyVideoRect() {
        android.widget.FrameLayout.LayoutParams lp;
        int w = playerFrame.getWidth();
        int h = playerFrame.getHeight();
        if (videoFrac == null || w == 0 || h == 0) {
            lp = new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        } else {
            lp = new android.widget.FrameLayout.LayoutParams(Math.max(1, Math.round((videoFrac[2] - videoFrac[0]) * w)),
                    Math.max(1, Math.round((videoFrac[3] - videoFrac[1]) * h)));
            lp.leftMargin = Math.round(videoFrac[0] * w);
            lp.topMargin = Math.round(videoFrac[1] * h);
        }
        state.playerView.setLayoutParams(lp);
    }

    /**
     * @return true = consumed, false = deliberately left for the framework, null = ask the
     *         framework itself (the tri-state the caller already understands)
     */
    @Nullable public Boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            View focus = a.getCurrentFocus();
            String id;
            try {
                id = focus == null || focus.getId() == View.NO_ID ? "?" : a.getResources().getResourceEntryName(focus.getId());
            } catch (RuntimeException ignored) {
                id = "?";
            }
            Log.d(AnalyzerState.TAG, "key " + e.getKeyCode() + " focus=" + (focus == null ? "null" : focus.getClass().getSimpleName() + "/" + id));
        }
        // A live data broadcast usually owns the D-pad so the page can be driven. The one
        // exception is the Remote tab: the on-screen pad is the remote there, so the D-pad
        // moves between its keys - otherwise the colour keys, which this set's remote does
        // not have, were unreachable. Only the D-pad steps aside; digits and the colour
        // keys still reach the page directly, and the pad's arrow/OK keys send theirs.
        int kc = e.getKeyCode();
        boolean dPadKey = kc == android.view.KeyEvent.KEYCODE_DPAD_UP
                || kc == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                || kc == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                || kc == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                || kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || kc == android.view.KeyEvent.KEYCODE_ENTER;
        boolean padOwnsDpad = state.currentTab == AnalyzerState.TAB_REMOTE && dPadKey;
        if (state.bml != null && state.bml.isActive() && !padOwnsDpad
                && e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            int code = -1;
            switch (kc) {
                case android.view.KeyEvent.KEYCODE_DPAD_UP: code = BmlController.UP; break;
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN: code = BmlController.DOWN; break;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT: code = BmlController.LEFT; break;
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: code = BmlController.RIGHT; break;
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER: code = BmlController.ENTER; break;
                case android.view.KeyEvent.KEYCODE_PROG_RED: code = BmlController.RED; break;
                case android.view.KeyEvent.KEYCODE_PROG_GREEN: code = BmlController.GREEN; break;
                case android.view.KeyEvent.KEYCODE_PROG_YELLOW: code = BmlController.YELLOW; break;
                case android.view.KeyEvent.KEYCODE_PROG_BLUE: code = BmlController.BLUE; break;
                case 230: code = BmlController.DATA; break;   // KEYCODE_TV_DATA_SERVICE
                default:
                    if (kc >= android.view.KeyEvent.KEYCODE_0 && kc <= android.view.KeyEvent.KEYCODE_9)
                        code = BmlController.DIGIT0 + kc - android.view.KeyEvent.KEYCODE_0;
            }
            if (code >= 0) {
                state.bml.tap(code);
                return true;
            }
        }
        // CH up/down on the remote step the multiplex: on this screen a "channel" is a
        // frequency, not a service. Deliberately after the BML block, so a data
        // broadcast keeps first refusal on its own keys.
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN
                && e.getRepeatCount() == 0
                && (e.getKeyCode() == android.view.KeyEvent.KEYCODE_CHANNEL_UP
                || e.getKeyCode() == android.view.KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            state.tuner.stepChannel(e.getKeyCode() == android.view.KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
            return true;
        }
        return null;
    }

    /**
     * The framework resolves BACK on key UP, so consuming the DOWN in dispatchKeyEvent
     * never stopped the activity from finishing - with a data broadcast on screen the
     * only way out was quitting the app. Closing it here catches every BACK, from a
     * remote or a gesture.
     */
    public boolean handleBack() {
        boolean bmlUp = state.bml != null && state.bml.isActive();
        Log.d(AnalyzerState.TAG, "back: bml active=" + bmlUp + " tab=" + state.currentTab);
        if (bmlUp) {
            state.bml.close();
            View tab = a.findViewById(AnalyzerState.TAB_LABEL_IDS[state.currentTab]);
            if (tab != null) tab.requestFocus();
            status.setStatus("Data broadcast closed.");
            return true;
        }
        return false;
    }
}
