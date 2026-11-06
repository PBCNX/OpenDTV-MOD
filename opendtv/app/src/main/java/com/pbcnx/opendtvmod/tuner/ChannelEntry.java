package com.pbcnx.opendtvmod.tuner;

import com.pbcnx.opendtvmod.ts.ServiceInfo;

/**
 * One channel of the saved list: its multiplex frequency and the service on it.
 *
 * <p>It lives with the tuner, not inside the activity that used to own it: {@code BenchHooks} and
 * the channel-file code need the type without importing an activity, which is one of the
 * package cycles the root activity package used to create.</p>
 */
public final class ChannelEntry {
    public int khz;
    public ServiceInfo service;

    public ChannelEntry(int khz, ServiceInfo service) {
        this.khz = khz;
        this.service = service;
        // The entry is the only place the two are known together, so it stamps the mux on the
        // service: a bare ServiceInfo cannot say which one it came from, and the star (like the
        // saved-channel check) has to tell same-SID services apart.
        service.khz = khz;
    }
}
