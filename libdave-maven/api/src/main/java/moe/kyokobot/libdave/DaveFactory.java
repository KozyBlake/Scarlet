package moe.kyokobot.libdave;

import moe.kyokobot.libdave.callbacks.MLSFailureCallback;

public interface DaveFactory {
    /**
     * Returns the maximum protocol version supported by the underlying native library.
     *
     * @return The maximum supported DAVE protocol version.
     */
    int maxSupportedProtocolVersion();

    /**
     * Creates a new Decryptor instance.
     */
    Decryptor createDecryptor();

    /**
     * Creates a new Encryptor instance.
     */
    Encryptor createEncryptor();

    /**
     * Creates a new DAVE session.
     *
     * @param context       A string context for the session, often used for logging or identifying the session.
     * @param authSessionId The authentication session ID associated with the user.
     * @param callback      Callback to handle MLS failures, such as invalid transitions.
     */
    Session createSession(String context, String authSessionId, MLSFailureCallback callback);
}
