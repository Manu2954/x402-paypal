package io.x402.paypal.agent;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import io.x402.paypal.security.CryptoService;
import io.x402.paypal.security.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentService {

    private final PrincipalRepository principalRepository;
    private final CryptoService cryptoService;

    public AgentService(PrincipalRepository principalRepository, CryptoService cryptoService) {
        this.principalRepository = principalRepository;
        this.cryptoService = cryptoService;
    }

    @Transactional
    public AgentBootstrap create(String name, long budgetCents) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (budgetCents < 100) {
            throw new IllegalArgumentException("budgetCents must be at least 100");
        }

        String agentId = Ids.id("agt_");
        String apiKey = Ids.apiKey();
        LocalDate periodStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);

        PrincipalEntity principal = new PrincipalEntity(
                agentId,
                name.trim(),
                cryptoService.sha256Hex(apiKey),
                budgetCents,
                0,
                periodStart,
                "ACTIVE",
                LocalDateTime.now(Clock.systemUTC())
        );
        principalRepository.save(principal);

        return new AgentBootstrap(agentId, apiKey, cryptoService.agentHmacSecret(agentId), budgetCents);
    }

    public PrincipalEntity requireActive(String principalId) {
        PrincipalEntity principal = principalRepository.findById(principalId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found"));
        if (!"ACTIVE".equals(principal.getStatus())) {
            throw new IllegalStateException("Agent is not active");
        }
        return principal;
    }

    public record AgentBootstrap(String agentId, String apiKey, String hmacSecret, long budgetCents) {
    }
}
