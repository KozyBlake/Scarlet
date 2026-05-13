package net.sybyline.scarlet.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public final class SecurityRegressionChecks
{
    public static void main(String[] args) throws Exception
    {
        testEncryptedPrefsRoundTrip();
        testEncryptedPrefsLegacyWrapperMigration();
        testPublicAddressClassification();
        testPublicUrlValidation();
        testPublicUrlRedirectValidation();
        testPublicUrlTargetPinning();
    }

    static void testEncryptedPrefsRoundTrip() throws Exception
    {
        Preferences prefs = tempPrefsNode("roundtrip");
        EncryptedPrefs encrypted = new EncryptedPrefs(prefs, "unit-test-password");
        encrypted.put("secret", "hello world");
        expect("hello world".equals(encrypted.get("secret")), "EncryptedPrefs should round-trip values");
    }

    /**
     * Exercises {@link HttpURLInputStream#isPublicAddress} directly with
     * literal addresses constructed via {@link InetAddress#getByAddress(byte[])}.
     * This path does not perform DNS resolution, so the test is hermetic and
     * runs identically offline and on CI.
     */
    static void testPublicAddressClassification() throws UnknownHostException
    {
        // Allowed: regular public IPv4 / IPv6.
        expect(HttpURLInputStream.isPublicAddress(ipv4(8, 8, 8, 8)),
            "Public IPv4 (8.8.8.8) should be allowed");
        expect(HttpURLInputStream.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")),
            "Public IPv6 (1.1.1.1 v6 sibling) should be allowed");

        // IPv4 reserved ranges that must be rejected.
        expect(!HttpURLInputStream.isPublicAddress(ipv4(0, 0, 0, 0)),         "0.0.0.0/8 (any/unspecified)");
        expect(!HttpURLInputStream.isPublicAddress(ipv4(10, 0, 0, 1)),         "10/8 (RFC1918)");
        expect(!HttpURLInputStream.isPublicAddress(ipv4(127, 0, 0, 1)),        "127/8 (loopback)");
        expect(!HttpURLInputStream.isPublicAddress(ipv4(100, 64, 0, 1)),       "100.64/10 (CGNAT)");
        expect(!HttpURLInputStream.isPublicAddress(ipv4(100, 127, 255, 254)),  "100.64/10 upper");
        expect(!HttpURLInputStream.isPublicAddress(ipv4(169, 254, 1, 1)),      "169.254/16 (link-local)");
        expect(!HttpURLInputStream.isPublicAddress(ipv4(172, 16, 0, 1)),       "172.16/12 (RFC1918)");
        expect(!HttpURLInputStream.isPublicAddress(ipv4(172, 31, 255, 254)),   "172.16/12 upper");
        expect(!HttpURLInputStream.isPublicAddress(ipv4(192, 0, 2, 1)),        "192.0.2/24 (TEST-NET-1)");
        expect(!HttpURLInputStream.isPublicAddress(ipv4(192, 168, 1, 1)),      "192.168/16 (RFC1918)");
        expect(!HttpURLInputStream.isPublicAddress(ipv4(198, 18, 0, 1)),       "198.18/15 (benchmark)");
        expect(!HttpURLInputStream.isPublicAddress(ipv4(198, 19, 255, 254)),   "198.18/15 upper");
        expect(!HttpURLInputStream.isPublicAddress(ipv4(198, 51, 100, 1)),     "198.51.100/24 (TEST-NET-2)");
        expect(!HttpURLInputStream.isPublicAddress(ipv4(203, 0, 113, 1)),      "203.0.113/24 (TEST-NET-3)");
        expect(!HttpURLInputStream.isPublicAddress(ipv4(224, 0, 0, 1)),        "224/4 (multicast)");
        expect(!HttpURLInputStream.isPublicAddress(ipv4(240, 0, 0, 1)),        "240/4 (reserved)");

        // Edge: addresses immediately outside reserved ranges must still pass.
        expect(HttpURLInputStream.isPublicAddress(ipv4(11, 0, 0, 1)),          "11/8 is public");
        expect(HttpURLInputStream.isPublicAddress(ipv4(100, 63, 255, 254)),    "100.63/16 is public (below CGNAT)");
        expect(HttpURLInputStream.isPublicAddress(ipv4(100, 128, 0, 1)),       "100.128/9 is public (above CGNAT)");
        expect(HttpURLInputStream.isPublicAddress(ipv4(172, 15, 255, 254)),    "172.15/16 is public");
        expect(HttpURLInputStream.isPublicAddress(ipv4(172, 32, 0, 1)),        "172.32/12 is public");

        // IPv6 reserved ranges.
        expect(!HttpURLInputStream.isPublicAddress(InetAddress.getByName("::1")),                "::1 loopback");
        expect(!HttpURLInputStream.isPublicAddress(InetAddress.getByName("::ffff:127.0.0.1")),   "IPv4-mapped loopback");
        expect(!HttpURLInputStream.isPublicAddress(InetAddress.getByName("fc00::1")),            "fc00::/7 ULA");
        expect(!HttpURLInputStream.isPublicAddress(InetAddress.getByName("fe80::1")),            "fe80::/10 link-local");
        expect(!HttpURLInputStream.isPublicAddress(InetAddress.getByName("ff02::1")),            "ff::/8 multicast");
        expect(!HttpURLInputStream.isPublicAddress(InetAddress.getByName("2001:db8::1")),        "2001:db8::/32 documentation");
    }

    static void testPublicUrlValidation()
    {
        // These checks never hit the network — the addresses are literal IPs.
        expect(!HttpURLInputStream.isPublicHttpUrl("http://127.0.0.1/test"),     "Loopback URL should be rejected");
        expect(!HttpURLInputStream.isPublicHttpUrl("http://192.168.1.10/test"),  "RFC1918 URL should be rejected");
        expect(!HttpURLInputStream.isPublicHttpUrl("http://169.254.169.254/"),   "AWS metadata IP should be rejected");
        expect(!HttpURLInputStream.isPublicHttpUrl("http://[::1]/"),             "IPv6 loopback should be rejected");
        expect(!HttpURLInputStream.isPublicHttpUrl("http://[fc00::1]/"),         "IPv6 ULA should be rejected");

        // Non-http schemes / malformed URLs.
        expect(!HttpURLInputStream.isPublicHttpUrl("file:///etc/passwd"),        "Non-http schemes should be rejected");
        expect(!HttpURLInputStream.isPublicHttpUrl("https:javascript:alert(1)"), "Opaque https URI should be rejected");
        expect(!HttpURLInputStream.isPublicHttpUrl("https://user:pass@example.com/"), "URLs with userinfo should be rejected");
        expect(!HttpURLInputStream.isPublicHttpUrl(null),                        "null URL should be rejected");
        expect(!HttpURLInputStream.isPublicHttpUrl(""),                          "empty URL should be rejected");

        // Public URL — depends on DNS. Treat resolution failure as a skip so this
        // test still passes on hermetic / offline CI.
        if (dnsAvailable("example.com"))
            expect(HttpURLInputStream.isPublicHttpUrl("https://example.com"), "Public https URL should be allowed");
    }

    static void testPublicUrlRedirectValidation() throws Exception
    {
        URL base = new URL("https://example.com/start");
        // The redirect resolver only short-circuits on the *target* URL's classification.
        // 127.0.0.1 doesn't require DNS, so this test is hermetic.
        try
        {
            HttpURLInputStream.resolveRedirectUrl(base, "http://127.0.0.1/admin", HttpURLInputStream.PUBLIC_ONLY);
            throw new AssertionError("Loopback redirect target should be rejected");
        }
        catch (IOException expected)
        {
        }
        try
        {
            HttpURLInputStream.resolveRedirectUrl(base, "http://[::1]/admin", HttpURLInputStream.PUBLIC_ONLY);
            throw new AssertionError("IPv6 loopback redirect target should be rejected");
        }
        catch (IOException expected)
        {
        }
        // Public-target redirect — only assert when DNS is available.
        if (dnsAvailable("example.com"))
            expect("https://example.com/next".equals(HttpURLInputStream.resolveRedirectUrl(base, "/next", HttpURLInputStream.PUBLIC_ONLY)),
                "Public relative redirects should be allowed");
    }

    /**
     * Verifies that {@link HttpURLInputStream#resolvePublicUrlTarget} captures
     * a concrete {@link InetAddress} at validation time, so the eventual socket
     * connect is pinned to that IP rather than re-resolving the host at connect
     * time (the DNS-rebinding defense). Skipped on hermetic CI.
     */
    static void testPublicUrlTargetPinning() throws Exception
    {
        if (!dnsAvailable("example.com"))
            return;
        HttpURLInputStream.PublicUrlTarget target = HttpURLInputStream.resolvePublicUrlTarget("https://example.com/");
        expect(target != null, "resolvePublicUrlTarget should return a non-null target for a public URL");
        expect(target.address != null, "resolvePublicUrlTarget should capture a concrete InetAddress");
        expect(HttpURLInputStream.isPublicAddress(target.address), "Pinned address should itself be public");
        expect("example.com".equalsIgnoreCase(target.host), "Pinned target should preserve the original hostname");
        expect(target.secure, "https target should be marked secure");
        expect(target.port == 443, "Default https port should be 443");
    }

    static void testEncryptedPrefsLegacyWrapperMigration()
    {
        Preferences prefs = tempPrefsNode("legacy-wrapper");
        String localPassword = "legacy-local-password";
        EncryptedPrefs.installLocalPassword(prefs, "legacy-password", localPassword);
        expect(localPassword.equals(EncryptedPrefs.tryReadLocalPassword(prefs, "legacy-password")), "Legacy wrapper should be readable");
        expect(EncryptedPrefs.tryReadLocalPassword(prefs, "new-password") == null, "New wrapper should not exist before migration");
        EncryptedPrefs.installLocalPassword(prefs, "new-password", localPassword);
        expect(localPassword.equals(EncryptedPrefs.tryReadLocalPassword(prefs, "new-password")), "New wrapper should read the migrated local password");
    }

    static Preferences tempPrefsNode(String name)
    {
        return new MemoryPreferences(null, "", "/" + name + "/" + UUID.randomUUID());
    }

    static void expect(boolean condition, String message)
    {
        if (!condition)
            throw new AssertionError(message);
    }

    private static InetAddress ipv4(int a, int b, int c, int d) throws UnknownHostException
    {
        return InetAddress.getByAddress(new byte[] { (byte) a, (byte) b, (byte) c, (byte) d });
    }

    private static boolean dnsAvailable(String host)
    {
        try
        {
            InetAddress.getAllByName(host);
            return true;
        }
        catch (Exception ex)
        {
            return false;
        }
    }

    static final class MemoryPreferences extends AbstractPreferences
    {
        final String path;
        final Map<String, String> values = new HashMap<>();
        final Map<String, MemoryPreferences> children = new HashMap<>();

        MemoryPreferences(AbstractPreferences parent, String name, String path)
        {
            super(parent, name);
            this.path = path;
        }

        @Override
        protected void putSpi(String key, String value)
        {
            this.values.put(key, value);
        }

        @Override
        protected String getSpi(String key)
        {
            return this.values.get(key);
        }

        @Override
        protected void removeSpi(String key)
        {
            this.values.remove(key);
        }

        @Override
        protected void removeNodeSpi() throws BackingStoreException
        {
            this.values.clear();
            this.children.clear();
        }

        @Override
        protected String[] keysSpi() throws BackingStoreException
        {
            return this.values.keySet().toArray(new String[0]);
        }

        @Override
        protected String[] childrenNamesSpi() throws BackingStoreException
        {
            return this.children.keySet().toArray(new String[0]);
        }

        @Override
        protected AbstractPreferences childSpi(String name)
        {
            return this.children.computeIfAbsent(name, $ -> new MemoryPreferences(this, name, this.path + "/" + name));
        }

        @Override
        protected void syncSpi() throws BackingStoreException
        {
        }

        @Override
        protected void flushSpi() throws BackingStoreException
        {
        }

        @Override
        public String absolutePath()
        {
            return this.path;
        }
    }
}
