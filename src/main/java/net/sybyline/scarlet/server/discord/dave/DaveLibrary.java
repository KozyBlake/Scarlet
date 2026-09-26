package net.sybyline.scarlet.server.discord.dave;

import java.nio.ByteBuffer;

import com.sun.jna.Callback;
import com.sun.jna.IntegerType;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.StringArray;
import com.sun.jna.Structure;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

public interface DaveLibrary extends Library
{

    abstract class OpaqueHandle extends PointerType
    {
        public OpaqueHandle() { super(); } public OpaqueHandle(Pointer p) { super(p); }
        public abstract class ByReference<OH extends OpaqueHandle> extends com.sun.jna.ptr.ByReference
        {
            public ByReference() { this(null); }
            public ByReference(OH handle) { super(Native.POINTER_SIZE); this.setHandle(handle); }
            public OH getHandle()
            { Pointer p = this.getPointer().getPointer(0L); return Pointer.nativeValue(p) == 0L ? null : this.create(p); }
            public void setHandle(OH handle)
            { this.getPointer().setPointer(0L, handle == null ? null : handle.getPointer()); }
            protected abstract OH create(Pointer p);
        }
    }


    /**
     * C {@code size_t}: 32-bit on 32-bit platforms (i386, arm32, PowerPC), 64-bit on 64-bit ones.
     * A Java {@code long} is always 64-bit, which misaligns every size_t argument on 32-bit ABIs.
     */
    class SizeT extends IntegerType
    {
        private static final long serialVersionUID = 1L;
        public SizeT() { this(0L); }
        public SizeT(long value) { super(Native.SIZE_T_SIZE, value, true); }
    }
    /** C {@code size_t*} out-parameter, sized and read correctly on 32- and 64-bit, either endianness. */
    class SizeTByReference extends com.sun.jna.ptr.ByReference
    {
        public SizeTByReference() { this(0L); }
        public SizeTByReference(long value) { super(Native.SIZE_T_SIZE); this.setValue(value); }
        public void setValue(long value)
        {
            if (Native.SIZE_T_SIZE == 8) this.getPointer().setLong(0L, value);
            else this.getPointer().setInt(0L, (int)value);
        }
        public long getValue()
        {
            return Native.SIZE_T_SIZE == 8 ? this.getPointer().getLong(0L) : (this.getPointer().getInt(0L) & 0xFFFFFFFFL);
        }
    }

    DaveLibrary INSTANCE = DaveLibraryLoader.load();

    class DAVESessionHandle extends OpaqueHandle
    {   public DAVESessionHandle() { super(); } public DAVESessionHandle(Pointer p) { super(p); }
        public class ByReference extends OpaqueHandle.ByReference<DAVESessionHandle>
        {   public ByReference() { super(); } public ByReference(DAVESessionHandle handle) { super(handle); }
            @Override protected DAVESessionHandle create(Pointer p) { return new DAVESessionHandle(p); }
        }
    }
    class DAVECommitResultHandle extends OpaqueHandle
    {   public DAVECommitResultHandle() { super(); } public DAVECommitResultHandle(Pointer p) { super(p); }
        public class ByReference extends OpaqueHandle.ByReference<DAVECommitResultHandle>
        {   public ByReference() { super(); } public ByReference(DAVECommitResultHandle handle) { super(handle); }
            @Override protected DAVECommitResultHandle create(Pointer p) { return new DAVECommitResultHandle(p); }
        }
    }
    class DAVEWelcomeResultHandle extends OpaqueHandle
    {   public DAVEWelcomeResultHandle() { super(); } public DAVEWelcomeResultHandle(Pointer p) { super(p); }
        public class ByReference extends OpaqueHandle.ByReference<DAVEWelcomeResultHandle>
        {   public ByReference() { super(); } public ByReference(DAVEWelcomeResultHandle handle) { super(handle); }
            @Override protected DAVEWelcomeResultHandle create(Pointer p) { return new DAVEWelcomeResultHandle(p); }
        }
    }
    class DAVEKeyRatchetHandle extends OpaqueHandle
    {   public DAVEKeyRatchetHandle() { super(); } public DAVEKeyRatchetHandle(Pointer p) { super(p); }
        public class ByReference extends OpaqueHandle.ByReference<DAVEKeyRatchetHandle>
        {   public ByReference() { super(); } public ByReference(DAVEKeyRatchetHandle handle) { super(handle); }
            @Override protected DAVEKeyRatchetHandle create(Pointer p) { return new DAVEKeyRatchetHandle(p); }
        }
    }
    class DAVEEncryptorHandle extends OpaqueHandle
    {   public DAVEEncryptorHandle() { super(); } public DAVEEncryptorHandle(Pointer p) { super(p); }
        public class ByReference extends OpaqueHandle.ByReference<DAVEEncryptorHandle>
        {   public ByReference() { super(); } public ByReference(DAVEEncryptorHandle handle) { super(handle); }
            @Override protected DAVEEncryptorHandle create(Pointer p) { return new DAVEEncryptorHandle(p); }
        }
    }
    class DAVEDecryptorHandle extends OpaqueHandle
    {   public DAVEDecryptorHandle() { super(); } public DAVEDecryptorHandle(Pointer p) { super(p); }
        public class ByReference extends OpaqueHandle.ByReference<DAVEDecryptorHandle>
        {   public ByReference() { super(); } public ByReference(DAVEDecryptorHandle handle) { super(handle); }
            @Override protected DAVEDecryptorHandle create(Pointer p) { return new DAVEDecryptorHandle(p); }
        }
    }

    int DAVE_CODEC_UNKNOWN = 0, /**< Unknown or unspecified codec */
        DAVE_CODEC_OPUS = 1,    /**< Opus audio codec */
        DAVE_CODEC_VP8 = 2,     /**< VP8 video codec */
        DAVE_CODEC_VP9 = 3,     /**< VP9 video codec */
        DAVE_CODEC_H264 = 4,    /**< H.264/AVC video codec */
        DAVE_CODEC_H265 = 5,    /**< H.265/HEVC video codec */
        DAVE_CODEC_AV1 = 6;     /**< AV1 video codec */

    int DAVE_MEDIA_TYPE_AUDIO = 0, /**< Audio stream */
        DAVE_MEDIA_TYPE_VIDEO = 1; /**< Video stream */

    int DAVE_ENCRYPTOR_RESULT_CODE_SUCCESS = 0,            /**< Encryption succeeded */
        DAVE_ENCRYPTOR_RESULT_CODE_ENCRYPTION_FAILURE = 1, /**< Encryption failed */
        DAVE_ENCRYPTOR_RESULT_CODE_MISSING_KEY_RATCHET = 2,/**< No key ratchet available */
        DAVE_ENCRYPTOR_RESULT_CODE_MISSING_CRYPTOR = 3,    /**< Missing cryptographic context */
        DAVE_ENCRYPTOR_RESULT_CODE_TOO_MANY_ATTEMPTS = 4;  /**< Too many attempts to encrypt the frame failed */

    int DAVE_DECRYPTOR_RESULT_CODE_SUCCESS = 0,            /**< Decryption succeeded */
        DAVE_DECRYPTOR_RESULT_CODE_DECRYPTION_FAILURE = 1, /**< Decryption failed */
        DAVE_DECRYPTOR_RESULT_CODE_MISSING_KEY_RATCHET = 2,/**< No key ratchet available */
        DAVE_DECRYPTOR_RESULT_CODE_INVALID_NONCE = 3,      /**< Invalid nonce in encrypted frame */
        DAVE_DECRYPTOR_RESULT_CODE_MISSING_CRYPTOR = 4;    /**< Missing cryptographic context */

    int DAVE_LOGGING_SEVERITY_VERBOSE = 0, /**< Verbose debug information */
        DAVE_LOGGING_SEVERITY_INFO = 1,    /**< Informational messages */
        DAVE_LOGGING_SEVERITY_WARNING = 2, /**< Warning messages */
        DAVE_LOGGING_SEVERITY_ERROR = 3,   /**< Error messages */
        DAVE_LOGGING_SEVERITY_NONE = 4;    /**< Messages to be ignored */

    short DAVE_DISABLED_PROTOCOL_VERSION = 0;
    long DAVE_MLS_NEW_GROUP_EXPECTED_EPOCH = 1L;
    int DAVE_INIT_TRANSITION_ID = 0;

    @FunctionalInterface interface DAVEMLSFailureCallback extends Callback
    { void callback(String source, String reason, Pointer userData); }

    @FunctionalInterface interface DAVEPairwiseFingerprintCallback extends Callback
    { void callback(Pointer fingerprint, SizeT length, Pointer userData); }

    @FunctionalInterface interface DAVEEncryptorProtocolVersionChangedCallback extends Callback
    { void callback(Pointer userData); }

    @FunctionalInterface interface DAVELogSinkCallback extends Callback
    { void callback(int severity, String file, int line, String message); }

    @Structure.FieldOrder({"passthroughCount","encryptSuccessCount","encryptFailureCount","encryptDuration","encryptAttempts","encryptMaxAttempts","encryptMissingKeyCount"})
    class DAVEEncryptorStats extends Structure
    {
        public DAVEEncryptorStats() {} public DAVEEncryptorStats(Pointer p) { super(p); this.read(); }
        public static class ByReference extends DAVEEncryptorStats implements Structure.ByReference
        { public ByReference() {} public ByReference(Pointer p) { super(p); } }
        public static class ByValue extends DAVEEncryptorStats implements Structure.ByValue
        { public ByValue() {} public ByValue(Pointer p) { super(p); } }
        public long passthroughCount;
        public long encryptSuccessCount;
        public long encryptFailureCount;
        public long encryptDuration;
        public long encryptAttempts;
        public long encryptMaxAttempts;
        public long encryptMissingKeyCount;
    }

    @Structure.FieldOrder({"passthroughCount","decryptSuccessCount","decryptFailureCount","decryptDuration","decryptAttempts","decryptMissingKeyCount","decryptInvalidNonceCount"})
    class DAVEDecryptorStats extends Structure
    {
        public DAVEDecryptorStats() {} public DAVEDecryptorStats(Pointer p) { super(p); this.read(); }
        public static class ByReference extends DAVEDecryptorStats implements Structure.ByReference
        { public ByReference() {} public ByReference(Pointer p) { super(p); } }
        public static class ByValue extends DAVEDecryptorStats implements Structure.ByValue
        { public ByValue() {} public ByValue(Pointer p) { super(p); } }
        public long passthroughCount;
        public long decryptSuccessCount;
        public long decryptFailureCount;
        public long decryptDuration;
        public long decryptAttempts;
        public long decryptMissingKeyCount;
        public long decryptInvalidNonceCount;
    }

    short daveMaxSupportedProtocolVersion();

    void daveFree(Pointer ptr);

    DAVESessionHandle daveSessionCreate(Pointer context, String authSessionId, DAVEMLSFailureCallback callback, Pointer userData);

    void daveSessionDestroy(DAVESessionHandle session);

    void daveSessionInit(DAVESessionHandle session, short version, long groupId, String selfUserId);

    void daveSessionReset(DAVESessionHandle session);

    void daveSessionSetProtocolVersion(DAVESessionHandle session, short version);

    short daveSessionGetProtocolVersion(DAVESessionHandle session);

    void daveSessionGetLastEpochAuthenticator(DAVESessionHandle session, PointerByReference authenticator, SizeTByReference length);

    void daveSessionSetExternalSender(DAVESessionHandle session, Pointer externalSender, SizeT length);
    void daveSessionSetExternalSender(DAVESessionHandle session, ByteBuffer externalSender, SizeT length);

    void daveSessionProcessProposals(DAVESessionHandle session, Pointer proposals, SizeT length, StringArray recognizedUserIds, SizeT recognizedUserIdsLength, PointerByReference commitWelcomeBytes, SizeTByReference commitWelcomeBytesLength);
    void daveSessionProcessProposals(DAVESessionHandle session, ByteBuffer proposals, SizeT length, StringArray recognizedUserIds, SizeT recognizedUserIdsLength, PointerByReference commitWelcomeBytes, SizeTByReference commitWelcomeBytesLength);

    DAVECommitResultHandle daveSessionProcessCommit(DAVESessionHandle session, Pointer commit, SizeT length);
    DAVECommitResultHandle daveSessionProcessCommit(DAVESessionHandle session, ByteBuffer commit, SizeT length);

    DAVEWelcomeResultHandle daveSessionProcessWelcome(DAVESessionHandle session, Pointer welcome, SizeT length, StringArray recognizedUserIds, SizeT recognizedUserIdsLength);
    DAVEWelcomeResultHandle daveSessionProcessWelcome(DAVESessionHandle session, ByteBuffer welcome, SizeT length, StringArray recognizedUserIds, SizeT recognizedUserIdsLength);

    void daveSessionGetMarshalledKeyPackage(DAVESessionHandle session, PointerByReference keyPackage, SizeTByReference length);

    DAVEKeyRatchetHandle daveSessionGetKeyRatchet(DAVESessionHandle session, String userId);

    void daveSessionGetPairwiseFingerprint(DAVESessionHandle session, short version, String userId, DAVEPairwiseFingerprintCallback callback, Pointer userData);

    void daveKeyRatchetDestroy(DAVEKeyRatchetHandle keyRatchet);

    boolean daveCommitResultIsFailed(DAVECommitResultHandle commitResultHandle);

    boolean daveCommitResultIsIgnored(DAVECommitResultHandle commitResultHandle);

    void daveCommitResultGetRosterMemberIds(DAVECommitResultHandle commitResultHandle, PointerByReference rosterIds, SizeTByReference rosterIdsLength);

    void daveCommitResultGetRosterMemberSignature(DAVECommitResultHandle commitResultHandle, long rosterId, PointerByReference signature, SizeTByReference signatureLength);

    void daveCommitResultDestroy(DAVECommitResultHandle commitResultHandle);

    void daveWelcomeResultGetRosterMemberIds(DAVEWelcomeResultHandle welcomeResultHandle, PointerByReference rosterIds, SizeTByReference rosterIdsLength);

    void daveWelcomeResultGetRosterMemberSignature(DAVEWelcomeResultHandle welcomeResultHandle, long rosterId, PointerByReference signature, SizeTByReference signatureLength);

    void daveWelcomeResultDestroy(DAVEWelcomeResultHandle welcomeResultHandle);

    DAVEEncryptorHandle daveEncryptorCreate();

    void daveEncryptorDestroy(DAVEEncryptorHandle encryptor);

    void daveEncryptorSetKeyRatchet(DAVEEncryptorHandle encryptor, DAVEKeyRatchetHandle keyRatchet);

    void daveEncryptorSetPassthroughMode(DAVEEncryptorHandle encryptor, boolean passthroughMode);

    void daveEncryptorAssignSsrcToCodec(DAVEEncryptorHandle encryptor, int ssrc, int codecType);

    short daveEncryptorGetProtocolVersion(DAVEEncryptorHandle encryptor);

    SizeT daveEncryptorGetMaxCiphertextByteSize(DAVEEncryptorHandle encryptor, int mediaType, SizeT frameSize);

    boolean daveEncryptorHasKeyRatchet(DAVEEncryptorHandle encryptor);

    boolean daveEncryptorIsPassthroughMode(DAVEEncryptorHandle encryptor);

    int daveEncryptorEncrypt(DAVEEncryptorHandle encryptor, int mediaType, int ssrc, Pointer frame, SizeT frameLength, Pointer encryptedFrame, SizeT encryptedFrameCapacity, SizeTByReference bytesWritten);
    int daveEncryptorEncrypt(DAVEEncryptorHandle encryptor, int mediaType, int ssrc, ByteBuffer frame, SizeT frameLength, ByteBuffer encryptedFrame, SizeT encryptedFrameCapacity, SizeTByReference bytesWritten);

    void daveEncryptorSetProtocolVersionChangedCallback(DAVEEncryptorHandle encryptor, DAVEEncryptorProtocolVersionChangedCallback callback);

    void daveEncryptorGetStats(DAVEEncryptorHandle encryptor, int mediaType, DAVEEncryptorStats.ByReference stats);

    DAVEDecryptorHandle daveDecryptorCreate();

    void daveDecryptorDestroy(DAVEDecryptorHandle decryptor);

    void daveDecryptorTransitionToKeyRatchet(DAVEDecryptorHandle decryptor, DAVEKeyRatchetHandle keyRatchet);

    void daveDecryptorTransitionToPassthroughMode(DAVEDecryptorHandle decryptor, boolean passthroughMode);

    int daveDecryptorDecrypt(DAVEDecryptorHandle decryptor, int mediaType, Pointer encryptedFrame, SizeT encryptedFrameLength, Pointer frame, SizeT frameCapacity, SizeTByReference bytesWritten);
    int daveDecryptorDecrypt(DAVEDecryptorHandle decryptor, int mediaType, ByteBuffer encryptedFrame, SizeT encryptedFrameLength, ByteBuffer frame, SizeT frameCapacity, SizeTByReference bytesWritten);

    SizeT daveDecryptorGetMaxPlaintextByteSize(DAVEDecryptorHandle decryptor, int mediaType, SizeT encryptedFrameSize);

    void daveDecryptorGetStats(DAVEDecryptorHandle decryptor, int mediaType, DAVEDecryptorStats.ByReference stats);

    void daveSetLogSinkCallback(DAVELogSinkCallback callback);

}
