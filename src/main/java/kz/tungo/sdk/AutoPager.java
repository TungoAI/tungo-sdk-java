package kz.tungo.sdk;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A lazy sequence of the items of every page of a list. Pages are requested only when the
 * iteration reaches them; stopping the iteration stops the requests. Each call of
 * {@link #iterator()} starts again from the first page.
 *
 * @param <T> type of the items
 */
public final class AutoPager<T> implements Iterable<T> {
    private final Supplier<? extends Page<T, ?>> firstPage;

    /**
     * Creates an auto-pager. Called by the generated resource methods.
     *
     * @param firstPage requests the first page; invoked lazily
     */
    public AutoPager(Supplier<? extends Page<T, ?>> firstPage) {
        this.firstPage = firstPage;
    }

    /**
     * Returns an iterator that requests pages as needed.
     *
     * @return a new iterator starting at the first page
     */
    @Override
    public Iterator<T> iterator() {
        return new Iterator<T>() {
            private Page<T, ?> page;
            private Iterator<T> items;

            @Override
            public boolean hasNext() {
                if (page == null) {
                    page = firstPage.get();
                    items = page.data().iterator();
                }
                while (!items.hasNext()) {
                    if (!page.hasNextPage()) {
                        return false;
                    }
                    page = page.nextPage();
                    items = page.data().iterator();
                }
                return true;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return items.next();
            }
        };
    }

    /**
     * Returns a lazy sequential stream of the items.
     *
     * @return a stream that requests pages as needed
     */
    public Stream<T> stream() {
        return StreamSupport.stream(
            () -> Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED | Spliterator.NONNULL),
            Spliterator.ORDERED | Spliterator.NONNULL, false);
    }
}
