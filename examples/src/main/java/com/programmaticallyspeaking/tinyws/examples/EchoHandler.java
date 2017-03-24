package com.programmaticallyspeaking.tinyws.examples;

import com.programmaticallyspeaking.tinyws.Server;

import java.io.IOException;

class EchoHandler implements Server.WebSocketHandler {

    private Server.WebSocketClient client;

    @Override
    public void onOpened(Server.WebSocketClient client) {
        this.client = client;
    }

    @Override
    public void onClosedByClient(int code, String reason) {
    }

    @Override
    public void onClosedByServer(int code, String reason) {
    }

    @Override
    public void onFailure(Throwable t) {
        t.printStackTrace(System.err);
    }

    @Override
    public void onTextMessage(CharSequence text) {
        try {
            client.sendTextMessage(text);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onBinaryData(byte[] data) {
        try {
            client.sendBinaryData(data);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
