package io.x402.paypal.agent;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/agents")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentService.AgentBootstrap create(@Valid @RequestBody CreateAgentRequest request) {
        return agentService.create(request.name(), request.budgetCents());
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        String principalId = (String) request.getAttribute(AgentAuthFilter.PRINCIPAL_ID_ATTRIBUTE);
        PrincipalEntity principal = agentService.requireActive(principalId);
        return Map.of(
                "agentId", principal.getId(),
                "name", principal.getName(),
                "budgetCents", principal.getBudgetCents(),
                "spentCents", principal.getSpentCents(),
                "periodStart", principal.getPeriodStart().toString(),
                "status", principal.getStatus()
        );
    }

    public record CreateAgentRequest(
            @NotBlank @Size(max = 120) String name,
            @Min(100) long budgetCents
    ) {
    }
}
