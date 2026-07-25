package com.paycore.api.dashboard;

import com.paycore.auth.MerchantPrincipal;
import com.paycore.common.config.OpenApiConfig;
import com.paycore.merchant.ApiKey;
import com.paycore.merchant.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/dashboard/api_keys")
@Tag(name = "Dashboard: API keys")
@SecurityRequirement(name = OpenApiConfig.DASHBOARD_JWT)
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    public record CreateRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record ApiKeyDto(String id, String name, String prefix, Instant createdAt, Instant lastUsedAt,
                            Instant revokedAt) {
        static ApiKeyDto from(ApiKey k) {
            return new ApiKeyDto(k.id(), k.name(), k.prefix(), k.createdAt(), k.lastUsedAt(), k.revokedAt());
        }
    }

    /** {@code key} is present only in the create response. */
    public record CreatedApiKeyDto(String id, String name, String prefix, String key, Instant createdAt) {
    }

    @GetMapping
    public List<ApiKeyDto> list(@AuthenticationPrincipal MerchantPrincipal principal) {
        return apiKeyService.list(principal.merchantId()).stream().map(ApiKeyDto::from).toList();
    }

    @PostMapping
    @Operation(summary = "Create a secret key. The full key is returned once and never again.")
    public ResponseEntity<CreatedApiKeyDto> create(@AuthenticationPrincipal MerchantPrincipal principal,
                                                   @Valid @RequestBody CreateRequest req) {
        ApiKeyService.CreatedKey created = apiKeyService.create(principal.merchantId(), req.name());
        ApiKey k = created.key();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreatedApiKeyDto(k.id(), k.name(), k.prefix(), created.plaintext(), k.createdAt()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke a key. Takes effect on the next request.")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        apiKeyService.revoke(principal.merchantId(), id);
        return ResponseEntity.noContent().build();
    }
}
