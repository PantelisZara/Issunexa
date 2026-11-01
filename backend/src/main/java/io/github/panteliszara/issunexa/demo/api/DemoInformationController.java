package io.github.panteliszara.issunexa.demo.api;

import io.github.panteliszara.issunexa.demo.DemoWorkspaceService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@Hidden
public class DemoInformationController {
    private final ObjectProvider<DemoWorkspaceService> workspaces;
    public DemoInformationController(ObjectProvider<DemoWorkspaceService> workspaces) { this.workspaces = workspaces; }

    @GetMapping("/api/demo")
    public ResponseEntity<DemoState> state(HttpServletRequest request) {
        var service = workspaces.getIfAvailable();
        Long workspace = DemoController.binding(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service == null
                ? new DemoState(false, false, null)
                : new DemoState(true, service.active(workspace), service.expiry(workspace)));
    }

    public record DemoState(boolean enabled, boolean active, Instant expiresAt) { }
}
