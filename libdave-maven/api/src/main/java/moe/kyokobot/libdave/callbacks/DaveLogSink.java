package moe.kyokobot.libdave.callbacks;

@FunctionalInterface
public interface DaveLogSink {
    void log(int severity, String file, int line, String message);
}
