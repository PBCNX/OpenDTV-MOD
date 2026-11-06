package com.pbcnx.opendtvmod.firmware;

import androidx.annotation.Nullable;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tuner firmware images, in one place.
 *
 * <p>Every stick here is a bridge with no firmware of its own: it starts blank and the driver uploads
 * an image every time it is plugged in. Those images are vendor material, so they are NOT in the
 * source tree and a PUBLIC build does not carry them at all. That leaves the app with two sources, in
 * this order:</p>
 *
 * <pre>
 *   1  files/firmware/&lt;name&gt;   what the viewer imported (Settings &gt; Tuner firmware)
 *   2  assets/firmware/&lt;name&gt;  the private/beta build's copy, if this build ships one
 * </pre>
 *
 * <p>Whichever is found is checked against the SHA-256 the driver was written against before it is
 * ever handed to a bridge. A byte-wrong image is how a dongle gets bricked, so a mismatch is refused
 * rather than "tried anyway".</p>
 */
public final class FirmwareStore {

    private static final String TAG = "OpenDTV-Firmware";
    /** Subdirectory of getFilesDir() that holds imported images. */
    public static final String DIR = "firmware";

    /** One image: the name it is stored under, and the identity that makes it the right image. */
    public static final class Image {
        public final String name;
        public final int size;
        public final String sha256;
        /** Short name for a status line ("IT9175"). */
        public final String label;
        /** The stick it drives, with its USB ids ("IT9175 (048d:275a)"). */
        public final String device;
        public final String what;

        Image(String name, int size, String sha256, String label, String device, String what) {
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
            this.label = label;
            this.device = device;
            this.what = what;
        }

        public boolean matches(byte[] data) {
            return data != null && data.length == size && sha256.equals(sha256Hex(data));
        }

        public File importedFile(Context context) {
            return new File(new File(context.getFilesDir(), DIR), name);
        }

        /** What the import screen says this image is. */
        public String hint() {
            return what;
        }

        @Override
        public String toString() {
            return device + " / " + name;
        }
    }

    public static final Image IT9175 = new Image(
            "it9175-ite-v3046.bin", 9036,
            "451b09a40d072d3e7c0b805f3d180a8c8e10456a3768cce0cc27510c05c3575c",
            "IT9175", "IT9175 (048d:275a)",
            "ITE IT9175 image, extracted from the GMA Now/Pad TV vendor driver (firmware V3_0_4_6)");

    public static final Image IT9308 = new Image(
            "it9308-sony-1.10.bin", 6498,
            "e4e94633b4ac47c6bba5ab90eedd4a7e5ffa2ce7e342884c91749c7464704a70",
            "IT9308", "IT9308 (048d:9308)",
            "IT930x bridge image (Sony 1.10)");

    /** Every image the app can drive a stick with, in the order the Settings row lists them. */
    public static List<Image> all() {
        List<Image> list = new ArrayList<>();
        list.add(IT9175);
        list.add(IT9308);
        return list;
    }

    private FirmwareStore() {
    }

    /**
     * The bytes for one image, verified.
     *
     * @throws IOException when the image is absent (say so plainly, this is the public build's normal
     *                     first run) or when it is present but not the image the driver expects.
     */
    public static byte[] load(Context context, Image image) throws IOException {
        byte[] data = null;
        File file = image.importedFile(context);
        if (file.isFile()) {
            data = readFile(file);
            if (data == null) {
                // The file is there but unreadable. Reporting "not installed" here sent the reader
                // looking for a missing import instead of a permissions problem.
                throw new IOException(image.name + ": the imported image at " + file
                        + " exists but cannot be read - check its permissions or import it again");
            }
            Log.i(TAG, image.name + ": imported copy, " + data.length + " bytes");
        }
        if (data == null) {
            try (InputStream in = context.getAssets().open("firmware/" + image.name)) {
                data = readStream(in);
                Log.i(TAG, image.name + ": build asset, " + data.length + " bytes");
            } catch (IOException e) {
                data = null;
            }
        }
        if (data == null) {
            throw new IOException(image.name + " is not installed - import the vendor image in Settings, "
                    + "Tuner setup (" + image.device + ", " + image.size + " bytes)");
        }
        if (!image.matches(data)) {
            String actual = sha256Hex(data);
            Log.e(TAG, image.name + ": REFUSED - " + data.length + " bytes, sha256 " + actual);
            throw new IOException(image.name + " is not the right image for " + image.device + ": "
                    + data.length + " bytes, sha256 " + shorten(actual) + ", expected " + image.size
                    + " bytes, " + shorten(image.sha256) + " - not uploading it");
        }
        return data;
    }

    /** For the Settings row: is this image available at all (without hashing it). */
    public static boolean available(Context context, Image image) {
        if (image.importedFile(context).isFile()) return true;
        try (InputStream in = context.getAssets().open("firmware/" + image.name)) {
            in.close();    // open() throws instead of returning null, so reaching here means present
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** True when the copy in use came from Settings rather than from the build. */
    public static boolean imported(Context context, Image image) {
        return image.importedFile(context).isFile();
    }

    /** Deletes an imported copy, falling back to the build's asset for the next bring-up. */
    public static boolean forget(Context context, Image image) {
        return image.importedFile(context).delete();
    }

    /** The image with this SHA-256, or null when the file is something else entirely. */
    @Nullable public static Image identify(@Nullable byte[] data) {
        if (data == null) return null;
        String hash = sha256Hex(data);
        for (Image image : all()) {
            if (image.sha256.equals(hash) && image.size == data.length) return image;
        }
        return null;
    }

    /**
     * Stores a viewer-chosen file. The FILE NAME IS NOT TRUSTED - the hash decides which image it is,
     * so a file renamed by hand or by a download manager still lands in the right slot, and an
     * unrelated file is refused with its own hash in the message.
     *
     * @return the image it was recognised as, or null when it is none of them
     */
    @Nullable public static Image importBytes(Context context, @Nullable byte[] data) throws IOException {
        Image image = identify(data);
        if (image == null) {
            Log.w(TAG, "import refused: " + (data == null ? 0 : data.length) + " bytes, sha256 "
                    + (data == null ? "-" : sha256Hex(data)));
            return null;
        }
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File target = new File(dir, image.name);
        File temp = new File(dir, image.name + ".part");
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write(data);
        }
        if (!temp.renameTo(target)) {
            // renameTo is allowed to fail; a plain copy is the fallback, never a half-written target
            try (OutputStream out = new FileOutputStream(target)) {
                out.write(data);
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
        Log.i(TAG, "imported " + image.name + " (" + image.device + "), " + data.length + " bytes");
        return image;
    }

    /** Convenience for a content:// URI from the document picker. */
    /**
     * @return the imported image, or null when the file is not a supported vendor image
     * @throws IOException when the file is a candidate image but cannot be read or stored
     */
    @Nullable public static Image importUri(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open that file");
            return importBytes(context, readStream(in));
        }
    }

    /** One line for the Settings row: what is installed, and what is not. */
    public static String summary(Context context) {
        StringBuilder installed = new StringBuilder();
        StringBuilder missing = new StringBuilder();
        for (Image image : all()) {
            StringBuilder target = available(context, image) ? installed : missing;
            if (target.length() > 0) target.append(", ");
            target.append(image.label);
        }
        if (installed.length() == 0) return "No vendor image yet - import one to use a USB tuner";
        if (missing.length() == 0) return installed + " ready";
        return installed + " ready - missing: " + missing;
    }

    // ------------------------------------------------------------------ hashing / io

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);   // cannot happen on Android
        }
    }

    /** First 8 and last 6 hex chars: enough to compare two hashes by eye in a toast. */
    public static String shorten(String hash) {
        if (hash == null || hash.length() < 20) return String.valueOf(hash);
        return hash.substring(0, 8) + "..." + hash.substring(hash.length() - 6);
    }

    private static byte[] readFile(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            return readStream(in);
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + file + ": " + e, e);
            return null;
        }
    }

    private static byte[] readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, in.available()));
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
