package moe.kyokobot.libdave.netty;

import io.netty.buffer.ByteBuf;
import moe.kyokobot.libdave.Encryptor;
import moe.kyokobot.libdave.MediaType;

public interface NettyEncryptor extends Encryptor {
    /**
     * Encrypts a media frame into Netty ByteBuf.
     *
     * @param mediaType      The type of media.
     * @param ssrc           The SSRC of the stream.
     * @param frame          The input ByteBuf containing the plaintext frame.
     * @param encryptedFrame The output ByteBuf to write the encrypted frame into.
     * @return The number of bytes written to {@code encryptedFrame} on success, or a negative error code on failure.
     */
    int encrypt(MediaType mediaType, int ssrc, ByteBuf frame, ByteBuf encryptedFrame);
}
