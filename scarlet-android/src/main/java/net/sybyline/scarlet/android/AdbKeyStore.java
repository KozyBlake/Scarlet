package net.sybyline.scarlet.android;

import android.content.Context;
import android.util.Log;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

/**
 * Persists the RSA keypair plus self-signed X.509 certificate that
 * libadb-android needs to drive the Wireless Debugging pairing and TLS
 * handshakes.
 *
 * <p>Files live in the app's internal {@code filesDir/adb}:
 *
 * <ul>
 *   <li>{@code adbkey}    - PKCS#8 DER-encoded RSA-2048 private key</li>
 *   <li>{@code adbcert}   - X.509 DER-encoded self-signed cert wrapping the
 *       matching public key.  Subject CN is the device name advertised
 *       to adbd's pairing dialog.</li>
 * </ul>
 *
 * <p>Both are app-private (mode 0600 by default for {@code openFileOutput}-
 * level files in {@code filesDir}), so no extra permission is required to
 * read or write them and no other app on the device can pick them up.
 *
 * <p>The certificate is generated on first launch only - subsequent
 * launches reload it from disk so that adbd's "Always allow from this
 * computer" / paired-fingerprint store keeps working across cold starts.
 */
final class AdbKeyStore {

    private static final String TAG = "Scarlet/AdbKeyStore";

    private static final String KEY_ALG = "RSA";
    private static final int KEY_SIZE = 2048;
    private static final String SIG_ALG = "SHA256withRSA";

    /** Roughly one human lifetime - adbd's cert validity check is forgiving. */
    private static final long CERT_VALIDITY_MS = 100L * 365L * 24L * 60L * 60L * 1000L;

    private final File dir;
    private final File privFile;
    private final File certFile;

    AdbKeyStore(Context ctx) {
        this.dir = new File(ctx.getFilesDir(), "adb");
        if (!this.dir.isDirectory() && !this.dir.mkdirs()) {
            Log.e(TAG, "Could not create " + this.dir);
        }
        this.privFile = new File(this.dir, "adbkey");
        this.certFile = new File(this.dir, "adbcert");
    }

    File dir()        { return this.dir; }
    File privateKeyFile() { return this.privFile; }
    File certificateFile() { return this.certFile; }

    boolean hasKeys() {
        return this.privFile.isFile() && this.certFile.isFile();
    }

    /**
     * Make sure a keypair + self-signed cert exist on disk.  Re-uses any
     * existing material; only generates fresh keys on a clean install or
     * after {@link #reset()}.
     *
     * @param subjectCn the CN baked into the cert's Subject DN.  This is
     *                  also what adbd shows in the on-device pairing
     *                  dialog as the "key fingerprint"-adjacent label, so
     *                  pick something the user will recognise (we use the
     *                  Android Build.MODEL or "Scarlet").
     */
    synchronized void ensureKeys(String subjectCn) throws Exception {
        if (hasKeys()) return;

        Log.i(TAG, "Generating ADB keypair + self-signed cert (CN=" + subjectCn + ")");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_ALG);
        kpg.initialize(KEY_SIZE, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 60_000L); // 1 min slack for clock drift
        Date notAfter  = new Date(now + CERT_VALIDITY_MS);

        X500Name dn = new X500Name("CN=" + (subjectCn == null || subjectCn.isEmpty() ? "Scarlet" : subjectCn));
        BigInteger serial = BigInteger.valueOf(now);

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn, serial, notBefore, notAfter, dn, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder(SIG_ALG).build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));

        // PKCS#8 (DER) for the private key, X.509 (DER) for the cert.
        byte[] privDer = kp.getPrivate().getEncoded();
        byte[] certDer = cert.getEncoded();

        writeAtomic(this.privFile, privDer);
        writeAtomic(this.certFile, certDer);
        Log.i(TAG, "Wrote " + this.privFile + " (" + privDer.length + " bytes) and "
                + this.certFile + " (" + certDer.length + " bytes)");
    }

    /** Load the persisted PKCS#8 RSA private key. */
    synchronized PrivateKey loadPrivateKey() throws Exception {
        byte[] der = readAll(this.privFile);
        KeyFactory kf = KeyFactory.getInstance(KEY_ALG);
        return kf.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** Load the persisted X.509 self-signed certificate. */
    synchronized Certificate loadCertificate() throws Exception {
        try (InputStream in = new FileInputStream(this.certFile)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return cf.generateCertificate(in);
        }
    }

    /** Forget the paired key material so the user can re-pair from scratch. */
    synchronized void reset() {
        if (this.privFile.isFile() && !this.privFile.delete())
            Log.w(TAG, "Could not delete " + this.privFile);
        if (this.certFile.isFile() && !this.certFile.delete())
            Log.w(TAG, "Could not delete " + this.certFile);
    }

    private static void writeAtomic(File target, byte[] bytes) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(bytes);
            fos.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("could not replace " + target);
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("could not rename " + tmp + " -> " + target);
        }
    }

    private static byte[] readAll(File f) throws IOException {
        long len = f.length();
        if (len <= 0 || len > (1L << 20)) {
            throw new IOException("unexpected size for " + f + ": " + len);
        }
        byte[] buf = new byte[(int) len];
        try (FileInputStream fis = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int r = fis.read(buf, off, buf.length - off);
                if (r < 0) throw new IOException("short read on " + f);
                off += r;
            }
        }
        return buf;
    }
}
