package io.x402.paypal.demo;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.x402.paypal.agent.AgentService;
import io.x402.paypal.config.X402Properties;
import io.x402.paypal.enrollment.EnrollmentEntity;
import io.x402.paypal.enrollment.EnrollmentRepository;
import io.x402.paypal.security.CryptoService;
import io.x402.paypal.security.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoSessionService {

    private static final String MODE = "live-sandbox";

    private final AgentService agentService;
    private final EnrollmentRepository enrollmentRepository;
    private final CryptoService cryptoService;
    private final DemoProperties demoProperties;
    private final X402Properties x402Properties;
    private final Map<String, Deque<Instant>> sessionsByAddress = new ConcurrentHashMap<>();

    public DemoSessionService(AgentService agentService, EnrollmentRepository enrollmentRepository,
                              CryptoService cryptoService, DemoProperties demoProperties,
                              X402Properties x402Properties) {
        this.agentService = agentService;
        this.enrollmentRepository = enrollmentRepository;
        this.cryptoService = cryptoService;
        this.demoProperties = demoProperties;
        this.x402Properties = x402Properties;
    }

    public boolean available() {
        return demoProperties.enabled() && demoProperties.vaultConfigured();
    }

    @Transactional
    public DemoSession create(String address) {
        if (!available()) {
            throw new DemoUnavailableException(
                    "The live demo card is not configured on this deployment.");
        }
        enforceRateLimit(address);

        AgentService.AgentBootstrap agent = agentService.create(
                "Judge Agent", demoProperties.sessionBudgetCents());

        String enrollmentId = Ids.id("enr_");
        EnrollmentEntity enrollment = new EnrollmentEntity(
                enrollmentId,
                agent.agentId(),
                Ids.payPalCustomerId(),
                "CARD",
                "PENDING",
                null,
                null,
                null,
                LocalDateTime.now(Clock.systemUTC())
        );
        enrollment.activate(
                cryptoService.encryptVaultToken(demoProperties.vaultToken().trim()),
                demoProperties.cardBrand(),
                demoProperties.cardLast4()
        );
        enrollmentRepository.save(enrollment);

        return new DemoSession(
                MODE,
                agent.agentId(),
                agent.apiKey(),
                agent.hmacSecret(),
                agent.budgetCents(),
                0,
                "CARD",
                demoProperties.cardBrand(),
                demoProperties.cardLast4(),
                pricing()
        );
    }

    public DemoInfo info() {
        String notice = null;
        if (!demoProperties.enabled()) {
            notice = "The live demo is disabled on this deployment.";
        } else if (!demoProperties.vaultConfigured()) {
            notice = "Add DEMO_VAULT_TOKEN to enable real PayPal sandbox settlements.";
        }
        return new DemoInfo(
                available(),
                MODE,
                demoProperties.cardBrand(),
                demoProperties.cardLast4(),
                demoProperties.sessionBudgetCents(),
                pricing(),
                notice
        );
    }

    public Map<String, Long> pricing() {
        Map<String, Long> pricing = new LinkedHashMap<>();
        pricing.put("quote", routePrice("GET /v1/premium/quote", 100));
        pricing.put("report", routePrice("GET /v1/premium/report", 200));
        return pricing;
    }

    private void enforceRateLimit(String address) {
        int limit = demoProperties.maxSessionsPerHour();
        if (limit <= 0) {
            return;
        }
        String key = address == null || address.isBlank() ? "unknown" : address;
        Deque<Instant> timestamps = sessionsByAddress.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.removeFirst();
            }
            if (timestamps.size() >= limit) {
                throw new DemoRateLimitException(3600);
            }
            timestamps.addLast(Instant.now());
        }
    }

    private long routePrice(String methodPath, long fallback) {
        if (x402Properties.routes() == null) {
            return fallback;
        }
        Long value = x402Properties.routes().get(methodPath);
        if (value == null) {
            value = x402Properties.routes().entrySet().stream()
                    .filter(entry -> normalizeRoute(entry.getKey()).equals(methodPath))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
        return value == null ? fallback : value;
    }

    private String normalizeRoute(String route) {
        return route.replace("[", "").replace("]", "").trim();
    }

    public record DemoSession(
            String mode,
            String agentId,
            String apiKey,
            String hmacSecret,
            long budgetCents,
            long spentCents,
            String sourceType,
            String cardBrand,
            String cardLast4,
            Map<String, Long> pricing
    ) {
    }

    public record DemoInfo(
            boolean available,
            String mode,
            String cardBrand,
            String cardLast4,
            long budgetCents,
            Map<String, Long> pricing,
            String notice
    ) {
    }
}
