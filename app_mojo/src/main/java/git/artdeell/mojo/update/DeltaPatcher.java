package git.artdeell.mojo.update;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Puts the new APK together from the APK that is installed and the small update file made by tools/make_delta.py.
 * The update file says which bytes of the installed APK to copy and holds the bytes that are new, so only
 * the files that changed or were added have to be downloaded. The result is the very same file the build made,
 * the signature stays valid, and it is checked against the sha256 before it is used.
 */
final class DeltaPatcher {
    private static final byte[] MAGIC = {'N', 'K', 'D', '1'};
    private static final int OP_COPY = 0;
    private static final int OP_ADD = 1;
    private static final int OP_END = 2;

    private DeltaPatcher() {}

    /**
     * @param expectedSha  sha256 of the new APK from the update API, empty when unknown
     * @param expectedSize size of the new APK from the update API, 0 when unknown
     */
    static void apply(File base, File delta, File out, String expectedSha, long expectedSize) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(delta), 64 * 1024));
             RandomAccessFile source = new RandomAccessFile(base, "r");
             OutputStream sink = new BufferedOutputStream(new FileOutputStream(out), 64 * 1024)) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) throw new IOException("This is not an update file");
            long newSize = in.readLong();
            byte[] newSha = new byte[32];
            in.readFully(newSha);
            in.readFully(new byte[32]); // sha256 of the base, the caller has checked the installed apk against the API
            long baseSize = in.readLong();
            if (baseSize != source.length()) throw new IOException("The update file is for another apk");
            if (newSize <= 0 || (expectedSize > 0 && newSize != expectedSize)) throw new IOException("Wrong size in the update file");

            byte[] buffer = new byte[64 * 1024];
            long written = 0;
            while (true) {
                int op = in.read();
                if (op < 0) throw new EOFException("The update file ends too early");
                if (op == OP_END) break;
                if (op == OP_COPY) {
                    long offset = in.readLong();
                    long length = in.readLong();
                    if (offset < 0 || length < 0 || offset + length > baseSize || written + length > newSize) {
                        throw new IOException("Wrong copy in the update file");
                    }
                    source.seek(offset);
                    for (long left = length; left > 0; ) {
                        int n = source.read(buffer, 0, (int) Math.min(buffer.length, left));
                        if (n < 0) throw new EOFException();
                        sink.write(buffer, 0, n);
                        digest.update(buffer, 0, n);
                        left -= n;
                    }
                    written += length;
                } else if (op == OP_ADD) {
                    long length = in.readLong();
                    if (length < 0 || written + length > newSize) throw new IOException("Wrong data in the update file");
                    for (long left = length; left > 0; ) {
                        int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                        if (n < 0) throw new EOFException();
                        sink.write(buffer, 0, n);
                        digest.update(buffer, 0, n);
                        left -= n;
                    }
                    written += length;
                } else {
                    throw new IOException("Unknown operation in the update file");
                }
            }
            if (written != newSize) throw new IOException("The new apk has a wrong size");
            sink.flush();
            byte[] result = digest.digest();
            if (!Arrays.equals(result, newSha)) throw new IOException("The new apk does not match its checksum");
            if (expectedSha != null && !expectedSha.isEmpty() && !hex(result).equals(expectedSha.toLowerCase(Locale.ROOT))) {
                throw new IOException("The new apk is not the one the API announced");
            }
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
