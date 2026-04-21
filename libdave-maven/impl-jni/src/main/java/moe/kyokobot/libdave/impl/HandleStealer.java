package moe.kyokobot.libdave.impl;

import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public class HandleStealer {
    public static long stealHandle(DaveNativeHandle handle) {
        return handle.stealHandle();
    }
}
