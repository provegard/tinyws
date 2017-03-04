package com.programmaticallyspeaking.tinyws.examples;

import com.programmaticallyspeaking.tinyws.TinyWS;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class EchoServer {
    public static void main(String[] args) {
        Executor executor = Executors.newCachedThreadPool();
        TinyWS ws = new TinyWS(Executors.defaultThreadFactory(), executor, TinyWS.Options.withPort(9001));
        ws.addHandler("/", new EchoHandler());
        try {
            System.out.println("Starting");
            ws.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class EchoHandler implements TinyWS.WebSocketHandler {

        private TinyWS.WebSocketClient client;

        @Override
        public void onOpened(TinyWS.WebSocketClient client) {
            this.client = client;
        }

        @Override
        public void onClosedByClient(int code, String reason) {
        }

        @Override
        public void onTextMessage(String text) {
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
}
