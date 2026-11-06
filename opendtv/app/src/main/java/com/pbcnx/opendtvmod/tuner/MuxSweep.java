package com.pbcnx.opendtvmod.tuner;

import androidx.annotation.Nullable;

import android.util.Log;

import java.util.Locale;

import com.pbcnx.opendtvmod.ts.TsDemux;

/**
 * Ranks every UHF mux by what it actually delivers, because the tuners here cannot rank by strength.
 *
 * <p>Why not a signal meter: the clean-room IT9308 core has no decoded RF metrics
 * ({@code ph.pbcnx.it9308.SignalStatus.unknown()} is the only status it can produce), and the vendor paths' strength
 * numbers are not comparable between sticks. What is comparable, and is what the viewer feels, is the
 * stream: packets received in a fixed window against the continuity errors counted in the same
 * window. A weak mux delivers fewer packets and more CC errors; a strong one delivers full packets
 * and no CC errors.
 *
 * <p>Bench tool only: it retunes the whole band, so it must never run while a viewer is watching.
 */
public final class MuxSweep {

    private static final String TAG = "OpenDTV-Sweep";

    /** UHF 14 to 51 in the Philippine plan: 473.143 MHz + 6 MHz per channel. */
    public static final int FIRST_KHZ = 473143;
    public static final int LAST_KHZ = 695143;   // UHF 51
    public static final int STEP_KHZ = 6000;

    /** How long each mux is watched before it is scored. */
    private static final long DWELL_MS = 2500;

    public interface Tuner {
        boolean tune(int khz);

        TsDemux demux();
    }

    /** One row of the table, kept for the log and for picking the winner. */
    public static final class Row {
        public final int khz;
        public final long packets;
        public final int ccErrors;
        public final int services;
        public final double score;

        Row(int khz, long packets, int ccErrors, int services) {
            this.khz = khz;
            this.packets = packets;
            this.ccErrors = ccErrors;
            this.services = services;
            // Rank by CLEANLINESS, not by volume: raw packets just reward a higher bitrate, and the
            // question is which mux arrives intact. score is continuity errors per million packets,
            // so lower is better and a perfect mux scores 0.
            this.score = packets > 0 ? ccErrors * 1_000_000.0 / packets : Double.MAX_VALUE;
        }

        public String line() {
            return String.format(Locale.US, "%d kHz  UHF %d  pkts %d  cc %d  services %d  errors/Mpkt %.0f",
                    this.khz, uhf(this.khz), this.packets, this.ccErrors, this.services, this.score);
        }
    }

    public static int uhf(int khz) {
        return 14 + Math.round((khz - FIRST_KHZ) / (float) STEP_KHZ);
    }

    /** The frequency of a UHF channel in this plan (14 = FIRST_KHZ). */
    public static int khz(int uhfChannel) {
        return FIRST_KHZ + (uhfChannel - 14) * STEP_KHZ;
    }

    /** The UHF channel when the frequency is on a centre inside the 14..51 plan, else -1. */
    public static int planChannel(int khz) {
        int ch = channel(khz);
        return ch > 0 && khz <= LAST_KHZ ? ch : -1;
    }

    /** Compact form without the unit (chips): UHF/VHF number, else MHz. */
    public static String compactLabel(int khz) {
        int uhf = planChannel(khz);
        if (uhf >= 0) return "UHF " + uhf + " \u00b7 " + MhzLabel.number(khz);
        if (khz >= 177143 && khz <= 177143 + 6 * 6000 && (khz - 177143) % 6000 == 0) {
            return "VHF " + (7 + (khz - 177143) / 6000) + " \u00b7 " + MhzLabel.number(khz);
        }
        return MhzLabel.mhz(khz);
    }

    /** The one user-facing label rule: UHF channel in the plan, VHF 7..12, else MHz. */
    public static String label(int khz) {
        int uhf = planChannel(khz);
        if (uhf >= 0) return "UHF " + uhf + " \u00b7 " + MhzLabel.mhz(khz);
        if (khz >= 177143 && khz <= 177143 + 6 * 6000 && (khz - 177143) % 6000 == 0) {
            return "VHF " + (7 + (khz - 177143) / 6000) + " \u00b7 " + MhzLabel.mhz(khz);
        }
        return MhzLabel.mhz(khz);
    }

    /** The UHF channel of a frequency on a channel centre, or -1 when it is off the plan. */
    public static int channel(int khz) {
        if (khz < FIRST_KHZ) return -1;
        int offset = khz - FIRST_KHZ;
        return offset % STEP_KHZ == 0 ? 14 + offset / STEP_KHZ : -1;
    }

    private MuxSweep() {
    }

    /**
     * Sweeps the band and returns the strongest mux, or null when no mux carried a service - in that
     * case it tunes back to {@code fallbackKhz} and logs it. Callers that only want the side effect
     * (the bench sweep) ignore the result.
     */
    @Nullable public static Row sweep(Tuner tuner, int fallbackKhz) {
        Row best = null;
        for (int khz = FIRST_KHZ; khz <= LAST_KHZ; khz += STEP_KHZ) {
            if (!tuner.tune(khz)) {
                Log.i(TAG, String.format(Locale.US, "%d kHz  UHF %d  tune refused", khz, uhf(khz)));
                continue;
            }
            // Baseline AFTER the tune: a retune swaps in a fresh demux, so a counter taken from the
            // previous one would compare two different objects and read as a huge loss (or a gain).
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                break;
            }
            TsDemux before = tuner.demux();
            long p0 = before != null ? before.getPacketCount() : 0;
            long cc0 = before != null ? before.sample().ccErrors : 0L;
            try {
                Thread.sleep(DWELL_MS);
            } catch (InterruptedException e) {
                break;
            }
            TsDemux d = tuner.demux();
            if (d == null) continue;
            long packets = d.getPacketCount() - p0;
            int cc = (int) (d.sample().ccErrors - cc0);
            int services = 0;
            for (com.pbcnx.opendtvmod.ts.ServiceInfo s : d.getServices()) {
                if (s.pmtSeen && s.hasVideo()) services++;
            }
            Row row = new Row(khz, packets, cc, services);
            Log.i(TAG, row.line());
            if (row.services > 0 && (best == null || row.score < best.score
                    || (row.score == best.score && row.packets > best.packets))) {
                best = row;
            }
        }
        if (best != null) {
            Log.i(TAG, "strongest: " + best.line());
            tuner.tune(best.khz);
            return best;
        }
        Log.w(TAG, "no mux carried a service; leaving " + fallbackKhz + " kHz");
        tuner.tune(fallbackKhz);
        return null;
    }
}
