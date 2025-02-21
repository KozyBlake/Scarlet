package net.sybyline.scarlet.log;

import java.io.File;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import net.sybyline.scarlet.Scarlet;

public class ScarletLogger extends LegacyAbstractLogger
{

    private static final long serialVersionUID = -875763706664943747L;

    public ScarletLogger()
    {
        this("");
    }

    public ScarletLogger(String name)
    {
        this.name = name;
    }

    @Override
    public boolean isTraceEnabled()
    {
        return false;
    }

    @Override
    public boolean isDebugEnabled()
    {
        return true;
    }

    @Override
    public boolean isInfoEnabled()
    {
        return true;
    }

    @Override
    public boolean isWarnEnabled()
    {
        return true;
    }

    @Override
    public boolean isErrorEnabled()
    {
        return true;
    }

    @Override
    protected String getFullyQualifiedCallerName()
    {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern, Object[] arguments, Throwable throwable)
    {
        LocalDateTime now = LocalDateTime.now();
        FormattingTuple result = MessageFormatter.arrayFormat(messagePattern, arguments);
        PrintStream stream = level == Level.ERROR ? System.err : System.out;
        String name = marker == null ? this.name : (this.name + "/" + marker.getName());
        String logMessage = String.format("%1$tF %1$tT [%2$s] [%3$s] %4$s%n", now, level, name, result.getMessage());
        
//        stream.print(logMessage);
//        if (throwable != null)
//            throwable.printStackTrace(stream);
        ThreadLogEntry.logEntry(stream, logMessage, throwable);
    }

    static class ThreadLogEntry
    {

        static void logEntry(PrintStream stream, String logMessage, Throwable throwable)
        {
            entries.add(new ThreadLogEntry(stream, logMessage, throwable));
        }

        private ThreadLogEntry(PrintStream stream, String logMessage, Throwable throwable)
        {
            this.stream = stream;
            this.logMessage = logMessage;
            this.throwable = throwable;
        }
        private final PrintStream stream;
        private final String logMessage;
        private final Throwable throwable;

        private static final Thread printer_thread = new Thread(ThreadLogEntry::thread),
                                    shutdown_thread = new Thread(ThreadLogEntry::shutdown);
        private static final BlockingQueue<ThreadLogEntry> entries = new LinkedBlockingQueue<>();
        private static final File logFile = new File(System.getenv("LOCALAPPDATA"), Scarlet.GROUP+"/"+Scarlet.NAME+"/logs/scarlet_log_"+DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now())+".txt");
        private static final PrintStream logFileStream;

        static
        {
            printer_thread.setDaemon(true);
            printer_thread.setName("Scarlet Logging Thread");
            printer_thread.start();
            
            shutdown_thread.setDaemon(true);
            shutdown_thread.setName("Scarlet Logging Shutdown Hook");
            Runtime.getRuntime().addShutdownHook(shutdown_thread);
            
            File logDir = logFile.getParentFile();
            if (!logDir.isDirectory())
                logDir.mkdirs();
            
            try
            {
                logFileStream = new PrintStream(logFile);
            }
            catch (Exception ex)
            {
                throw new Error("Failed to initialize log file", ex);
            }
        }

        private static void thread()
        {
            while (!Thread.interrupted()) try
            {
                ThreadLogEntry entry = entries.take();
                
                entry.stream.print(entry.logMessage);
                logFileStream.print(entry.logMessage);
                
                if (entry.throwable != null)
                {
                    entry.throwable.printStackTrace(entry.stream);
                    entry.throwable.printStackTrace(logFileStream);
                }
                
            }
            catch (Exception ex)
            {
                // ignore
            }
        }

        private static void shutdown()
        {
            try
            {
                printer_thread.interrupt();
                printer_thread.join(1_000L);
            }
            catch (Exception ex)
            {
                ex.printStackTrace();
            }
            if (logFileStream != null) try
            {
                logFileStream.flush();
                logFileStream.close();
            }
            catch (Exception ex)
            {
                ex.printStackTrace();
            }
        }

    }

}
