package net.sybyline.scarlet.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.BooleanSupplier;

public class Sys
{

    private static final String PATH_SEPARATOR = System.getProperty("path.separator");
    private static final Path[] PATHS = Arrays.stream(System.getenv("PATH").split(PATH_SEPARATOR)).map(Paths::get).toArray(Path[]::new);
    private static final String[] PATHEXTS = Optional.ofNullable(System.getenv("PATHEXT")).map($ -> $.split(PATH_SEPARATOR)).orElse(new String[0]);

    public static boolean hasInPath(String name)
    {
        for (Path path : PATHS)
        {
            if (Files.isRegularFile(path.resolve(name)))
            {
                return true;
            }
            for (String pathext : PATHEXTS)
            {
                if (Files.isRegularFile(path.resolve(name + pathext)))
                {
                    return true;
                }
            }
        }
        return false;
    }

    public static Optional<String> searchPath(String... names)
    {
        return Arrays.stream(names).filter(Sys::hasInPath).findFirst();
    }

    /**
     * Polls {@code condition} until it returns true or the timeout elapses. Intended for waiting on
     * an asynchronous side effect - for example a package appearing on {@code PATH} after a
     * fork-and-exit terminal launched an installer. Returns true if the condition became true within
     * the timeout. Safe to call only from a background thread (it sleeps).
     */
    public static boolean waitForCondition(BooleanSupplier condition, long timeoutMs, long intervalMs)
    {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        for (;;)
        {
            try
            {
                if (condition.getAsBoolean())
                    return true;
            }
            catch (Throwable ignored)
            {
            }
            if (System.currentTimeMillis() >= deadline)
                return false;
            try
            {
                Thread.sleep(Math.max(1L, intervalMs));
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

}
