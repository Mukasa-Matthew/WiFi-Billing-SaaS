package world.edubridge.wifibilling.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.edubridge.wifibilling.service.ProvisioningClientService;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final ProvisioningClientService provisioningClientService;

    public HealthController(JdbcTemplate jdbcTemplate,
                            ProvisioningClientService provisioningClientService) {
        this.jdbcTemplate = jdbcTemplate;
        this.provisioningClientService = provisioningClientService;
    }

    @GetMapping
    public Map<String, Object> checkHealth() {
        Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return Map.of(
            "status", "UP",
            "database", one != null && one == 1 ? "CONNECTED" : "DEGRADED",
            "timestamp", OffsetDateTime.now().toString(),
            "provisioningContract", provisioningClientService.getContractStatus()
        );
    }
}
