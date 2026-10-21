package io.x402.paypal.paypal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Link(
        String href,
        String rel,
        String method,
        @JsonProperty("encType") String encType
) {
}
