package kz.tungo.sdk.internal;

import java.nio.charset.StandardCharsets;
import kz.tungo.sdk.errors.ValidationException;

/**
 * Percent-encoding of path parameters. Internal: not part of the public API.
 */
public final class PathSegments {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private PathSegments() {
    }

    /**
     * Encodes a path parameter as one path segment: every byte outside
     * {@code A-Z a-z 0-9 - . _ ~} is percent-encoded.
     *
     * @param name the parameter name, used in the error message
     * @param value the parameter value
     * @return the encoded segment
     * @throws ValidationException when the value is null, empty, {@code .} or {@code ..}
     */
    public static String encode(String name, String value) {
        if (value == null) {
            throw new ValidationException("Path parameter '" + name + "' must not be null");
        }
        if (value.isEmpty() || value.equals(".") || value.equals("..")) {
            throw new ValidationException("Path parameter '" + name + "' must not be empty, \".\" or \"..\"");
        }
        return percentEncode(value);
    }

    /**
     * Percent-encodes a string, keeping only unreserved characters.
     *
     * @param value the text
     * @return the encoded text (a space becomes {@code %20})
     */
    public static String percentEncode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length + 8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return sb.toString();
    }
}
