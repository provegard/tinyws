package com.programmaticallyspeaking.tinyws;

import com.programmaticallyspeaking.tinyws.Server.Headers;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.testng.Assert.assertEquals;

public class HeaderTests {

    @DataProvider
    public Object[][] failure_data() {
        return new Object[][] {
            { "Missing GET", "Upgrade: websocket\r\n\r\n", IllegalArgumentException.class },
            { "Malformed GET", "GET /foo\r\nUpgrade: websocket\r\n\r\n", IllegalArgumentException.class },
            { "Non-GET", "POST /foo HTTP/1.1\r\nUpgrade: websocket\r\n\r\n", Server.MethodNotAllowedException.class },
            { "GET too late", "Upgrade: websocket\r\nGET /foo HTTP/1.1\r\n\r\n", IllegalArgumentException.class },
        };
    }

    private InputStream streamFromString(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test(dataProvider = "failure_data")
    public void Invalid_request_should_fail(String desc, String headers, Class<? extends Throwable> expectedExceptionClass) throws IOException {
        Assert.assertThrows(expectedExceptionClass, () -> Headers.read(streamFromString(headers)));
    }

    @Test
    public void WebSocket_key_should_be_extracted() throws IOException {
        Headers headers = Headers.read(streamFromString("GET / HTTP/1.1\r\nSec-WebSocket-Key: foo\r\n\r\n"));
        assertEquals(headers.key(), "foo");
    }
}
