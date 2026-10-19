package ph.opendtv.ts;

/** Receives every valid, aligned 188-byte packet from TsDemux. */
public interface PacketSink {
    void onPacket(byte[] buf, int off);

    /** Called once at the end of each TsDemux.feed() call. */
    void flush();
}
