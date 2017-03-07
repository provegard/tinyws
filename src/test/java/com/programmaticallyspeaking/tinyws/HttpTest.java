package com.programmaticallyspeaking.tinyws;

import com.programmaticallyspeaking.tinyws.Server.WebSocketHandler;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_17;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.testng.annotations.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.mockito.Mockito.*;
import static org.testng.Assert.assertEquals;

public class HttpTest {

    private Server server;
    private List<HttpURLConnection> connections = new ArrayList<>();
    private Queue<WebSocketHandler> createdHandlers = new ConcurrentLinkedQueue<>();

    @BeforeSuite
    public void init() {
        // Allow setting e.g. the Connection header
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    @BeforeClass
    public void startServer() throws IOException {
        Executor executor = Executors.newCachedThreadPool();
        Server ws = new Server(executor, executor, Server.Options.withPort(59001));
        ws.addHandlerFactory("/", () -> {
            WebSocketHandler h = mock(WebSocketHandler.class);
            createdHandlers.add(h);
            return h;
        });
        ws.start();
        server = ws;
    }

    @AfterClass
    public void stopServer() {
        if (server != null) server.stop();
    }

    @BeforeMethod
    public void reset() {
        createdHandlers.clear();
//        NoopHandler.count = 0;
    }

    @AfterMethod
    public void cleanup() {
        while (connections.size() > 0) {
            connections.remove(0).disconnect();
        }
    }

    private HttpURLConnection sendPOST() throws Exception {
        URL url = new URL("http://localhost:59001");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.connect();

        connections.add(connection);
        return connection;
    }

    private HttpURLConnection sendGET(String path, Consumer<HttpURLConnection> configure) throws Exception {
        URL url = new URL("http://localhost:59001" + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Connection", "Upgrade");
        connection.setRequestProperty("Upgrade", "websocket");
        connection.setRequestProperty("Sec-WebSocket-Version", "13");
        connection.setRequestProperty("Sec-WebSocket-Key", "dGlueXdzIEZUVw==");
        configure.accept(connection);
        connection.connect();

        connections.add(connection);
        return connection;
    }

    @Test
    public void POST_should_result_in_405() throws Exception {
        HttpURLConnection conn = sendPOST();
        assertEquals(conn.getResponseCode(), 405);
    }

    @Test
    public void POST_response_should_include_Allow_GET() throws Exception {
        HttpURLConnection conn = sendPOST();
        assertEquals(conn.getHeaderField("Allow"), "GET");
    }

    @Test
    public void Proper_GET_should_result_in_101() throws Exception {
        HttpURLConnection conn = sendGET("/", c -> {});
        assertEquals(conn.getResponseCode(), 101);
    }

    @Test
    public void Proper_GET_with_unknown_endpoint_should_result_in_404() throws Exception {
        HttpURLConnection conn = sendGET("/foo", c -> {});
        assertEquals(conn.getResponseCode(), 404);
    }

    @Test
    public void GET_with_wrong_Connection_should_result_in_400() throws Exception {
        HttpURLConnection conn = sendGET("/", c -> c.setRequestProperty("Connection", "close"));
        assertEquals(conn.getResponseCode(), 400);
    }

    @Test
    public void GET_with_wrong_Upgrade_should_result_in_400() throws Exception {
        HttpURLConnection conn = sendGET("/", c -> c.setRequestProperty("Upgrade", "foosocket"));
        assertEquals(conn.getResponseCode(), 400);
    }

    @Test
    public void GET_with_wrong_version_should_result_in_400() throws Exception {
        HttpURLConnection conn = sendGET("/", c -> c.setRequestProperty("Sec-WebSocket-Version", "42"));
        assertEquals(conn.getResponseCode(), 400);
    }

    @Test
    public void GET_with_wrong_version_should_adverties_correct_version() throws Exception {
        HttpURLConnection conn = sendGET("/", c -> c.setRequestProperty("Sec-WebSocket-Version", "42"));
        assertEquals(conn.getHeaderField("Sec-WebSocket-Version"), "13");
    }

    @Test
    public void GET_with_missing_key_should_result_in_400() throws Exception {
        HttpURLConnection conn = sendGET("/", c -> c.setRequestProperty("Sec-WebSocket-Key", null));
        assertEquals(conn.getResponseCode(), 400);
    }

    @Test
    public void Proper_GET_should_create_one_handler_per_request() throws Exception {
        sendGET("/", c -> {}).getResponseCode();
        sendGET("/", c -> {}).getResponseCode();
        assertEquals(createdHandlers.size(), 2);
    }

    private void sendIncorrectFrame() throws Exception {
        SimpleClient cl = new SimpleClient(new URI("ws://localhost:59001/"));
        cl.sendRawData(new byte [] { (byte)112 });
        cl.waitUntilClosed();
    }

    private void sendClose() throws Exception {
        SimpleClient cl = new SimpleClient(new URI("ws://localhost:59001/"));
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
