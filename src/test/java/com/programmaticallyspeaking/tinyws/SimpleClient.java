package com.programmaticallyspeaking.tinyws;

import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_17;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

class SimpleClient extends org.java_websocket.client.WebSocketClient {
    private CountDownLatch closeLatch = new CountDownLatch(1);

    List<String> messages = new ArrayList<>();

    void sendRawData(byte[] data) {
        ((DraftThatAllowsUsToSendBogusData) getConnection().getDraft()).setDataToSend(data);
        getConnection().sendFrame(null);
    }

    void waitUntilClosed() throws InterruptedException {
        closeLatch.await();
    }

    public SimpleClient(URI serverURI) throws InterruptedException {
        super(serverURI, new DraftThatAllowsUsToSendBogusData());
        if (!this.connectBlocking()) throw new IllegalStateException("Not connected");
    }

    public void onOpen(ServerHandshake handshakedata) {
    }

    public void onMessage(String message) {
        messages.add(message);
    }

    public void onClose(int code, String reason, boolean remote) {
        closeLatch.countDown();
    }

    public void onError(Exception ex) {
    }

    void sendClose(int code) {
        // We just want to send the close frame and let the remote close the connection, otherwise we might
        // not receive an echo response in time.
        sendRawData(new byte [] { (byte)136, 2, (byte) ((code & 0xff00) >>> 8), (byte) (code & 0xff) });
    }

    static void sendIncorrectFrame(URI uri) throws Exception {
        SimpleClient cl = new SimpleClient(uri);
        cl.sendRawData(new byte [] { (byte)112, 0 });
        cl.waitUntilClosed();
    }

    static SimpleClient sendText(URI uri, String text) throws Exception {
        SimpleClient cl = new SimpleClient(uri);
        cl.send(text);
        cl.sendClose(1001);
        cl.waitUntilClosed();
        return cl;
    }

    static void sendClose(URI uri) throws Exception {
        SimpleClient cl = new SimpleClient(uri);
        cl.getConnection().close(1001);
        cl.waitUntilClosed();
    }

    static class DraftThatAllowsUsToSendBogusData extends Draft_17 {
        private byte[] dataToSend;

        void setDataToSend(byte[] data) {
            dataToSend = data;
        }
        @Override
        public ByteBuffer createBinaryFrame(Framedata framedata) {
            if (dataToSend != null) {
                ByteBuffer buf = ByteBuffer.wrap(dataToSend);
                dataToSend = null;
                return buf;
            }
            return super.createBinaryFrame(framedata);
        }

        @Override
        public Draft copyInstance() {
            return new DraftThatAllowsUsToSendBogusData();
        }
    }
}
