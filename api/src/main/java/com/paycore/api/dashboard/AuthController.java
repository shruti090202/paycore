package com.paycore.api.dashboard;

import com.paycore.auth.JwtService;
import com.paycore.merchant.Merchant;
import com.paycore.merchant.MerchantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/dashboard/auth")
@Tag(name = "Dashboard: auth")
public class AuthController {

    private final MerchantService merchantService;
    private final JwtService jwtService;

    public AuthController(MerchantService merchantService, JwtService jwtService) {
        this.merchantService = merchantService;
        this.jwtService = jwtService;
    }

    public record SignupRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 128) String password) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record AuthResponse(String token, Instant expiresAt, MerchantDto merchant) {
    }

    @PostMapping("/signup")
    @Operation(summary = "Create a merchant account and return a dashboard token")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest req) {
        Merchant m = merchantService.signup(req.name(), req.email(), req.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(issue(m));
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange email + password for a short-lived dashboard token")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return issue(merchantService.authenticate(req.email(), req.password()));
    }

    @PostMapping("/demo")
    @Operation(summary = "Log in as the seeded demo merchant (no credentials needed)")
    public AuthResponse demo() {
        return issue(merchantService.requireDemo());
    }

    private AuthResponse issue(Merchant m) {
        JwtService.IssuedToken t = jwtService.issueDashboardToken(m.id());
        return new AuthResponse(t.token(), t.expiresAt(), MerchantDto.from(m));
    }
}
