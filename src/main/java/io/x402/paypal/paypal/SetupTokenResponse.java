package io.x402.paypal.paypal;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SetupTokenResponse(
        String id,
        String status,
        List<Link> links,
        @JsonProperty("payment_source") Object paymentSource,
        Object customer
) {

    public String link(String rel) {
        if (links == null) {
            return null;
        }
        return links.stream()
                .filter(link -> rel.equals(link.rel()))
                .map(Link::href)
                .findFirst()
                .orElse(null);
    }
}
