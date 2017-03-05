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

    @DataProvider
    public Object[][] version_data() {
        return new Object[][] {
            { "Proper", "Sec-WebSocket-Version: 13\r\n", 13 },
            { "Case insensitive", "sec-websocket-version: 13\r\n", 13 },
            { "Invalid", "Sec-WebSocket-Version: foo\r\n", 0 },
            { "Missing", "", 0 }
        };
    }

    @Test(dataProvider = "version_data")
    public void WebSocket_version_should_be_extracted(String desc, String header, int expected) throws IOException {
        Headers headers = Headers.read(streamFromString("GET / HTTP/1.1\r\n" + header + "\r\n"));
        assertEquals(headers.version(), expected);
    }

    @DataProvider
    public Object[][] userAgent_data() {
        return new Object[][] {
            { "Proper", "User-Agent: foobar\r\n", "foobar" },
            { "Case insensitive", "user-agent: foobar\r\n", "foobar" },
            { "Missing", "", null }
        };
    }

    @Test(dataProvider = "userAgent_data")
    public void User_agent_should_be_extracted(String desc, String header, String expected) throws IOException {
        Headers headers = Headers.read(streamFromString("GET / HTTP/1.1\r\n" + header + "\r\n"));
        assertEquals(headers.userAgent(), expected);
    }

    @DataProvider
    public Object[][] host_data() {
        return new Object[][] {
            { "Proper", "Host: test:81\r\n", "test:81" },
            { "Missing", "", null }
        };
    }

    @Test(dataProvider = "host_data")
    public void Host_should_be_extracted(String desc, String header, String expected) throws IOException {
        Headers headers = Headers.read(streamFromString("GET / HTTP/1.1\r\n" + header + "\r\n"));
        assertEquals(headers.host(), expected);
    }

    @DataProvider
    public Object[][] endpoint_data() {
        return new Object[][] {
            { "Plain GET", "GET /foo HTTP/1.1\r\n\r\n", "/foo", null, null },
            { "Pct-encoded path", "GET /foo%20bar HTTP/1.1\r\n\r\n", "/foo bar", null, null },
            { "Empty query", "GET /foo? HTTP/1.1\r\n\r\n", "/foo", "", null },
            { "GET with query", "GET /foo?bar=baz HTTP/1.1\r\n\r\n", "/foo", "bar=baz", null },
            { "Query and fragment", "GET /foo?bar#baz HTTP/1.1\r\n\r\n", "/foo", "bar", "baz" }
        };
    }

    @Test(dataProvider = "endpoint_data")
    public void Endpoint_should_be_extracted(String desc, String headers, String expected, String ignored, String ignored2) throws IOException {
        Headers h = Headers.read(streamFromString(headers));
        assertEquals(h.endpoint, expected);
    }

    @Test(dataProvider = "endpoint_data")
    public void Querystring_should_be_extracted(String desc, String headers, String ignored, String expected, String ignored2) throws IOException {
        Headers h = Headers.read(streamFromString(headers));
        assertEquals(h.query, expected);
    }

    @Test(dataProvider = "endpoint_data")
    public void Fragment_should_be_extracted(String desc, String headers, String ignored, String ignored2, String expected) throws IOException {
        Headers h = Headers.read(streamFromString(headers));
        assertEquals(h.fragment, expected);
    }
}
