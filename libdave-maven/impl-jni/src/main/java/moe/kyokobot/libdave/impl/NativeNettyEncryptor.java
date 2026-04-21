package moe.kyokobot.libdave.impl;

import io.netty.buffer.ByteBuf;
import moe.kyokobot.libdave.MediaType;
import moe.kyokobot.libdave.netty.NettyEncryptor;
import moe.kyokobot.libdave.natives.DaveNativeBindings;

import java.nio.ByteBuffer;

public class NativeNettyEncryptor extends NativeEncryptor implements NettyEncryptor {
    public NativeNettyEncryptor(long handle) {
        super(handle);
    }

    @Override
    public int encrypt(MediaType mediaType, int ssrc, ByteBuf frame, ByteBuf encryptedFrame) {
        assertOpen();
        int res;
        if (frame.hasMemoryAddress() && encryptedFrame.hasMemoryAddress()) {
            // Fast-path: direct native pointer access.
            // Account for reader index in the input frame
            long frameAddr = frame.memoryAddress() + frame.readerIndex();
            int frameSize = frame.readableBytes();
            // Account for writer index in the output frame
            long encryptedFrameAddr = encryptedFrame.memoryAddress() + encryptedFrame.writerIndex();
            int encryptedFrameCapacity = encryptedFrame.writableBytes();

            res = DaveNativeBindings.inst().daveEncryptorEncrypt(
                handle, mediaType.getValue(), ssrc,
                frameAddr, frameSize,
                encryptedFrameAddr, encryptedFrameCapacity
            );
        } else {
            // Fallback: use NIO buffers (still requires direct buffers; native checks this).
            ByteBuffer frameNio = frame.nioBuffer(frame.readerIndex(), frame.readableBytes());
            ByteBuffer encryptedFrameNio = encryptedFrame.nioBuffer(encryptedFrame.writerIndex(), encryptedFrame.writableBytes());

            if (!frameNio.isDirect()) {
                throw new IllegalArgumentException("frame must be backed by a direct buffer");
            }
            if (!encryptedFrameNio.isDirect()) {
                throw new IllegalArgumentException("encryptedFrame must be backed by a direct buffer");
            }

            res = DaveNativeBindings.inst().daveEncryptorEncrypt(
                handle, mediaType.getValue(), ssrc,
                frameNio, encryptedFrameNio
            );
        }

        if (res > 0) {
            encryptedFrame.writerIndex(encryptedFrame.writerIndex() + res);
        }
        return res;
    }
}
