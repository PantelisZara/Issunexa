package io.github.panteliszara.issunexa.demo.api;

import io.github.panteliszara.issunexa.auth.LoginSourceResolver;
import io.github.panteliszara.issunexa.demo.DemoAdmissionLimiter;
import io.github.panteliszara.issunexa.demo.DemoExpiredException;
import io.github.panteliszara.issunexa.demo.DemoWorkspaceService;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserRole;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@Profile("demo")
@Hidden
@RequestMapping("/api/demo")
public class DemoController {
    private final DemoWorkspaceService workspaces;
    private final DemoAdmissionLimiter limiter;
    private final LoginSourceResolver source;
    private final SessionAuthenticationStrategy strategy;
    private final SecurityContextHolderStrategy holder;
    private final SecurityContextRepository repository;

    public DemoController(DemoWorkspaceService workspaces, DemoAdmissionLimiter limiter, LoginSourceResolver source,
            SessionAuthenticationStrategy strategy, SecurityContextHolderStrategy holder, SecurityContextRepository repository) {
        this.workspaces = workspaces;
        this.limiter = limiter;
        this.source = source;
        this.strategy = strategy;
        this.holder = holder;
        this.repository = repository;
    }

    @PostMapping("/start")
    public ResponseEntity<Void> start(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request, HttpServletResponse response) {
        requireEmpty(body, request);
        var session = request.getSession();
        synchronized (session) {
            Long workspace = binding(request);
            if (workspace == null) limiter.check(source.resolve(request));
            UserAccount account = workspaces.start(workspace);
            session.setAttribute(DemoWorkspaceService.SESSION_WORKSPACE, account.getDemoWorkspaceId());
            establish(account, request, response);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/requester")
    public ResponseEntity<Void> requester(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request, HttpServletResponse response) {
        return switchTo(UserRole.REQUESTER, body, request, response);
    }

    @PostMapping("/agent")
    public ResponseEntity<Void> agent(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request, HttpServletResponse response) {
        return switchTo(UserRole.AGENT, body, request, response);
    }

    private ResponseEntity<Void> switchTo(UserRole role, Map<String, Object> body,
            HttpServletRequest request, HttpServletResponse response) {
        requireEmpty(body, request);
        var session = request.getSession(false);
        if (session == null) throw new DemoExpiredException();
        synchronized (session) {
            establish(workspaces.account(binding(request), role), request, response);
        }
        return ResponseEntity.noContent().build();
    }

    private void establish(UserAccount account, HttpServletRequest request, HttpServletResponse response) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(account.getEmail(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().name())));
        strategy.onAuthentication(authentication, request, response);
        var context = holder.createEmptyContext();
        context.setAuthentication(authentication);
        holder.setContext(context);
        repository.saveContext(context, request, response);
    }

    private static void requireEmpty(Map<String, Object> body, HttpServletRequest request) {
        if ((body != null && !body.isEmpty()) || request.getQueryString() != null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This operation accepts no selections.");
    }

    public static Long binding(HttpServletRequest request) {
        var session = request.getSession(false);
        return session != null && session.getAttribute(DemoWorkspaceService.SESSION_WORKSPACE) instanceof Long id ? id : null;
    }
}
