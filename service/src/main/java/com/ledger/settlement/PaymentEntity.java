package com.ledger.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/*
 * Why a mutable class and not a record:
 * Hibernate creates entities by calling a no-argument constructor through reflection and
 * then writing each field as it reads a row. It also subclasses entities to build lazy
 * proxies and tracks field changes for dirty checking. A record is final, has only its
 * canonical constructor and only final fields, so Hibernate can neither instantiate,
 * populate nor proxy it. Records stay at the API edge as request and response DTOs.
 */
@Entity
@Table(name = "payments")
public class PaymentEntity {

    @Id
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    // Whole minor units (pence, cents): integers are exact, floating point is not.
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    // Required by JPA; protected so application code uses the full constructor.
    protected PaymentEntity() {
    }

    public PaymentEntity(String id, String merchantId, long amountMinor, String currency, Instant recordedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.recordedAt = recordedAt;
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
