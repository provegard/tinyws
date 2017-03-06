package com.programmaticallyspeaking.tinyws;

import org.testng.annotations.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.testng.Assert.assertEquals;

public class HttpTest {

    private Server server;
    private List<HttpURLConnection> connections = new ArrayList<>();

    @BeforeSuite
    public void init() {
        // Allow setting e.g. the Connection header
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    @BeforeClass
    public void startServer() throws IOException {
        Executor executor = Executors.newCachedThreadPool();
        Server ws = new Server(executor, executor, Server.Options.withPort(59001));
        ws.addHandlerFactory("/", NoopHandler::new);
        ws.start();
        server = ws;
    }

    @AfterClass
    public void stopServer() {
        if (server != null) server.stop();
    }

    @BeforeMethod
    public void reset() {
        NoopHandler.count = 0;
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
        assertEquals(NoopHandler.count, 2);
    }

    private static class NoopHandler implements Server.WebSocketHandler {
        static volatile int count = 0;
        NoopHandler() {
            count++;
        }

        public void onOpened(Server.WebSocketClient client) {}

        public void onClosedByClient(int code, String reason) {}

        public void onFailure(Throwable t) {}

        public void onTextMessage(String text) {}

        public void onBinaryData(byte[] data) {}
    }
}
