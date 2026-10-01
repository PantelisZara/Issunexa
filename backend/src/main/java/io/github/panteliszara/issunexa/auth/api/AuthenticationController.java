package io.github.panteliszara.issunexa.auth.api;

import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthenticationController {

    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextHolderStrategy securityContextHolderStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final LogoutHandler logoutHandler;
    private final UserAccountService userAccountService;

    public AuthenticationController(AuthenticationManager authenticationManager,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            SecurityContextHolderStrategy securityContextHolderStrategy,
            SecurityContextRepository securityContextRepository, LogoutHandler logoutHandler,
            UserAccountService userAccountService) {
        this.authenticationManager = authenticationManager;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextHolderStrategy = securityContextHolderStrategy;
        this.securityContextRepository = securityContextRepository;
        this.logoutHandler = logoutHandler;
        this.userAccountService = userAccountService;
    }

    @GetMapping("/session")
    @Operation(summary = "Get the current authenticated session",
            description = "Returns the authenticated account's ID, canonical email, display name and persisted role.")
    @SecurityRequirement(name = "sessionAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current authenticated account. Must not be cached.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AuthenticatedSessionResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<AuthenticatedSessionResponse> session(@Parameter(hidden = true) Principal principal) {
        UserAccount account = userAccountService.getAuthenticatedAccount(principal.getName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AuthenticatedSessionResponse(account.getId(), account.getEmail(),
                        account.getDisplayName(), account.getRole()));
    }

    @GetMapping("/csrf")
    @Operation(summary = "Get a CSRF token", description = "Public endpoint. Retain the session cookie and send the "
            + "token in the returned headerName on unsafe requests. Fetch a fresh token after login and logout.")
    @ApiResponse(responseCode = "200", description = "CSRF token for the current session. Response must not be cached.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CsrfTokenResponse.class)))
    public ResponseEntity<CsrfTokenResponse> csrf(@Parameter(hidden = true) CsrfToken csrfToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfTokenResponse(csrfToken.getToken(), csrfToken.getHeaderName()));
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Log in", description = "Authenticates email and password and establishes an HTTP session. "
            + "Rotates the session ID and invalidates the previous CSRF token. Fetch a fresh token after success.")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "Token obtained from GET /api/auth/csrf using the same session.",
            schema = @Schema(type = "string"))
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Authenticated session established.", content = @Content),
            @ApiResponse(responseCode = "400", description = "Invalid login request.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Invalid email or password.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing or invalid CSRF token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Void> login(@Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(loginRequest.email(), loginRequest.password()));
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(authentication);
        securityContextHolderStrategy.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    @Operation(summary = "Log out", description = "Requires an authenticated session and a current CSRF token. "
            + "Clears authentication and CSRF state and invalidates the session.")
    @SecurityRequirement(name = "sessionAuth")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "Current CSRF token obtained after login.", schema = @Schema(type = "string"))
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Session invalidated.", content = @Content),
            @ApiResponse(responseCode = "401", description = "Authentication required.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing or invalid CSRF token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        logoutHandler.logout(request, response, securityContextHolderStrategy.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }

}
