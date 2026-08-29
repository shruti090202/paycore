package com.paycore.api.dashboard;

import com.paycore.auth.MerchantPrincipal;
import com.paycore.common.config.OpenApiConfig;
import com.paycore.common.error.PayCoreException;
import com.paycore.reconciliation.PayoutService;
import com.paycore.reconciliation.ReconciliationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

/**
 * Reconciliation is a platform activity, but each merchant sees the exceptions that concern its payments and
 * its own payouts. Runs (files, totals) are visible read-only to every merchant.
 */
@RestController
@RequestMapping("/dashboard/reconciliation")
@Tag(name = "Dashboard: reconciliation")
@SecurityRequirement(name = OpenApiConfig.DASHBOARD_JWT)
public class DashboardReconciliationController {

    private final ReconciliationRepository repo;
    private final PayoutService payouts;
    private final Clock clock;

    public DashboardReconciliationController(ReconciliationRepository repo, PayoutService payouts, Clock clock) {
        this.repo = repo;
        this.payouts = payouts;
        this.clock = clock;
    }

    public record ResolveRequest(@NotBlank @Size(max = 500) String resolution) {
    }

    @GetMapping("/runs")
    public List<ReconciliationRepository.Run> runs() {
        return repo.runs(50);
    }

    @GetMapping("/runs/{id}")
    public ReconciliationRepository.Run run(@PathVariable String id) {
        return repo.run(id).orElseThrow(() -> PayCoreException.notFound("reconciliation_run", id));
    }

    @GetMapping("/files")
    public List<ReconciliationRepository.SettlementFile> files() {
        return repo.files(30).stream().map(f -> new ReconciliationRepository.SettlementFile(f.id(), f.settlementDate(),
                f.rowCount(), f.totalMinor(), f.currency(), null, f.generatedAt())).toList();
    }

    @GetMapping(value = "/files/{id}/csv", produces = "text/csv")
    public String csv(@PathVariable String id) {
        return repo.file(id).orElseThrow(() -> PayCoreException.notFound("settlement_file", id)).csv();
    }

    @GetMapping("/items")
    @Operation(summary = "Exceptions touching this merchant's payments (open by default)")
    public List<ReconciliationRepository.Item> items(@AuthenticationPrincipal MerchantPrincipal principal,
                                                     @RequestParam(defaultValue = "open") String status) {
        return repo.itemsForMerchant(principal.merchantId(), "all".equals(status) ? null : status, 200);
    }

    @PostMapping("/items/{id}/resolve")
    public List<ReconciliationRepository.Item> resolve(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id,
                                                       @Valid @RequestBody ResolveRequest req) {
        if (repo.resolveItem(id, principal.merchantId(), req.resolution(), clock.instant()) == 0) {
            throw PayCoreException.notFound("reconciliation_item", id);
        }
        return repo.itemsForMerchant(principal.merchantId(), "open", 200);
    }

    @GetMapping("/payouts")
    public List<ReconciliationRepository.Payout> payouts(@AuthenticationPrincipal MerchantPrincipal principal) {
        return payouts.payouts(principal.merchantId());
    }
}
