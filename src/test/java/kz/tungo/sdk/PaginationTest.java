package kz.tungo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import kz.tungo.sdk.errors.PaginationException;
import kz.tungo.sdk.models.ProposalListItem;
import kz.tungo.sdk.models.ProposalPage;
import kz.tungo.sdk.params.ProposalsListParams;
import org.junit.jupiter.api.Test;

class PaginationTest {
    private static String page(String cursorJson, String... ids) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < ids.length; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"id\":\"").append(ids[i]).append("\"}");
        }
        sb.append("],\"counts\":{}");
        if (cursorJson != null) {
            sb.append(",\"next_cursor\":").append(cursorJson);
        }
        return sb.append(",\"extra_total\":5}").toString();
    }

    /** Serves three pages keyed by the cursor of the request. */
    private static void threePages(MockServer server) {
        server.always((request, exchange) -> {
            String query = request.query();
            if (query.contains("cursor=c2")) {
                exchange.json(200, page("null", "p5"));
            } else if (query.contains("cursor=c1")) {
                exchange.json(200, page("\"c2\"", "p3", "p4"));
            } else {
                exchange.json(200, page("\"c1\"", "p1", "p2"), "x-request-id", "req_page_1");
            }
        });
    }

    @Test
    void aPageIsTypedAndFetchesTheNextOneWithTheSameFilters() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            threePages(server);
            Page<ProposalListItem, ProposalPage> first = client.proposals().list("co_1",
                ProposalsListParams.builder().status("new").limit(2).build());
            assertEquals(2, first.data().size());
            assertEquals("p1", first.data().get(0).id());
            assertEquals("c1", first.nextCursor().orElseThrow());
            assertTrue(first.hasNextPage());
            assertEquals("req_page_1", first.response().requestId().orElseThrow());
            assertEquals(5, first.body().additionalProperties().get("extra_total").asInt());
            assertThrows(UnsupportedOperationException.class, () -> first.data().clear());

            Page<ProposalListItem, ProposalPage> second = first.nextPage();
            assertEquals("p3", second.data().get(0).id());
            String query = server.request(1).query();
            assertTrue(query.contains("status=new") && query.contains("limit=2") && query.contains("cursor=c1"), query);

            Page<ProposalListItem, ProposalPage> third = second.nextPage();
            assertFalse(third.hasNextPage());
            assertFalse(third.nextCursor().isPresent());
            assertThrows(NoSuchElementException.class, third::nextPage);
            assertEquals(3, server.requestCount());
        }
    }

    @Test
    void theAutoPagerWalksEveryPageLazily() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            threePages(server);
            AutoPager<ProposalListItem> pager = client.proposals().listAll("co_1", ProposalsListParams.builder().status("new").build());
            assertEquals(0, server.requestCount(), "nothing is fetched before iteration starts");
            List<String> ids = new ArrayList<>();
            for (ProposalListItem item : pager) {
                ids.add(item.id());
            }
            assertEquals(List.of("p1", "p2", "p3", "p4", "p5"), ids);
            assertEquals(3, server.requestCount());
            assertTrue(server.request(2).query().contains("status=new"), server.request(2).query());

            assertEquals(List.of("p1", "p2", "p3", "p4", "p5"),
                client.proposals().listAll("co_1").stream().map(ProposalListItem::id).collect(Collectors.toList()));
        }
    }

    @Test
    void anEarlyBreakStopsFetching() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            threePages(server);
            for (ProposalListItem item : client.proposals().listAll("co_1")) {
                if (item.id().equals("p2")) {
                    break;
                }
            }
            assertEquals(1, server.requestCount());
            assertEquals("p3", client.proposals().listAll("co_1").stream().skip(2).findFirst().orElseThrow().id());
            assertEquals(3, server.requestCount());
        }
    }

    @Test
    void stopsOnNullAbsentAndEmptyCursors() {
        for (String cursor : new String[] {"null", null, "\"\""}) {
            try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
                server.always((request, exchange) -> exchange.json(200, page(cursor, "p1")));
                assertEquals(1, client.proposals().listAll("co_1").stream().count(), "cursor " + cursor);
                assertEquals(1, server.requestCount(), "cursor " + cursor);
            }
        }
    }

    @Test
    void aRepeatedCursorStopsWithAnErrorInsteadOfLoopingForever() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.json(200, page("\"same\"", "p1")));
            List<String> seen = new ArrayList<>();
            PaginationException e = assertThrows(PaginationException.class, () -> {
                for (ProposalListItem item : client.proposals().listAll("co_1")) {
                    seen.add(item.id());
                }
            });
            assertTrue(e.getMessage().contains("same"), e.getMessage());
            assertEquals(2, server.requestCount());
            assertEquals(2, seen.size());
        }
    }

    @Test
    void anEmptyPageWithACursorIsNotTheEnd() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> {
                if (request.query().contains("cursor=c1")) {
                    exchange.json(200, page("null", "p9"));
                } else {
                    exchange.json(200, page("\"c1\""));
                }
            });
            assertEquals("p9", client.proposals().listAll("co_1").iterator().next().id());
        }
    }
}
