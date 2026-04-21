package moe.kyokobot.libdave.netty;

import io.netty.buffer.ByteBuf;
import moe.kyokobot.libdave.Decryptor;
import moe.kyokobot.libdave.MediaType;

/**
 * Factory for creating Netty-enabled decryptors and encryptors.
 * <p>
 * Creates instances of {@link NettyDecryptor} and {@link NettyEncryptor} that support
 * direct operations on Netty {@link ByteBuf} for improved performance and reduced allocations.
 * <p>
 * <b>Requirements:</b> This requires {@code io.netty:netty-buffer} to be available on the classpath.
 * Loading any classes from this package without Netty will result in {@link ClassNotFoundException}.
 *
 * @see NettyDecryptor
 * @see NettyEncryptor
 */
public interface NettyDecryptor extends Decryptor {
    /**
     * Decrypts an encrypted frame into Netty ByteBuf.
     *
     * @param mediaType      The type of media.
     * @param encryptedFrame The input ByteBuf containing the encrypted frame.
     * @param frame          The output ByteBuf to write the decrypted frame into.
     * @return The number of bytes written to {@code frame} on success, or a negative error code on failure.
     */
    int decrypt(MediaType mediaType, ByteBuf encryptedFrame, ByteBuf frame);
}
