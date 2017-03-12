package com.programmaticallyspeaking.tinyws;

import com.programmaticallyspeaking.tinyws.Server.WebSocketHandler;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_17;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class WebSocketTest extends ClientTestBase {

    private boolean useEchoHandler;

    private URI createURI() throws URISyntaxException {
        return new URI("ws://" + host + ":" + port);
    }

    @BeforeMethod
    public void init() {
        useEchoHandler = false;
    }

    @Override
    protected WebSocketHandler createHandler() {
        return useEchoHandler ? new EchoHandler() : super.createHandler();
    }

    private void sendIncorrectFrame() throws Exception {
        SimpleClient cl = new SimpleClient(createURI());
        cl.sendRawData(new byte [] { (byte)112, 0 });
        cl.waitUntilClosed();
    }

    private SimpleClient sendText(String text) throws Exception {
        SimpleClient cl = new SimpleClient(createURI());
        cl.send(text);
        cl.sendClose(1001);
        cl.waitUntilClosed();
        return cl;
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

    @Test
    public void Correct_frame_should_result_in_echo_response() throws Exception {
        useEchoHandler = true;
        SimpleClient cl = sendText("hello world");
        assertThat(cl.messages).containsExactly("hello world");
    }

    static class SimpleClient extends org.java_websocket.client.WebSocketClient {
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
