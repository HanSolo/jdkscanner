package eu.hansolo.jdkscanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public class DiscoApiClient {

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
                                                    .connectTimeout(Duration.ofSeconds(10))
                                                    .build();


    public DiscoApiClient(final String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }


    public JsonArray searchPackages(final String distribution, final int jdkVersion, final String releaseStatus, final String operatingSystem, final String architecture, final String packageType, final boolean javafxBundled, final String libcType) {
        final Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("distribution",     List.of(distribution));
        query.put("jdk_version",      List.of(String.valueOf(jdkVersion)));
        query.put("release_status",   List.of(releaseStatus));
        query.put("operating_system", List.of(operatingSystem));
        query.put("architecture",     List.of(architecture));
        query.put("package_type",     List.of(packageType));
        query.put("javafx_bundled",   List.of(String.valueOf(javafxBundled)));
        if (null != libcType) { query.put("libc_type", List.of(libcType)); }
        return searchPkgs(query);
    }

    public JsonArray searchPkgs(final Map<String, List<String>> queryParams) {
        final JsonObject  response = getJson("/disco/v4.0/pkgs", queryParams);
        final JsonElement result   = response.get("result");
        return (null != result && result.isJsonArray()) ? result.getAsJsonArray() : new JsonArray();
    }

    private JsonObject getJson(final String path, final Map<String, List<String>> queryParams) {
        final String query = queryParams.entrySet()
                                        .stream()
                                        .filter(e -> null != e.getValue() && !e.getValue().isEmpty())
                                        .flatMap(e -> e.getValue().stream().map(v -> encode(e.getKey()) + "=" + encode(v)))
                                        .collect(Collectors.joining("&"));
        final String url = baseUrl + path + (query.isEmpty() ? "" : "?" + query);

        try {
            final HttpRequest request = HttpRequest.newBuilder()
                                                   .uri(URI.create(url))
                                                   .timeout(Duration.ofSeconds(20))
                                                   .header("Accept", "application/json")
                                                   .header("User-Agent", "jdk-scanner/21.0.0")
                                                   .GET()
                                                   .build();
            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) { throw new RuntimeException("disco-api returned HTTP " + response.statusCode() + " for " + url); }

            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new RuntimeException("Failed to call disco-api at " + url + ": " + e.getMessage(), e);
        }
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}