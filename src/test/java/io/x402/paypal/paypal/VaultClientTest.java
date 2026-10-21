package io.x402.paypal.paypal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VaultClientTest {

    @Test
    void exchangesSetupTokenUsingPaymentSourceTokenSchema() {
        PayPalApi payPalApi = mock(PayPalApi.class);
        when(payPalApi.post(anyString(), any(), eq(PaymentTokenResponse.class), anyString())).thenReturn(null);
        VaultClient client = new VaultClient(payPalApi);

        client.exchangeSetupToken("09V961239Y132880M");

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(payPalApi).post(eq("/v3/vault/payment-tokens"), body.capture(),
                eq(PaymentTokenResponse.class), anyString());
        assertEquals(Map.of("payment_source", Map.of("token", Map.of(
                "id", "09V961239Y132880M",
                "type", "SETUP_TOKEN"
        ))), body.getValue());
    }
}
