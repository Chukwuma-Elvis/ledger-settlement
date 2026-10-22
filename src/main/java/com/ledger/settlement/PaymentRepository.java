package com.ledger.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRepository extends JpaRepository<PaymentEntity, String> {

    // Derived query: Spring Data builds "where merchant_id = ?" from the method name.
    List<PaymentEntity> findByMerchantId(String merchantId);

    // Derived query with a record (class) projection: selects amount_minor and currency only.
    List<PaymentAmount> findAmountsByMerchantId(String merchantId);
}
