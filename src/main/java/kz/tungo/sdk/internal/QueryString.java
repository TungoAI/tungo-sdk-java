package kz.tungo.sdk.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import kz.tungo.sdk.errors.ValidationException;

/**
 * Builder of a URL query string. Internal: not part of the public API.
 */
public final class QueryString {
    private final StringBuilder sb = new StringBuilder();

    /** Creates an empty query. */
    public QueryString() {
    }

    /**
     * Adds a parameter. A null value is skipped; a collection or an array adds one pair per
     * element; booleans are written as {@code true}/{@code false}; date-times as RFC 3339.
     *
     * @param name the parameter name
     * @param value the value, may be null
     * @return this builder
     */
    public QueryString add(String name, Object value) {
        if (value == null) {
            return this;
        }
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                add(name, item);
            }
            return this;
        }
        if (value instanceof Object[]) {
            for (Object item : (Object[]) value) {
                add(name, item);
            }
            return this;
        }
        if (sb.length() > 0) {
            sb.append('&');
        }
        sb.append(PathSegments.percentEncode(name)).append('=').append(PathSegments.percentEncode(format(name, value)));
        return this;
    }

    private static String format(String name, Object value) {
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new ValidationException("Query parameter '" + name + "' must be a finite number");
            }
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (value instanceof OffsetDateTime) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format((OffsetDateTime) value);
        }
        if (value instanceof ZonedDateTime) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(((ZonedDateTime) value).toOffsetDateTime());
        }
        if (value instanceof Instant) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(((Instant) value).atOffset(ZoneOffset.UTC));
        }
        return String.valueOf(value);
    }

    /**
     * Tells whether no parameter was added.
     *
     * @return true when the query is empty
     */
    public boolean isEmpty() {
        return sb.length() == 0;
    }

    /**
     * Returns the query string without the leading question mark.
     *
     * @return the encoded query, possibly empty
     */
    @Override
    public String toString() {
        return sb.toString();
    }
}
