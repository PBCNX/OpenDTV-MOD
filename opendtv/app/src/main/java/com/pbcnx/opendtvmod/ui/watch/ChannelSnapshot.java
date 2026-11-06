package com.pbcnx.opendtvmod.ui.watch;

import com.pbcnx.opendtvmod.source.TunerSignal;
import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.TsDemux;

/**
 * One channel's display snapshot, shared by the three watch HUD surfaces (card, strip, status
 * row) so they cannot disagree about what they were told or take positional parameters.
 */
public final class ChannelSnapshot {
    public ServiceInfo service;
    public int khz;
    public TunerSignal signal;
    public long clockMs;
    public boolean data;
    public boolean caption;
    public boolean encrypted;
    public boolean emergency;
    public boolean live;
    /** The hub has a supported USB tuner right now: without it the surfaces must not say "Tuning…". */
    public boolean tunerPresent;
    public String nowName;
    public String nextName;
    public String networkName;
    public TsDemux demux;
}
