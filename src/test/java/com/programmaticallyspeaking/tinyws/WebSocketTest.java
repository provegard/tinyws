package com.programmaticallyspeaking.tinyws;

import com.programmaticallyspeaking.tinyws.Server.WebSocketHandler;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.URISyntaxException;

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
        SimpleClient.sendIncorrectFrame(createURI());
    }

    private SimpleClient sendText(String text) throws Exception {
        return SimpleClient.sendText(createURI(), text);
    }

    private void sendClose() throws Exception {
        SimpleClient.sendClose(createURI());
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
}
