package moe.kyokobot.libdave;

import java.nio.ByteBuffer;

/**
 * Handles the decryption of inbound media frames using DAVE.
 * <p>
 * The Decryptor uses a {@link KeyRatchet} to derive the specific keys needed to decrypt
 * individual RTP packets/frames for a specific sender.
 */
public interface Decryptor extends AutoCloseable {
    /**
     * Transitions the decryptor to use a specific key ratchet.
     * <p>
     * This links the decryptor to a specific sender's key chain.
     *
     * @param keyRatchet The key ratchet to use.
     */
    void transitionToKeyRatchet(KeyRatchet keyRatchet);

    /**
     * Sets whether the decryptor should operate in passthrough mode.
     * <p>
     * In passthrough mode, the decryptor typically copies the input to the output without attempting decryption,
     * or performs minimal processing.
     *
     * @param passthroughMode {@code true} to enable passthrough, {@code false} to disable.
     */
    void transitionToPassthroughMode(boolean passthroughMode);

    /**
     * Calculates the maximum possible size of the plaintext (decrypted) frame for a given encrypted frame size.
     *
     * @param mediaType          The type of media (audio/video).
     * @param encryptedFrameSize The size of the encrypted frame.
     * @return The maximum size of the plaintext frame in bytes.
     */
    default int getMaxPlaintextByteSize(MediaType mediaType, int encryptedFrameSize) {
        return encryptedFrameSize;
    }

    /**
     * Decrypts an encrypted frame.
     *
     * @param mediaType      The type of media.
     * @param encryptedFrame The input buffer containing the encrypted frame.
     * @param frame          The output buffer to write the decrypted frame into.
     * @return The number of bytes written to {@code frame} on success, or a negative error code (see {@link DecryptorResultCode}) on failure.
     */
    int decrypt(MediaType mediaType, byte[] encryptedFrame, byte[] frame);

    /**
     * Decrypts an encrypted frame into NIO ByteBuffer.
     *
     * @param mediaType      The type of media.
     * @param encryptedFrame The input ByteBuffer containing the encrypted frame.
     * @param frame          The output ByteBuffer to write the decrypted frame into.
     * @return The number of bytes written to {@code frame} on success, or a negative error code on failure.
     */
    int decrypt(MediaType mediaType, ByteBuffer encryptedFrame, ByteBuffer frame);

    @Override
    void close();
}
