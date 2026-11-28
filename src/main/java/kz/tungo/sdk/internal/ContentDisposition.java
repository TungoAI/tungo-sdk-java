package kz.tungo.sdk.internal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the file name of a download from its {@code Content-Disposition} header. Internal: not
 * part of the public API.
 */
public final class ContentDisposition {
    private ContentDisposition() {
    }

    /**
     * Returns the file name the header suggests. {@code filename*} (RFC 5987, UTF-8) wins over
     * {@code filename} in any order; a {@code filename*} in another charset or with bad encoding is
     * ignored. The name is returned as sent: it is not a safe path.
     *
     * @param header the header value, may be null
     * @return the file name, or null when the header is absent or names no file
     */
    public static String filename(String header) {
        if (header == null) {
            return null;
        }
        Map<String, String> params = params(header);
        String extended = params.get("filename*");
        if (extended != null) {
            int first = extended.indexOf('\'');
            if (first >= 0 && extended.substring(0, first).equalsIgnoreCase("utf-8")) {
                int second = extended.indexOf('\'', first + 1);
                if (second >= 0) {
                    String decoded = percentDecodeUtf8(extended.substring(second + 1));
                    if (decoded != null && !decoded.isEmpty()) {
                        return decoded;
                    }
                }
            }
        }
        String plain = params.get("filename");
        return plain == null || plain.isEmpty() ? null : plain;
    }

    /** The parameters after the disposition type: lower-case names, unquoted values, first wins. */
    private static Map<String, String> params(String header) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (quoted && c == '\\') {
                escaped = true;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == ';' && !quoted) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        Map<String, String> out = new HashMap<>();
        for (String part : parts.subList(1, parts.size())) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String name = part.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            String value = part.substring(eq + 1).strip();
            if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
                StringBuilder unquoted = new StringBuilder();
                boolean escape = false;
                for (int i = 1; i < value.length() - 1; i++) {
                    char c = value.charAt(i);
                    if (!escape && c == '\\') {
                        escape = true;
                        continue;
                    }
                    escape = false;
                    unquoted.append(c);
                }
                value = unquoted.toString();
            }
            out.putIfAbsent(name, value);
        }
        return out;
    }

    /** Decodes percent-encoded UTF-8; null when an escape is malformed or the bytes are not UTF-8. */
    private static String percentDecodeUtf8(String encoded) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%') {
                if (i + 2 >= encoded.length()) {
                    return null;
                }
                int hi = Character.digit(encoded.charAt(i + 1), 16);
                int lo = Character.digit(encoded.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                bytes.write(hi * 16 + lo);
                i += 2;
            } else {
                int end = Character.isHighSurrogate(c) && i + 1 < encoded.length() ? i + 2 : i + 1;
                byte[] utf8 = encoded.substring(i, end).getBytes(StandardCharsets.UTF_8);
                bytes.write(utf8, 0, utf8.length);
                i = end - 1;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
