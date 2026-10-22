package com.ledger.settlement;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for the settlement arithmetic: no Spring context, no database.
 */
class SettlementServiceTest {

    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final SettlementService service = new SettlementService(repository, new BigDecimal("0.031"));

    @Test
    void labScenarioRoundsFeeDownSoMerchantIsOwed124469() {
        givenPayments("MR-4471", 128450);

        Settlement settlement = service.settlementFor("MR-4471");

        // 128450 x 0.031 = 3981.95 -> fee 3981 (rounded down), not 3982
        assertThat(settlement.totalMinor()).isEqualTo(128450);
        assertThat(settlement.feeMinor()).isEqualTo(3981);
        assertThat(settlement.owedMinor()).isEqualTo(124469);
        assertThat(settlement.currency()).isEqualTo("GBP");
    }

    @Test
    void feeIsTakenOnTheTotalNotPerPayment() {
        // Per payment: 0.31 + 0.31 + 0.31 would each round down to 0, losing the fee entirely.
        givenPayments("MR-1", 10, 10, 10, 970);

        Settlement settlement = service.settlementFor("MR-1");

        assertThat(settlement.totalMinor()).isEqualTo(1000);
        assertThat(settlement.feeMinor()).isEqualTo(31);
        assertThat(settlement.owedMinor()).isEqualTo(969);
    }

    @Test
    void tinyPaymentsCarryNoFeeBecauseFractionalPenniesRoundDown() {
        givenPayments("MR-2", 32); // 32 x 0.031 = 0.992

        assertThat(service.settlementFor("MR-2").feeMinor()).isZero();
    }

    @Test
    void unknownMerchantIsRejected() {
        when(repository.findByMerchantId("NOPE")).thenReturn(List.of());

        assertThatThrownBy(() -> service.settlementFor("NOPE"))
                .isInstanceOf(UnknownMerchantException.class)
                .hasMessageContaining("NOPE");
    }

    @Test
    void recordPaymentSavesAPaymentWithAnIdAndTimestamp() {
        when(repository.save(any(PaymentEntity.class))).thenAnswer(call -> call.getArgument(0));

        PaymentEntity saved = service.recordPayment("MR-4471", 128450, "GBP");

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getMerchantId()).isEqualTo("MR-4471");
        assertThat(saved.getAmountMinor()).isEqualTo(128450);
        assertThat(saved.getCurrency()).isEqualTo("GBP");
        assertThat(saved.getRecordedAt()).isNotNull();
    }

    private void givenPayments(String merchantId, long... amounts) {
        List<PaymentEntity> payments = Arrays.stream(amounts)
                .mapToObj(amount -> new PaymentEntity("id-" + amount, merchantId, amount, "GBP", Instant.now()))
                .toList();
        when(repository.findByMerchantId(merchantId)).thenReturn(payments);
    }
}
