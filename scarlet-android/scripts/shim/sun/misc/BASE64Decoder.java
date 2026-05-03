package sun.misc;

import java.io.IOException;
import java.util.Base64;

/**
 * Companion shim for sun.misc.BASE64Decoder (also removed in JDK 9).
 * Same rationale as BASE64Encoder: delegate to java.util.Base64.
 */
public class BASE64Decoder {
    public byte[] decodeBuffer(String s) throws IOException {
        return s == null ? new byte[0] : Base64.getMimeDecoder().decode(s);
    }
    public byte[] decodeBuffer(byte[] s) throws IOException {
        return s == null ? new byte[0] : Base64.getMimeDecoder().decode(s);
    }
}
