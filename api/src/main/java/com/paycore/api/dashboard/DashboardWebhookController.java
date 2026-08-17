package com.paycore.api.dashboard;

import com.paycore.api.dto.PageDto;
import com.paycore.api.dto.WebhookDtos;
import com.paycore.auth.MerchantPrincipal;
import com.paycore.common.config.OpenApiConfig;
import com.paycore.common.error.PayCoreException;
import com.paycore.webhooks.WebhookDelivery;
import com.paycore.webhooks.WebhookEndpointService;
import com.paycore.webhooks.WebhookRepository;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dashboard")
@Tag(name = "Dashboard: webhooks")
@SecurityRequirement(name = OpenApiConfig.DASHBOARD_JWT)
public class DashboardWebhookController {

    private final WebhookEndpointService endpoints;
    private final WebhookRepository repo;
    private final Clock clock;

    public DashboardWebhookController(WebhookEndpointService endpoints, WebhookRepository repo, Clock clock) {
        this.endpoints = endpoints;
        this.repo = repo;
        this.clock = clock;
    }

    public record CreateEndpointRequest(@NotBlank @Size(max = 2000) String url,
                                        @Size(max = 20) List<@Size(max = 50) String> enabledEvents,
                                        @Size(max = 200) String description) {
    }

    @GetMapping("/webhook_endpoints")
    public List<WebhookDtos.EndpointDto> list(@AuthenticationPrincipal MerchantPrincipal principal) {
        return endpoints.list(principal.merchantId()).stream().map(e -> WebhookDtos.EndpointDto.from(e, null)).toList();
    }

    @PostMapping("/webhook_endpoints")
    public ResponseEntity<WebhookDtos.EndpointDto> create(@AuthenticationPrincipal MerchantPrincipal principal,
                                                          @Valid @RequestBody CreateEndpointRequest req) {
        WebhookEndpointService.Created c = endpoints.create(principal.merchantId(), req.url(), req.enabledEvents(), req.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(WebhookDtos.EndpointDto.from(c.endpoint(), c.secret()));
    }

    @GetMapping("/webhook_endpoints/{id}/secret")
    @Operation(summary = "Reveal the signing secret (stored encrypted, never hashed, so it can be re-read)")
    public Map<String, String> secret(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        return Map.of("secret", endpoints.revealSecret(principal.merchantId(), id));
    }

    @DeleteMapping("/webhook_endpoints/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        endpoints.delete(principal.merchantId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/webhook_endpoints/{id}/test")
    public Map<String, String> test(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        return Map.of("event_id", endpoints.sendTest(principal.merchantId(), id).id());
    }

    @GetMapping("/webhook_events/types")
    public List<String> eventTypes() {
        return WebhookEndpointService.EVENT_TYPES;
    }

    @GetMapping("/webhook_deliveries")
    public PageDto<WebhookDtos.DeliveryDto> deliveries(@AuthenticationPrincipal MerchantPrincipal principal,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String endpointId,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(defaultValue = "25") int limit) {
        int lim = Math.max(1, Math.min(limit, 100));
        List<WebhookDelivery> rows = repo.deliveriesForMerchant(principal.merchantId(), status, endpointId, cursor, lim + 1);
        String next = rows.size() > lim ? rows.get(lim - 1).id() : null;
        List<WebhookDtos.DeliveryDto> data = rows.stream().limit(lim)
                .map(d -> WebhookDtos.DeliveryDto.from(d, repo.eventById(d.eventId()).map(e -> e.type()).orElse(null), null))
                .toList();
        return PageDto.of(data, next);
    }

    @GetMapping("/webhook_deliveries/{id}")
    public WebhookDtos.DeliveryDto delivery(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        WebhookDelivery d = repo.delivery(id, principal.merchantId())
                .orElseThrow(() -> PayCoreException.notFound("webhook_delivery", id));
        List<WebhookDtos.AttemptDto> attempts = repo.attempts(d.id()).stream().map(WebhookDtos.AttemptDto::from).toList();
        return WebhookDtos.DeliveryDto.from(d, repo.eventById(d.eventId()).map(e -> e.type()).orElse(null), attempts);
    }

    @PostMapping("/webhook_deliveries/{id}/replay")
    @Operation(summary = "Re-queue a dead or delivered delivery; it is sent on the next dispatch")
    public WebhookDtos.DeliveryDto replay(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        if (repo.replay(id, principal.merchantId(), clock.instant()) == 0) {
            throw PayCoreException.stateConflict("delivery_pending", "Delivery is already pending");
        }
        return delivery(principal, id);
    }

    @GetMapping("/webhook_deliveries/summary")
    public Map<String, Long> summary(@AuthenticationPrincipal MerchantPrincipal principal) {
        String m = principal.merchantId();
        return Map.of("pending", repo.countByStatus(m, "pending"), "delivered", repo.countByStatus(m, "delivered"),
                "dead", repo.countByStatus(m, "dead"));
    }
}
