package com.ledger.settlement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends one request through the full request path as soon as the web server is up, before the
 * application reports itself started. Class loading, Spring MVC's DispatcherServlet setup,
 * Hibernate's first query compilation and JSON rendering then happen once, sequentially, instead
 * of under the first burst of real traffic, where many request threads contend for the same
 * class-loading locks.
 *
 * The request asks for a merchant that cannot exist, so it stops at "unknown merchant" (404) and
 * never touches downstream dependencies.
 */
@Component
@ConditionalOnProperty(name = "ledger.warm-up.enabled", matchIfMissing = true)
class StartupWarmUp implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupWarmUp.class);
    static final String WARM_UP_MERCHANT = "__warm-up__";

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        URI uri = URI.create("http://localhost:" + event.getWebServer().getPort()
                + "/payments/settlement?merchantId=" + WARM_UP_MERCHANT);
        long start = System.nanoTime();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.discarding());
            log.info("Warm-up request answered {} in {} ms", response.statusCode(), (System.nanoTime() - start) / 1_000_000);
        } catch (Exception e) {
            // Warm-up is an optimisation: never block startup on it.
            log.warn("Warm-up request failed: {}", e.toString());
        }
    }
}
