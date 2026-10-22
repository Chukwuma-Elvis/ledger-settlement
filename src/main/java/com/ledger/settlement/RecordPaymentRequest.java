package com.ledger.settlement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Body of POST /payments. A missing amountMinor binds to 0, which @Positive rejects.
 */
public record RecordPaymentRequest(
        @NotBlank String merchantId,
        @Positive long amountMinor,
        @NotBlank String currency) {
}
