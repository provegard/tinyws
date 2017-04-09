package com.programmaticallyspeaking.tinyws;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeSuite;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class HttpTestBase extends ClientTestBase {
    private List<HttpURLConnection> connections = new ArrayList<>();

    @BeforeSuite
    public void init() {
        // Allow setting e.g. the Connection header
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    @AfterMethod
    public void cleanup() {
        while (connections.size() > 0) {
            connections.remove(0).disconnect();
        }
    }

    private URL createURL(String path) throws MalformedURLException {
        return new URL("http://" + host + ":" + port + path);
    }

    protected HttpURLConnection sendPOST(String path, byte[] data) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) createURL(path).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.connect();

        if (data != null) {
            OutputStream os = connection.getOutputStream();
            os.write(data);
            os.flush();
        }

        connections.add(connection);
        return connection;
    }

    protected HttpURLConnection sendGET(String path, Map<String, String> headers, Consumer<HttpURLConnection> configure) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) createURL(path).openConnection();
        connection.setRequestMethod("GET");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            connection.setRequestProperty(entry.getKey(), entry.getValue());
        }
        configure.accept(connection);
        connection.connect();

        connections.add(connection);
        return connection;
    }

    protected Map<String, String> headers(String... keyValues) {
        if (keyValues.length % 2 != 0) throw new IllegalArgumentException("Key-value pair mismatch");
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
