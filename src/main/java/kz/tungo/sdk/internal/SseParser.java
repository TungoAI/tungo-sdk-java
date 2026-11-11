package kz.tungo.sdk.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;
import kz.tungo.sdk.ServerSentEvent;

/**
 * Incremental parser of a server-sent event stream (WHATWG HTML, "interpreting an event
 * stream"). Feed it bytes as they arrive; it reports each dispatched event. Internal: not part
 * of the public API.
 *
 * <p>Lines may end with LF, CRLF or a bare CR. A leading byte order mark is skipped. Lines are
 * split on bytes, so UTF-8 sequences and events may be split across chunks at any position, and
 * a line may be arbitrarily long. An event that is not terminated by a blank line is discarded.
 */
public final class SseParser {
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private byte[] line = new byte[256];
    private int lineLength;
    private boolean skipLineFeed;
    private int bomMatched;
    private boolean started;

    private final StringBuilder data = new StringBuilder();
    private boolean hasData;
    private String eventName = "";
    private String lastId;
    private Integer retry;

    /** Creates a parser at the start of a stream. */
    public SseParser() {
    }

    /**
     * Consumes a chunk of the stream.
     *
     * @param chunk the buffer
     * @param offset index of the first byte
     * @param length number of bytes
     * @param sink receives every event completed by this chunk, in order
     */
    public void feed(byte[] chunk, int offset, int length, Consumer<ServerSentEvent> sink) {
        for (int i = offset; i < offset + length; i++) {
            byte b = chunk[i];
            if (!started) {
                if (b == BOM[bomMatched]) {
                    bomMatched++;
                    if (bomMatched == BOM.length) {
                        started = true;
                    }
                    continue;
                }
                started = true;
                for (int k = 0; k < bomMatched; k++) {
                    accept(BOM[k], sink);
                }
            }
            accept(b, sink);
        }
    }

    /**
     * Consumes a whole chunk of the stream.
     *
     * @param chunk the bytes
     * @param sink receives every event completed by this chunk, in order
     */
    public void feed(byte[] chunk, Consumer<ServerSentEvent> sink) {
        feed(chunk, 0, chunk.length, sink);
    }

    private void accept(byte b, Consumer<ServerSentEvent> sink) {
        if (skipLineFeed) {
            skipLineFeed = false;
            if (b == '\n') {
                return;
            }
        }
        if (b == '\n') {
            endLine(sink);
        } else if (b == '\r') {
            endLine(sink);
            skipLineFeed = true;
        } else {
            if (lineLength == line.length) {
                line = Arrays.copyOf(line, grow(line.length));
            }
            line[lineLength++] = b;
        }
    }

    private static int grow(int size) {
        long doubled = (long) size * 2;
        if (doubled > Integer.MAX_VALUE - 16) {
            if (size >= Integer.MAX_VALUE - 16) {
                throw new OutOfMemoryError("A server-sent event line exceeds 2 GB");
            }
            return Integer.MAX_VALUE - 16;
        }
        return (int) doubled;
    }

    private void endLine(Consumer<ServerSentEvent> sink) {
        if (lineLength == 0) {
            if (hasData) {
                sink.accept(new ServerSentEvent(eventName.isEmpty() ? "message" : eventName, data.toString(), lastId, retry));
            }
            data.setLength(0);
            hasData = false;
            eventName = "";
            retry = null;
            return;
        }
        int length = lineLength;
        lineLength = 0;
        if (line[0] == ':') {
            shrink();
            return;
        }
        int colon = -1;
        for (int i = 0; i < length; i++) {
            if (line[i] == ':') {
                colon = i;
                break;
            }
        }
        String field;
        String value;
        if (colon < 0) {
            field = new String(line, 0, length, StandardCharsets.UTF_8);
            value = "";
        } else {
            field = new String(line, 0, colon, StandardCharsets.UTF_8);
            int start = colon + 1;
            if (start < length && line[start] == ' ') {
                start++;
            }
            value = new String(line, start, length - start, StandardCharsets.UTF_8);
        }
        shrink();
        switch (field) {
            case "event":
                eventName = value;
                break;
            case "data":
                if (hasData) {
                    data.append('\n');
                }
                data.append(value);
                hasData = true;
                break;
            case "id":
                if (value.indexOf('\0') < 0) {
                    lastId = value;
                }
                break;
            case "retry":
                if (!value.isEmpty() && value.length() <= 9 && value.chars().allMatch(c -> c >= '0' && c <= '9')) {
                    retry = Integer.valueOf(value);
                }
                break;
            default:
                break;
        }
    }

    /** Releases the buffer of an unusually long line once it has been handled. */
    private void shrink() {
        if (line.length > (1 << 20)) {
            line = new byte[256];
        }
    }
}
