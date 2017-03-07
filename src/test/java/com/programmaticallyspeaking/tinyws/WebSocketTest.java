package com.programmaticallyspeaking.tinyws;

import com.programmaticallyspeaking.tinyws.Server.WebSocketHandler;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_17;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;

import static org.mockito.Mockito.*;

public class WebSocketTest extends ClientTestBase {

    private URI createURI() throws URISyntaxException {
        return new URI("ws://" + host + ":" + port);
    }

    private void sendIncorrectFrame() throws Exception {
        SimpleClient cl = new SimpleClient(createURI());
        cl.sendRawData(new byte [] { (byte)112 });
        cl.waitUntilClosed();
    }

    private void sendClose() throws Exception {
        SimpleClient cl = new SimpleClient(createURI());
        cl.getConnection().close(1001);
        cl.waitUntilClosed();
    }

    @Test
    public void Incorrect_frame_should_invoke_onClosedByServer() throws Exception {
        sendIncorrectFrame();
        WebSocketHandler handler = createdHandlers.remove();
        verify(handler, times(1)).onClosedByServer(1002, "Protocol error");
    }

    @Test
    public void Incorrect_frame_should_not_invoke_onClosedByClient() throws Exception {
        sendIncorrectFrame();
        WebSocketHandler handler = createdHandlers.remove();
        verify(handler, never()).onClosedByClient(anyInt(), anyString());
    }

    @Test
    public void Proper_close_should_invoke_onClosedByClient() throws Exception {
        sendClose();
        WebSocketHandler handler = createdHandlers.remove();
        verify(handler, times(1)).onClosedByClient(1001, null);
    }

    @Test
    public void Proper_close_should_not_invoke_onClosedByServer() throws Exception {
        sendClose();
        WebSocketHandler handler = createdHandlers.remove();
        verify(handler, never()).onClosedByServer(anyInt(), anyString());
    }

    static class SimpleClient extends org.java_websocket.client.WebSocketClient {
        private CountDownLatch closeLatch = new CountDownLatch(1);

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
        }

        public void onClose(int code, String reason, boolean remote) {
            closeLatch.countDown();
        }

        public void onError(Exception ex) {
        }
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
