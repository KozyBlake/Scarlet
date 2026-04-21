package moe.kyokobot.libdave;

/**
 * Enumeration of supported audio and video codecs.
 * <p>
 * These values correspond to the codec identifiers used in the DAVE protocol.
 */
public enum Codec {
    UNKNOWN(0),
    OPUS(1),
    VP8(2),
    VP9(3),
    H264(4),
    H265(5),
    AV1(6);

    private final int value;

    Codec(int value) {
        this.value = value;
    }

    /**
     * Gets the integer value of the codec.
     *
     * @return The codec value.
     */
    public int getValue() {
        return value;
    }

    /**
     * Retrieves the Codec enum from its integer value.
     *
     * @param value The integer value.
     * @return The corresponding {@link Codec}.
     * @throws IllegalArgumentException if the value is unknown.
     */
    public static Codec fromValue(int value) {
        for (Codec codec : values()) {
            if (codec.value == value) {
                return codec;
            }
        }
        throw new IllegalArgumentException("Unknown Codec value: " + value);
    }
}

