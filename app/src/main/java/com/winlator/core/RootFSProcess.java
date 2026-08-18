package com.winlator.core;

public final class RootFSProcess {
    static {
        System.loadLibrary("winlator");
    }

    private RootFSProcess() {}

    public static native int launch(String rootDir, String[] command, String[] environment, String workingDir);

    public static native int waitFor(int pid);
}
