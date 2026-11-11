package kz.tungo.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import kz.tungo.sdk.errors.DecodeException;
import kz.tungo.sdk.errors.PaginationException;

/**
 * One page of a cursor-paginated list.
 *
 * @param <T> type of the items
 * @param <B> type of the whole response body, which may carry more than the items (totals, counts)
 */
public final class Page<T, B> {
    private final ApiResponse<B> response;
    private final List<T> data;
    private final String nextCursor;
    private final String requestedCursor;
    private final Function<String, Page<T, B>> fetcher;
    // Every cursor requested on the way to this page (the walk that started at the first page).
    private Set<String> seenCursors = Collections.emptySet();

    /**
     * Creates a page. Called by the generated resource methods.
     *
     * @param response the decoded response of this page
     * @param data the items of the page
     * @param nextCursor the cursor of the next page, null or empty when this is the last page
     * @param requestedCursor the cursor this page was requested with, null for the first page
     * @param fetcher requests the page for a cursor with the same filters and options
     * @throws DecodeException when the response carries no list of items
     */
    public Page(ApiResponse<B> response, List<T> data, String nextCursor, String requestedCursor,
        Function<String, Page<T, B>> fetcher) {
        this.response = response;
        if (data == null) {
            // A page without its list is a broken response, not an empty last page.
            throw new DecodeException("The list response carries no \"data\" array",
                null, response == null ? 0 : response.statusCode(),
                response == null ? null : response.requestId().orElse(null), null);
        }
        this.data = Collections.unmodifiableList(new ArrayList<>(data));
        this.nextCursor = nextCursor == null || nextCursor.isEmpty() ? null : nextCursor;
        this.requestedCursor = requestedCursor;
        this.fetcher = fetcher;
    }

    /**
     * Returns the items of this page.
     *
     * @return an unmodifiable list
     */
    public List<T> data() {
        return data;
    }

    /**
     * Returns the cursor of the next page.
     *
     * @return the cursor, empty on the last page
     */
    public Optional<String> nextCursor() {
        return Optional.ofNullable(nextCursor);
    }

    /**
     * Tells whether there is a next page.
     *
     * @return true when the response carried a non-empty next cursor
     */
    public boolean hasNextPage() {
        return nextCursor != null;
    }

    /**
     * Requests the next page with the same filters and request options.
     *
     * @return the next page
     * @throws NoSuchElementException when this is the last page
     * @throws PaginationException when the server returned a cursor that was already requested
     *     on the way to this page (the same cursor again, or a cycle such as A, B, A)
     */
    public Page<T, B> nextPage() {
        if (nextCursor == null) {
            throw new NoSuchElementException("This is the last page");
        }
        if (nextCursor.equals(requestedCursor) || seenCursors.contains(nextCursor)) {
            throw new PaginationException("The server returned the same cursor twice (" + nextCursor
                + "); stopping instead of requesting the same page forever");
        }
        Page<T, B> next = fetcher.apply(nextCursor);
        Set<String> seen = new HashSet<>(seenCursors);
        if (requestedCursor != null) {
            seen.add(requestedCursor);
        }
        seen.add(nextCursor);
        next.seenCursors = seen;
        return next;
    }

    /**
     * Returns the whole decoded response body of this page.
     *
     * @return the body
     */
    public B body() {
        return response.body();
    }

    /**
     * Returns the HTTP details of this page: status, headers, request id, rate limit.
     *
     * @return the response
     */
    public ApiResponse<B> response() {
        return response;
    }

    /**
     * Returns a lazy iterable over the items of this page and of every following page.
     *
     * @return an auto-paging iterable that starts with this page
     */
    public AutoPager<T> autoPager() {
        return new AutoPager<>(() -> this);
    }

    @Override
    public String toString() {
        return "Page{items=" + data.size() + ", nextCursor=" + nextCursor + "}";
    }
}
