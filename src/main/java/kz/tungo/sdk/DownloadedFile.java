package kz.tungo.sdk;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import kz.tungo.sdk.internal.ContentDisposition;

/**
 * A file downloaded from the API (a chat file, a proposal or batch export): its bytes, read
 * whole into memory, the file name the server suggests, the media type, and the HTTP details of
 * the response. Instances are immutable.
 *
 * <p>The file name comes from {@code Content-Disposition}: the UTF-8 {@code filename*} when
 * present, otherwise {@code filename}. It is the server's suggestion, not a safe path: do not
 * build a path from it unchecked.
 */
public final class DownloadedFile {
    private final byte[] content;
    private final String filename;
    private final String contentType;
    private final int statusCode;
    private final HttpHeaders headers;

    /**
     * Creates a downloaded file from a response. The file name and the media type are read from
     * the {@code Content-Disposition} and {@code Content-Type} headers.
     *
     * @param statusCode HTTP status
     * @param headers response headers
     * @param content the body bytes; copied
     */
    public DownloadedFile(int statusCode, HttpHeaders headers, byte[] content) {
        this.statusCode = statusCode;
        this.headers = Objects.requireNonNull(headers, "headers");
        this.content = Objects.requireNonNull(content, "content").clone();
        this.filename = ContentDisposition.filename(headers.firstValue("content-disposition").orElse(null));
        this.contentType = headers.firstValue("content-type").orElse(null);
    }

    /**
     * Returns the bytes of the file.
     *
     * @return a copy of the body bytes, exactly as received
     */
    public byte[] content() {
        return content.clone();
    }

    /**
     * Returns the bytes of the file as a stream, without copying them.
     *
     * @return a stream over the body bytes
     */
    public InputStream inputStream() {
        return new ByteArrayInputStream(content);
    }

    /**
     * Returns the size of the file.
     *
     * @return the number of bytes
     */
    public int size() {
        return content.length;
    }

    /**
     * Returns the file name the server suggests in {@code Content-Disposition}.
     *
     * @return the name as sent (not a safe path), if the server sent one
     */
    public Optional<String> filename() {
        return Optional.ofNullable(filename);
    }

    /**
     * Returns the {@code Content-Type} of the response as sent, parameters included.
     *
     * @return the media type, if the header is present
     */
    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    /**
     * Writes the bytes of the file to a path.
     *
     * @param path the target file
     * @param options how the file is opened, as for {@link Files#write(Path, byte[], OpenOption...)}
     * @return the path
     * @throws IOException when the file cannot be written
     */
    public Path writeTo(Path path, OpenOption... options) throws IOException {
        return Files.write(path, content, options);
    }

    /**
     * Returns the HTTP status.
     *
     * @return the status code
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns the response headers.
     *
     * @return the headers (names are case-insensitive)
     */
    public HttpHeaders headers() {
        return headers;
    }

    /**
     * Returns the first value of a response header.
     *
     * @param name header name, case-insensitive
     * @return the value, if the header is present
     */
    public Optional<String> header(String name) {
        return headers.firstValue(name);
    }

    /**
     * Returns the request id assigned by the API ({@code x-request-id}).
     *
     * @return the request id, if the header is present
     */
    public Optional<String> requestId() {
        return headers.firstValue("x-request-id");
    }

    /**
     * Returns the rate-limit information of this response.
     *
     * @return the rate limit, if the API sent the {@code x-ratelimit-*} headers
     */
    public Optional<RateLimit> rateLimit() {
        return RateLimit.from(headers);
    }

    @Override
    public String toString() {
        return "DownloadedFile{filename=" + filename + ", contentType=" + contentType + ", size=" + content.length
            + ", requestId=" + requestId().orElse(null) + "}";
    }
}
