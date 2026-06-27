package net.sybyline.scarlet.util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProcLock
{

    @SuppressWarnings("resource")
    public static boolean tryLock(File file)
    {
        try
        {
            FileChannel channel = new RandomAccessFile(file, "rw").getChannel();
            FileLock lock = channel.tryLock();
            if (lock == null)
            {
                channel.close();
                throw new IOException();
            }
            locks.add(() ->
            {
                MiscUtils.close(lock);
                MiscUtils.close(channel);
                file.delete();
            });
            return true;
        }
        catch (Exception ex)
        {
            return false;
        }
    }

    private static final List<Runnable> locks = Collections.synchronizedList(new ArrayList<>());
    public static void releaseAll()
    {
        List<Runnable> copy;
        synchronized (locks)
        {
            copy = new ArrayList<>(locks);
            locks.clear();
        }
        for (Runnable lock : copy)
        {
            try
            {
                lock.run();
            }
            catch (Throwable ignored)
            {
            }
        }
    }
    static
    {
        Runtime.getRuntime().addShutdownHook(new Thread(ProcLock::releaseAll, "ProcLock.shutdownHook"));
    }

}
