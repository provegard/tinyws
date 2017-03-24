package com.programmaticallyspeaking.tinyws;

import com.programmaticallyspeaking.tinyws.Server.WebSocketHandler;
import org.testng.annotations.Test;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.URISyntaxException;

import static org.assertj.core.api.Assertions.assertThat;

// Run with "-Djavax.net.debug=ssl,handshake" to debug SSL.
public class SSLWebSocketTest extends ClientTestBase {

    private URI createURI() throws URISyntaxException {
        return new URI("wss://" + host + ":" + port);
    }

    @Override
    protected Server.Options configureAdditionalOptions(Server.Options options) throws Exception {
        SSLContext sslContext = SSLTesting.createSSLContextForTests(true);
        return super.configureAdditionalOptions(options).andSSL(sslContext);
    }

    @Override
    protected WebSocketHandler createHandler() {
        return new EchoHandler();
    }

    private SimpleClient sendTextUsingSSL(String text) throws Exception {
        return SimpleClient.sendSSLText(createURI(), text);
    }

    @Test
    public void SSL_should_work() throws Exception {
        SimpleClient cl = sendTextUsingSSL("hello world");
        assertThat(cl.messages).containsExactly("hello world");
    }
}
