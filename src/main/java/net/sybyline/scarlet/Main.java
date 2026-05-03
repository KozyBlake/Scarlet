package net.sybyline.scarlet;

import net.sybyline.scarlet.util.MavenDepsLoader;
import net.sybyline.scarlet.util.Platform;

public abstract class Main
{

    static
    {
        if (Platform.forceHeadlessUi() && System.getProperty("java.awt.headless") == null)
        {
            System.setProperty("java.awt.headless", "true");
            System.err.println("Android/Termux runtime detected; forcing headless UI mode");
        }
        MavenDepsLoader.init();
    }

    public static void main(String[] args) throws Throwable
    {
        Scarlet.main(args);
    }

    private Main()
    {
        throw new UnsupportedOperationException();
    }

}
