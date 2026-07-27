package it.itsprodigi.proofchain.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import it.itsprodigi.proofchain.auth.application.AuthenticationService;
import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
            summary = "Authenticate an operator",
            description = "Authenticates an operator by username and password.")
    @ApiResponse(responseCode = "200", description = "Login successful")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(authenticationService.login(request));
    }

    @GetMapping("/me")
    @Operation(summary = "Get the current operator")
    @ApiResponse(responseCode = "200", description = "Current operator")
    @ApiResponse(responseCode = "401", description = "Authentication required or invalid token")
    public CurrentOperatorResponse me(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return CurrentOperatorResponse.from(operator);
    }
}
