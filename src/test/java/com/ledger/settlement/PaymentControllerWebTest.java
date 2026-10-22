package com.ledger.settlement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Web-layer slice: controller + ProblemHandler only, service mocked, no database.
 * Pins the HTTP contract: status codes, Location header and the problem+json error shape.
 */
@WebMvcTest(PaymentController.class)
class PaymentControllerWebTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private SettlementService settlementService;

    @Test
    void validPostReturns201WithLocation() {
        when(settlementService.recordPayment(anyString(), anyLong(), anyString()))
                .thenReturn(new PaymentEntity("abc-123", "MR-4471", 128450, "GBP", Instant.now()));

        MvcTestResult result = post("{\"merchantId\":\"MR-4471\",\"amountMinor\":128450,\"currency\":\"GBP\"}");

        assertThat(result).hasStatus(201).hasHeader("Location", "http://localhost/payments/abc-123");
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo("abc-123");
    }

    @Test
    void negativeAmountIsAProblemDetail() {
        MvcTestResult result = post("{\"merchantId\":\"MR-4471\",\"amountMinor\":-5,\"currency\":\"GBP\"}");

        assertProblem(result, 400, "/problems/validation-error", "Invalid request", "/payments");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("amountMinor");
        verifyNoInteractions(settlementService);
    }

    @Test
    void blankMerchantIdIsAProblemDetail() {
        MvcTestResult result = post("{\"merchantId\":\"\",\"amountMinor\":100,\"currency\":\"GBP\"}");

        assertProblem(result, 400, "/problems/validation-error", "Invalid request", "/payments");
        assertThat(result).bodyJson().extractingPath("$.errors.merchantId").isEqualTo("must not be blank");
    }

    @Test
    void unknownMerchantIs404ProblemDetail() {
        when(settlementService.settlementFor("NOBODY")).thenThrow(new UnknownMerchantException("NOBODY"));

        MvcTestResult result = mvc.get().uri("/payments/settlement").param("merchantId", "NOBODY").exchange();

        assertProblem(result, 404, "/problems/unknown-merchant", "Unknown merchant", "/payments/settlement");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("NOBODY");
    }

    @Test
    void missingMerchantIdParamIsAlsoAProblemDetail() {
        MvcTestResult result = mvc.get().uri("/payments/settlement").exchange();

        assertThat(result).hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/payments/settlement");
    }

    @Test
    void malformedJsonIsAProblemDetail() {
        MvcTestResult result = post("{not json");

        assertProblem(result, 400, "/problems/malformed-body", "Malformed request body", "/payments");
    }

    @Test
    void getPaymentByIdReturnsTheResponseRecord() {
        String id = "0fbdf7d6-0be8-43bd-ac3a-9194072bbc7a";
        when(settlementService.getPayment(id))
                .thenReturn(new PaymentEntity(id, "MR-4471", 128450, "GBP", Instant.now()));

        MvcTestResult result = mvc.get().uri("/payments/{id}", id).exchange();

        assertThat(result).hasStatus(200).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(id);
        assertThat(result).bodyJson().extractingPath("$.amountMinor").isEqualTo(128450);
    }

    // Agent Review defect 4: the generated endpoint returned 404 with an empty body.
    @Test
    void missingPaymentIs404ProblemDetailNotAnEmptyBody() {
        String id = "00000000-0000-0000-0000-000000000000";
        when(settlementService.getPayment(id)).thenThrow(new PaymentNotFoundException(id));

        MvcTestResult result = mvc.get().uri("/payments/{id}", id).exchange();

        assertProblem(result, 404, "/problems/payment-not-found", "Payment not found", "/payments/" + id);
    }

    // Agent Review defect 3: the generated endpoint had no constraint on the path variable.
    @Test
    void nonUuidPaymentIdIs400ProblemDetailAndNeverReachesTheService() {
        MvcTestResult result = mvc.get().uri("/payments/not-a-uuid").exchange();

        assertProblem(result, 400, "/problems/validation-error", "Invalid request", "/payments/not-a-uuid");
        assertThat(result).bodyJson().extractingPath("$.errors.id").isEqualTo("must be a UUID");
        verifyNoInteractions(settlementService);
    }

    private MvcTestResult post(String json) {
        return mvc.post().uri("/payments").contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private void assertProblem(MvcTestResult result, int status, String type, String title, String instance) {
        assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(type);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo(title);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().isNotBlank();
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo(instance);
    }
}
