package com.programmaticallyspeaking.tinyws;

import com.programmaticallyspeaking.tinyws.Server.WebSocketHandler;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.BindException;
import java.security.GeneralSecurityException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.mockito.Mockito.mock;

public abstract class ClientTestBase {

    private Server server;
    protected Queue<WebSocketHandler> createdHandlers = new ConcurrentLinkedQueue<>();
    protected String host = "localhost";
    protected int port;

    protected WebSocketHandler createHandler() {
        return mock(WebSocketHandler.class);
    }

    protected Server.Options configureAdditionalOptions(Server.Options options) throws Exception {
        return options;
    }

    private Server startServer(int port) throws Exception {
        Executor executor = Executors.newCachedThreadPool();
        Server.Logger logger = new Server.Logger() {
            public void log(Server.LogLevel level, String message, Throwable error) {
//                PrintStream os = level == Server.LogLevel.ERROR ? System.err : System.out;
//                os.println("TINYWS - " + level + ": " + message);
//                if (error != null) error.printStackTrace(os);
            }

            public boolean isEnabledAt(Server.LogLevel level) {
//                return level.level >= Server.LogLevel.WARN.level;
                return false;
            }
        };
        Server ws = new Server(executor, configureAdditionalOptions(Server.Options.withPort(port).andLogger(logger)));
        ws.addHandlerFactory("/", () -> {
            WebSocketHandler h = createHandler();
            createdHandlers.add(h);
            return h;
        });
        ws.start();
        return ws;
    }

    private void attemptToStartServer() throws Exception {
        int attempts = 20;
        while (attempts-- > 0) {
            int port = (int) (50000 + 2000 * Math.random());
            try {
                server = startServer(port);
                this.port = port;
                return; // done
            } catch (BindException e) {
                // retry
            }
        }
        throw new RuntimeException("Failed to find an available port for the server.");
    }

    @BeforeClass
    public void startServer() throws Exception {
        attemptToStartServer();
    }

    @AfterClass
    public void stopServer() {
        if (server != null) server.stop();
    }

    @BeforeMethod
    public void reset() {
        createdHandlers.clear();
    }

}
