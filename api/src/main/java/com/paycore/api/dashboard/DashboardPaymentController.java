package com.paycore.api.dashboard;

import com.paycore.api.dto.LedgerDtos;
import com.paycore.api.dto.PageDto;
import com.paycore.payments.PaymentDto;
import com.paycore.payments.RefundDto;
import com.paycore.auth.MerchantPrincipal;
import com.paycore.common.config.OpenApiConfig;
import com.paycore.common.config.PayCoreProperties;
import com.paycore.common.error.PayCoreException;
import com.paycore.ledger.LedgerService;
import com.paycore.payments.Payment;
import com.paycore.payments.PaymentQueries;
import com.paycore.payments.PaymentService;
import com.paycore.payments.PaymentStatus;
import com.paycore.payments.BankAttempt;
import com.paycore.payments.BankAttemptRepository;
import com.paycore.payments.RefundService;
import com.paycore.risk.RiskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Dashboard views of payments: same data as /v1 plus the timeline and ledger postings in one call. */
@RestController
@RequestMapping("/dashboard")
@Tag(name = "Dashboard: payments")
@SecurityRequirement(name = OpenApiConfig.DASHBOARD_JWT)
public class DashboardPaymentController {

    private final PaymentService paymentService;
    private final LedgerService ledgerService;
    private final RefundService refundService;
    private final BankAttemptRepository bankAttempts;
    private final RiskService risk;
    private final String checkoutBaseUrl;

    public DashboardPaymentController(PaymentService paymentService, LedgerService ledgerService,
                                      RefundService refundService, BankAttemptRepository bankAttempts,
                                      RiskService risk, PayCoreProperties props) {
        this.paymentService = paymentService;
        this.ledgerService = ledgerService;
        this.refundService = refundService;
        this.bankAttempts = bankAttempts;
        this.risk = risk;
        this.checkoutBaseUrl = props.checkoutBaseUrl();
    }

    public record EventDto(String id, String type, String fromStatus, String toStatus, Map<String, Object> data,
                           Instant createdAt) {
    }

    public record BankAttemptDto(String id, String kind, String bankRef, long amountMinor, String outcome, String declineCode,
                                 Integer latencyMs, String resolution, Instant createdAt, Instant resolvedAt) {
        static BankAttemptDto from(BankAttempt a) {
            return new BankAttemptDto(a.id(), a.kind(), a.bankRef(), a.amountMinor(), a.outcome(), a.declineCode(),
                    a.latencyMs(), a.resolution(), a.createdAt(), a.resolvedAt());
        }
    }

    public record RiskDto(int score, String decision, Object reasons) {
    }

    public record PaymentDetail(PaymentDto payment, List<EventDto> events, List<LedgerDtos.JournalEntryDto> ledger,
                                List<RefundDto> refunds, List<BankAttemptDto> bankAttempts, RiskDto risk,
                                String cardFingerprint) {
    }

    public record RefundRequest(@Min(1) Long amountMinor, @Size(max = 500) String reason) {
    }

    @GetMapping("/payments")
    public PageDto<PaymentDto> list(@AuthenticationPrincipal MerchantPrincipal principal,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) Instant createdAfter,
                                    @RequestParam(required = false) Instant createdBefore,
                                    @RequestParam(required = false) String customerEmail,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "25") int limit) {
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
        return PageDto.of(page.items().stream().map(p -> PaymentDto.from(p, checkoutBaseUrl)).toList(), page.nextCursor());
    }

    @GetMapping("/payments/{id}")
    public PaymentDetail detail(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        Payment p = paymentService.require(principal.merchantId(), id);
        List<EventDto> events = paymentService.timeline(p.getId()).stream()
                .map(e -> new EventDto(e.id(), e.type(), e.fromStatus(), e.toStatus(),
                        e.data() == null ? Map.of() : e.data().asMap(), e.createdAt()))
                .toList();
        List<LedgerDtos.JournalEntryDto> ledger = ledgerService.entriesFor(LedgerService.REF_PAYMENT, p.getId())
                .stream().map(LedgerDtos::from).toList();
        List<RefundDto> refunds = refundService.forPayment(p.getId()).stream().map(RefundDto::from).toList();
        List<BankAttemptDto> attempts = bankAttempts.findByPaymentIdOrderByIdAsc(p.getId()).stream().map(BankAttemptDto::from).toList();
        RiskDto riskDto = risk.decisionFor(p.getId(), p.getMerchantId())
                .map(r -> new RiskDto(r.score(), r.decision(), r.reasons().node())).orElse(null);
        return new PaymentDetail(PaymentDto.from(p, checkoutBaseUrl), events, ledger, refunds, attempts, riskDto,
                p.getCardFingerprint());
    }

    @PostMapping("/payments/{id}/refunds")
    public ResponseEntity<RefundDto> refund(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id,
                                            @Valid @RequestBody(required = false) RefundRequest req) {
        var r = refundService.create(principal.merchantId(), id, req == null ? null : req.amountMinor(),
                req == null ? null : req.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(RefundDto.from(r));
    }

    @PostMapping("/payments/{id}/capture")
    public PaymentDto capture(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        return PaymentDto.from(paymentService.capture(principal.merchantId(), id, null), checkoutBaseUrl);
    }

    @PostMapping("/payments/{id}/cancel")
    public PaymentDto cancel(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        return PaymentDto.from(paymentService.cancel(principal.merchantId(), id), checkoutBaseUrl);
    }

    @GetMapping("/balance")
    public List<LedgerDtos.BalanceDto> balance(@AuthenticationPrincipal MerchantPrincipal principal) {
        return ledgerService.merchantBalances(principal.merchantId()).stream()
                .map(b -> new LedgerDtos.BalanceDto(b.code(), b.type(), b.currency(), b.balanceMinor(), b.postingCount()))
                .toList();
    }
}
