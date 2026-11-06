package com.pbcnx.opendtvmod.ui.watch;

import java.util.List;

import com.pbcnx.opendtvmod.tuner.ChannelEntry;
import com.pbcnx.opendtvmod.tuner.ChannelStore;

/** The channel-step arithmetic: find the current row, then wrap one step either way. */
public final class ChannelStep {

    private ChannelStep() {
    }

    /** Index of the entry with the same (frequency, service) key, or -1. */
    public static int indexOf(List<ChannelEntry> channels, String key) {
        for (int i = 0; i < channels.size(); i++) {
            ChannelEntry ce = channels.get(i);
            if (ChannelStore.key(ce.khz, ce.service.serviceId).equals(key)) return i;
        }
        return -1;
    }

    /** Wrapping step; -1 when the list is empty (the caller must do nothing). */
    public static int nextIndex(int count, int currentIndex, int delta) {
        if (count <= 0) return -1;
        return (currentIndex + delta + count) % count;
    }
}
