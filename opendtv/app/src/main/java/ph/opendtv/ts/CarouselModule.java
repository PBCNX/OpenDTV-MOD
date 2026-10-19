package ph.opendtv.ts;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** A module of a DSM-CC data carousel (ARIB STD-B24 data broadcasting). Snapshots hold no raw block data. */
public final class CarouselModule {
    public int downloadId;
    public int moduleId;
    public int version;
    public int size;            // size announced in the DII (compressed size when compressed)
    public int blockSize;
    public int blocksTotal;
    public int blocksReceived;
    public boolean complete;
    public boolean compressed;
    public int originalSize;    // from the compression descriptor, 0 if unknown
    public String name = "";
    public String typeHint = "";
    public String error = "";

    /** Decompressed module content; only set once complete. */
    public byte[] content;
    /** Files found in the module; empty when the content is not MIME multipart. */
    public List<CarouselResource> resources = new ArrayList<>();

    // internal assembly state (not copied into snapshots)
    byte[] data;
    BitSet received;

    public int percent() {
        return blocksTotal == 0 ? 0 : blocksReceived * 100 / blocksTotal;
    }

    CarouselModule snapshot() {
        CarouselModule c = new CarouselModule();
        c.downloadId = downloadId;
        c.moduleId = moduleId;
        c.version = version;
        c.size = size;
        c.blockSize = blockSize;
        c.blocksTotal = blocksTotal;
        c.blocksReceived = blocksReceived;
        c.complete = complete;
        c.compressed = compressed;
        c.originalSize = originalSize;
        c.name = name;
        c.typeHint = typeHint;
        c.error = error;
        c.content = complete ? content : null;
        c.resources = resources; // immutable after completion
        return c;
    }
}
