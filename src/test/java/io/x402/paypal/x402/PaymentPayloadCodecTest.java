package io.x402.paypal.x402;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PaymentPayloadCodecTest {

    private final PaymentPayloadCodec codec = new PaymentPayloadCodec(new ObjectMapper());

    @Test
    void canonicalUsesNewlineSeparatedFieldsInOrder() {
        assertThat(codec.canonical("ch_1", "GET", "https://api.test/quote", "100", "USD", "n-1", 1760000000L))
                .isEqualTo("ch_1\nGET\nhttps://api.test/quote\n100\nUSD\nn-1\n1760000000");
    }

    @Test
    void decodesStandardBase64Json() {
        String json = """
                {"x402Version":2,"payload":{"agentId":"agt_1","challengeId":"ch_1",
                "nonce":"n-1","timestamp":1760000000,"signature":"sig"}}
                """;
        String header = Base64.getEncoder().encodeToString(json.getBytes());

        PaymentPayloadCodec.PaymentPayload payload = codec.decode(header);

        assertThat(payload.x402Version()).isEqualTo(2);
        assertThat(payload.payload().challengeId()).isEqualTo("ch_1");
        assertThat(payload.payload().nonce()).isEqualTo("n-1");
        assertThat(payload.payload().signature()).isEqualTo("sig");
    }

    @Test
    void rejectsNonJsonPayload() {
        String header = Base64.getEncoder().encodeToString("not json".getBytes());

        assertThatThrownBy(() -> codec.decode(header))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
