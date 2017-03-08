package com.programmaticallyspeaking.tinyws;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.function.Consumer;

class StreamReadingThread extends Thread {
    private final InputStream in;
    private final Consumer<String> appender;

    StreamReadingThread(InputStream in, Consumer<String> appender) {
        this.in = in;
        this.appender = appender;
    }

    public void run() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String str = "";
            while (str != null) {
                str = reader.readLine();
                if (str != null) appender.accept(str);
            }
        } catch (IOException ex) {
            ex.printStackTrace(System.err);
        }
    }
}
