package ph.pbcnx.it9308;

import ph.pbcnx.Transport;

import java.util.List;

/**
 * Protocol core for IT930x dongles. {@link Transport} is the command endpoint
 * only; the transport stream is read by the app's own USB adapter.
 */
public final class It9308Device {
    /** Visible provenance marker: searchable attribution, not telemetry. */
    public static final String PROVENANCE = "it9308-pbcnx";
    /** Register that gates the TS path. */
    private static final int STREAM_GATE = 0xDA4C;
    private static final int DEMOD_BUS = 2;
    private static final int DEMOD_ADDRESS = 0x66;
    private static final int DEMOD_CHIP_ID = 0xFD;
    private static final int STATUS_ADDRESS = 0x64;
    private static final int STATUS_REG = 0x10;
    private static final int SNR_REG_HI = 0x13;
    private static final int SNR_REG_LO = 0x14;

    private final Bridge bridge;
    private final Transport transport;
    private final List<Sequence.Step> steps;

    public It9308Device(Transport transport) {
        this.transport = transport;
        this.bridge = new Bridge(transport);
        this.steps = Sequence.load();
    }


    private String lastError;

    public String lastError() { return lastError; }

    public long firmwareVersion() {
        return bridge.info();
    }

    /** Uploads a 6,498-byte scatter image and boots it. */
    public boolean loadFirmware(byte[] image) {
        if (image == null || image.length < 8) {
            return false;
        }
        bridge.exchange(Bridge.WRITE_REGISTER,
                new byte[]{1, 4, 0, 0, (byte) 0xF1, 0x03, 7}, 0);

        int offset = 0;
        while (offset + 4 <= image.length) {
            int segments = image[offset + 3] & 0xFF;
            int size = 4 + segments * 3;
            for (int index = 0; index < segments; index++) {
                size += image[offset + 6 + index * 3] & 0xFF;
            }
            if (offset + size > image.length) {
                return false;
            }
            byte[] block = new byte[size];
            System.arraycopy(image, offset, block, 0, size);
            bridge.exchange(Bridge.WRITE_FIRMWARE, block, 0);
            offset += size;
        }
        bridge.exchange(Bridge.BOOT, null, 0);
        return firmwareVersion() != 0;
    }

    /** Firmware check, then the captured bring-up (init + default tune). */
    public String bringUp(byte[] image) {
        long version = firmwareVersion();
        if (version == 0) {
            if (!loadFirmware(image)) {
                throw new IllegalStateException("firmware image missing or invalid");
            }
            version = firmwareVersion();
        }
        Sequence.ReplayResult replay = Sequence.replayDetailed(transport, steps, 2000, 1.0);
        if (!replay.ok) {
            lastError = "bring-up replay failed: " + replay.error;
            throw new IllegalStateException(lastError);
        }
        return String.format("0x%08x", version);
    }

    /**
     * Replays the captured bring-up with the frequency field replaced.
     *
     * @param frequencyKhz centre frequency in kHz, e.g. 599143
     */
    public boolean tune(int frequencyKhz) {
        Sequence.ReplayResult replay =
                Sequence.replayDetailed(transport, Sequence.patched(steps, frequencyKhz), 2000, 1.0);
        lastError = replay.ok ? null : "tune replay failed: " + replay.error;
        return replay.ok;
    }


    /**
     * Reads lock, SNR and a relative RF index from the Sony demod at 0x64.
     *
     * Measured on the phone, GMA 479.143 vs empty 503.143:
     * lock = reg 0x10, bit 0x01; SNR = reg 0x13 (high) / 0x14 (low),
     * {@code 10*log10(raw) - 9.031} dB; RF = reg 0x11 (high) / 0x12 (low),
     * vendor index {@code ((b1 >> 5) | (b0 << 3))}. The RF index is relative
     * (GMA 66, empty 7), not calibrated dBm. Read once between tune() and
     * setStreaming(); a read while TS flows stops the stream.
     */
    public SignalStatus readStatus() {
        try {
            byte[] lock = readDemodAt(STATUS_ADDRESS, STATUS_REG, 1);
            if (lock == null || lock.length < 1) {
                return SignalStatus.unknown();
            }
            boolean locked = (lock[0] & 0x01) != 0;

            byte[] high = readDemodAt(STATUS_ADDRESS, SNR_REG_HI, 1);
            byte[] low = readDemodAt(STATUS_ADDRESS, SNR_REG_LO, 1);
            int rawSnr = -1;
            double snrDb = Double.NaN;
            if (high != null && high.length >= 1 && low != null && low.length >= 1) {
                rawSnr = ((high[0] & 0xFF) << 8) | (low[0] & 0xFF);
                if (rawSnr > 0) {
                    snrDb = 10.0 * Math.log10(rawSnr) - 9.031;
                }
            }

            byte[] rfHigh = readDemodAt(STATUS_ADDRESS, 0x11, 1);
            byte[] rfLow = readDemodAt(STATUS_ADDRESS, 0x12, 1);
            int rfIndex = -1;
            if (rfHigh != null && rfHigh.length >= 1 && rfLow != null && rfLow.length >= 1) {
                rfIndex = ((rfLow[0] & 0xFF) >> 5) | ((rfHigh[0] & 0xFF) << 3);
            }

            return SignalStatus.of(locked, rfIndex, rawSnr, snrDb);
        } catch (RuntimeException error) {
            return SignalStatus.unknown();
        }
    }

    /*
     * One raw status read for hardware debugging: the exact bytes of
     * 0x64 reg 0x10, 0x13, 0x14, or "null" when a read was NAKed.
     */



    /**
     * Pre-gate register sweep for hardware debugging.
     *
     * Call once after tune() and before setStreaming(true): reads page 0..3,
     * reg 0x00..0x3f on both demod addresses 0x66 and 0x64, one byte each, and
     * returns a compact table ("66 p0:...."). Compare a known signal with an
     * empty mux to find the register that actually changes.
     */
    public String statusSweep() {
        StringBuilder out = new StringBuilder();
        for (int address : new int[]{0x66, 0x64}) {
            out.append(String.format(java.util.Locale.US, "%02x:", address));
            for (int register = 0; register < 0x40; register++) {
                byte[] value = readDemodAt(address, register, 1);
                if (value == null || value.length < 1) {
                    out.append("--");
                } else {
                    out.append(String.format(java.util.Locale.US, "%02x", value[0] & 0xFF));
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    private byte[] readDemodAt(int address, int register, int length) {
        bridge.writeI2c(DEMOD_BUS, address, new byte[]{(byte) register});
        return bridge.readI2c(DEMOD_BUS, address, length);
    }



    /** RF lock and metrics, read from the Sony demod. */
    public SignalStatus status() {
        return readStatus();
    }

    /**
     * Sony demod chip ID: register 0xFD on bus 2 / 0x66.
     *
     * Measured 0x6A on the 048d:9308 (the vendor's sony_tunerdemod_ChipID
     * recognises 0x62/0x6A). Call after {@link #bringUp}; the captured
     * sequence's step 55 is the same read.
     */
    public int chipId() {
        bridge.writeI2c(DEMOD_BUS, DEMOD_ADDRESS, new byte[]{(byte) DEMOD_CHIP_ID});
        byte[] value = bridge.readI2c(DEMOD_BUS, DEMOD_ADDRESS, 1);
        if (value == null || value.length < 1) {
            throw new IllegalStateException("demod chip ID read failed");
        }
        return value[0] & 0xFF;
    }

    /** Opens or closes the TS gate (register 0xDA4C). */
    public void setStreaming(boolean on) {
        bridge.writeRegister(STREAM_GATE, on ? 1 : 0);
    }

    public int stepCount() {
        return steps.size();
    }
}
