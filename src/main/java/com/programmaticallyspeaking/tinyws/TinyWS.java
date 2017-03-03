package com.programmaticallyspeaking.tinyws;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class TinyWS {
    private static final String HANDSHAKE_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException, InterruptedException {
        System.out.println("Listening");
        boolean running = true;
        ServerSocket serverSocket = new ServerSocket(9001);
        while (running) {
            Socket clientSocket = serverSocket.accept();
            System.out.println("Accepted");
            Thread t = new Thread(new ClientHandler(clientSocket));
            t.start();
//                t.join();
        }
        System.out.println("Bye");
    }

    private static class ClientHandler implements Runnable {

        private final Socket clientSocket;
        private final OutputStream out;
        private final InputStream in;

        ClientHandler(Socket clientSocket) throws IOException {
            this.clientSocket = clientSocket;
            out = clientSocket.getOutputStream();
            in = clientSocket.getInputStream();
        }

        @Override
        public void run() {
            try {
                communicate();
            } catch (SocketException ex) {
                System.err.println(ex.getMessage());
            } catch (Exception ex) {
                ex.printStackTrace(System.err);
            }
            abort();
        }

        private void abort() {
            try {
                clientSocket.close();
            } catch (IOException ignore) {
                // Ignoring close failure...
            }
        }

        private void communicate() throws IOException, NoSuchAlgorithmException {
            Headers headers = Headers.read(in);
            if (!headers.isProperUpgrade()) throw new IllegalArgumentException("Handshake has malformed upgrade.");

            String key = headers.key();
            String responseKey = createResponseKey(key);
            System.out.println("Client key = " + key);
            System.out.println("Response key = " + responseKey);

            sendHandshakeRespose(responseKey);

            List<Frame> frameBatch = new ArrayList<>();
            while (true) {
                frameBatch.add(Frame.read(in));
                handleBatch(frameBatch);
            }
        }

        private void handleBatch(List<Frame> frameBatch) throws IOException {
            Frame lastOne = frameBatch.get(frameBatch.size() - 1);
            if (!lastOne.isFin) return;

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
                Frame firstFrame = frameBatch.get(0);
                result = new Frame(firstFrame.opCode, allTheData, true);
            } else result = lastOne;

            frameBatch.clear();

            handleResultFrame(result);
        }

        private void handleResultFrame(Frame result) throws IOException {
            switch (result.opCode) {
                case 0:
                    System.out.println("* Continuation frame (ignoring)");
                    break;
                case 1:
                    System.out.println("* Text frame");
                    // TODO: Fail if not valid UTF-8!!
                    String data = toUtf8String(result.payloadData);
                    System.out.println("DATA: " + data);

                    // Echo
                    writeBytes(frameDataForTextFrame(data));

                    break;
                case 2:
                    System.out.println("* Binary frame");
                    byte[] bdata = result.payloadData;

                    // Echo
                    writeBytes(frameDataForBinaryFrame(bdata));
                    break;
                case 8:
                    System.out.println("* Connection close frame");
                    CloseData cd = result.toCloseData();
                    System.out.println(cd);

                    Integer i = cd.code != null ? cd.code : 0;
                    writeBytes(frameDataForCloseFrame(i.intValue(), ""));

                    // TODO:
                    // If an endpoint receives a Close frame and did not previously send a
                    // Close frame, the endpoint MUST send a Close frame in response.

                    abort();
                    break;
                case 9:
                    System.out.println("* Ping frame (TODO)");
                    break;
                case 10:
                    System.out.println("* Pong frame (TODO)");
                    break;
                default:
                    System.err.println("* Unknown frame: " + result.opCode);
                    break;

            }
        }

        private void writeBytes(byte[] bytes) throws IOException {
            System.out.println("Writing " + bytes.length + " bytes ...");
            out.write(bytes);
        }

        private void outputLine(PrintWriter writer, String data) {
            System.out.println("==> " + data);
            writer.print(data);
            writer.print("\r\n");
        }

        private void sendHandshakeRespose(String responseKey) {
            PrintWriter writer = new PrintWriter(out, false);
            outputLine(writer, "HTTP/1.1 101 Switching Protocols");
            outputLine(writer, "Upgrade: websocket");
            outputLine(writer, "Connection: Upgrade");
            outputLine(writer, "Sec-WebSocket-Accept: " + responseKey);
            outputLine(writer, "");
            writer.flush();
            // Note: Do NOT close the writer, as the stream must remain open
        }
    }

    static byte[] frameDataForCloseFrame(int code, String reason) {
        byte[] s = utf8StringToBytes(reason);
        byte[] bytes = new byte[s.length + 2];
        bytes[0] = (byte) ((code & 0xff00) >> 8);
        bytes[1] = (byte) (code & 0xff);
        System.arraycopy(s, 0, bytes, 2, s.length);
        return frameDataFor(8, bytes);
    }

    static byte[] frameDataForTextFrame(String text) {
        byte[] s = utf8StringToBytes(text);
        return frameDataFor(1, s);
    }

    static byte[] frameDataForBinaryFrame(byte[] data) {
        return frameDataFor(2, data);
    }

    static byte[] frameDataFor(int opCode, byte[] data) {
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
        byte[] result = new byte[2 + extraLengthBytes + dataLen];
        result[0] = (byte) firstByte;
        result[1] = (byte) secondByte;
        System.out.println(">> B1: " + firstByte);
        System.out.println(">> B2: " + secondByte);
        System.out.println(">> XL: " + extraLengthBytes);
        if (extraLengthBytes > 0) {
            writeNumberInArray(data.length, result, 2, extraLengthBytes);
        }
        System.arraycopy(data, 0, result, 2 + extraLengthBytes, data.length);
        return result;
    }

    private static void writeNumberInArray(int number, byte[] array, int offset, int len) {
        // Start from the end (network byte order), assume array is filled with zeros.
        for (int i = offset + len - 1; i >= offset; i--) {
            array[i] = (byte) (number & 0xff);
            number = number >> 8;
        }
    }

    private static class Frame {

        final int opCode;
        final byte[] payloadData;
        final boolean isFin;

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
////            if (readLen < buf.length) throw new IOException("Too few bytes read, expected " + buf.length + " but got " + readLen);
//            if (readLen < buf.length) {
//                byte[] tmp = new byte[readLen];
//                System.arraycopy(buf, 0, tmp, 0, readLen);
//                return tmp;
//            }
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
            System.out.println("== B1: " + firstByte);
            boolean isFin = (firstByte & 128) == 128;
            System.out.println("== FIN: " + isFin);
            boolean hasZeroReserved = (firstByte & 112) == 0;
            if (!hasZeroReserved) throw new IllegalArgumentException("Reserved frame bytes 2-4 must be zero.");
            int opCode = (firstByte & 15);
            System.out.println("== OP: " + opCode);
            int secondByte = readUnsignedByte(in);
            System.out.println("== B2: " + secondByte);
            boolean isMasked = (secondByte & 128) == 128;
            int len = (secondByte & 127);
            System.out.println("== LEN: " + len);
            if (len == 126) {
                // 2 bytes of extended len
                byte[] extLen = readBytes(in, 2);
                long tmp = toLong(extLen);
                len = (int) tmp;
            } else if (len == 127) {
                // 8 bytes of extended len
                byte[] extLen = readBytes(in, 8);
                long tmp = toLong(extLen);
                if (tmp > Integer.MAX_VALUE) throw new IllegalArgumentException("Frame too long: " + tmp);
                len = (int) tmp;
            }
            System.out.println("== LEN: " + len);
            byte[] maskingKey = null;
            if (isMasked) {
                maskingKey = readBytes(in, 4);
                System.out.println("== MASK!");
            }
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

        CloseData toCloseData() {
            if (opCode != 8) throw new IllegalStateException("Not a close frame: " + opCode);
            if (payloadData.length == 0) return new CloseData(null, null);
            int code = (int) toLong(payloadData, 0, 2);
            String reason = payloadData.length > 2 ? toUtf8String(payloadData, 2, payloadData.length - 2) : null;
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

        @Override
        public String toString() {
            return "CloseData{" +
                    "code=" + code +
                    ", reason='" + reason + '\'' +
                    '}';
        }
    }

    private static class Headers {
        private final Map<String, String> headers;

        private Headers(Map<String, String> headers) {
            this.headers = headers;
        }

        boolean isProperUpgrade() {
            return "websocket".equalsIgnoreCase(headers.get("Upgrade")) && "Upgrade".equalsIgnoreCase(headers.get("Connection"));
        }
//        int version() {
//            String versionStr =
//        }
        String key() {
            String key = headers.get("Sec-WebSocket-Key");
            if (key == null) throw new IllegalStateException("Missing Sec-WebSocket-Key in handshake.");
            return key;
        }

        static Headers read(InputStream in) throws IOException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String inputLine;
            Map<String, String> headers = new HashMap<>();
            while (!"".equals((inputLine = reader.readLine()))) {
                String[] keyValue = inputLine.split(":", 2);
                // Ignore the initial GET line here. TODO: Don't!
                if (keyValue.length == 2) {
                    System.out.println("<== " + inputLine);
                    headers.put(keyValue[0], keyValue[1].trim());
                }
            }
            // Note: Do NOT close the reader, because the stream must remain open!
            return new Headers(headers);
        }
    }

    private static String createResponseKey(String key) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] rawBytes = (key + HANDSHAKE_GUID).getBytes();
        byte[] result = sha1.digest(rawBytes);
        return Base64.getEncoder().encodeToString(result);
    }

    static String toUtf8String(byte[] data) {
        return toUtf8String(data, 0, data.length);
    }
    static String toUtf8String(byte[] data, int offset, int len) {
        try {
            return new String(data, offset, len, "utf-8");
        } catch (UnsupportedEncodingException e) {
            return "???" + e.getMessage();
        }
    }
    static byte[] utf8StringToBytes(String s) {
        try {
            return s.getBytes("utf-8");
        } catch (UnsupportedEncodingException e) {
            return new byte[0]; // ???
        }
    }
}
