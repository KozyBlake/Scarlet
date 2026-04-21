package moe.kyokobot.libdave.jda;

import moe.kyokobot.libdave.DaveFactory;
import moe.kyokobot.libdave.jda.internal.DAVEManager;
import net.dv8tion.jda.api.audio.dave.DaveProtocolCallbacks;
import net.dv8tion.jda.api.audio.dave.DaveSession;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;

public class LDJDADaveSession implements DaveSession {
    private final DAVEManager manager;

    public LDJDADaveSession(DaveFactory factory, long userId, long channelId, DaveProtocolCallbacks callbacks) {
        this.manager = new DAVEManager(factory, userId, channelId, callbacks);
    }

    @Override
    public int getMaxProtocolVersion() {
        return manager.getMaxDAVEProtocolVersion();
    }

    @Override
    public int getMaxEncryptedFrameSize(@NotNull MediaType type, int frameSize) {
        return manager.getMaxCiphertextByteSize(mapMediaType(type), frameSize);
    }

    @Override
    public int getMaxDecryptedFrameSize(@NotNull MediaType type, long userId, int frameSize) {
        return manager.getMaxPlaintextByteSize(mapMediaType(type), userId, frameSize);
    }

    @Override
    public void assignSsrcToCodec(@NotNull Codec codec, int ssrc) {
        manager.assignSsrcToCodec(ssrc, mapCodec(codec));
    }

    @Override
    public boolean encrypt(@NotNull MediaType mediaType, int ssrc, @NotNull ByteBuffer data, @NotNull ByteBuffer encrypted) {
        return manager.encrypt(mapMediaType(mediaType), ssrc, data, encrypted);
    }

    @Override
    public boolean decrypt(@NotNull MediaType mediaType, long userId, @NotNull ByteBuffer encrypted, @NotNull ByteBuffer decrypted) {
        return manager.decrypt(mapMediaType(mediaType), userId, encrypted, decrypted);
    }

    @Override
    public void addUser(long userId) {
        manager.addUser(userId);
    }

    @Override
    public void removeUser(long userId) {
        manager.removeUser(userId);
    }

    @Override
    public void initialize() {
        // No-op, managed internally
    }

    @Override
    public void destroy() {
        manager.close();
    }

    @Override
    public void onSelectProtocolAck(int protocolVersion) {
        manager.onSelectProtocolAck(protocolVersion);
    }

    @Override
    public void onDaveProtocolPrepareTransition(int transitionId, int protocolVersion) {
        manager.onDaveProtocolPrepareTransition(transitionId, protocolVersion);
    }

    @Override
    public void onDaveProtocolExecuteTransition(int transitionId) {
        manager.onDaveProtocolExecuteTransition(transitionId);
    }

    @Override
    public void onDaveProtocolPrepareEpoch(long epoch, int protocolVersion) {
        manager.onDaveProtocolPrepareEpoch(epoch, protocolVersion);
    }

    @Override
    public void onDaveProtocolMLSExternalSenderPackage(@NotNull ByteBuffer externalSenderPackage) {
        manager.onDaveProtocolMLSExternalSenderPackage(externalSenderPackage);
    }

    @Override
    public void onMLSProposals(@NotNull ByteBuffer proposals) {
        manager.onMLSProposals(proposals);
    }

    @Override
    public void onMLSPrepareCommitTransition(int transitionId, @NotNull ByteBuffer commit) {
        manager.onMLSPrepareCommitTransition(transitionId, commit);
    }

    @Override
    public void onMLSWelcome(int transitionId, @NotNull ByteBuffer welcome) {
        manager.onMLSWelcome(transitionId, welcome);
    }

    private moe.kyokobot.libdave.MediaType mapMediaType(MediaType type) {
        return type == MediaType.AUDIO ? moe.kyokobot.libdave.MediaType.AUDIO : moe.kyokobot.libdave.MediaType.VIDEO;
    }

    private moe.kyokobot.libdave.Codec mapCodec(Codec codec) {
        if (codec == Codec.OPUS) {
            return moe.kyokobot.libdave.Codec.OPUS;
        } else {
            return moe.kyokobot.libdave.Codec.UNKNOWN;
        }
    }
}
