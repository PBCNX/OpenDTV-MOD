package com.pbcnx.opendtvmod.tuner;

import androidx.annotation.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.StreamInfo;
import com.pbcnx.opendtvmod.ts.TsDemux;

/**
 * The "is the service parsed yet?" wait: every tuner caller asked it its own way - three loops with
 * different predicates, poll intervals and timeouts, and the same 12 s/25 s hardware-vs-network rule
 * copied into two of them. The demux is a supplier because a retune swaps in a fresh one.
 */
public final class ServiceWait {

    /** Poll interval while a PMT is being parsed (it usually lands within ~100 ms). */
    public static final long FAST_MS = 20;
    /** Poll interval for the TV session, which is less latency-sensitive. */
    public static final long CALM_MS = 150;

    private ServiceWait() {
    }

    /** Waits for the service with the given id to have its PMT and its video. */
    @Nullable public static ServiceInfo byId(Supplier<TsDemux> demux, int serviceId, long limitMs,
                                   long pollMs, BooleanSupplier cancelled) {
        long start = System.currentTimeMillis();
        while (!cancelled.getAsBoolean() && System.currentTimeMillis() - start < limitMs) {
            for (ServiceInfo s : demux.get().getServices()) {
                if (s.serviceId == serviceId && s.pmtSeen && s.hasVideo()) return s;
            }
            if (sleep(pollMs)) return null;
        }
        return null;
    }

    /**
     * The TV session's variant: prefer the TS id the scan stored, but accept any TS id after 4 s
     * (the broadcaster may have changed it since).
     */
    @Nullable public static ServiceInfo preferredTsId(Supplier<TsDemux> demux, int serviceId, int tsId,
                                            long limitMs, BooleanSupplier cancelled) {
        long start = System.currentTimeMillis();
        while (!cancelled.getAsBoolean() && System.currentTimeMillis() - start < limitMs) {
            TsDemux d = demux.get();
            for (ServiceInfo s : d.getServices()) {
                if (s.serviceId != serviceId || !s.pmtSeen || s.streams.isEmpty() || !s.hasVideo()) continue;
                if (tsId <= 0 || d.getTsId() == tsId || System.currentTimeMillis() - start > 4000) return s;
            }
            if (sleep(CALM_MS)) return null;
        }
        return null;
    }

    /**
     * The capture/watch path: the best service of whatever source is playing. A data+video service
     * wins, then any video service, and a data-only service is a last resort after 3 s - a capture
     * whose data-only service appears first must not hide the TV service behind it.
     *
     * <p>PMTs do not arrive together, so "any video service" cannot mean "the first one parsed": on a
     * capture whose PAT lists 3200..3225 the oneseg service (3224, video and no datacast) has its PMT
     * parsed first, and the main service - video AND datacast, the one with BML and EWBS - would then
     * never be selected. While a service with a LOWER id still has no PMT, a lower-id service may
     * still turn out to be the video+data one, so the plain-video answer is held back for
     * {@link #LOWER_PMT_GRACE_MS}; after that the best seen is returned rather than waiting on a PID
     * that may be dead.</p>
     *
     * <p>The first version of that holdback checked "a lower service was seen unparsed while a video
     * candidate already existed" inside the same pass. Services are visited in ascending id order, so
     * the lower service is always seen BEFORE the higher candidate exists and the condition could
     * never be true - the holdback was dead code and 3224 was still adopted. The lowest unparsed id is
     * tracked and compared after the pass now.</p>
     */
    private static final long LOWER_PMT_GRACE_MS = 2_000;

    @Nullable public static ServiceInfo anyPlayable(Supplier<TsDemux> demux, long limitMs,
                                          BooleanSupplier cancelled) {
        long start = System.currentTimeMillis();
        while (!cancelled.getAsBoolean() && System.currentTimeMillis() - start < limitMs) {
            ServiceInfo both = null;
            ServiceInfo withVideo = null;
            ServiceInfo withData = null;
            int lowestUnparsed = Integer.MAX_VALUE;
            for (ServiceInfo s : demux.get().getServices()) {
                if (!s.pmtSeen) {
                    if (s.serviceId < lowestUnparsed) lowestUnparsed = s.serviceId;
                    continue;
                }
                boolean hasData = false;
                for (StreamInfo st : s.streams) hasData |= st.isDatacast();
                boolean hasVideo = s.hasVideo();
                if (hasVideo && hasData && lowerSid(s, both)) both = s;
                if (hasVideo && lowerSid(s, withVideo)) withVideo = s;
                if (!hasVideo && hasData && lowerSid(s, withData)) withData = s;
            }
            if (both != null) return both;
            if (withVideo != null) {
                boolean mayFindBoth = lowestUnparsed < withVideo.serviceId
                        && System.currentTimeMillis() - start < LOWER_PMT_GRACE_MS;
                if (!mayFindBoth) return withVideo;
            }
            if (withData != null && System.currentTimeMillis() - start > 3_000) return withData;
            if (sleep(50)) return null;
        }
        return withVideoBest(demux);
    }

    /** Last look before giving up: the best video service seen, if any (the loop may have timed out). */
    @Nullable private static ServiceInfo withVideoBest(Supplier<TsDemux> demux) {
        ServiceInfo best = null;
        for (ServiceInfo s : demux.get().getServices()) {
            if (s.pmtSeen && s.hasVideo() && lowerSid(s, best)) best = s;
        }
        return best;
    }

    private static boolean lowerSid(ServiceInfo candidate, ServiceInfo current) {
        return current == null || candidate.serviceId < current.serviceId;
    }

    /** Returns true when interrupted (the caller returns null), restoring the interrupt flag. */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return true;
        }
    }
}
