package net.sybyline.scarlet.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.Preferences;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EncryptedPrefs
{

    private static final Logger LOG = LoggerFactory.getLogger("EncryptedPrefs");

    private static final String DIGEST_METHOD = "SHA-256",
                                CIPHER_METHOD = "AES/GCM/NoPadding",
                                KEYGEN_METHOD = "PBKDF2WithHmacSHA256",
                                LEGACY_KEYGEN_METHOD = "PBKDF2WithHmacSHA1",
                                FALLBACK_PREFIX = "plain:";
    private static final int    PREFERRED_KEY_SIZE = 256,
                                KEYGEN_ITERATIONS = 100_000,
                                IV_LENGTH = 12,
                                GCM_TAG_SIZE = 128;
    private static final int    SUPPORTED_KEY_SIZE = detectSupportedKeySize();
    private static final SecureRandom rand;
    static
    {
        SecureRandom srand;
        try
        {
            srand = SecureRandom.getInstanceStrong();
        }
        catch (NoSuchAlgorithmException e)
        {
            srand = new SecureRandom();
        }
        rand = srand;
    }
    private static final ThreadLocal<Cipher> threadLocalCipher = ThreadLocal.withInitial(EncryptedPrefs::createCipher);
    private static int detectSupportedKeySize()
    {
        try
        {
            int max = Cipher.getMaxAllowedKeyLength("AES");
            if (max >= PREFERRED_KEY_SIZE)
                return PREFERRED_KEY_SIZE;
            if (max >= 128)
                return 128;
            return Math.max(0, max);
        }
        catch (GeneralSecurityException e)
        {
            LOG.warn("Could not detect maximum AES key size; encrypted preferences will fall back to plain storage", e);
            return 0;
        }
    }
    private static Cipher createCipher()
    {
        try
        {
            return Cipher.getInstance(CIPHER_METHOD);
        }
        catch (GeneralSecurityException e)
        {
            LOG.warn("Cipher {} is unavailable; encrypted preferences will fall back to plain storage", CIPHER_METHOD, e);
            return null;
        }
    }
    private static byte[] crypt(int opmode, SecretKey key, byte[] iv, byte[] text) throws GeneralSecurityException
    {
        Cipher cipher = threadLocalCipher.get();
        if (cipher == null)
            throw new GeneralSecurityException("Cipher unavailable: " + CIPHER_METHOD);
        cipher.init(opmode, key, new GCMParameterSpec(GCM_TAG_SIZE, iv));
        return cipher.doFinal(text);
    }

    public EncryptedPrefs(Preferences prefs, String globalPassword)
    {
        this.prefs = prefs;
        this.plaintextFallback = prefs == null || globalPassword == null || SUPPORTED_KEY_SIZE < 128;
        this.localPassword = this.plaintextFallback ? copyOrEmpty(globalPassword) : init(prefs, globalPassword);
        this.keyCache = new ConcurrentHashMap<>();
        if (this.plaintextFallback)
            LOG.warn("Encrypted preferences unavailable; falling back to plain Java Preferences storage");
        else if (SUPPORTED_KEY_SIZE < PREFERRED_KEY_SIZE)
            LOG.warn("Encrypted preferences are using AES-{} compatibility mode instead of AES-{}", SUPPORTED_KEY_SIZE, PREFERRED_KEY_SIZE);
    }
    public static String tryReadLocalPassword(Preferences prefs, String globalPassword)
    {
        if (prefs == null || globalPassword == null)
            return null;
        try
        {
            String absolutePath = prefs.absolutePath(),
                   hash = masterPasswordKey(absolutePath, globalPassword);
            if (SUPPORTED_KEY_SIZE < 128)
                return prefs.get(FALLBACK_PREFIX + hash, null);
            byte[] localPasswordBytes = prefs.getByteArray(hash, null);
            if (localPasswordBytes == null)
                return null;
            SecretKey initKey = derive(globalPassword.toCharArray(), absolutePath);
            return decrypt(initKey, localPasswordBytes);
        }
        catch (Exception ex)
        {
            return null;
        }
    }
    public static void installLocalPassword(Preferences prefs, String globalPassword, String localPassword)
    {
        if (prefs == null || globalPassword == null || localPassword == null)
            throw new IllegalArgumentException("prefs, globalPassword, and localPassword must be non-null");
        if (SUPPORTED_KEY_SIZE < 128)
        {
            prefs.put(FALLBACK_PREFIX + masterPasswordKey(prefs.absolutePath(), globalPassword), localPassword);
            return;
        }
        String absolutePath = prefs.absolutePath(),
               hash = masterPasswordKey(absolutePath, globalPassword);
        SecretKey initKey = derive(globalPassword.toCharArray(), absolutePath);
        prefs.putByteArray(hash, encrypt(initKey, localPassword));
    }
    public static String masterPasswordKey(String absolutePath, String globalPassword)
    {
        return hash(absolutePath + ":" + globalPassword);
    }
    private static char[] init(Preferences prefs, String globalPassword)
    {
        String absolutePath = prefs.absolutePath(),
               hash = masterPasswordKey(absolutePath, globalPassword);
        if (SUPPORTED_KEY_SIZE < 128)
        {
            String existing = prefs.get(FALLBACK_PREFIX + hash, null);
            if (existing != null)
                return existing.toCharArray();
            byte[] bytes = new byte[32];
            rand.nextBytes(bytes);
            String localPassword = new String(Base64.getUrlEncoder().encode(bytes), StandardCharsets.UTF_8);
            prefs.put(FALLBACK_PREFIX + hash, localPassword);
            return localPassword.toCharArray();
        }
        byte[] localPasswordBytes = prefs.getByteArray(hash, null);
        SecretKey initKey = derive(globalPassword.toCharArray(), absolutePath);
        if (localPasswordBytes != null)
        {
            String decrypted = decrypt(initKey, localPasswordBytes);
            if (decrypted != null)
                return decrypted.toCharArray();
        }
        byte[] bytes = new byte[32];
        rand.nextBytes(bytes);
        String localPassword = new String(Base64.getUrlEncoder().encode(bytes), StandardCharsets.UTF_8);
        byte[] encrypted = encrypt(initKey, localPassword);
        if (encrypted == null)
        {
            LOG.warn("Initial secure preference seed could not be encrypted; falling back to plain Java Preferences storage");
            prefs.put(FALLBACK_PREFIX + hash, localPassword);
        }
        else
        {
            prefs.putByteArray(hash, encrypted);
        }
        return localPassword.toCharArray();
    }

    private final Preferences prefs;
    private final char[] localPassword;
    private final Map<String, SecretKey> keyCache;
    private final boolean plaintextFallback;

    public void put(String key, String value)
    {
        if (value == null)
            this.remove(key);
        else if (this.plaintextFallback)
            this.prefs.put(FALLBACK_PREFIX + hash(key), value);
        else
        {
            byte[] encrypted = encrypt(this.getOrDerive(key), value);
            if (encrypted == null)
                this.prefs.put(FALLBACK_PREFIX + hash(key), value);
            else
                this.prefs.putByteArray(hash(key), encrypted);
        }
    }

    public String get(String key)
    {
        if (this.plaintextFallback)
            return this.prefs.get(FALLBACK_PREFIX + hash(key), null);
        String plain = this.prefs.get(FALLBACK_PREFIX + hash(key), null);
        if (plain != null)
            return plain;
        return decrypt(this.getOrDerive(key), this.prefs.getByteArray(hash(key), null));
    }

    public void remove(String key)
    {
        this.prefs.remove(hash(key));
        this.prefs.remove(FALLBACK_PREFIX + hash(key));
    }

    public boolean contains(String key)
    {
        return this.prefs.getByteArray(hash(key), null) != null
            || this.prefs.get(FALLBACK_PREFIX + hash(key), null) != null;
    }

    private SecretKey getOrDerive(String salt)
    {
        return this.keyCache.computeIfAbsent(salt, $ -> derive(this.localPassword, $));
    }

    private static SecretKey derive(char[] password, String salt)
    {
        try
        {
            return new SecretKeySpec(SecretKeyFactory.getInstance(KEYGEN_METHOD).generateSecret(new PBEKeySpec(password, salt.getBytes(StandardCharsets.UTF_8), KEYGEN_ITERATIONS, SUPPORTED_KEY_SIZE)).getEncoded(), "AES");
        }
        catch (GeneralSecurityException e)
        {
            try
            {
                return new SecretKeySpec(SecretKeyFactory.getInstance(LEGACY_KEYGEN_METHOD).generateSecret(new PBEKeySpec(password, salt.getBytes(StandardCharsets.UTF_8), KEYGEN_ITERATIONS, SUPPORTED_KEY_SIZE)).getEncoded(), "AES");
            }
            catch (GeneralSecurityException legacy)
            {
                throw new IllegalStateException("Derrivation failed", legacy);
            }
        }
    }

    private static byte[] encrypt(SecretKey key, String value)
    {
        try
        {
            byte[] iv = new byte[IV_LENGTH];
            rand.nextBytes(iv);
            byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, key, iv, value.getBytes(StandardCharsets.UTF_8)),
                   combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encode(combined);
        }
        catch (Exception e)
        {
            LOG.warn("Exception encrypting", e);
            return null;
        }
    }

    private static String decrypt(SecretKey key, byte[] combined)
    {
        try
        {
            if (combined == null)
                return null;
            combined = Base64.getDecoder().decode(combined);
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH),
                   ciphertext = Arrays.copyOfRange(combined, IV_LENGTH, combined.length),
                   plaintext = crypt(Cipher.DECRYPT_MODE, key, iv, ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            LOG.warn("Exception decrypting", e);
            return null;
        }
    }

    private static String hash(String key)
    {
        try
        {
            byte[] bytes = MessageDigest.getInstance(DIGEST_METHOD).digest(key.getBytes(StandardCharsets.UTF_8));
            char[] chars = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++)
            {
                byte b = bytes[i];
                chars[i * 2] = Character.forDigit((b >>> 4) & 0xF, 16);
                chars[i * 2 + 1] = Character.forDigit(b & 0xF, 16);
            }
            return new String(chars);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new AssertionError(e);
        }
    }

    private static char[] copyOrEmpty(String value)
    {
        return value == null ? new char[0] : value.toCharArray();
    }

}
