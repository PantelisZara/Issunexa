package io.github.panteliszara.issunexa.shared.web;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
@Hidden
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/live")
    public ResponseEntity<Map<String, String>> live() {
        return status(HttpStatus.OK, "UP");
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try (var connection = dataSource.getConnection(); var query = connection.createStatement()) {
            query.setQueryTimeout(2);
            query.execute("SELECT 1");
            return status(HttpStatus.OK, "UP");
        } catch (SQLException exception) {
            return status(HttpStatus.SERVICE_UNAVAILABLE, "DOWN");
        }
    }

    private ResponseEntity<Map<String, String>> status(HttpStatus code, String value) {
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(Map.of("status", value));
    }
}
