package com.ledger.settlement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private final SettlementService settlementService;

    public PaymentController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> recordPayment(@Valid @RequestBody RecordPaymentRequest request) {
        PaymentEntity payment = settlementService.recordPayment(
                request.merchantId(), request.amountMinor(), request.currency());

        // 201 Created with Location: <this request's URL>/{id}
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(payment.getId())
                .toUri();
        return ResponseEntity.created(location).body(PaymentResponse.from(payment));
    }

    // Ids are UUIDs (see SettlementService.recordPayment); anything else is rejected with a
    // 400 problem detail before it reaches the database.
    @GetMapping("/{id}")
    public PaymentResponse getPayment(@PathVariable @Pattern(regexp = UUID_PATTERN, message = "must be a UUID") String id) {
        return PaymentResponse.from(settlementService.getPayment(id));
    }

    @GetMapping("/settlement")
    public SettlementResponse settlement(@RequestParam String merchantId) {
        return SettlementResponse.from(settlementService.settlementFor(merchantId));
    }
}
