package com.pbcnx.opendtvmod.ts.carousel;


import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link MimeParts}, the ARIB data-broadcast MIME multipart parser.
 *
 * <p>The point of the parser is that bodies are cut by Content-Length and never by searching for
 * the boundary, because JPEG/PNG payloads may legitimately contain the boundary text. The test
 * bodies below carry the boundary inside a body to pin that down.
 */
public class MimePartsTest {

    private static final String BOUNDARY = "VI_BOUNDARY";

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    @Test
    public void singleNonMultipartResourceIsTheWholeBody() {
        byte[] body = ascii("HELLO BODY");
        byte[] d = concat(ascii(
                        "Content-Type: text/X-arib-bml; charset=\"euc-jp\"\r\n"
                                + "Content-Location: startup.bml\r\n\r\n"),
                body);

        List<CarouselResource> parts = MimeParts.parse(d);

        assertEquals(1, parts.size());
        CarouselResource r = parts.get(0);
        assertEquals("text/X-arib-bml; charset=\"euc-jp\"", r.contentType);
        assertEquals("startup.bml", r.location);
        assertEquals(body.length, r.length);
        assertArrayEquals(body, r.body);
    }

    @Test
    public void multipartWithQuotedBoundarySplitsIntoParts() {
        byte[] hello = ascii("HELLO");
        byte[] image = concat(ascii("JPEG"), new byte[] {0x00, 0x01, 0x02, (byte) 0xFF});
        byte[] d = concat(
                ascii("Content-Type: multipart/mixed;boundary=\"" + BOUNDARY + "\"\r\n"
                        + "Content-Length: 999\r\nContent-Location: \r\n\r\n"),
                ascii("--" + BOUNDARY + "\r\n"
                        + "Content-Type: text/X-arib-bml; charset=\"euc-jp\"\r\n"
                        + "Content-Length: " + hello.length + "\r\n"
                        + "Content-Location: startup.bml\r\n\r\n"),
                hello,
                ascii("\r\n--" + BOUNDARY + "\r\n"
                        + "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + image.length + "\r\n"
                        + "Content-Location: img.jpg\r\n\r\n"),
                image,
                ascii("\r\n--" + BOUNDARY + "--\r\n"));

        List<CarouselResource> parts = MimeParts.parse(d);

        assertEquals(2, parts.size());
        assertEquals("text/X-arib-bml; charset=\"euc-jp\"", parts.get(0).contentType);
        assertEquals("startup.bml", parts.get(0).location);
        assertEquals(hello.length, parts.get(0).length);
        assertArrayEquals(hello, parts.get(0).body);
        assertEquals("image/jpeg", parts.get(1).contentType);
        assertEquals("img.jpg", parts.get(1).location);
        assertEquals(image.length, parts.get(1).length);
        assertArrayEquals(image, parts.get(1).body);
    }

    @Test
    public void bodyIsCutByContentLengthEvenWhenItContainsTheBoundaryText() {
        // A JPEG may contain the delimiter string; only Content-Length may decide where it ends.
        byte[] body = concat(
                ascii("JPEG"),
                new byte[] {0x00, 0x01, 0x02, (byte) 0xFF},
                ascii("--" + BOUNDARY),
                ascii("DATA"));
        byte[] d = concat(
                ascii("Content-Type: multipart/mixed;boundary=\"" + BOUNDARY + "\"\r\n"
                        + "Content-Length: 4096\r\n\r\n"),
                ascii("--" + BOUNDARY + "\r\n"
                        + "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Content-Location: img.jpg\r\n\r\n"),
                body,
                ascii("\r\n--" + BOUNDARY + "--\r\n"));

        List<CarouselResource> parts = MimeParts.parse(d);

        assertEquals("the inner delimiter must not start a second part", 1, parts.size());
        assertEquals(body.length, parts.get(0).length);
        assertArrayEquals(body, parts.get(0).body);
        assertTrue("the body really does contain the boundary text",
                text(parts.get(0).body).contains("--" + BOUNDARY));
    }

    @Test
    public void partWithoutContentLengthEndsAtTheNextBoundaryAndLosesTheFinalCrLf() {
        byte[] d = concat(
                ascii("Content-Type: multipart/mixed; boundary=" + BOUNDARY + "\r\n\r\n"),
                ascii("--" + BOUNDARY + "\r\n"
                        + "Content-Type: text/plain\r\n"
                        + "Content-Location: a.txt\r\n\r\n"
                        + "ALPHA\r\n"),
                ascii("--" + BOUNDARY + "--\r\n"));

        List<CarouselResource> parts = MimeParts.parse(d);

        assertEquals(1, parts.size());
        assertEquals("text/plain", parts.get(0).contentType);
        assertEquals("a.txt", parts.get(0).location);
        assertEquals(5, parts.get(0).length);
        assertEquals("ALPHA", text(parts.get(0).body));
    }

    @Test
    public void boundaryParameterIsFoundCaseInsensitivelyAndWithoutQuotes() {
        byte[] d = concat(
                ascii("Content-Type: MULTIPART/MIXED; BOUNDARY=XYZ; charset=x\r\n\r\n"),
                ascii("--XYZ\r\nContent-Type: text/plain\r\n\r\nBODY\r\n"),
                ascii("--XYZ--\r\n"));

        List<CarouselResource> parts = MimeParts.parse(d);

        assertEquals(1, parts.size());
        assertEquals("text/plain", parts.get(0).contentType);
        assertEquals("BODY", text(parts.get(0).body));
    }

    @Test
    public void missingHeadersGiveAnEmptyListInsteadOfAThrow() {
        assertTrue(MimeParts.parse(null).isEmpty());
        assertTrue("shorter than any header block", MimeParts.parse(new byte[13]).isEmpty());
        assertTrue("no Content-Type", MimeParts.parse(ascii("Content-Location: x\r\n\r\nBODY")).isEmpty());
        assertTrue("Content-Type but no blank line", MimeParts.parse(ascii("Content-Type: text/plain")).isEmpty());
        assertTrue("a header line without a colon", MimeParts.parse(ascii(
                "Content-Type: text/plain\r\nnot a header\r\n\r\nBODY")).isEmpty());
    }

    @Test
    public void hugeContentLengthCannotOverflowTheCut() {
        // 2^31-1 as Content-Length used to make start + declared overflow int into a negative end.
        byte[] d = concat(
                ascii("Content-Type: multipart/mixed; boundary=\"" + BOUNDARY + "\"\r\n\r\n"),
                ascii("--" + BOUNDARY + "\r\nContent-Type: text/plain\r\nContent-Length: 2147483647\r\n\r\nBODY\r\n"),
                ascii("--" + BOUNDARY + "--\r\n"));

        List<CarouselResource> parts = MimeParts.parse(d);

        assertEquals(1, parts.size());
        assertEquals("the body falls back to the boundary search", "BODY", text(parts.get(0).body));
    }

    @Test
    public void overlongBoundaryIsTreatedAsNoMultipartInsteadOfScannedFor() {
        // RFC 2046 caps a boundary at 70 characters; a longer one made the delimiter search take minutes
        // per module under the demux lock.
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 200; i++) b.append('x');
        byte[] d = concat(
                ascii("Content-Type: multipart/mixed; boundary=" + b + "\r\n\r\n"),
                ascii("--" + b + "\r\nContent-Type: text/plain\r\n\r\nBODY\r\n"));

        List<CarouselResource> parts = MimeParts.parse(d);

        assertEquals("treated as one non-multipart resource", 1, parts.size());
        assertTrue("content type is carried through whole",
                parts.get(0).contentType.startsWith("multipart/mixed"));
    }

    @Test
    public void multipartWithNoPartsGivesAnEmptyList() {
        assertTrue(MimeParts.parse(ascii(
                "Content-Type: multipart/mixed; boundary=\"" + BOUNDARY + "\"\r\n\r\n"
                        + "there is no delimiter here")).isEmpty());
    }
}
