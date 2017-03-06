/*
 * Copyright (c) 2017, Per Rovegård <per@rovegard.se>
 *
 * Distributed under the MIT License (license terms are at http://per.mit-license.org, or in the LICENSE file at
 * https://github.com/provegard/tinyws/blob/master/LICENSE).
 */

package com.programmaticallyspeaking.tinyws.examples;

import com.programmaticallyspeaking.tinyws.Server;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class EchoServer {
    public static void main(String[] args) {
        Executor executor = Executors.newCachedThreadPool();
        Server.Logger logger = new Server.Logger() {
            @Override
            public void log(Server.LogLevel level, String message, Throwable error) {
                String msg = level + ": " + message;
                (error != null ? System.err : System.out).println(msg);
                if (error != null) error.printStackTrace(System.err);
            }

            @Override
            public boolean isEnabledAt(Server.LogLevel level) {
                return true;
            }
        };
        Server ws = new Server(executor, executor,
                Server.Options.withPort(9001).andLogger(logger));
        ws.addHandlerFactory("/", EchoHandler::new);
        try {
            System.out.println("Starting");
            ws.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class EchoHandler implements Server.WebSocketHandler {

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
