package com.ledger.settlement;

/**
 * Read-only projection of a payment: only the two columns settlement needs. As a record (a class
 * projection) Spring Data builds it with a constructor call, so neither recorded_at (timestamp
 * conversion), managed entities, nor per-row proxies are created. An interface projection was
 * tried first and allocated more than full entities: one proxy per row (docs/gc.md).
 */
public record PaymentAmount(long amountMinor, String currency) {
}
