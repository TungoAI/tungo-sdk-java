package kz.tungo.sdk;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny HTTP/1.1 server on a raw socket (127.0.0.1, random port). Tests script every response
 * byte: delays, hung bodies, truncated bodies and aborted connections included.
 */
final class MockServer implements AutoCloseable {
    /** Answers one request. */
    interface Handler {
        void handle(Request request, Exchange exchange) throws Exception;
    }

    /** A request as it arrived on the wire. */
    static final class Request {
        final String method;
        final String target;
        final Map<String, String> headers;
        final byte[] body;

        Request(String method, String target, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.target = target;
            this.headers = headers;
            this.body = body;
        }

        String path() {
            int q = target.indexOf('?');
            return q < 0 ? target : target.substring(0, q);
        }

        String query() {
            int q = target.indexOf('?');
            return q < 0 ? "" : target.substring(q + 1);
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** The response side of one request. */
    static final class Exchange {
        private final Socket socket;
        private final OutputStream out;
        private boolean keepAlive;

        Exchange(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
        }

        void respond(int status, String contentType, byte[] body, String... headers) throws IOException {
            StringBuilder head = new StringBuilder("HTTP/1.1 " + status + " Status\r\n");
            if (contentType != null) {
                head.append("Content-Type: ").append(contentType).append("\r\n");
            }
            head.append("Content-Length: ").append(body.length).append("\r\n");
            appendHeaders(head, headers);
            head.append("\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            keepAlive = true;
        }

        void json(int status, String body, String... headers) throws IOException {
            respond(status, "application/json", body.getBytes(StandardCharsets.UTF_8), headers);
        }

        /** Starts a response whose body ends when the connection closes. */
        void head(int status, String contentType, String... headers) throws IOException {
            StringBuilder head = new StringBuilder("HTTP/1.1 " + status + " Status\r\n");
            head.append("Content-Type: ").append(contentType).append("\r\n");
            head.append("Connection: close\r\n");
            appendHeaders(head, headers);
            head.append("\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        void sse(String... headers) throws IOException {
            head(200, "text/event-stream", headers);
        }

        void write(String text) throws IOException {
            write(text.getBytes(StandardCharsets.UTF_8));
        }

        void write(byte[] bytes) throws IOException {
            out.write(bytes);
            out.flush();
        }

        /** Starts a chunked response, so that an aborted connection is visible to the client. */
        void respondChunkedStart(String contentType) throws IOException {
            out.write(("HTTP/1.1 200 Status\r\nContent-Type: " + contentType + "\r\nTransfer-Encoding: chunked\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        void writeChunk(String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        /** Waits until the client closes its side of the connection. */
        boolean clientClosed(int millis) {
            try {
                socket.setSoTimeout(millis);
                return socket.getInputStream().read() < 0;
            } catch (IOException e) {
                return !(e instanceof java.net.SocketTimeoutException);
            }
        }

        /** Aborts the connection without finishing the response. */
        void abort() throws IOException {
            socket.setSoLinger(true, 0);
            socket.close();
        }

        private static void appendHeaders(StringBuilder head, String... headers) {
            for (int i = 0; i + 1 < headers.length; i += 2) {
                head.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
            }
        }
    }

    private final ServerSocket server;
    private final Thread acceptor;
    private final ConcurrentLinkedQueue<Handler> queue = new ConcurrentLinkedQueue<>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger connections = new AtomicInteger();
    private volatile Handler fallback = (request, exchange) ->
        exchange.json(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"unexpected request\"}}");
    private volatile boolean closed;

    MockServer() {
        try {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        acceptor = new Thread(this::acceptLoop, "mock-server-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    /** Queues a handler for the next request that has no handler yet. */
    MockServer enqueue(Handler handler) {
        queue.add(handler);
        return this;
    }

    MockServer enqueueJson(int status, String body, String... headers) {
        return enqueue((request, exchange) -> exchange.json(status, body, headers));
    }

    /** Sets the handler of every request that finds the queue empty. */
    MockServer always(Handler handler) {
        fallback = handler;
        return this;
    }

    List<Request> requests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    Request request(int index) {
        return requests().get(index);
    }

    Request lastRequest() {
        List<Request> all = requests();
        return all.get(all.size() - 1);
    }

    int requestCount() {
        return requests.size();
    }

    int connectionCount() {
        return connections.get();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                return;
            }
            connections.incrementAndGet();
            sockets.add(socket);
            Thread t = new Thread(() -> serve(socket), "mock-server-connection");
            t.setDaemon(true);
            t.start();
        }
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            InputStream in = new BufferedInputStream(s.getInputStream());
            while (true) {
                Request request = read(in);
                if (request == null) {
                    return;
                }
                requests.add(request);
                Handler handler = queue.poll();
                Exchange exchange = new Exchange(s);
                (handler == null ? fallback : handler).handle(request, exchange);
                if (!exchange.keepAlive) {
                    return;
                }
            }
        } catch (Exception ignored) {
            // the client went away or the test aborted the connection
        }
    }

    private static Request read(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            head.write(b);
            if (b == '\r' && (state == 0 || state == 2)) {
                state++;
            } else if (b == '\n' && (state == 1 || state == 3)) {
                state++;
            } else {
                state = 0;
            }
        }
        String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
        String[] start = lines[0].split(" ");
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
        }
        int length = headers.containsKey("content-length") ? Integer.parseInt(headers.get("content-length")) : 0;
        byte[] body = in.readNBytes(length);
        return new Request(start[0], start[1], headers, body);
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // already closed
        }
        synchronized (sockets) {
            for (Socket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // already closed
                }
            }
        }
    }
}
