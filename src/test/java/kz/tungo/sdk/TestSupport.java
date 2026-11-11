package kz.tungo.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Helpers shared by the tests. */
final class TestSupport {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String API_KEY = "tg_test_SECRETKEY0123456789";

    private TestSupport() {
    }

    /** A client for the mock server that takes nothing from the real environment. */
    static TungoClient.Builder builder(MockServer server) {
        return TungoClient.builder().environment(name -> null).apiKey(API_KEY).baseUrl(server.url());
    }

    static TungoClient client(MockServer server) {
        return builder(server).build();
    }

    static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The shared conformance vectors, read from conformance/vectors.json of the repository. */
    static JsonNode vectors() {
        Path path = Paths.get("conformance", "vectors.json");
        try {
            return MAPPER.readTree(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path.toAbsolutePath(), e);
        }
    }

    static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    static HttpHeaders headers(JsonNode object) {
        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            map.put(name, Collections.singletonList(object.get(name).asText()));
        }
        return HttpHeaders.of(map, (a, b) -> true);
    }

    static String[] headerPairs(JsonNode object) {
        List<String> out = new ArrayList<>();
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (name.equalsIgnoreCase("content-type")) {
                continue;
            }
            out.add(name);
            out.add(object.get(name).asText());
        }
        return out.toArray(new String[0]);
    }

    static String envelope(String type, String code, String message) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"code\":\"" + code + "\",\"message\":\"" + message
            + "\"},\"request_id\":\"req_test_1\"}";
    }
}
