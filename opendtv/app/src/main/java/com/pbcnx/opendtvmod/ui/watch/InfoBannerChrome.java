package com.pbcnx.opendtvmod.ui.watch;

import android.os.Handler;
import android.view.View;

import com.pbcnx.opendtvmod.ts.ServiceInfo;

/** The channel info banner: content fill, visibility and the timed auto-hide. */
public final class InfoBannerChrome {

    public interface Host {
        Handler ui();
        View banner();
        ServiceInfo channelService();
        boolean watchCardShowing();
        void fillContent(ServiceInfo service);
        void updateButtonPosition();
        void updateBmlButton();
        void updateInfoBanner();
    }

    private final Host host;
    private final Runnable hide = this::hide;

    public InfoBannerChrome(Host host) {
        this.host = host;
    }

    public void show(int durationMs) {
        if (host.watchCardShowing()) return;
        host.ui().removeCallbacks(hide);
        View banner = host.banner();
        ServiceInfo svc = host.channelService();
        if (banner == null || svc == null) return;
        host.fillContent(svc);
        banner.setVisibility(View.VISIBLE);
        host.updateButtonPosition();
        host.updateBmlButton();
        host.updateInfoBanner();
        if (durationMs > 0) host.ui().postDelayed(hide, durationMs);
    }

    public void hide() {
        View banner = host.banner();
        if (banner != null) banner.setVisibility(View.GONE);
        host.updateButtonPosition();
        host.updateBmlButton();
    }

    public void cancel() {
        host.ui().removeCallbacks(hide);
    }
}
