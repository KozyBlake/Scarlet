package sun.misc;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Base64;

/**
 * Minimal shim re-providing the JDK 1.6/1.7-internal sun.misc.BASE64Encoder
 * that android-maven-plugin 4.6.0 still references.  JDK 9 removed the
 * sun.misc.BASE64* classes entirely; this delegates everything to the
 * public java.util.Base64 API that exists in every JDK 8+ runtime.
 *
 * Only the methods the plugin actually calls are implemented.  If a new
 * call site needs a method not listed here, JDK will throw
 * NoSuchMethodError at the call point and we'll add it.
 */
public class BASE64Encoder {
    public String encode(byte[] data) {
        return data == null ? "" : Base64.getEncoder().encodeToString(data);
    }
    public void encode(byte[] data, OutputStream out) throws IOException {
        if (data == null || out == null) return;
        out.write(Base64.getEncoder().encode(data));
    }
    public String encodeBuffer(byte[] data) {
        // Original encodeBuffer wrapped at 76 chars + newline; the plugin
        // doesn't care about the wrapping, just the content.
        return data == null ? "" : Base64.getMimeEncoder().encodeToString(data) + "\n";
    }
}
