package kz.tungo.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import kz.tungo.sdk.errors.NotFoundException;
import kz.tungo.sdk.models.GetCompanyBatchExportFormat;
import kz.tungo.sdk.models.GetCompanyProposalExportFormat;
import kz.tungo.sdk.params.BatchesExportParams;
import kz.tungo.sdk.params.ProposalsExportParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Operations that answer with a file: chat files and the proposal and batch exports. */
class DownloadTest {
    private static final byte[] BYTES = {'P', 'K', 0x03, 0x04, 0x00, (byte) 0xFF, (byte) 0xFE, (byte) 0x80, 0x0A, 0x0D};
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DISPOSITION =
        "attachment; filename=\"Otchet.xlsx\"; filename*=UTF-8''%D0%9E%D1%82%D1%87%D1%91%D1%82.xlsx";

    @Test
    void anExportReturnsTheExactBytesWithTheNameAndType(@TempDir Path dir) throws Exception {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueue((request, exchange) -> exchange.respond(200, XLSX, BYTES,
                "Content-Disposition", DISPOSITION, "x-request-id", "req_dl_1"));
            DownloadedFile file = client.proposals().export("co_1", "P-000123",
                ProposalsExportParams.builder().format(GetCompanyProposalExportFormat.PDF).build());
            assertArrayEquals(BYTES, file.content());
            assertEquals(BYTES.length, file.size());
            assertArrayEquals(BYTES, file.inputStream().readAllBytes());
            assertEquals("Отчёт.xlsx", file.filename().orElseThrow());
            assertEquals(XLSX, file.contentType().orElseThrow());
            assertEquals("req_dl_1", file.requestId().orElseThrow());
            assertEquals(200, file.statusCode());
            assertEquals(DISPOSITION, file.header("Content-Disposition").orElseThrow());
            String shown = file.toString();
            assertTrue(shown.contains("size=" + BYTES.length) && shown.contains("Отчёт.xlsx"), shown);

            MockServer.Request request = server.lastRequest();
            assertEquals("GET", request.method);
            assertEquals("/v1/companies/co_1/proposals/P-000123/export", request.path());
            assertEquals("format=pdf", request.query());
            assertEquals("*/*", request.header("accept"));
            assertEquals(TestSupport.API_KEY, request.header("x-api-key"));

            byte[] copy = file.content();
            copy[0] = 'X';
            assertArrayEquals(BYTES, file.content(), "the content is a copy");
            Path saved = file.writeTo(dir.resolve("out.xlsx"));
            assertArrayEquals(BYTES, Files.readAllBytes(saved));
        }
    }

    @Test
    void aChatFileAndABatchExport() throws Exception {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueue((request, exchange) -> exchange.respond(200, "application/octet-stream", BYTES));
            DownloadedFile file = client.chat().downloadFile("co_1", "dlg_1", "report.docx");
            assertArrayEquals(BYTES, file.content());
            assertFalse(file.filename().isPresent(), "no Content-Disposition, no name");
            assertEquals("application/octet-stream", file.contentType().orElseThrow());
            assertEquals("/v1/companies/co_1/chat/dlg_1/files/report.docx", server.lastRequest().path());

            server.enqueue((request, exchange) -> exchange.respond(200, null, new byte[0], "x-request-id", "req_dl_2"));
            ApiResponse<DownloadedFile> response = client.batches().exportWithResponse("co_1", "b_1",
                BatchesExportParams.builder().format(GetCompanyBatchExportFormat.CSV).build(), RequestOptions.none());
            assertEquals(0, response.body().size());
            assertFalse(response.body().contentType().isPresent());
            assertEquals("req_dl_2", response.requestId().orElseThrow());
            assertEquals("/v1/companies/co_1/batch/b_1/export", server.lastRequest().path());
            assertEquals("format=csv", server.lastRequest().query());

            server.enqueue((request, exchange) -> exchange.respond(200, XLSX, BYTES, "Content-Disposition", DISPOSITION));
            DownloadedFile async = client.async().proposals().export("co_1", "P-000123").get(20, TimeUnit.SECONDS);
            assertEquals("Отчёт.xlsx", async.filename().orElseThrow());
            assertEquals("", server.lastRequest().query());
        }
    }

    @Test
    void aMissingFileIsTheTypedNotFoundError() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(404, "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"Файл не найден\"},"
                + "\"request_id\":\"req_404\"}");
            NotFoundException e = assertThrows(NotFoundException.class,
                () -> client.chat().downloadFile("co_1", "dlg_1", "gone.xlsx"));
            assertEquals(404, e.statusCode());
            assertEquals("req_404", e.requestId().orElseThrow());
            assertEquals(1, server.requestCount());
        }
    }

    @Test
    void aBusyServerIsRetried() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(503, TestSupport.envelope("unavailable_error", "busy", "Busy"), "retry-after-ms", "1")
                .enqueue((request, exchange) -> exchange.respond(200, XLSX, BYTES, "Content-Disposition", DISPOSITION));
            DownloadedFile file = client.proposals().export("co_1", "P-000123");
            assertArrayEquals(BYTES, file.content());
            assertEquals(2, server.requestCount());
            assertEquals("*/*", server.request(1).header("accept"));
        }
    }
}
