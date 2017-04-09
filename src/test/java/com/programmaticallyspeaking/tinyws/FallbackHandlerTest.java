package com.programmaticallyspeaking.tinyws;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertEquals;

public class FallbackHandlerTest extends HttpTestBase {

    ThrowingConsumer<Server.Connection> handler;

    @Override
    protected void onBeforeStart(Server server) {
        super.onBeforeStart(server);
        server.setFallbackHandler(new MyFallbackHandler());
        logAll = true;
    }

    @BeforeMethod
    public void reset() {
        handler = null;
    }

    private HttpURLConnection sendGET(String path) throws Exception {
        return sendGET(path, headers(), c -> {});
    }

    @Test
    public void Fallback_handler_should_be_invoked_for_unknown_endpoint() throws Exception {
        handler = c -> c.sendResponse(418, "I'm a teapot", null);
        HttpURLConnection conn = sendGET("/foo");
        assertEquals(conn.getResponseCode(), 418);
    }

    @Test
    public void Fallback_handler_should_get_access_to_the_method() throws Exception {
        handler = c -> c.sendResponse(200, c.method(), null);
        HttpURLConnection conn = sendPOST("/foo", null);
        assertEquals(conn.getResponseMessage(), "POST");
    }

    @Test
    public void Fallback_handler_should_get_access_to_the_URI() throws Exception {
        handler = c -> c.sendResponse(200, c.uri().toString(), null);
        HttpURLConnection conn = sendGET("/foo");
        String expected = "http://localhost:" + port + "/foo";
        assertEquals(conn.getResponseMessage(), expected);
    }

    @DataProvider
    public Object[][] headerName() {
        return new Object[][] {
                {"Connection"},
                {"CONNECTION"},
        };
    }

    @Test(dataProvider = "headerName")
    public void Fallback_handler_should_be_able_to_read_a_header(String name) throws Exception {
        handler = c -> c.sendResponse(200, c.header(name).orElse(""), null);
        HttpURLConnection conn = sendGET("/foo");
        assertEquals(conn.getResponseMessage(), "keep-alive");
    }

    @Test
    public void Fallback_handler_should_be_able_to_list_header_names() throws Exception {
        handler = c -> c.sendResponse(200, String.join(", ", c.headerNames()), null);
        HttpURLConnection conn = sendGET("/foo");
        assertThat(conn.getResponseMessage()).contains("User-Agent");
    }

    @Test
    public void Fallback_handler_should_be_able_to_read_data() throws Exception {
        handler = c -> {
            String s = readAvailableDataAsString(c.inputStream());
            c.sendResponse(200, s, null);
        };
        HttpURLConnection conn = sendPOST("/foo", "hello world".getBytes(StandardCharsets.UTF_8));
        assertThat(conn.getResponseMessage()).isEqualTo("hello world");
    }

    @Test
    public void Fallback_handler_should_be_able_to_write_data() throws Exception {
        handler = c -> {
            Map<String, String> headers = new HashMap<>();
            byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
            headers.put("Content-Length", "" + bytes.length);
            c.sendResponse(200, "OK", headers);
            c.outputStream().write(bytes);
            c.outputStream().close();
        };
        HttpURLConnection conn = sendGET("/foo");
        InputStream in = conn.getInputStream();
        String s = readAvailableDataAsString(in);
        assertThat(s).isEqualTo("hello world");
    }

    private String readAvailableDataAsString(InputStream in) throws IOException {
        byte[] arr = new byte[4096];
        int bytes = in.read(arr);
        return new String(arr, 0, bytes, StandardCharsets.UTF_8);
    }

    class MyFallbackHandler implements Server.FallbackHandler {
        @Override
        public void handle(Server.Connection connection) throws IOException {
            if (handler != null) {
                try {
                    handler.accept(connection);
                } catch (Throwable t) {
                    throw new IOException("Error", t);
                }
            } else throw new FileNotFoundException();
        }
    }
}
