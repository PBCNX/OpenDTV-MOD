package ph.pbcnx.it9175;

import ph.pbcnx.Transport;

/** Protocol core for ITE IT9175 dongles: firmware, init, tune, status. */
public final class It9175Device {
    /** Visible provenance marker: searchable attribution, not telemetry. */
    public static final String PROVENANCE = "it9175-pbcnx";
    private static final int LINK = 0;

    private final AfaIo io;
    private final Tuner tuner;

    public It9175Device(Transport transport) {
        this.io = new AfaIo(transport);
        this.tuner = new Tuner(io);
    }

    public byte[] readRegisters(int processor, long address, int count) {
        return io.read(processor, address, count);
    }

    public boolean writeRegisters(int processor, long address, byte[] data) {
        return io.write(processor, address, data);
    }

    public byte[] firmwareVersion(int processor) {
        return io.firmwareVersion(processor);
    }

    /** Uploads a 9,036-byte image using the chunk table, then starts it. */
    public boolean loadFirmware(byte[] image) {
        if (image == null || image.length != Firmware.SIZE) {
            return false;
        }
        if (!io.write(LINK, 0x4BFB, new byte[]{0})
                || !io.write(LINK, 0xCFFF, new byte[]{0})
                || !io.write(LINK, 0xF103, new byte[]{7})) {
            return false;
        }

        int offset = 0;
        for (int size : Firmware.CHUNKS) {
            byte[] chunk = new byte[size];
            System.arraycopy(image, offset, chunk, 0, size);
            offset += size;
            if (io.command(41, LINK, chunk, 0) == null) {
                return false;
            }
        }
        return io.command(35, LINK, null, 0) != null;
    }

    /** Reads the chip id and uploads firmware when the version is blank. */
    public String bringUp(byte[] image) {
        byte[] identifier = readRegisters(LINK, 0x1222, 3);
        if (identifier == null) {
            throw new IllegalStateException("cannot read chip id");
        }
        int version = identifier[0] & 0xFF;
        int chip = (identifier[1] & 0xFF) | ((identifier[2] & 0xFF) << 8);

        byte[] firmware = firmwareVersion(LINK);
        if (firmware == null) {
            throw new IllegalStateException("no answer to the firmware version query");
        }
        boolean blank = true;
        for (byte value : firmware) {
            blank &= value == 0;
        }
        if (blank) {
            if (!loadFirmware(image)) {
                throw new IllegalStateException("firmware image missing or wrong size");
            }
            firmware = firmwareVersion(LINK);
            if (firmware == null) {
                throw new IllegalStateException("firmware did not start");
            }
        }
        return "chip version=" + version + " id=0x" + Integer.toHexString(chip)
                + " firmware=" + toHex(firmware);
    }

    public boolean init() {
        return tuner.init();
    }

    public boolean tune(int frequencyKhz) {
        return tuner.tune(frequencyKhz);
    }

    public boolean waitLock(boolean quick) {
        return tuner.waitLock(quick);
    }

    public boolean isLocked() {
        return tuner.mpeg2Locked();
    }

    public Status status() {
        return tuner.status();
    }

    private static String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder();
        for (byte value : data) {
            builder.append(String.format("%02x ", value & 0xFF));
        }
        return builder.toString().trim();
    }
}
