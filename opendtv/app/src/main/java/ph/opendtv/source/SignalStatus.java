package ph.opendtv.source;

/** Demodulator measurements. Fields the chip cannot supply stay at their defaults. */
public final class SignalStatus {
    public boolean locked;
    public int strengthPercent = -1;
    public int qualityPercent = -1;
    public double snrDb = Double.NaN;
    /** Received power in dBm (OFDM reg 0x13f minus 100); Integer.MIN_VALUE when not read. */
    public int dbm = Integer.MIN_VALUE;
    public double ber = Double.NaN;
    /** Raw register values (OFDM 0x13e, 0x49, 0x1c9) for analysis; -1 when not read. */
    public int rawStrength = -1, rawQuality = -1, rawSnr = -1;
    /** Post-Viterbi error bits and measurement window (units of 204*8 bits) from OFDM 0x32..0x38. */
    public long berErrors = -1;
    public int berWindow = -1;
}