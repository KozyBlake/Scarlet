package moe.kyokobot.libdave;

/**
 * Represents a key ratchet, which manages the sequence of encryption/decryption keys for a sender.
 * <p>
 * In MLS, keys are "ratcheted" forward to ensure forward secrecy. This class holds the native handle
 * to the ratchet state.
 */
public interface KeyRatchet extends AutoCloseable {
    byte[] getEncryptionKey(int keyGeneration);

    void deleteKey(int keyGeneration);

    @Override
    void close();
}
