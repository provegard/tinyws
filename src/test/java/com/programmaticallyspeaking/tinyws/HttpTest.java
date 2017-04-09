package com.programmaticallyspeaking.tinyws;

import org.testng.annotations.Test;

import java.net.HttpURLConnection;
import java.util.Map;
import java.util.function.Consumer;

import static org.testng.Assert.assertEquals;

public class HttpTest extends HttpTestBase {


    private HttpURLConnection sendGET(String path, Consumer<HttpURLConnection> configure) throws Exception {
        Map<String, String> h = headers(
            "Connection", "Upgrade",
            "Upgrade", "websocket",
            "Sec-WebSocket-Version", "13",
            "Sec-WebSocket-Key", "dGlueXdzIEZUVw=="
        );

        return sendGET(path, h, configure);
    }

    private HttpURLConnection sendPOST() throws Exception {
        return sendPOST("/", null);
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
}
