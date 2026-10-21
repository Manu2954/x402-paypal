package io.x402.paypal.diagnostics;

import java.util.Map;

import io.x402.paypal.paypal.PayPalAuthClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/diagnostics")
public class DiagnosticsController {

    private final PayPalAuthClient payPalAuthClient;

    public DiagnosticsController(PayPalAuthClient payPalAuthClient) {
        this.payPalAuthClient = payPalAuthClient;
    }

    @GetMapping("/paypal")
    public Map<String, Object> paypal() {
        return payPalAuthClient.tokenStatus();
    }
}
