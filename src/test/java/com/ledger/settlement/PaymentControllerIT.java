package com.ledger.settlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end over real HTTP against a real PostgreSQL in Docker:
 * Tomcat -> validation -> SettlementService -> JPA -> Flyway-created schema -> back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaymentControllerIT {

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
