package com.paycore.api.v1;

import com.paycore.auth.MerchantPrincipal;
import com.paycore.common.config.OpenApiConfig;
import com.paycore.merchant.Merchant;
import com.paycore.merchant.MerchantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/account")
@Tag(name = "Account")
@SecurityRequirement(name = OpenApiConfig.MERCHANT_API_KEY)
public class AccountController {

    private final MerchantService merchantService;

    public AccountController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    public record AccountDto(String id, String name, String livemode, int feeBps, long feeFixedMinor, String apiKeyId) {
    }

    @GetMapping
    @Operation(summary = "Identify the merchant behind the presented API key")
    public AccountDto account(@AuthenticationPrincipal MerchantPrincipal principal) {
        Merchant m = merchantService.require(principal.merchantId());
        // livemode is always "test": PayCore never processes real card data.
        return new AccountDto(m.id(), m.name(), "test", m.feeBps(), m.feeFixedMinor(), principal.apiKeyId());
    }
}
