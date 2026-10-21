package io.x402.paypal.payments;

import io.x402.paypal.agent.AgentAuthFilter;
import io.x402.paypal.premium.PremiumController;
import io.x402.paypal.x402.X402ReceiptFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments/attempts")
public class PaymentAttemptController {

    private final PaymentService paymentService;
    private final X402ReceiptFactory receiptFactory;

    public PaymentAttemptController(PaymentService paymentService, X402ReceiptFactory receiptFactory) {
        this.paymentService = paymentService;
        this.receiptFactory = receiptFactory;
    }

    @GetMapping("/{attemptId}")
    public PaymentAttemptView get(HttpServletRequest request, @PathVariable String attemptId) {
        return paymentService.view(principalId(request), attemptId);
    }

    @PostMapping("/{attemptId}/resume")
    public ResponseEntity<String> resume(HttpServletRequest request, @PathVariable String attemptId) {
        String principalId = principalId(request);
        PaymentResult result = paymentService.resume(principalId, attemptId);
        return ResponseEntity.ok()
                .header(PremiumController.PAYMENT_RESPONSE_HEADER, receiptFactory.header(result, principalId))
                .contentType(result.contentType() == null
                        ? MediaType.APPLICATION_JSON
                        : MediaType.parseMediaType(result.contentType()))
                .body(result.contentJson());
    }

    private String principalId(HttpServletRequest request) {
        return (String) request.getAttribute(AgentAuthFilter.PRINCIPAL_ID_ATTRIBUTE);
    }
}
