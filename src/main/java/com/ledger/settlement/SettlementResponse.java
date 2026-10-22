package com.ledger.settlement;

/**
 * Body of GET /payments/settlement: what a merchant is owed, in minor units.
 */
public record SettlementResponse(String merchantId, long totalMinor, long feeMinor, long owedMinor, String currency) {

    static SettlementResponse from(Settlement settlement) {
        return new SettlementResponse(settlement.merchantId(), settlement.totalMinor(), settlement.feeMinor(),
                settlement.owedMinor(), settlement.currency());
    }
}
