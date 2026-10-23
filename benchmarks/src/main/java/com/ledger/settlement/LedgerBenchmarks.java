package com.ledger.settlement;

import jakarta.servlet.http.HttpServletRequest;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.core.MethodParameter;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * Microbenchmarks of the Ledger settlement service's own classes (this module depends on the
 * service jar; nothing here is a copy). In package com.ledger.settlement so package-private
 * constructors such as CurrencyTable's can be called directly.
 *
 * Forks matter because each fork is a fresh JVM: one run's JIT compilation decisions, profile
 * pollution and heap layout cannot skew the others, and fork-to-fork variation shows up in the error.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(3)
public class LedgerBenchmarks {

    /** All inputs live here, built at run time in @Setup, so the JIT cannot constant-fold them. */
    @State(Scope.Benchmark)
    public static class Inputs {
        String merchantId;
        PaymentEntity payment;
        SettlementService settlementService;
        ProblemHandler problemHandler;
        MethodArgumentNotValidException invalidPayment;
        HttpServletRequest request;

        @Setup
        public void setUp() throws Exception {
            merchantId = "MR-LOAD";
            // Same shape as the MR-LOAD seed data (perf/seed-load-merchant.sql): 500 GBP payments
            // with amounts 1000 + (i * 37) % 9000 minor units.
            List<PaymentAmount> amounts = IntStream.rangeClosed(1, 500)
                    .mapToObj(i -> new PaymentAmount(1000 + (i * 37L) % 9000, "GBP"))
                    .toList();
            payment = new PaymentEntity(UUID.randomUUID().toString(), merchantId, 128450, "GBP", Instant.now());

            // The real SettlementService and CurrencyTable; only the repository (a database) is stubbed.
            PaymentRepository repository = (PaymentRepository) Proxy.newProxyInstance(
                    PaymentRepository.class.getClassLoader(), new Class<?>[] {PaymentRepository.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("findAmountsByMerchantId")) {
                            return amounts;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            settlementService = new SettlementService(repository, new BigDecimal("0.031"),
                    new CurrencyTable(() -> Map.of("GBP", 2, "EUR", 2, "USD", 2, "JPY", 0, "KWD", 3)));

            // A POST /payments with a blank merchantId and a negative amount, as Spring MVC reports it.
            RecordPaymentRequest body = new RecordPaymentRequest("", -128450, "GBP");
            BeanPropertyBindingResult errors = new BeanPropertyBindingResult(body, "recordPaymentRequest");
            errors.addError(new FieldError("recordPaymentRequest", "merchantId", "", false,
                    new String[] {"NotBlank"}, null, "must not be blank"));
            errors.addError(new FieldError("recordPaymentRequest", "amountMinor", -128450L, false,
                    new String[] {"Positive"}, null, "must be greater than 0"));
            invalidPayment = new MethodArgumentNotValidException(new MethodParameter(
                    PaymentController.class.getMethod("recordPayment", RecordPaymentRequest.class), 0), errors);
            problemHandler = new ProblemHandler();
            request = new MockHttpServletRequest("POST", "/payments");
        }
    }

    // --- 1. Mapping: PaymentResponse.from, on every POST /payments and GET /payments/{id} --------

    /**
     * DELIBERATELY BROKEN (dead-code elimination demo, kept so the error can be reproduced): the
     * result is computed and thrown away, so the JIT can prove it is unused and remove the work.
     */
    @Benchmark
    public void mappingBroken_resultDiscarded(Inputs in) {
        PaymentResponse.from(in.payment);
    }

    /** Fix 1: return the result; JMH consumes it, so the work must happen. */
    @Benchmark
    public PaymentResponse mapping_returned(Inputs in) {
        return PaymentResponse.from(in.payment);
    }

    /** Fix 2: consume it with a Blackhole, for when a benchmark produces more than one value. */
    @Benchmark
    public void mapping_blackhole(Inputs in, Blackhole bh) {
        bh.consume(PaymentResponse.from(in.payment));
    }

    // --- 2. String- and collection-heavy: the problem detail for a rejected payment --------------

    @Benchmark
    public ProblemDetail validationProblemDetail(Inputs in) {
        return in.problemHandler.handleValidation(in.invalidPayment, in.request);
    }

    // --- 3. Settlement calculation: 500 payments, long minor units, BigDecimal fee ---------------

    @Benchmark
    public Settlement settlement500Payments(Inputs in) {
        return in.settlementService.settlementFor(in.merchantId);
    }
}
