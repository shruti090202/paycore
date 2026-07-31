package com.paycore.api.v1;

import com.paycore.api.dto.LedgerDtos;
import com.paycore.auth.MerchantPrincipal;
import com.paycore.common.config.OpenApiConfig;
import com.paycore.ledger.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/balance")
@Tag(name = "Balance")
@SecurityRequirement(name = OpenApiConfig.MERCHANT_API_KEY)
public class BalanceController {

    private final LedgerService ledgerService;

    public BalanceController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    public record BalanceResponse(String object, List<LedgerDtos.BalanceDto> available) {
    }

    @GetMapping
    @Operation(summary = "Merchant balance per currency, derived from the ledger (merchant_payable accounts)")
    public BalanceResponse balance(@AuthenticationPrincipal MerchantPrincipal principal) {
        List<LedgerDtos.BalanceDto> rows = ledgerService.merchantBalances(principal.merchantId()).stream()
                .map(b -> new LedgerDtos.BalanceDto(b.code(), b.type(), b.currency(), b.balanceMinor(), b.postingCount()))
                .toList();
        return new BalanceResponse("balance", rows);
    }
}
