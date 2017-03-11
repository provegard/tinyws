package com.programmaticallyspeaking.tinyws;

import java.io.IOException;
import java.net.SocketException;

class EchoHandler implements Server.WebSocketHandler {

    private Server.WebSocketClient client;

    public void onOpened(Server.WebSocketClient client) {
        this.client = client;
    }

    public void onClosedByClient(int code, String reason) {}

    public void onClosedByServer(int code, String reason) {}

    public void onFailure(Throwable t) {
        maybePrint(t);
    }

    public void onTextMessage(CharSequence text) {
        try {
            client.sendTextMessage(text);
        } catch (IOException e) {
            maybePrint(e);
        }
    }

    @Override
    public void onBinaryData(byte[] data) {
        try {
            client.sendBinaryData(data);
        } catch (IOException e) {
            maybePrint(e);
        }
    }

    private void maybePrint(Throwable t) {
        // Don't print "socket closed" on stderr when running tests with Gradle.
        if (t instanceof SocketException) return;
        t.printStackTrace(System.err);
    }
}
