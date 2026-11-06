package ph.pbcnx;

import androidx.annotation.Nullable;

/**
 * The byte transport every tuner core talks through: implemented by the host
 * ({@code UsbDeviceConnection} on Android, a fake in tests).
 *
 * <p>One seam, one failure convention - it used to be three identical interfaces in the three core
 * packages with two conventions between them (two threw on a failed read, one returned null), so a
 * caller had to know which core it was holding.</p>
 *
 * <ul>
 *   <li>{@link #write}: sends every byte or throws {@link IllegalStateException}. A short write is
 *       a dead link, never a partial command.</li>
 *   <li>{@link #read}: returns the bytes that arrived (possibly fewer than asked) or {@code null}
 *       when the transfer failed or timed out. Null is "no reply", never data - a caller that treats
 *       it as bytes is the bug this convention exists to prevent.</li>
 * </ul>
 */
public interface Transport {

    /** Sends all of {@code data} or throws; {@code timeoutMs} bounds each transfer. */
    void write(byte[] data, int timeoutMs);

    /** Reads up to {@code maxLength} bytes, or null when the transfer failed or timed out. */
    @Nullable byte[] read(int maxLength, int timeoutMs);
}
