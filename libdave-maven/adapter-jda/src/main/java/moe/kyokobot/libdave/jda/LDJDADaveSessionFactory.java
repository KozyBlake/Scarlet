package moe.kyokobot.libdave.jda;

import moe.kyokobot.libdave.DaveFactory;
import net.dv8tion.jda.api.audio.dave.DaveProtocolCallbacks;
import net.dv8tion.jda.api.audio.dave.DaveSession;
import net.dv8tion.jda.api.audio.dave.DaveSessionFactory;
import org.jetbrains.annotations.NotNull;

public class LDJDADaveSessionFactory implements DaveSessionFactory {
    private final DaveFactory factory;

    public LDJDADaveSessionFactory(DaveFactory factory) {
        this.factory = factory;
    }

    @Override
    @NotNull
    public DaveSession createDaveSession(@NotNull DaveProtocolCallbacks callbacks, long userId, long channelId) {
        return new LDJDADaveSession(factory, userId, channelId, callbacks);
    }
}
