package ph.opendtv.ts;

/** One file inside a data-carousel module (a part of the module's MIME multipart body). */
public final class CarouselResource {
    /** e.g. "text/X-arib-bml; charset=\"euc-jp\"", "image/jpeg", "application/X-arib-btable". */
    public String contentType = "";
    /** Content-Location, e.g. "startup.bml". Empty for the resource list part. */
    public String location = "";
    public int length;
    public byte[] body = new byte[0];
}
