package ph.opendtv.ts;

/** One elementary stream listed in a PMT. */
public final class StreamInfo {
    public int pid;
    public int streamType;
    /** From stream_identifier_descriptor (0x52), -1 if absent. */
    public int componentTag = -1;
    /** From data_component_descriptor (0xFD), -1 if absent. */
    public int dataComponentId = -1;
    /** ISO 639-2 language (ISO_639_language_descriptor 0x0A / audio_component_descriptor 0xC4), null if absent. */
    public String language;
    /** Second language of a dual-language audio stream (audio_component_descriptor), null if none. */
    public String language2;

    public boolean isVideo() {
        switch (streamType) {
            case 0x01: case 0x02: case 0x10: case 0x1B: case 0x24:
                return true;
            default:
                return false;
        }
    }

    public boolean isAudio() {
        switch (streamType) {
            case 0x03: case 0x04: case 0x0F: case 0x11: case 0x81:
                return true;
            default:
                return false;
        }
    }

    /** DSM-CC types used for data broadcasting (datacast): U-N messages, stream descriptors, data carousel. */
    public boolean isDatacast() {
        return streamType == 0x0B || streamType == 0x0C || streamType == 0x0D;
    }

    public String codecName() {
        switch (streamType) {
            case 0x01: return "MPEG-1 Video";
            case 0x02: return "MPEG-2 Video";
            case 0x03: return "MPEG-1 Audio";
            case 0x04: return "MPEG-2 Audio";
            case 0x06: return "PES private (captions/text)";
            case 0x0B: return "DSM-CC U-N messages";
            case 0x0C: return "DSM-CC stream descriptors";
            case 0x0D: return "DSM-CC data carousel";
            case 0x0F: return "AAC (ADTS)";
            case 0x10: return "MPEG-4 Visual";
            case 0x11: return "AAC (LATM)";
            case 0x15: return "Metadata in PES";
            case 0x1B: return "H.264/AVC";
            case 0x24: return "H.265/HEVC";
            case 0x81: return "AC-3";
            default: return String.format("type 0x%02X", streamType);
        }
    }

    public String shortCodec() {
        switch (streamType) {
            case 0x02: return "MPEG-2";
            case 0x1B: return "H.264";
            case 0x24: return "HEVC";
            case 0x0F: return "AAC-ADTS";
            case 0x11: return "AAC-LATM";
            case 0x03: case 0x04: return "MPEG Audio";
            default: return codecName();
        }
    }

    public StreamInfo copy() {
        StreamInfo s = new StreamInfo();
        s.pid = pid;
        s.streamType = streamType;
        s.componentTag = componentTag;
        s.dataComponentId = dataComponentId;
        s.language = language;
        s.language2 = language2;
        return s;
    }
}
