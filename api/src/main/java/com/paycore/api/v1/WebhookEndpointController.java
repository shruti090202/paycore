package com.paycore.api.v1;

import com.paycore.api.dto.PageDto;
import com.paycore.api.dto.WebhookDtos;
import com.paycore.auth.MerchantPrincipal;
import com.paycore.common.config.OpenApiConfig;
import com.paycore.common.error.PayCoreException;
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

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
@Tag(name = "Webhooks & events")
@SecurityRequirement(name = OpenApiConfig.MERCHANT_API_KEY)
public class WebhookEndpointController {

    private final WebhookEndpointService endpoints;
    private final WebhookRepository repo;

    public WebhookEndpointController(WebhookEndpointService endpoints, WebhookRepository repo) {
        this.endpoints = endpoints;
        this.repo = repo;
    }

    public record CreateEndpointRequest(@NotBlank @Size(max = 2000) String url,
                                        @Size(max = 20) List<@Size(max = 50) String> enabledEvents,
                                        @Size(max = 200) String description) {
    }

    @PostMapping("/webhook_endpoints")
    @Operation(summary = "Register a webhook URL. The signing secret (whsec_...) is returned once.",
            description = "Events are signed with `PayCore-Signature: t=<unix>,v1=<hex HMAC-SHA256 of \"<t>.<body>\">`.")
    public ResponseEntity<WebhookDtos.EndpointDto> create(@AuthenticationPrincipal MerchantPrincipal principal,
                                                          @Valid @RequestBody CreateEndpointRequest req) {
        WebhookEndpointService.Created c = endpoints.create(principal.merchantId(), req.url(), req.enabledEvents(), req.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(WebhookDtos.EndpointDto.from(c.endpoint(), c.secret()));
    }

    @GetMapping("/webhook_endpoints")
    public List<WebhookDtos.EndpointDto> list(@AuthenticationPrincipal MerchantPrincipal principal) {
        return endpoints.list(principal.merchantId()).stream().map(e -> WebhookDtos.EndpointDto.from(e, null)).toList();
    }

    @GetMapping("/webhook_endpoints/{id}")
    public WebhookDtos.EndpointDto get(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        return WebhookDtos.EndpointDto.from(endpoints.require(principal.merchantId(), id), null);
    }

    @DeleteMapping("/webhook_endpoints/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        endpoints.delete(principal.merchantId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/webhook_endpoints/{id}/test")
    @Operation(summary = "Queue a `ping` event to this endpoint")
    public Map<String, String> test(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        return Map.of("event_id", endpoints.sendTest(principal.merchantId(), id).id());
    }

    @GetMapping("/events")
    @Operation(summary = "List events (what webhooks are made of), newest first")
    public PageDto<WebhookDtos.EventDto> events(@AuthenticationPrincipal MerchantPrincipal principal,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(required = false) String cursor,
                                                @RequestParam(defaultValue = "20") int limit) {
        int lim = Math.max(1, Math.min(limit, 100));
        var rows = repo.eventsForMerchant(principal.merchantId(), type, cursor, lim + 1);
        String next = rows.size() > lim ? rows.get(lim - 1).id() : null;
        return PageDto.of(rows.stream().limit(lim).map(WebhookDtos.EventDto::from).toList(), next);
    }

    @GetMapping("/events/{id}/deliveries")
    @Operation(summary = "Delivery status of an event to each of your endpoints")
    public List<WebhookDtos.DeliveryDto> eventDeliveries(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        repo.event(id, principal.merchantId()).orElseThrow(() -> PayCoreException.notFound("event", id));
        return repo.deliveriesForEvent(id, principal.merchantId()).stream()
                .map(d -> WebhookDtos.DeliveryDto.from(d, null, repo.attempts(d.id()).stream().map(WebhookDtos.AttemptDto::from).toList()))
                .toList();
    }

    @GetMapping("/events/{id}")
    public WebhookDtos.EventDto event(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable String id) {
        return repo.event(id, principal.merchantId()).map(WebhookDtos.EventDto::from)
                .orElseThrow(() -> PayCoreException.notFound("event", id));
    }
}
