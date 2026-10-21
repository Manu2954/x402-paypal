package io.x402.paypal.premium;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PremiumContentServiceTest {

    private final PremiumContentService service = new PremiumContentService(new ObjectMapper());

    @Test
    void quoteIsJsonWithSymbol() {
        PremiumContent content = service.quote();

        assertThat(content.contentType()).isEqualTo("application/json");
        assertThat(content.json()).contains("\"symbol\":\"X402/USD\"");
    }

    @Test
    void reportContainsSignals() {
        PremiumContent content = service.report();

        assertThat(content.json()).contains("\"signals\"");
        assertThat(content.json()).contains("\"sentiment\"");
    }
}
