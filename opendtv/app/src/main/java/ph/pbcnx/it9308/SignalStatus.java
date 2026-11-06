package ph.pbcnx.it9308;

/**
 * Tune/stream status.
 *
 * {@code strength} is the relative RF index clamped to 0..100 (not dBm);
 * {@code quality} stays -1 (the app maps SNR to a percentage).
 * {@code rawStrength} is the RF index, {@code rawSnr} the 16-bit SNR raw
 * value, and {@code snrDb = 10*log10(rawSnr) - 9.031} from the vendor formula.
 */
public final class SignalStatus {
    public final boolean known;
    public final boolean locked;
    public final int strength;
    public final int quality;
    public final int rawStrength;
    public final int rawSnr;
    public final double snrDb;

    private SignalStatus(boolean known, boolean locked, int strength, int quality,
                         int rawStrength, int rawSnr, double snrDb) {
        this.known = known;
        this.locked = locked;
        this.strength = strength;
        this.quality = quality;
        this.rawStrength = rawStrength;
        this.rawSnr = rawSnr;
        this.snrDb = snrDb;
    }

    public static SignalStatus unknown() {
        return new SignalStatus(false, false, -1, -1, -1, -1, Double.NaN);
    }

    public static SignalStatus of(boolean locked, int rawStrength, int rawSnr, double snrDb) {
        int strength = rawStrength >= 0 ? Math.min(100, rawStrength) : -1;
        return new SignalStatus(true, locked, strength, -1, rawStrength, rawSnr, snrDb);
    }

    @Override
    public String toString() {
        if (!known) {
            return "unknown";
        }
        return "locked=" + locked + " rawStrength=" + rawStrength
                + " rawSnr=" + rawSnr + " snrDb=" + String.format(java.util.Locale.US, "%.2f", snrDb);
    }
}
