package com.ledger.settlement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * HTTP client for the reference-data service that owns the currency table
 * (GET /currencies, CSV lines "code,minorUnitDigits").
 */
final class ReferenceDataClient {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private ReferenceDataClient() {
    }

    static Map<String, Integer> fetchCurrencyTable() {
        URI uri = URI.create(System.getProperty("ledger.reference-data.url", "http://localhost:8091/currencies"));
        try {
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Reference data returned " + response.statusCode() + " from " + uri);
            }
            return response.body().lines()
                    .map(line -> line.split(","))
                    .collect(Collectors.toUnmodifiableMap(f -> f[0].trim(), f -> Integer.parseInt(f[1].trim())));
        } catch (IOException e) {
            throw new UncheckedIOException("Reference data unavailable at " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted fetching reference data", e);
        }
    }
}
