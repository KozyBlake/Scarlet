package moe.kyokobot.libdave;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Represents a session for Discord's Audio/Video End-to-End Encryption (DAVE) protocol.
 * <p>
 * This class manages the Messaging Layer Security (MLS) group state, handling key exchanges,
 * group membership changes (proposals, commits, welcomes), and per-sender key ratchets.
 * It serves as the central coordination point for E2EE in a media session.
 */
public interface Session extends AutoCloseable {
    /**
     * Initializes the session with initial parameters.
     *
     * @param version    The DAVE protocol version to use.
     * @param groupId    The ID of the group (e.g., channel ID or call ID).
     * @param selfUserId The user ID of the current participant.
     */
    void init(int version, long groupId, String selfUserId);

    /**
     * Resets the session state.
     * <p>
     * This clears the current MLS group state and resets the session to its initial state.
     * Used when the session needs to be restarted, for example, after a hard failure or re-connection.
     */
    void reset();

    /**
     * Sets the protocol version for the session.
     *
     * @param version The protocol version to set.
     */
    void setProtocolVersion(int version);

    /**
     * Gets the current protocol version of the session.
     *
     * @return The current protocol version.
     */
    int getProtocolVersion();

    /**
     * Retrieves the authenticator for the last processed epoch.
     * <p>
     * This can be used to verify the integrity and authenticity of the current group state.
     *
     * @return A byte array containing the epoch authenticator.
     */
    byte[] getLastEpochAuthenticator();

    /**
     * Sets the external sender for the session.
     * <p>
     * The external sender is typically the Voice Gateway, which facilitates group changes.
     *
     * @param externalSender The binary representation of the external sender's public key/info.
     */
    void setExternalSender(byte[] externalSender);

    /**
     * Processes a list of MLS proposals.
     * <p>
     * Proposals are changes to the group (add, remove, update) that are not yet committed.
     *
     * @param proposals         The binary encoded proposals.
     * @param recognizedUserIds An array of user IDs that are recognized/expected in the session.
     * @return A byte array containing the result of processing the proposals (e.g., a commit message to be sent), or null/empty if no action is needed immediately.
     */
    byte[] processProposals(byte @NotNull [] proposals, @NotNull String[] recognizedUserIds);

    /**
     * Processes an MLS commit message.
     * <p>
     * A commit finalizes proposals and transitions the group to a new epoch.
     *
     * @param commit The binary encoded commit message.
     * @return A {@link CommitResult} indicating the outcome (success, failure, ignored) and containing the roster changes.
     */
    @NotNull CommitResult processCommit(byte @NotNull [] commit);

    /**
     * Processes an MLS welcome message.
     * <p>
     * A welcome message is received when joining an existing group. It contains the initial group state.
     *
     * @param welcome           The binary encoded welcome message.
     * @param recognizedUserIds An array of user IDs that are recognized/expected.
     * @return A {@link RosterMap} containing the initial set of participants and their keys, or null on failure.
     */
    @Nullable RosterMap processWelcome(byte @NotNull [] welcome, @NotNull String[] recognizedUserIds);

    /**
     * Generates a marshalled key package for the current user.
     * <p>
     * This package is used to publish the user's initial key material so they can be added to the MLS group.
     *
     * @return A byte array containing the marshalled key package.
     */
    byte[] getMarshalledKeyPackage();

    /**
     * Retrieves the key ratchet for a specific user.
     * <p>
     * The key ratchet contains the sequence of keys used to decrypt media from that user (or encrypt for self).
     *
     * @param userId The ID of the user whose ratchet is requested.
     * @return A {@link KeyRatchet} instance.
     */
    @Nullable KeyRatchet getKeyRatchet(String userId);

    /**
     * Computes a pairwise fingerprint for verification with another user asynchronously.
     * <p>
     * The returned fingerprint can be compared out-of-band to verify that no man-in-the-middle attack is occurring.
     *
     * @param version The protocol version.
     * @param userId  The ID of the other user.
     * @return A {@link CompletableFuture} that will complete with the computed fingerprint as a byte array,
     * or complete exceptionally if the computation failed.
     */
    CompletableFuture<byte[]> getPairwiseFingerprint(int version, String userId);

    /**
     * Closes the session and releases native resources.
     */
    @Override
    void close();
}
