package com.ledger.settlement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class SettlementService {

    private final PaymentRepository paymentRepository;
    private final BigDecimal feeRate;

    public SettlementService(PaymentRepository paymentRepository,
                             @Value("${ledger.fee-rate}") BigDecimal feeRate) {
        this.paymentRepository = paymentRepository;
        this.feeRate = feeRate;
    }

    @Transactional
    public PaymentEntity recordPayment(String merchantId, long amountMinor, String currency) {
        // PostgreSQL timestamps hold microseconds and round anything finer, so truncate here:
        // otherwise the POST response echoes a recordedAt that differs from what is stored.
        Instant recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PaymentEntity payment = new PaymentEntity(
                UUID.randomUUID().toString(), merchantId, amountMinor, currency, recordedAt);
        return paymentRepository.save(payment);
    }

    // Throws instead of returning Optional so "not found" flows through ProblemHandler
    // like every other failure, giving clients the same problem+json shape.
    @Transactional(readOnly = true)
    public PaymentEntity getPayment(String id) {
        return paymentRepository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Settlement settlementFor(String merchantId) {
        List<PaymentEntity> payments = paymentRepository.findByMerchantId(merchantId);
        if (payments.isEmpty()) {
            throw new UnknownMerchantException(merchantId);
        }

        // addExact fails loudly on overflow instead of silently wrapping a money total.
        long totalMinor = payments.stream().mapToLong(PaymentEntity::getAmountMinor).reduce(0L, Math::addExact);
        long feeMinor = feeFor(totalMinor);
        String currency = payments.getFirst().getCurrency();
        CurrencyTable.requireSupported(currency);

        return new Settlement(merchantId, totalMinor, feeMinor, totalMinor - feeMinor, currency);
    }

    /*
     * The fee is computed in BigDecimal (never double) and rounded DOWN to a whole minor unit,
     * so a fractional penny always stays with the merchant:
     * 128450 x 0.031 = 3981.95 -> fee 3981 -> owed 124469.
     */
    long feeFor(long totalMinor) {
        return BigDecimal.valueOf(totalMinor)
                .multiply(feeRate)
                .setScale(0, RoundingMode.DOWN)
                .longValueExact();
    }
}
