package moe.kyokobot.libdave;

/**
 * Result codes for {@link Decryptor#decrypt} operations.
 */
public enum DecryptorResultCode {
    /**
     * Decryption was successful.
     */
    SUCCESS(0),
    /**
     * Decryption failed (generic error).
     */
    DECRYPTION_FAILURE(1),
    /**
     * Decryption failed because the key ratchet for the sender is missing.
     */
    MISSING_KEY_RATCHET(2),
    /**
     * Decryption failed due to an invalid nonce.
     */
    INVALID_NONCE(3),
    /**
     * Decryption failed because the underlying cryptor was missing or invalid.
     */
    MISSING_CRYPTOR(4);

    private final int value;

    DecryptorResultCode(int value) {
        this.value = value;
    }

    /**
     * Gets the integer value of the result code.
     *
     * @return The result code value.
     */
    public int getValue() {
        return value;
    }

    /**
     * Retrieves the DecryptorResultCode enum from its integer value.
     *
     * @param value The integer value.
     * @return The corresponding {@link DecryptorResultCode}.
     * @throws IllegalArgumentException if the value is unknown.
     */
    public static DecryptorResultCode fromValue(int value) {
        for (DecryptorResultCode code : values()) {
            if (code.value == value) {
                return code;
            }
        }
        throw new IllegalArgumentException("Unknown DecryptorResultCode value: " + value);
    }
}

