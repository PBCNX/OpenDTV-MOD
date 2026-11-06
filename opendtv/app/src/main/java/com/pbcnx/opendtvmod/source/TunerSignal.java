package com.pbcnx.opendtvmod.source;

/**
 * Demodulator measurements, in the app's terms. Fields the chip cannot supply stay at their defaults.
 *
 * <p>Named TunerSignal rather than SignalStatus on purpose: the vendor cores under {@code ph.pbcnx}
 * have their own immutable {@code SignalStatus}, and the two are opposite in kind (this one is a
 * mutable struct the drivers fill in). The shared name used to force fully-qualified types wherever
 * both were in scope.</p>
 */
public final class TunerSignal {
    public boolean locked;
    public int strengthPercent = -1;
    public int qualityPercent = -1;
    public double snrDb = Double.NaN;
    /** Received power in dBm (OFDM reg 0x13f minus 100); Integer.MIN_VALUE when not read. */
    public int dbm = Integer.MIN_VALUE;
    public double ber = Double.NaN;
    /** Raw register values (OFDM 0x13e, 0x49, 0x1c9) for analysis; -1 when not read. */
    public int rawStrength = -1;
    public int rawQuality = -1;
    public int rawSnr = -1;
    /** Post-Viterbi error bits and measurement window (units of 204*8 bits) from OFDM 0x32..0x38. */
    public long berErrors = -1;
    public int berWindow = -1;
}