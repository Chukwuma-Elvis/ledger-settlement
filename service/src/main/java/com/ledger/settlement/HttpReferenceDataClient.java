package com.ledger.settlement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** Fetches the currency table over HTTP: GET {url}, CSV lines "code,minorUnitDigits". */
@Component
class HttpReferenceDataClient implements ReferenceDataClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final URI uri;

    HttpReferenceDataClient(@Value("${ledger.reference-data.url}") URI uri) {
        this.uri = uri;
    }

    @Override
    public Map<String, Integer> fetchCurrencyTable() {
        try {
            HttpResponse<String> response = http.send(
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
