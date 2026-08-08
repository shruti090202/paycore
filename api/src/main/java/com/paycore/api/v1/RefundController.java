package com.paycore.api.v1;

import com.paycore.api.dto.RefundDto;
import com.paycore.auth.MerchantPrincipal;
import com.paycore.common.config.OpenApiConfig;
import com.paycore.payments.PaymentService;
import com.paycore.payments.Refund;
import com.paycore.payments.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1")
@Tag(name = "Refunds")
@SecurityRequirement(name = OpenApiConfig.MERCHANT_API_KEY)
public class RefundController {

    private final RefundService refundService;
    private final PaymentService paymentService;

    public RefundController(RefundService refundService, PaymentService paymentService) {
        this.refundService = refundService;
        this.paymentService = paymentService;
    }

    public record CreateRefundRequest(@Min(1) Long amountMinor, @Size(max = 500) String reason) {
    }

    @PostMapping("/payments/{id}/refunds")
    @Operation(summary = "Refund a captured payment. Omit amount_minor to refund everything still refundable.",
            description = "201 with status succeeded/failed, or status pending when the bank timed out (resolved by the status-check job).")
    public ResponseEntity<RefundDto> create(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id,
                                            @Valid @RequestBody(required = false) CreateRefundRequest req) {
        Refund r = refundService.create(principal.merchantId(), id,
                req == null ? null : req.amountMinor(), req == null ? null : req.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(RefundDto.from(r));
    }

    @GetMapping("/payments/{id}/refunds")
    public List<RefundDto> forPayment(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        paymentService.require(principal.merchantId(), id);
        return refundService.forPayment(id).stream().map(RefundDto::from).toList();
    }

    @GetMapping("/refunds/{id}")
    public RefundDto get(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        return RefundDto.from(refundService.require(principal.merchantId(), id));
    }
}
