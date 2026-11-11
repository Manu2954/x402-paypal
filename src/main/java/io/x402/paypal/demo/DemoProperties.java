package io.x402.paypal.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo")
public record DemoProperties(
        boolean enabled,
        String vaultToken,
        String brand,
        String last4,
        long budgetCents,
        int maxSessionsPerHour
) {

    public boolean vaultConfigured() {
        return vaultToken != null && !vaultToken.isBlank();
    }

    public String cardBrand() {
        return brand == null || brand.isBlank() ? "VISA" : brand;
    }

    public String cardLast4() {
        return last4 == null || last4.isBlank() ? "1111" : last4;
    }

    public long sessionBudgetCents() {
        return budgetCents < 100 ? 1000 : budgetCents;
    }
}
