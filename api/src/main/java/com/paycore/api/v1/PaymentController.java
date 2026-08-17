package com.paycore.api.v1;

import com.paycore.api.dto.LedgerDtos;
import com.paycore.api.dto.PageDto;
import com.paycore.payments.PaymentDto;
import com.paycore.auth.MerchantPrincipal;
import com.paycore.common.config.OpenApiConfig;
import com.paycore.common.config.PayCoreProperties;
import com.paycore.common.error.PayCoreException;
import com.paycore.ledger.LedgerService;
import com.paycore.payments.CaptureMethod;
import com.paycore.payments.Payment;
import com.paycore.payments.PaymentQueries;
import com.paycore.payments.PaymentService;
import com.paycore.payments.PaymentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/payments")
@Tag(name = "Payments")
@SecurityRequirement(name = OpenApiConfig.MERCHANT_API_KEY)
public class PaymentController {

    private final PaymentService paymentService;
    private final LedgerService ledgerService;
    private final String checkoutBaseUrl;

    public PaymentController(PaymentService paymentService, LedgerService ledgerService, PayCoreProperties props) {
        this.paymentService = paymentService;
        this.ledgerService = ledgerService;
        this.checkoutBaseUrl = props.checkoutBaseUrl();
    }

    public record CreatePaymentRequest(
            @NotNull @Min(1) @Max(100_000_000_000L) Long amountMinor,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO code") String currency,
            @Pattern(regexp = "automatic|manual") String captureMethod,
            @Size(max = 500) String description,
            @Valid Customer customer,
            @Pattern(regexp = "https?://.*", message = "must be an http(s) URL") @Size(max = 2000) String successUrl,
            @Pattern(regexp = "https?://.*", message = "must be an http(s) URL") @Size(max = 2000) String cancelUrl,
            @Size(max = 20) Map<@Size(max = 40) String, @Size(max = 500) String> metadata) {

        public record Customer(@Email @Size(max = 254) String email, @Size(max = 100) String ref) {
        }
    }

    public record CaptureRequest(@Min(1) Long amountMinor) {
    }

    @PostMapping
    @Operation(summary = "Create a payment and get a hosted checkout URL",
            description = "Amounts are integer minor units (paise/cents). Optional `Idempotency-Key` header.")
    public ResponseEntity<PaymentDto> create(@AuthenticationPrincipal MerchantPrincipal principal,
                                             @Valid @RequestBody CreatePaymentRequest req) {
        Payment p = paymentService.create(new PaymentService.CreateCommand(
                principal.merchantId(), req.amountMinor(), req.currency(),
                req.captureMethod() == null ? CaptureMethod.AUTOMATIC : CaptureMethod.fromWire(req.captureMethod()),
                req.description(),
                req.customer() == null ? null : req.customer().email(),
                req.customer() == null ? null : req.customer().ref(),
                req.successUrl(), req.cancelUrl(), req.metadata()));
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentDto.from(p, checkoutBaseUrl));
    }

    @GetMapping("/{id}")
    public PaymentDto get(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        return PaymentDto.from(paymentService.require(principal.merchantId(), id), checkoutBaseUrl);
    }

    @GetMapping
    @Operation(summary = "List payments, newest first, with cursor pagination")
    public PageDto<PaymentDto> list(@AuthenticationPrincipal MerchantPrincipal principal,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) @Parameter(description = "ISO-8601 instant") Instant createdAfter,
                                    @RequestParam(required = false) @Parameter(description = "ISO-8601 instant") Instant createdBefore,
                                    @RequestParam(required = false) String customerEmail,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "20") int limit) {
        PaymentStatus st = null;
        if (status != null && !status.isBlank()) {
            try {
                st = PaymentStatus.fromWire(status);
            } catch (IllegalArgumentException e) {
                throw PayCoreException.invalid("status_invalid", "Unknown status '" + status + "'", "status");
            }
        }
        PaymentQueries.Page page = paymentService.list(new PaymentQueries.Filter(
                principal.merchantId(), st, createdAfter, createdBefore, customerEmail, cursor, limit));
        List<PaymentDto> data = page.items().stream().map(p -> PaymentDto.from(p, checkoutBaseUrl)).toList();
        return PageDto.of(data, page.nextCursor());
    }

    @PostMapping("/{id}/capture")
    @Operation(summary = "Capture an authorized payment (manual capture). Omit amount_minor for the full amount.")
    public PaymentDto capture(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id,
                              @Valid @RequestBody(required = false) CaptureRequest req) {
        Long amount = req == null ? null : req.amountMinor();
        return PaymentDto.from(paymentService.capture(principal.merchantId(), id, amount), checkoutBaseUrl);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a payment before capture (voids an authorization)")
    public PaymentDto cancel(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        return PaymentDto.from(paymentService.cancel(principal.merchantId(), id), checkoutBaseUrl);
    }

    @GetMapping("/{id}/ledger")
    @Operation(summary = "Journal entries and postings recorded for this payment")
    public List<LedgerDtos.JournalEntryDto> ledger(@AuthenticationPrincipal MerchantPrincipal principal,
                                                   @PathVariable String id) {
        paymentService.require(principal.merchantId(), id); // ownership check
        return ledgerService.entriesFor(LedgerService.REF_PAYMENT, id).stream().map(LedgerDtos::from).toList();
    }
}
