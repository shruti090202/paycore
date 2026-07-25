package com.paycore.api.dashboard;

import com.paycore.auth.MerchantPrincipal;
import com.paycore.common.config.OpenApiConfig;
import com.paycore.merchant.MerchantService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dashboard")
@Tag(name = "Dashboard: account")
@SecurityRequirement(name = OpenApiConfig.DASHBOARD_JWT)
public class MeController {

    private final MerchantService merchantService;

    public MeController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @GetMapping("/me")
    public MerchantDto me(@AuthenticationPrincipal MerchantPrincipal principal) {
        return MerchantDto.from(merchantService.require(principal.merchantId()));
    }
}
