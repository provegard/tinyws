package com.programmaticallyspeaking.tinyws;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public class TinyWS {
    private static final String HANDSHAKE_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final ThreadFactory threadFactory;
    private final Executor handlerExecutor;
    private final Options options;
    private final Logger logger;

    private ServerSocket serverSocket;
    private Map<String, WebSocketHandler> handlers = new HashMap<>();

    public TinyWS(ThreadFactory threadFactory, Executor handlerExecutor, Options options) {
        this.threadFactory = threadFactory;
        this.handlerExecutor = handlerExecutor;
        this.options = options;
        this.logger = new Logger() {
            public void log(LogLevel level, String message, Throwable error) {
                if (isEnabledAt(level)) {
                    try {
                        options.logger.log(level, message, error);
                    } catch (Exception ignore) {
                        // ignore logging errors
                    }
                }
            }

            @Override
            public boolean isEnabledAt(LogLevel level) {
                return options.logger != null && options.logger.isEnabledAt(level);
            }
        };
    }

    public void addHandler(String endpoint, WebSocketHandler handler) {
        if (serverSocket != null) throw new IllegalStateException("Please add handlers before starting the server.");
        handlers.put(endpoint, handler);
    }

    public void start() throws IOException {
        Integer backlog = options.backlog;
        serverSocket = backlog == null
            ? new ServerSocket(options.port)
            : new ServerSocket(options.port, backlog);
        Thread acceptThread = threadFactory.newThread(this::acceptInLoop);
        acceptThread.start();
    }

    public void stop() {
        if (serverSocket == null) return;
        try {
            serverSocket.close();
        } catch (IOException e) {
            logger.log(LogLevel.WARN, "Failed to close server socket.", e);
        }
        serverSocket = null;
    }

    private void acceptInLoop() {
        try {
            if (logger.isEnabledAt(LogLevel.INFO))
                logger.log(LogLevel.INFO, "Receiving WebSocket clients at " +
                    serverSocket.getLocalSocketAddress(), null);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                Thread t = threadFactory.newThread(new ClientHandler(clientSocket, handlers::get, (handler, fun) -> {
                    if (handler != null) handlerExecutor.execute(() -> fun.accept(handler));
                }, logger));
                t.start();
            }
        } catch (SocketException e) {
            logger.log(LogLevel.DEBUG, "Server socket was closed, probably because the server was stopped.", e);
        } catch (Exception ex) {
            logger.log(LogLevel.ERROR, "Error accepting a client socket.", ex);
        }
    }

    private static class ClientHandler implements Runnable {

        private final Socket clientSocket;
        private final OutputStream out;
        private final InputStream in;
        private final Function<String, WebSocketHandler> handlerLookup;
        private final BiConsumer<WebSocketHandler, Consumer<WebSocketHandler>> handlerExecutor;
        private final Logger logger;
        private final PayloadCoder payloadCoder;
        private final FrameWriter frameWriter;
        private WebSocketHandler handler;
        private volatile boolean isClosed; // potentially set from handler thread

        ClientHandler(Socket clientSocket, Function<String, WebSocketHandler> handlerLookup, BiConsumer<WebSocketHandler, Consumer<WebSocketHandler>> handlerExecutor, Logger logger) throws IOException {
            this.clientSocket = clientSocket;
            out = clientSocket.getOutputStream();
            in = clientSocket.getInputStream();
            this.handlerLookup = handlerLookup;
            this.handlerExecutor = handlerExecutor;
            this.logger = logger;

            payloadCoder = new PayloadCoder();
            frameWriter = new FrameWriter(out, payloadCoder);
        }

        @Override
        public void run() {
            try {
                communicate();
            } catch (WebSocketError ex) {
                if (logger.isEnabledAt(LogLevel.DEBUG)) {
                    String msg = String.format("Closing with code %d (%s)%s", ex.code, ex.reason,
                        ex.debugDetails != null ? (" because: " + ex.debugDetails) : "");
                    logger.log(LogLevel.DEBUG, msg, null);
                }
                doIgnoringExceptions(() -> frameWriter.writeCloseFrame(ex.code, ex.reason));
                handlerExecutor.accept(handler, h -> h.onClosedByClient(ex.code, ex.reason));
            } catch (IllegalArgumentException ex) {
                if (logger.isEnabledAt(LogLevel.WARN))
                    logger.log(LogLevel.WARN, String.format("WebSocket client from %s sent a malformed request.",
                        clientSocket.getRemoteSocketAddress()), null);
                sendBadRequestResponse();
            } catch (FileNotFoundException ex) {
                if (logger.isEnabledAt(LogLevel.WARN))
                    logger.log(LogLevel.WARN, String.format("WebSocket client from %s requested an unknown endpoint.",
                        clientSocket.getRemoteSocketAddress()), null);
                sendNotFoundResponse();
            } catch (SocketException ex) {
                if (!isClosed) {
                    logger.log(LogLevel.ERROR, "Client socket error.", ex);
                    handlerExecutor.accept(handler, h -> h.onFailure(ex));
                }
            } catch (Exception ex) {
                logger.log(LogLevel.ERROR, "Client communication error.", ex);
                handlerExecutor.accept(handler, h -> h.onFailure(ex));
            }
            abort();
        }

        private void abort() {
            if (isClosed) return;
            doIgnoringExceptions(clientSocket::close);
            isClosed = true;
        }

        private void communicate() throws IOException, NoSuchAlgorithmException {
            Headers headers = Headers.read(in);
            if (!headers.isProperUpgrade()) throw new IllegalArgumentException("Handshake has malformed upgrade.");
            if (headers.version() != 13) throw new IllegalArgumentException("Bad version, must be 13.");
            String endpoint = headers.endpoint;
            if (endpoint == null) throw new IllegalArgumentException("Missing endpoint.");

            handler = handlerLookup.apply(endpoint);
            if (handler == null) throw new FileNotFoundException("Unknown endpoint: " + endpoint);

            if (logger.isEnabledAt(LogLevel.INFO))
                logger.log(LogLevel.INFO, String.format("New WebSocket client from %s at endpoint '%s'.",
                        clientSocket.getRemoteSocketAddress(), endpoint), null);

            handlerExecutor.accept(handler, h -> h.onOpened(new WebSocketClientImpl(frameWriter, this::abort)));

            String key = headers.key();
            String responseKey = createResponseKey(key);

            if (logger.isEnabledAt(LogLevel.TRACE))
                logger.log(LogLevel.TRACE, String.format("Opening handshake key is '%s', sending response key '%s'.", key, responseKey), null);

            sendHandshakeResponse(responseKey);

            List<Frame> frameBatch = new ArrayList<>();
            while (true) {
                frameBatch.add(Frame.read(in));
                handleBatch(frameBatch);
            }
        }

        private void handleBatch(List<Frame> frameBatch) throws IOException {
            Frame firstFrame = frameBatch.get(0);

            if (firstFrame.opCode == 0) throw WebSocketError.protocolError("Continuation frame with nothing to continue.");

            Frame lastOne = frameBatch.get(frameBatch.size() - 1);
            if (logger.isEnabledAt(LogLevel.TRACE)) logger.log(LogLevel.TRACE, lastOne.toString(), null);
            if (!lastOne.isFin) return;
            if (firstFrame != lastOne) {
                if (lastOne.isControl()) {
                    // Interleaved control frame
                    frameBatch.remove(frameBatch.size() - 1);
                    handleResultFrame(lastOne);
                    return;
                } else if (lastOne.opCode > 0) {
                    throw WebSocketError.protocolError("Continuation frame must have opcode 0.");
                }
            }

            Frame result;

            if (frameBatch.size() > 1) {
                // Combine payloads!
                int totalLength = frameBatch.stream().mapToInt(f -> f.payloadData.length).sum();
                byte[] allTheData = new byte[totalLength];
                int offs = 0;
                for (Frame frame : frameBatch) {
                    System.arraycopy(frame.payloadData, 0, allTheData, offs, frame.payloadData.length);
                    offs += frame.payloadData.length;
                }
                result = new Frame(firstFrame.opCode, allTheData, true);
            } else result = lastOne;

            frameBatch.clear();

            handleResultFrame(result);
        }

        private void handleResultFrame(Frame result) throws IOException {
            switch (result.opCode) {
                case 1:
                    String data = payloadCoder.decode(result.payloadData);
                    handlerExecutor.accept(handler, h -> h.onTextMessage(data));
                    break;
                case 2:
                    handlerExecutor.accept(handler, h -> h.onBinaryData(result.payloadData));
                    break;
                case 8:
                    CloseData cd = result.toCloseData(payloadCoder);

                    if (cd.hasInvalidCode()) throw WebSocketError.protocolError("Invalid close frame code: " + cd.code);

                    // 1000 is normal close
                    int i = cd.code != null ? cd.code : 1000;
                    throw new WebSocketError(i, "", null);
                case 9:
                    // Ping, send pong!
                    logger.log(LogLevel.TRACE, "Got ping frame, sending pong.", null);
                    frameWriter.writePongFrame(result.payloadData);
                    break;
                case 10:
                    // Pong is ignored
                    logger.log(LogLevel.TRACE, "Ignoring unsolicited pong frame.", null);
                    break;
                default:
                    throw WebSocketError.protocolError("Invalid opcode: " + result.opCode);
            }
        }

        private void outputLine(PrintWriter writer, String data) {
            writer.print(data);
            writer.print("\r\n");
        }

        private void sendHandshakeResponse(String responseKey) {
            Map<String, String> headers = new HashMap<String, String>() {{
                put("Upgrade", "websocket");
                put("Connection", "upgrade");
                put("Sec-WebSocket-Accept", responseKey);
            }};
            sendResponse(101, "Switching Protocols", headers);
        }

        private void sendBadRequestResponse() {
            sendResponse(400, "Bad Request", Collections.emptyMap());
        }
        private void sendNotFoundResponse() {
            sendResponse(404, "Not Found", Collections.emptyMap());
        }

        private void sendResponse(int statusCode, String reason, Map<String, String> headers) {
            PrintWriter writer = new PrintWriter(out, false);
            outputLine(writer, "HTTP/1.1 " + statusCode + " " + reason);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                outputLine(writer, entry.getKey() + ": " + entry.getValue());
            }
            outputLine(writer, "");
            writer.flush();
            // Note: Do NOT close the writer, as the stream must remain open
        }
    }

    private static class Frame {

        final int opCode;
        final byte[] payloadData;
        final boolean isFin;

        public String toString() {
            return String.format("Frame[opcode=%d, control=%b, payload length=%d, fragmented=%b]",
                    opCode, isControl(), payloadData.length, !isFin);
        }

        boolean isControl() {
            return (opCode & 8) == 8;
        }

        private Frame(int opCode, byte[] payloadData, boolean isFin) {
            this.opCode = opCode;
            this.payloadData = payloadData;
            this.isFin = isFin;
        }

        private static int readUnsignedByte(InputStream in) throws IOException {
            int b = in.read();
            if (b < 0) throw new IOException("End of stream");
            return b;
        }
        private static int toUnsigned(byte b) {
            int result = b;
            if (result < 0) result += 256;
            return result;
        }

        private static byte[] readBytes(InputStream in, int len) throws IOException {
            byte[] buf = new byte[len];
            int totalRead = 0;
            int offs = 0;
            while (totalRead < len) {
                int readLen = in.read(buf, offs, len - offs);
                if (readLen < 0) break;
                totalRead += readLen;
                offs += readLen;
            }
            if (totalRead != len) throw new IOException("Expected to read " + len + " bytes but read " + totalRead);
            return buf;
        }

        private static long toLong(byte[] data, int offset, int len) {
            long result = 0;
            for (int i = offset, j = offset + len; i < j; i++) {
                result = (result << 8) + toUnsigned(data[i]);
            }
            return result;
        }

        private static long toLong(byte[] data) {
            return toLong(data, 0, data.length);
        }

        static Frame read(InputStream in) throws IOException {
            int firstByte = readUnsignedByte(in);
            boolean isFin = (firstByte & 128) == 128;
            boolean hasZeroReserved = (firstByte & 112) == 0;
            if (!hasZeroReserved) throw WebSocketError.protocolError("Non-zero reserved bits in 1st byte: " + (firstByte & 112));
            int opCode = (firstByte & 15);
            boolean isControlFrame = (opCode & 8) == 8;
            int secondByte = readUnsignedByte(in);
            boolean isMasked = (secondByte & 128) == 128;
            int len = (secondByte & 127);
            if (isControlFrame) {
                if (len > 125) throw WebSocketError.protocolError("Control frame length exceeding 125 bytes.");
                if (!isFin) throw WebSocketError.protocolError("Fragmented control frame.");
            }
            if (len == 126) {
                // 2 bytes of extended len
                long tmp = toLong(readBytes(in, 2));
                len = (int) tmp;
            } else if (len == 127) {
                // 8 bytes of extended len
                long tmp = toLong(readBytes(in, 8));
                if (tmp > Integer.MAX_VALUE) throw WebSocketError.protocolError("Frame length greater than 0x7fffffff not supported.");
                len = (int) tmp;
            }
            byte[] maskingKey = isMasked ? readBytes(in, 4) : null;
            byte[] payloadData = unmaskIfNeededInPlace(readBytes(in, len), maskingKey);
            return new Frame(opCode, payloadData, isFin);
        }

        private static byte[] unmaskIfNeededInPlace(byte[] bytes, byte[] maskingKey) {
            if (maskingKey != null) {
                for (int i = 0; i < bytes.length; i++) {
                    int j = i % 4;
                    bytes[i] = (byte) (bytes[i] ^ maskingKey[j]);
                }
            }
            return bytes;
        }

        CloseData toCloseData(PayloadCoder payloadCoder) throws WebSocketError {
            if (opCode != 8) throw new IllegalStateException("Not a close frame: " + opCode);
            if (payloadData.length == 0) return new CloseData(null, null);
            if (payloadData.length == 1) throw WebSocketError.protocolError("Invalid close frame payload length (1).");
            int code = (int) toLong(payloadData, 0, 2);
            String reason = payloadData.length > 2 ? payloadCoder.decode(payloadData, 2, payloadData.length - 2) : null;
            return new CloseData(code, reason);
        }
    }

    private static class CloseData {
        private final Integer code;
        private final String reason;

        CloseData(Integer code, String reason) {
            this.code = code;
            this.reason = reason;
        }

        boolean hasInvalidCode() {
            if (code == null) return false; // no code isn't invalid
            if (code < 1000 || code >= 5000) return true;
            if (code >= 3000) return false; // 3000-3999 and 4000-4999 are valid
            return code == 1004 || code == 1005 || code == 1006 || code > 1011;
        }
    }

    private static class Headers {
        private final Map<String, String> headers;
        final String endpoint;

        private Headers(Map<String, String> headers, String endpoint) {
            this.headers = headers;
            this.endpoint = endpoint;
        }

        boolean isProperUpgrade() {
            return "websocket".equalsIgnoreCase(headers.get("Upgrade")) && "Upgrade".equalsIgnoreCase(headers.get("Connection"));
        }
        int version() {
            String versionStr = headers.get("Sec-WebSocket-Version");
            try {
                return Integer.parseInt(versionStr);
            } catch (Exception ignore) {
                return 0;
            }
        }

        String key() {
            String key = headers.get("Sec-WebSocket-Key");
            if (key == null) throw new IllegalArgumentException("Missing Sec-WebSocket-Key in handshake.");
            return key;
        }

        static Headers read(InputStream in) throws IOException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String inputLine, endpoint = null;
            Map<String, String> headers = new HashMap<>();
            while (!"".equals((inputLine = reader.readLine()))) {
                if (inputLine.startsWith("GET ")) {
                    String[] parts = inputLine.split(" ", 3);
                    if (parts.length != 3) throw new IOException("Unexpected GET line: " + inputLine);
                    endpoint = parts[1];
                }

                String[] keyValue = inputLine.split(":", 2);
                if (keyValue.length == 2) headers.put(keyValue[0], keyValue[1].trim());
            }
            // Note: Do NOT close the reader, because the stream must remain open!
            return new Headers(headers, endpoint);
        }
    }

    static class PayloadCoder {
        private final Charset charset;
        private final CharsetDecoder decoder;

        PayloadCoder() {
            charset = Charset.forName("UTF-8");
            decoder = charset.newDecoder();
        }

        String decode(byte[] bytes) throws WebSocketError {
            return decode(bytes, 0, bytes.length);
        }

        /**
         * Decodes the given byte data as UTF-8 and returns the result as a string.
         *
         * @param bytes the byte array
         * @param offset offset into the array where to start decoding
         * @param len length of data to decode
         * @return the decoded string
         * @throws WebSocketError (1007) thrown if the data are not valid UTF-8
         */
        synchronized String decode(byte[] bytes, int offset, int len) throws WebSocketError {
            decoder.reset();
            try {
                CharBuffer buf = decoder.decode(ByteBuffer.wrap(bytes, offset, len));
                return buf.toString();
            } catch (Exception ex) {
                throw WebSocketError.invalidFramePayloadData();
            }
        }

        byte[] encode(String s) {
            return s.getBytes(charset);
        }
    }

    static class WebSocketError extends IOException {
        final int code;
        final String reason;
        final String debugDetails;

        WebSocketError(int code, String reason, String debugDetails) {
            this.code = code;
            this.reason = reason;
            this.debugDetails = debugDetails;
        }

        static WebSocketError protocolError(String debugDetails) {
            return new WebSocketError(1002, "Protocol error", debugDetails);
        }
        static WebSocketError invalidFramePayloadData() {
            return new WebSocketError(1007, "Invalid frame payload data", null);
        }
    }

    static class FrameWriter {
        private final OutputStream out;
        private final PayloadCoder payloadCoder;

        FrameWriter(OutputStream out, PayloadCoder payloadCoder) {
            this.out = out;
            this.payloadCoder = payloadCoder;
        }

        void writeCloseFrame(int code, String reason) throws IOException {
            byte[] s = payloadCoder.encode(reason);
            byte[] numBytes = numberToBytes(code, 2);
            byte[] combined = new byte[numBytes.length + s.length];
            System.arraycopy(numBytes, 0, combined, 0, 2);
            System.arraycopy(s, 0, combined, 2, s.length);
            writeFrame(8, combined);
        }

        void writeTextFrame(String text) throws IOException {
            byte[] s = payloadCoder.encode(text);
            writeFrame(1, s);
        }

        void writeBinaryFrame(byte[] data) throws IOException {
            writeFrame(2, data);
        }

        void writePongFrame(byte[] data) throws IOException {
            writeFrame(10, data);
        }

        /**
         * Writes a frame to the output stream. Since FrameWriter is handed out to potentially different threads,
         * this method is synchronized.
         *
         * @param opCode the opcode of the frame
         * @param data frame data
         * @throws IOException thrown if writing to the socket fails
         */
        synchronized void writeFrame(int opCode, byte[] data) throws IOException {
            int firstByte = 128 | opCode; // FIN + opCode
            int dataLen = data.length;
            int secondByte;
            int extraLengthBytes = 0;
            if (dataLen < 126) {
                secondByte = dataLen; // no masking
            } else if (dataLen < 65536) {
                secondByte = 126;
                extraLengthBytes = 2;
            } else {
                secondByte = 127;
                extraLengthBytes = 8;
            }
            out.write(firstByte);
            out.write(secondByte);
            if (extraLengthBytes > 0) {
                out.write(numberToBytes(data.length, extraLengthBytes));
            }
            out.write(data);
            out.flush();
        }
    }

    private static class WebSocketClientImpl implements WebSocketClient {

        private final FrameWriter writer;
        private final Runnable closeCallback;

        WebSocketClientImpl(FrameWriter writer, Runnable closeCallback) {
            this.writer = writer;
            this.closeCallback = closeCallback;
        }

        public void close(int code, String reason) throws IOException {
            writer.writeCloseFrame(code, reason);
            closeCallback.run();
        }

        public void sendTextMessage(String text) throws IOException {
            writer.writeTextFrame(text);
        }

        public void sendBinaryData(byte[] data) throws IOException {
            writer.writeBinaryFrame(data);
        }
    }

    static byte[] numberToBytes(int number, int len) {
        byte[] array = new byte[len];
        // Start from the end (network byte order), assume array is filled with zeros.
        for (int i = len - 1; i >= 0; i--) {
            array[i] = (byte) (number & 0xff);
            number = number >> 8;
        }
        return array;
    }

    private static String createResponseKey(String key) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] rawBytes = (key + HANDSHAKE_GUID).getBytes();
        byte[] result = sha1.digest(rawBytes);
        return Base64.getEncoder().encodeToString(result);
    }

    private static void doIgnoringExceptions(RunnableThatThrows runnable) {
        try {
            runnable.run();
        } catch (Exception ex) {
            // ignore
        }
    }
    private interface RunnableThatThrows {
        void run() throws Exception;
    }

    public static class Options {
        Integer backlog;
        int port;
        Logger logger;

        private Options(int port) {
            this.port = port;
        }
        public static Options withPort(int port) {
            return new Options(port);
        }
        public Options withBacklog(int backlog) {
            if (backlog < 0) throw new IllegalArgumentException("Backlog must be >= 0");
            this.backlog = backlog;
            return this;
        }
        public Options withLogger(Logger logger) {
            this.logger = logger;
            return this;
        }
    }

    public enum LogLevel {
        TRACE(0),
        DEBUG(10),
        INFO(20),
        WARN(50),
        ERROR(100);

        public final int level;

        LogLevel(int level) {
            this.level = level;
        }
    }

    public interface Logger {
        void log(LogLevel level, String message, Throwable error);
        boolean isEnabledAt(LogLevel level);
    }

    public interface WebSocketClient {
        void close(int code, String reason) throws IOException;

        void sendTextMessage(String text) throws IOException;

        void sendBinaryData(byte[] data) throws IOException;

        // TODO: Get user-agent, query string params
    }

    public interface WebSocketHandler {
        void onOpened(WebSocketClient client);

        void onClosedByClient(int code, String reason);

        void onFailure(Throwable t);

        void onTextMessage(String text);

        void onBinaryData(byte[] data);
    }
}
