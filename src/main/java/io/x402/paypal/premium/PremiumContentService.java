package io.x402.paypal.premium;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class PremiumContentService {

    private final ObjectMapper objectMapper;

    public PremiumContentService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PremiumContent quote() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", "X402/USD");
        payload.put("price", 1.42);
        payload.put("change24h", 3.71);
        payload.put("bid", 1.41);
        payload.put("ask", 1.43);
        payload.put("asOf", Instant.now().toString());
        payload.put("source", "demo-feed");
        return json(payload);
    }

    public PremiumContent report() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "Agent commerce signal report");
        payload.put("asOf", Instant.now().toString());
        payload.put("sentiment", "risk-on");
        payload.put("confidence", 0.72);
        payload.put("signals", List.of(
                Map.of("name", "stablecoin_settlement_volume", "value", 184200000, "change24h", 6.4),
                Map.of("name", "agent_marketplace_listings", "value", 9120, "change24h", 12.1),
                Map.of("name", "paypal_vault_enrollments", "value", 1432, "change24h", 4.8)
        ));
        payload.put("summary", "Autonomous agent purchases continue to grow while settlement stays concentrated in stable assets.");
        return json(payload);
    }

    private PremiumContent json(Map<String, Object> payload) {
        try {
            return new PremiumContent(objectMapper.writeValueAsString(payload), "application/json");
        } catch (Exception exception) {
            throw new IllegalStateException("Could not build premium content", exception);
        }
    }
}
