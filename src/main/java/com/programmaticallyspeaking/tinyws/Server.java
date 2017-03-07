/*
 * Copyright (c) 2017, Per Rovegård <per@rovegard.se>
 *
 * Distributed under the MIT License (license terms are at http://per.mit-license.org, or in the LICENSE file at
 * https://github.com/provegard/tinyws/blob/master/LICENSE).
 */

package com.programmaticallyspeaking.tinyws;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A WebSocket server. Usage:
 *
 * 1. Create an instance of this class.
 * 2. Add one or more handler factories using the {@see Server#addHandlerFactory(Supplier)} method.
 * 3. Start the server using {@see Server#start()}.
 * 4. Connect clients...
 * 5. Stop using {@see Server#stop()}.
 *
 * The server implementation passes all tests of <a href="https://github.com/crossbario/autobahn-testsuite">
 * Autobahn|Testsuite</a> (version 0.10.9) except 12.* and 13.* (compression using the permessage-deflate extension).
 */
public class Server {
    public static final String ServerName = "TinyWS Server";
    public static final String ServerVersion = "@VERSION@";

    private static final String HANDSHAKE_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int SupportedVersion = 13;

    private final Executor mainExecutor;
    private final Executor handlerExecutor;
    private final Options options;
    private final Logger logger;

    private ServerSocket serverSocket;
    private Map<String, Supplier<WebSocketHandler>> handlerFactories = new HashMap<>();

    /**
     * Constructs a new server instance but doesn't start listening for client connections.
     *
     * @param mainExecutor the {@see Executor} instance that will be used to create the main listener task as well as
     *                     tasks for handling connected clients. Please note that each task will use excessive blocking
     *                     I/O, so use an appropriate executor.
     * @param handlerExecutor the {@code Executor} instance that will be used to invoke handlers.
     * @param options server options
     */
    public Server(Executor mainExecutor, Executor handlerExecutor, Options options) {
        this.mainExecutor = mainExecutor;
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

    private void lazyLog(LogLevel level, Supplier<String> msgFun) {
        if (logger.isEnabledAt(level)) logger.log(level, msgFun.get(), null);
    }

    /**
     * Adds a factory for creating handlerFactories for a specific endpoint. Handler factories must be added before the server
     * is started. The endpoint must match a requested resource exactly to be used. The root handler factory must thus
     * be registered for the "/" endpoint.
     *
     * @param endpoint non-{@code null}, non-empty endpoint
     * @param handlerFactory a handler factory
     * @exception IllegalStateException if the server has been started
     */
    public void addHandlerFactory(String endpoint, Supplier<WebSocketHandler> handlerFactory) {
        if (endpoint == null || "".equals(endpoint)) throw new IllegalArgumentException("Endpoint must be non-empty.");
        if (serverSocket != null) throw new IllegalStateException("Please add handler factories before starting the server.");
        handlerFactories.put(endpoint, handlerFactory);
    }

    /**
     * Starts listening for client connections, using the port specified in the options passed to the constructor. If
     * a backlog was not specified in the options, the Java-default backlog (50 for Java 8) is used.
     *
     * The server socket is created on the current thread, in the interest of fail-fast. The main executor is then
     * used to start the listening task.
     *
     * @exception IOException if creating the server socket fails
     */
    public void start() throws IOException {
        // Using backlog 0 will force ServerSocket to use the default (50).
        int backlog = options.backlog != null ? options.backlog : 0;
        serverSocket = new ServerSocket(options.port, backlog, options.address);
        mainExecutor.execute(this::acceptInLoop);
    }

    /**
     * Stops listening for client connection. Does nothing if the server has already been stopped (or hasn't been started
     * to begin with). Any exception from closing the server socket is suppressed but will be logged at WARN level to
     * any supplied logger.
     */
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
            lazyLog(LogLevel.INFO, () -> "Receiving WebSocket clients at " + serverSocket.getLocalSocketAddress());

            while (true) {
                Socket clientSocket = serverSocket.accept();
                mainExecutor.execute(new ClientHandler(clientSocket));
            }
        } catch (SocketException e) {
            logger.log(LogLevel.DEBUG, "Server socket was closed, probably because the server was stopped.", e);
        } catch (Exception ex) {
            logger.log(LogLevel.ERROR, "Error accepting a client socket.", ex);
        }
    }

    private class ClientHandler implements Runnable {

        private final Socket clientSocket;
        private final OutputStream out;
        private final InputStream in;
        private final PayloadCoder payloadCoder;
        private final FrameWriter frameWriter;
        private WebSocketHandler handler;
        private volatile boolean isClosed; // potentially set from handler thread

        ClientHandler(Socket clientSocket) throws IOException {
            this.clientSocket = clientSocket;
            out = clientSocket.getOutputStream();
            in = clientSocket.getInputStream();

            payloadCoder = new PayloadCoder();
            frameWriter = new FrameWriter(out, payloadCoder, options.maxFrameSize);
        }

        private void invokeHandler(Consumer<WebSocketHandler> fun) {
            if (handler != null) handlerExecutor.execute(() -> fun.accept(handler));
        }

        @Override
        public void run() {
            try {
                communicate();
            } catch (WebSocketClosure ex) {
                lazyLog(LogLevel.DEBUG, () -> String.format("Closing with code %d (%s)%s", ex.code, ex.reason,
                        ex.debugDetails != null ? (" because: " + ex.debugDetails) : ""));
                doIgnoringExceptions(() -> frameWriter.writeClose(ex.code, ex.reason));
                // If the connection was closed by the client, we expect onClosedByClient to have been invoked and
                // we must *not* invoke onClosedByServer since that would be a lie...
                if (!ex.closedByClient)
                    invokeHandler(h -> h.onClosedByServer(ex.code, ex.reason));
            } catch (MethodNotAllowedException ex) {
                lazyLog(LogLevel.WARN, () -> String.format("WebSocket client from %s used a non-allowed method: %s",
                            clientSocket.getRemoteSocketAddress(), ex.method));
                sendMethodNotAllowedResponse();
            } catch (IllegalArgumentException ex) {
                lazyLog(LogLevel.WARN, () -> String.format("WebSocket client from %s sent a malformed request.",
                        clientSocket.getRemoteSocketAddress()));
                sendBadRequestResponse();
            } catch (FileNotFoundException ex) {
                lazyLog(LogLevel.WARN, () -> String.format("WebSocket client from %s requested an unknown endpoint.",
                        clientSocket.getRemoteSocketAddress()));
                sendNotFoundResponse();
            } catch (SocketException ex) {
                if (!isClosed) {
                    logger.log(LogLevel.ERROR, "Client socket error.", ex);
                    invokeHandler(h -> h.onFailure(ex));
                }
            } catch (Exception ex) {
                logger.log(LogLevel.ERROR, "Client communication error.", ex);
                invokeHandler(h -> h.onFailure(ex));
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
            if (headers.version() != SupportedVersion) throw new IllegalArgumentException("Bad version, must be: " + SupportedVersion);
            String endpoint = headers.endpoint;

            Supplier<WebSocketHandler> handlerFactory = handlerFactories.get(endpoint);
            if (handlerFactory == null || (handler = handlerFactory.get()) == null)
                throw new FileNotFoundException("Unknown endpoint: " + endpoint);

            lazyLog(LogLevel.INFO, () -> String.format("New WebSocket client from %s at endpoint '%s'.",
                        clientSocket.getRemoteSocketAddress(), endpoint));

            invokeHandler(h -> h.onOpened(new WebSocketClientImpl(frameWriter, this::abort, headers)));

            String key = headers.key();
            if (key == null) throw new IllegalArgumentException("Missing Sec-WebSocket-Key in handshake.");

            String responseKey = createResponseKey(key);

            lazyLog(LogLevel.TRACE, () -> String.format("Opening handshake key is '%s', sending response key '%s'.", key, responseKey));

            sendHandshakeResponse(responseKey);

            List<Frame> frameBatch = new ArrayList<>();
            while (true) {
                frameBatch.add(Frame.read(in));
                handleBatch(frameBatch);
            }
        }

        private void handleBatch(List<Frame> frameBatch) throws IOException {
            Frame firstFrame = frameBatch.get(0);

            if (firstFrame.opCode == 0) throw WebSocketClosure.protocolError("Continuation frame with nothing to continue.");

            Frame lastOne = frameBatch.get(frameBatch.size() - 1);
            lazyLog(LogLevel.TRACE, lastOne::toString);
            if (!lastOne.isFin) return;
            if (firstFrame != lastOne) {
                if (lastOne.isControl()) {
                    // Interleaved control frame
                    frameBatch.remove(frameBatch.size() - 1);
                    handleResultFrame(lastOne);
                    return;
                } else if (lastOne.opCode > 0) {
                    throw WebSocketClosure.protocolError("Continuation frame must have opcode 0.");
                }
            }

            Frame result = frameBatch.size() > 1 ? Frame.merge(frameBatch) : lastOne;

            frameBatch.clear();

            handleResultFrame(result);
        }

        private void handleResultFrame(Frame result) throws IOException {
            switch (result.opCode) {
                case 1:
                    String data = payloadCoder.decode(result.payloadData);
                    invokeHandler(h -> h.onTextMessage(data));
                    break;
                case 2:
                    invokeHandler(h -> h.onBinaryData(result.payloadData));
                    break;
                case 8:
                    CloseData cd = result.toCloseData(payloadCoder);

                    if (cd.hasInvalidCode()) throw WebSocketClosure.protocolError("Invalid close frame code: " + cd.code);

                    // 1000 is normal close
                    int i = cd.code != null ? cd.code : 1000;

                    invokeHandler(h -> h.onClosedByClient(i, cd.reason));

                    throw WebSocketClosure.fromClient(i);
                case 9:
                    // Ping, send pong!
                    logger.log(LogLevel.TRACE, "Got ping frame, sending pong.", null);
                    frameWriter.writePong(result.payloadData);
                    break;
                case 10:
                    // Pong is ignored
                    logger.log(LogLevel.TRACE, "Ignoring unsolicited pong frame.", null);
                    break;
                default:
                    throw WebSocketClosure.protocolError("Invalid opcode: " + result.opCode);
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
            // Advertise supported version regardless of what was bad. A bit lazy, but simple.
            Map<String, String> headers = new HashMap<String, String>() {{
                put("Sec-WebSocket-Version", Integer.toString(SupportedVersion));
            }};
            sendResponse(400, "Bad Request", headers);
        }
        private void sendMethodNotAllowedResponse() {
            Map<String, String> headers = new HashMap<String, String>() {{
                put("Allow", "GET");
            }};
            sendResponse(405, "Method Not Allowed", headers);
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

            // https://tools.ietf.org/html/rfc7231#section-7.4.2
            outputLine(writer, String.format("Server: %s %s", ServerName, ServerVersion));

            // Headers added when we don't do a connection upgrade to WebSocket!
            if (statusCode >= 200) {
                // https://tools.ietf.org/html/rfc7230#section-6.1
                outputLine(writer, "Connection: close");

                // https://tools.ietf.org/html/rfc7230#section-3.3.2
                outputLine(writer, "Content-Length: 0");
            }

            outputLine(writer, "");
            writer.flush();
            // Note: Do NOT close the writer, as the stream must remain open
        }
    }

    static class Frame {

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
            if (!hasZeroReserved) throw WebSocketClosure.protocolError("Non-zero reserved bits in 1st byte: " + (firstByte & 112));
            int opCode = (firstByte & 15);
            boolean isControlFrame = (opCode & 8) == 8;
            int secondByte = readUnsignedByte(in);
            boolean isMasked = (secondByte & 128) == 128;
            int len = (secondByte & 127);
            if (isControlFrame) {
                if (len > 125) throw WebSocketClosure.protocolError("Control frame length exceeding 125 bytes.");
                if (!isFin) throw WebSocketClosure.protocolError("Fragmented control frame.");
            }
            if (len == 126) {
                // 2 bytes of extended len
                long tmp = toLong(readBytes(in, 2));
                len = (int) tmp;
            } else if (len == 127) {
                // 8 bytes of extended len
                long tmp = toLong(readBytes(in, 8));
                if (tmp > Integer.MAX_VALUE) throw WebSocketClosure.protocolError("Frame length greater than 0x7fffffff not supported.");
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

        CloseData toCloseData(PayloadCoder payloadCoder) throws WebSocketClosure {
            if (opCode != 8) throw new IllegalStateException("Not a close frame: " + opCode);
            if (payloadData.length == 0) return new CloseData(null, null);
            if (payloadData.length == 1) throw WebSocketClosure.protocolError("Invalid close frame payload length (1).");
            int code = (int) toLong(payloadData, 0, 2);
            String reason = payloadData.length > 2 ? payloadCoder.decode(payloadData, 2, payloadData.length - 2) : null;
            return new CloseData(code, reason);
        }

        static Frame merge(List<Frame> frameBatch) {                // Combine payloads!
            int totalLength = frameBatch.stream().mapToInt(f -> f.payloadData.length).sum();
            byte[] allTheData = new byte[totalLength];
            int offs = 0;
            for (Frame frame : frameBatch) {
                System.arraycopy(frame.payloadData, 0, allTheData, offs, frame.payloadData.length);
                offs += frame.payloadData.length;
            }
            return new Frame(frameBatch.get(0).opCode, allTheData, true);
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

    static class Headers {
        private final Map<String, String> headers;
        final String endpoint;
        final String query;
        final String fragment;

        private Headers(Map<String, String> headers, URI uri) {
            this.headers = headers;
            this.endpoint = uri.getPath();
            this.query = uri.getQuery();
            this.fragment = uri.getFragment();
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

        String key() { return headers.get("Sec-WebSocket-Key"); }
        String userAgent() { return headers.get("User-Agent"); }
        String host() { return headers.get("Host"); }

        static Headers read(InputStream in) throws IOException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String inputLine;
            URI endpoint = null;
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            boolean isFirstLine = true;
            while (!"".equals((inputLine = reader.readLine()))) {
                if (isFirstLine) {
                    String[] parts = inputLine.split(" ", 3);
                    if (parts.length != 3) throw new IllegalArgumentException("Malformed 1st header line: " + inputLine);
                    if (!"GET".equals(parts[0])) throw new MethodNotAllowedException(parts[0]);
                    try {
                        endpoint = new URI("http://server" + parts[1]);
                    } catch (URISyntaxException e) {
                        throw new IllegalArgumentException("Invalid endpoint: " + parts[1]);
                    }
                    isFirstLine = false;
                }

                String[] keyValue = inputLine.split(":", 2);
                if (keyValue.length != 2) continue;

                headers.put(keyValue[0], keyValue[1].trim());
            }
            // Note: Do NOT close the reader, because the stream must remain open!
            return new Headers(headers, endpoint);
        }
    }

    static class PayloadCoder {
        private final Charset charset = StandardCharsets.UTF_8;
        private final CharsetDecoder decoder = charset.newDecoder();

        String decode(byte[] bytes) throws WebSocketClosure {
            return decode(bytes, 0, bytes.length);
        }

        /**
         * Decodes the given byte data as UTF-8 and returns the result as a string.
         *
         * @param bytes the byte array
         * @param offset offset into the array where to start decoding
         * @param len length of data to decode
         * @return the decoded string
         * @throws WebSocketClosure (1007) thrown if the data are not valid UTF-8
         */
        synchronized String decode(byte[] bytes, int offset, int len) throws WebSocketClosure {
            decoder.reset();
            try {
                CharBuffer buf = decoder.decode(ByteBuffer.wrap(bytes, offset, len));
                return buf.toString();
            } catch (Exception ex) {
                throw WebSocketClosure.invalidFramePayloadData();
            }
        }

        byte[] encode(String s) {
            return s.getBytes(charset);
        }
    }

    static class WebSocketClosure extends IOException {
        final int code;
        final String reason;
        final String debugDetails;
        final boolean closedByClient;

        private WebSocketClosure(int code, String reason, String debugDetails, boolean closedByClient) {
            this.code = code;
            this.reason = reason;
            this.debugDetails = debugDetails;
            this.closedByClient = closedByClient;
        }

        static WebSocketClosure fromClient(int code) {
            return new WebSocketClosure(code, "", "Closed by client", true);
        }
        static WebSocketClosure protocolError(String debugDetails) {
            return new WebSocketClosure(1002, "Protocol error", debugDetails, false);
        }
        static WebSocketClosure invalidFramePayloadData() {
            return new WebSocketClosure(1007, "Invalid frame payload data", null, false);
        }
    }

    static class FrameWriter {
        private final OutputStream out;
        private final PayloadCoder payloadCoder;
        private final int maxFrameSize;

        FrameWriter(OutputStream out, PayloadCoder payloadCoder, int maxFrameSize) {
            this.out = out;
            this.payloadCoder = payloadCoder;
            this.maxFrameSize = maxFrameSize;
        }

        void writeClose(int code, String reason) throws IOException {
            byte[] s = payloadCoder.encode(reason);
            byte[] numBytes = numberToBytes(code, 2);
            byte[] combined = new byte[numBytes.length + s.length];
            System.arraycopy(numBytes, 0, combined, 0, 2);
            System.arraycopy(s, 0, combined, 2, s.length);
            writeFrame(8, combined);
        }

        void writeText(String text) throws IOException {
            byte[] s = payloadCoder.encode(text);
            writePossiblyFragmentedFrames(1, s);
        }

        void writeBinary(byte[] data) throws IOException {
            writePossiblyFragmentedFrames(2, data);
        }

        void writePing(byte[] data) throws IOException {
            writeFrame(9, data);
        }

        void writePong(byte[] data) throws IOException {
            writeFrame(10, data);
        }

        private void writePossiblyFragmentedFrames(int opCode, byte[] data) throws IOException {
            // https://tools.ietf.org/html/rfc6455#section-5.6 implies that a single frame may contain an UTF-8
            // sequence that by itself is invalid, as long as the entire message text is valid UTF-8.
            if (maxFrameSize == 0 || data == null || data.length <= maxFrameSize) {
                writeFrame(opCode, data);
            } else {
                int offset = 0;
                while (offset < data.length) {
                    int len = Math.min(data.length - offset, maxFrameSize);
                    writeFrame(opCode, data, offset, len);
                    offset += len;
                }
            }
        }

        private void writeFrame(int opCode, byte[] data) throws IOException {
            writeFrame(opCode, data, 0, data != null ? data.length : 0);
        }

        /**
         * Writes a frame to the output stream. Since FrameWriter is handed out to potentially different threads,
         * this method is synchronized.
         *
         * @param opCode the opcode of the frame
         * @param data frame data
         * @throws IOException thrown if writing to the socket fails
         */
        synchronized private void writeFrame(int opCode, byte[] data, int offset, int len) throws IOException {
            int totalLen = data != null ? data.length : 0;
            boolean isFirstFrame = offset == 0;
            boolean isFinalFrame = offset + len == totalLen;

            int firstByte = isFirstFrame ? opCode : 0;
            if (isFinalFrame) firstByte |= 128; // FIN
            int secondByte;
            int extraLengthBytes = 0;
            if (len < 126) {
                secondByte = len;
            } else if (len < 65536) {
                secondByte = 126;
                extraLengthBytes = 2;
            } else {
                secondByte = 127;
                extraLengthBytes = 8;
            }
            out.write(firstByte);
            out.write(secondByte);
            if (extraLengthBytes > 0) {
                out.write(numberToBytes(len, extraLengthBytes));
            }
            if (data != null) out.write(data, offset, len);
            out.flush();
        }
    }

    private static class WebSocketClientImpl implements WebSocketClient {

        private final FrameWriter writer;
        private final Runnable closeCallback;
        private final Headers headers;

        WebSocketClientImpl(FrameWriter writer, Runnable closeCallback, Headers headers) {
            this.writer = writer;
            this.closeCallback = closeCallback;
            this.headers = headers;
        }

        public void ping() throws IOException {
            writer.writePing(null);
        }

        public void close() {
            doIgnoringExceptions(() -> {
                writer.writeClose(1001, "Going Away");
                closeCallback.run();
            });
        }

        public void sendTextMessage(String text) throws IOException {
            writer.writeText(text);
        }

        public void sendBinaryData(byte[] data) throws IOException {
            writer.writeBinary(data);
        }

        public String userAgent() { return headers.userAgent(); }
        public String host() { return headers.host(); }
        public String query() { return headers.query; }
        public String fragment() { return headers.fragment; }
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

    static String createResponseKey(String key) throws NoSuchAlgorithmException {
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

    static class MethodNotAllowedException extends IllegalArgumentException {
        final String method;
        public MethodNotAllowedException(String method) {
            this.method = method;
        }
    }

    /**
     * Server options, configured using a fluent interface. Start with {@code Options.withPort(int)} since port is
     * required.
     */
    public static class Options {
        Integer backlog;
        int port;
        Logger logger;
        InetAddress address;
        int maxFrameSize;

        private Options(int port) {
            this.port = port;
        }

        /**
         * Creates new options with the given port.
         *
         * @param port the port to use when listening for WebSocket clients
         * @return this options instance
         */
        public static Options withPort(int port) {
            return new Options(port);
        }

        /**
         * Specifies the backlog size, i.e. the size of the client connection queue. If the queue is full, a client
         * connection is rejected.
         *
         * @param backlog the backlog size, which must be greater than 0
         * @return this options instance
         */
        public Options andBacklog(int backlog) {
            if (backlog <= 0) throw new IllegalArgumentException("Backlog must be > 0");
            this.backlog = backlog;
            return this;
        }

        /**
         * Specifies a logger.
         *
         * @param logger the logger instance
         * @return this options instance
         */
        public Options andLogger(Logger logger) {
            this.logger = logger;
            return this;
        }

        /**
         * Specifies the address to use when creating the server socket.
         *
         * @param address the address to bind to
         * @return this options instance
         */
        public Options andAddress(InetAddress address) {
            this.address = address;
            return this;
        }

        /**
         * Specifies the maximum frame size. The maximum frame size must be at least 126, as it doesn't make much
         * sense to create frame fragments smaller than that.
         *
         * @param size maximum frame size
         * @return this options instance
         */
        public Options andMaxFrameSize(int size) {
            if (size <= 125) throw new IllegalArgumentException("Max frame size must be at least 126.");
            this.maxFrameSize = size;
            return this;
        }
    }

    /**
     * Log level for logging.
     */
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

    /**
     * A simple interface for logging.
     */
    public interface Logger {
        /**
         * Logs a message at a certain level. Note that this method is not called if {@see isEnabledAt(LogLevel)}
         * returns {@code false} for the given level.
         *
         * @param level the log level
         * @param message the message to log
         * @param error an optional error
         */
        void log(LogLevel level, String message, Throwable error);

        /**
         * Determines if logging is enabled for the given level.
         *
         * @param level a log level
         * @return {@code true} if logging is enabled at the level, {@code false} otherwise
         */
        boolean isEnabledAt(LogLevel level);
    }

    /**
     * Represents a WebSocket client and exposes methods that makes it possible to interact with the client, as well
     * as methods for getting information about the client and how it requested the handled resource.
     *
     * Methods on this interface can be invoked from any thread.
     */
    public interface WebSocketClient {
        /**
         * Sends a ping to the client. This can be used to send keep-alive messages to the client.
         *
         * @throws IOException on I/O failure while sending the ping
         */
        void ping() throws IOException;

        /**
         * Performs a clean close of the connection to the client.
         */
        void close();

        /**
         * Sends a text message to the client.
         *
         * @param text the text to send
         * @throws IOException on I/O failure while sending
         */
        void sendTextMessage(String text) throws IOException;

        /**
         * Sends binary data to the client.
         *
         * @param data the data to send
         * @throws IOException on I/O failure while sending
         */
        void sendBinaryData(byte[] data) throws IOException;

        /**
         * Returns the value of the User-Agent header passed by the client when requesting a Websocket connection. If no
         * User-Agent header was present, returns {@code null}.
         *
         * @return the Host header value, or {@code null}
         */
        String userAgent();

        /**
         * Returns the value of the Host header passed by the client when requesting a Websocket connection. If no
         * Host header was present, returns {@code null}.
         *
         * @return the Host header value, or {@code null}
         */
        String host();

        /**
         * Returns the query (part after '?', not including the fragment) used by the client when requesting a WebSocket
         * connection. If no query was specified, returns {@code null}. If an empty query was specified, returns the
         * empty string. The '?' character is never included.
         *
         * @return a query string, or {@code null}
         * @see <a href="https://tools.ietf.org/html/rfc3986#section-3">Uniform Resource Identifier (URI): Generic Syntax</a>
         */
        String query();

        /**
         * Returns the fragment (part after '#') used by the client when requesting a WebSocket connection. If no
         * fragment was specified, returns {@code null}. If an empty fragment was specified, returns the empty string.
         * The '#' character is never included.
         *
         * @return a fragment string, or {@code null}
         * @see <a href="https://tools.ietf.org/html/rfc3986#section-3">Uniform Resource Identifier (URI): Generic Syntax</a>
         */
        String fragment();
    }

    /**
     * A handler for a WebSocket client connection. A new handler instance will be created for each connected client.
     * Handlers are invoked on the handler executor passed to the {@code Server} constructor.
     */
    public interface WebSocketHandler {
        /**
         * Invoked right after construction.
         *
         * @param client instance for interacting with the client
         */
        void onOpened(WebSocketClient client);

        /**
         * Invoked when the client closes the connection in an orderly fashion.
         *
         * @param code the close code the client used, if any, otherwise 1000 (Normal Closure).
         * @param reason the close reason the client used, if any. May be {@code null}.
         */
        void onClosedByClient(int code, String reason);

        /**
         * Invoked when the server closes the connection in an orderly fashion, but must likely because of an error.
         *
         * @param code the close code sent to the client
         * @param reason the close reason sent to the client
         */
        void onClosedByServer(int code, String reason);

        /**
         * Invoked when the connection is closed abruptly because of an error.
         *
         * @param t the error that occurred
         */
        void onFailure(Throwable t);

        /**
         * Invoked when the client sends a text message.
         *
         * @param text the message
         */
        void onTextMessage(String text);

        /**
         * Invoked when the client sends binary data.
         *
         * @param data the data
         */
        void onBinaryData(byte[] data);
    }
}
