package com.paycore.api.dashboard;

import com.paycore.api.dto.PageDto;
import com.paycore.auth.MerchantPrincipal;
import com.paycore.common.config.OpenApiConfig;
import com.paycore.common.error.PayCoreException;
import com.paycore.risk.BlocklistRepository;
import com.paycore.risk.RiskDecisionRepository;
import com.paycore.risk.RiskService;
import com.paycore.risk.RuleConfig;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dashboard/risk")
@Tag(name = "Dashboard: risk")
@SecurityRequirement(name = OpenApiConfig.DASHBOARD_JWT)
public class DashboardRiskController {

    private final RiskService risk;

    public DashboardRiskController(RiskService risk) {
        this.risk = risk;
    }

    public record RuleDto(String type, String name, Map<String, Object> params, int weight, boolean enabled, boolean overridden) {
        static RuleDto from(RuleConfig r) {
            return new RuleDto(r.type(), r.name(), r.params(), r.weight(), r.enabled(), r.isOverride());
        }
    }

    public record RuleUpdate(Map<String, Object> params, Integer weight, Boolean enabled) {
    }

    public record DecisionDto(String id, String paymentId, int score, String decision, List<Map<String, Object>> reasons,
                              Instant evaluatedAt) {
        @SuppressWarnings("unchecked")
        static DecisionDto from(RiskDecisionRepository.Row r) {
            Object reasons = r.reasons().node().isArray() ? new tools.jackson.databind.ObjectMapper().convertValue(r.reasons().node(), List.class) : List.of();
            return new DecisionDto(r.id(), r.paymentId(), r.score(), r.decision(), (List<Map<String, Object>>) reasons, r.evaluatedAt());
        }
    }

    public record BlockRequest(@NotBlank String kind, @NotBlank @Size(max = 254) String value, @Size(max = 200) String reason) {
    }

    public record BlockEntryDto(String id, String kind, String valueHash, String reason, Instant createdAt) {
        static BlockEntryDto from(BlocklistRepository.Entry e) {
            return new BlockEntryDto(e.id(), e.kind(), e.valueHash(), e.reason(), e.createdAt());
        }
    }

    @GetMapping("/decisions")
    public PageDto<DecisionDto> decisions(@AuthenticationPrincipal MerchantPrincipal principal,
                                          @RequestParam(required = false) String decision,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(defaultValue = "25") int limit) {
        int lim = Math.max(1, Math.min(limit, 100));
        List<RiskDecisionRepository.Row> rows = risk.decisions(principal.merchantId(), decision, cursor, lim + 1);
        String next = rows.size() > lim ? rows.get(lim - 1).id() : null;
        return PageDto.of(rows.stream().limit(lim).map(DecisionDto::from).toList(), next);
    }

    @GetMapping("/decisions/{paymentId}")
    public DecisionDto decision(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String paymentId) {
        return risk.decisionFor(paymentId, principal.merchantId()).map(DecisionDto::from)
                .orElseThrow(() -> PayCoreException.notFound("risk_decision", paymentId));
    }

    @GetMapping("/rules")
    @Operation(summary = "Effective rules for this merchant (global defaults with your overrides applied)")
    public List<RuleDto> rules(@AuthenticationPrincipal MerchantPrincipal principal) {
        return risk.rulesFor(principal.merchantId()).stream().map(RuleDto::from).toList();
    }

    @PutMapping("/rules/{type}")
    @Operation(summary = "Override a rule's params, weight or enabled flag for this merchant")
    public List<RuleDto> update(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String type,
                                @RequestBody RuleUpdate update) {
        return risk.overrideRule(principal.merchantId(), type, update.params(), update.weight(), update.enabled())
                .stream().map(RuleDto::from).toList();
    }

    @DeleteMapping("/rules/{type}")
    @Operation(summary = "Remove the override and fall back to the global default")
    public List<RuleDto> reset(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String type) {
        return risk.resetRule(principal.merchantId(), type).stream().map(RuleDto::from).toList();
    }

    @GetMapping("/blocklist")
    public List<BlockEntryDto> blocklist(@AuthenticationPrincipal MerchantPrincipal principal) {
        return risk.blocklist(principal.merchantId()).stream().map(BlockEntryDto::from).toList();
    }

    @PostMapping("/blocklist")
    @Operation(summary = "Block a card fingerprint (fp_...) or an email (stored hashed)")
    public ResponseEntity<BlockEntryDto> block(@AuthenticationPrincipal MerchantPrincipal principal,
                                               @Valid @RequestBody BlockRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BlockEntryDto.from(risk.block(principal.merchantId(), req.kind(), req.value(), req.reason())));
    }

    @DeleteMapping("/blocklist/{id}")
    public ResponseEntity<Void> unblock(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        risk.unblock(principal.merchantId(), id);
        return ResponseEntity.noContent().build();
    }
}
