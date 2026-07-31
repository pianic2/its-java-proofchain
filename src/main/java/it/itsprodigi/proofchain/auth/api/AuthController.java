package it.itsprodigi.proofchain.auth.api;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import it.itsprodigi.proofchain.auth.application.AuthenticationService;
import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
            operationId = "login",
            summary = "Authenticate an operator",
            description = "Authenticates an ACTIVE operator by normalized username and password.")
    @ApiResponse(
            responseCode = "200",
            description = "Login successful",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = LoginResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation error",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Invalid credentials",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(authenticationService.login(request));
    }

    /**
     * Method guard, not an operation. Without an explicit handler a {@code GET} on the login path would raise
     * {@code HttpRequestMethodNotSupportedException} and be translated into a {@code 500} by the catch-all advice, so
     * the guard exists purely to answer the correct {@code 405}. It is hidden from the published contract because the
     * approved surface contains no readable login resource, and an advertised operation here would be an accidental
     * endpoint the allowlist test is meant to catch.
     */
    @Hidden
    @GetMapping("/login")
    public ResponseEntity<Void> loginWithUnsupportedMethod() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            operationId = "getCurrentOperator",
            summary = "Get the current operator",
            description = "Returns the immutable database-backed operator identity authenticated for this request.")
    @ApiResponse(
            responseCode = "200",
            description = "Current operator",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CurrentOperatorResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication required or invalid token",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public CurrentOperatorResponse me(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return CurrentOperatorResponse.from(operator);
    }
}
