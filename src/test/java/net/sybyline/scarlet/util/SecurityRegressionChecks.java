package net.sybyline.scarlet.util;

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
        testPublicUrlValidation();
    }

    static void testEncryptedPrefsRoundTrip() throws Exception
    {
        Preferences prefs = tempPrefsNode("roundtrip");
        EncryptedPrefs encrypted = new EncryptedPrefs(prefs, "unit-test-password");
        encrypted.put("secret", "hello world");
        expect("hello world".equals(encrypted.get("secret")), "EncryptedPrefs should round-trip values");
    }

    static void testPublicUrlValidation()
    {
        expect(HttpURLInputStream.isPublicHttpUrl("https://example.com"), "Public https URL should be allowed");
        expect(!HttpURLInputStream.isPublicHttpUrl("http://127.0.0.1/test"), "Loopback URL should be rejected");
        expect(!HttpURLInputStream.isPublicHttpUrl("http://192.168.1.10/test"), "RFC1918 URL should be rejected");
        expect(!HttpURLInputStream.isPublicHttpUrl("file:///etc/passwd"), "Non-http schemes should be rejected");
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
