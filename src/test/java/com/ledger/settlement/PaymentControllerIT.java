package com.ledger.settlement;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end over real HTTP against a real PostgreSQL in Docker:
 * Tomcat -> validation -> SettlementService -> JPA -> Flyway-created schema -> back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaymentControllerIT {

    // The CurrencyTable bean fetches the currency table over HTTP at startup.
    @DynamicPropertySource
    static void referenceData(DynamicPropertyRegistry registry) {
        registry.add("ledger.reference-data.url", ReferenceDataTestServer::start);
    }

    // @ServiceConnection points spring.datasource.* at this container, so no URL, user or
    // password needs configuring; static means one container shared by every test here.
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @LocalServerPort
    private int port;

    @Autowired
    private PaymentRepository paymentRepository;

    private RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port).build();
        paymentRepository.deleteAll();
    }

    @Test
    void postedPaymentSettlesTo124469MinorUnitsForMR4471() {
        ResponseEntity<PaymentResponse> created = http.post()
                .uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RecordPaymentRequest("MR-4471", 128450, "GBP"))
                .retrieve()
                .toEntity(PaymentResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).hasPath("/payments/" + created.getBody().id());

        SettlementResponse settlement = http.get()
                .uri("/payments/settlement?merchantId={id}", "MR-4471")
                .retrieve()
                .body(SettlementResponse.class);

        assertThat(settlement.owedMinor()).isEqualTo(124469);
        assertThat(settlement.totalMinor()).isEqualTo(128450);
        assertThat(settlement.feeMinor()).isEqualTo(3981);
        assertThat(settlement.currency()).isEqualTo("GBP");
    }

    @Test
    void locationHeaderFromPostResolvesToTheSamePayment() {
        ResponseEntity<PaymentResponse> created = http.post()
                .uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RecordPaymentRequest("MR-4471", 128450, "GBP"))
                .retrieve()
                .toEntity(PaymentResponse.class);

        PaymentResponse fetched = http.get()
                .uri(created.getHeaders().getLocation())
                .retrieve()
                .body(PaymentResponse.class);

        assertThat(fetched).isEqualTo(created.getBody());
    }

    @Test
    void requestsAreHandledOnVirtualThreads() {
        http.get().uri("/payments/settlement?merchantId={id}", "MR-NONE").retrieve()
                .onStatus(status -> true, (request, response) -> { }).toBodilessEntity();

        assertThat(RequestThreadProbe.lastRequestWasVirtual).isTrue();
    }

    @Test
    void applicationTaskExecutorRunsTasksOnVirtualThreads(
            @Autowired @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor) throws Exception {
        assertThat(executor.submit(() -> Thread.currentThread().isVirtual()).get()).isTrue();
    }

    /** Records whether the thread that served the last HTTP request was a virtual thread. */
    @TestConfiguration
    static class RequestThreadProbe {
        static volatile boolean lastRequestWasVirtual;

        @Bean
        OncePerRequestFilter requestThreadProbe() {
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                FilterChain chain) throws ServletException, IOException {
                    lastRequestWasVirtual = Thread.currentThread().isVirtual();
                    chain.doFilter(request, response);
                }
            };
        }
    }

    @Test
    void postedPaymentIsActuallyPersistedInPostgres() {
        http.post()
                .uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RecordPaymentRequest("MR-4471", 128450, "GBP"))
                .retrieve()
                .toBodilessEntity();

        assertThat(paymentRepository.findByMerchantId("MR-4471"))
                .singleElement()
                .satisfies(payment -> {
                    assertThat(payment.getAmountMinor()).isEqualTo(128450);
                    assertThat(payment.getCurrency()).isEqualTo("GBP");
                    assertThat(payment.getRecordedAt()).isNotNull();
                });
    }
}
