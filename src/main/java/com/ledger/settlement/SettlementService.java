package com.ledger.settlement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
        PaymentEntity payment = new PaymentEntity(
                UUID.randomUUID().toString(), merchantId, amountMinor, currency, Instant.now());
        return paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public Optional<PaymentEntity> findPayment(String id) {
        return paymentRepository.findById(id);
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
