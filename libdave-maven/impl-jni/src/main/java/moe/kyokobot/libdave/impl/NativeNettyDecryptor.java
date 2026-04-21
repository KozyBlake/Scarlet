package moe.kyokobot.libdave.impl;

import io.netty.buffer.ByteBuf;
import moe.kyokobot.libdave.MediaType;
import moe.kyokobot.libdave.netty.NettyDecryptor;
import moe.kyokobot.libdave.natives.DaveNativeBindings;

import java.nio.ByteBuffer;

public class NativeNettyDecryptor extends NativeDecryptor implements NettyDecryptor {
    public NativeNettyDecryptor(long handle) {
        super(handle);
    }

    @Override
    public int decrypt(MediaType mediaType, ByteBuf encryptedFrame, ByteBuf frame) {
        assertOpen();
        int res;
        if (encryptedFrame.hasMemoryAddress() && frame.hasMemoryAddress()) {
            // Fast-path: direct native pointer access.
            // Account for reader index in the input encrypted frame
            long encryptedFrameAddr = encryptedFrame.memoryAddress() + encryptedFrame.readerIndex();
            int encryptedFrameSize = encryptedFrame.readableBytes();
            // Account for writer index in the output frame
            long frameAddr = frame.memoryAddress() + frame.writerIndex();
            int frameCapacity = frame.writableBytes();

            res = DaveNativeBindings.inst().daveDecryptorDecrypt(
                handle, mediaType.getValue(),
                encryptedFrameAddr, encryptedFrameSize,
                frameAddr, frameCapacity
            );
        } else {
            // Fallback: use NIO buffers (still requires direct buffers; native checks this).
            ByteBuffer encryptedFrameNio = encryptedFrame.nioBuffer(encryptedFrame.readerIndex(), encryptedFrame.readableBytes());
            ByteBuffer frameNio = frame.nioBuffer(frame.writerIndex(), frame.writableBytes());

            if (!encryptedFrameNio.isDirect()) {
                throw new IllegalArgumentException("encryptedFrame must be backed by a direct buffer");
            }
            if (!frameNio.isDirect()) {
                throw new IllegalArgumentException("frame must be backed by a direct buffer");
            }

            res = DaveNativeBindings.inst().daveDecryptorDecrypt(
                handle, mediaType.getValue(),
                encryptedFrameNio, frameNio
            );
        }

        if (res > 0) {
            frame.writerIndex(frame.writerIndex() + res);
        }
        return res;
    }
}

