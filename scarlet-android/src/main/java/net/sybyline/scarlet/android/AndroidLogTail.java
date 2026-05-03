package net.sybyline.scarlet.android;

import java.io.File;

interface AndroidLogTail {
    File currentOutputFile();
    boolean isRunning();
    void start();
    void stop();
}
