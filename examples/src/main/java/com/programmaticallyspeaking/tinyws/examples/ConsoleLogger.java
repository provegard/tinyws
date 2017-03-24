package com.programmaticallyspeaking.tinyws.examples;

import com.programmaticallyspeaking.tinyws.Server;

class ConsoleLogger implements Server.Logger {
    @Override
    public void log(Server.LogLevel level, String message, Throwable error) {
        if (!isEnabledAt(level)) return;
        String msg = level + ": " + message;
        (error != null ? System.err : System.out).println(msg);
        if (error != null) error.printStackTrace(System.err);
    }

    @Override
    public boolean isEnabledAt(Server.LogLevel level) {
        return level.level >= Server.LogLevel.DEBUG.level;
    }
}
