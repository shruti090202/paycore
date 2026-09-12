package com.paycore.api.checkout;

import com.paycore.banksim.BankGateway;
import com.paycore.banksim.TestCards;
import com.paycore.common.error.ApiError;
import com.paycore.common.error.ErrorType;
import com.paycore.common.web.CorrelationIdFilter;
import com.paycore.merchant.MerchantService;
import com.paycore.payments.CheckoutService;
import com.paycore.payments.DeclineCodes;
import com.paycore.payments.Payment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Hosted checkout, driven by the session token in the URL (no merchant credential ever reaches the browser). */
@RestController
@RequestMapping("/checkout/sessions")
@Tag(name = "Checkout (hosted page)")
public class CheckoutController {

    private final CheckoutService checkout;
    private final MerchantService merchants;

    public CheckoutController(CheckoutService checkout, MerchantService merchants) {
        this.checkout = checkout;
        this.merchants = merchants;
    }

    public record TestCardDto(String number, String brand, String description) {
    }

    public record SessionDto(String paymentId, String status, long amountMinor, String currency, String merchantName,
                             String description, String customerEmail, String successUrl, String cancelUrl,
                             String redirectUrl, Failure failure, Instant createdAt, List<TestCardDto> testCards) {
        public record Failure(String code, String message) {
        }
    }

    /** Card input. toString is overridden so the PAN can never end up in a log line by accident. */
    public record ConfirmRequest(
            @NotBlank @Size(min = 12, max = 23) String cardNumber,
            @Min(1) @Max(12) int expMonth,
            @Min(0) @Max(2099) int expYear,
            @NotBlank @Size(min = 3, max = 4) String cvc,
            @Size(max = 100) String cardholderName) {
        @Override
        public String toString() {
            return "ConfirmRequest[card=****]";
        }
    }

    public record ConfirmResponse(String result, String paymentId, String status, String redirectUrl, String message) {
    }

    @GetMapping("/{token}")
    @Operation(summary = "Load a checkout session (amount, merchant, allowed test cards)")
    public SessionDto session(@PathVariable String token) {
        Payment p = checkout.session(token);
        return toDto(p);
    }

    @PostMapping("/{token}/confirm")
    @Operation(summary = "Submit a TEST card. 200 approved, 402 declined, 202 bank timeout (poll the session).")
    public ResponseEntity<?> confirm(@PathVariable String token, @Valid @RequestBody ConfirmRequest req,
                                     HttpServletRequest http) {
        BankGateway.CardDetails card = new BankGateway.CardDetails(req.cardNumber(), req.expMonth(), req.expYear(), req.cvc());
        CheckoutService.ConfirmResult r = checkout.confirm(token, card);
        return switch (r.kind()) {
            case APPROVED -> ResponseEntity.ok(new ConfirmResponse("approved", r.payment().getId(), r.payment().getStatus(),
                    r.redirectUrl(), null));
            case PENDING -> ResponseEntity.status(HttpStatus.ACCEPTED).body(new ConfirmResponse("pending",
                    r.payment().getId(), r.payment().getStatus(), null,
                    "We are confirming the payment with your bank. This page will update automatically."));
            case DECLINED -> ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(ApiError.of(ErrorType.CARD_ERROR,
                    r.declineCode(), DeclineCodes.message(r.declineCode()), "card_number", CorrelationIdFilter.current(http)));
        };
    }

    @PostMapping("/{token}/cancel")
    @Operation(summary = "Shopper abandons checkout; returns the merchant's cancel URL")
    public ConfirmResponse cancel(@PathVariable String token) {
        Payment p = checkout.cancel(token);
        return new ConfirmResponse("canceled", p.getId(), p.getStatus(), p.getCancelUrl(), null);
    }

    private SessionDto toDto(Payment p) {
        String merchantName = merchants.require(p.getMerchantId()).name();
        List<TestCardDto> cards = TestCards.ALL.stream()
                .map(c -> new TestCardDto(c.number(), c.brand(), c.description())).toList();
        String redirect = switch (p.paymentStatus()) {
            case AUTHORIZED, CAPTURED, PARTIALLY_REFUNDED, REFUNDED -> CheckoutService.successRedirect(p);
            case CANCELED -> p.getCancelUrl();
            default -> null;
        };
        SessionDto.Failure failure = p.getFailureCode() == null ? null
                : new SessionDto.Failure(p.getFailureCode(), p.getFailureMessage());
        return new SessionDto(p.getId(), p.getStatus(), p.getAmountMinor(), p.getCurrency(), merchantName,
                p.getDescription(), p.getCustomerEmail(), p.getSuccessUrl(), p.getCancelUrl(), redirect, failure,
                p.getCreatedAt(), cards);
    }
}
