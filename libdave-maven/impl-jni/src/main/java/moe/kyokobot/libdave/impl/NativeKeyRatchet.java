package moe.kyokobot.libdave.impl;

import moe.kyokobot.libdave.KeyRatchet;
import moe.kyokobot.libdave.natives.DaveNativeBindings;

public class NativeKeyRatchet extends DaveNativeHandle implements KeyRatchet {
    NativeKeyRatchet(long handle) {
        super(handle);
//        this.handle = handle;
    }

    @Override public byte[] getEncryptionKey(int keyGeneration) {
        assertOpen();
        return DaveNativeBindings.inst().daveKeyRatchetGetEncryptionKey(handle, keyGeneration);
    }

    @Override public void deleteKey(int keyGeneration) {
        assertOpen();
        DaveNativeBindings.inst().daveKeyRatchetDeleteKey(handle, keyGeneration);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        DaveNativeBindings.inst().daveKeyRatchetDestroy(handle);
    }
}

