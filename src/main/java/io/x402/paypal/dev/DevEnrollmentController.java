package io.x402.paypal.dev;

import java.util.Map;

import io.x402.paypal.agent.AgentAuthFilter;
import io.x402.paypal.enrollment.EnrollmentEntity;
import io.x402.paypal.enrollment.EnrollmentRepository;
import io.x402.paypal.security.CryptoService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/dev")
@Profile("dev")
public class DevEnrollmentController {

    private final EnrollmentRepository enrollmentRepository;
    private final CryptoService cryptoService;

    public DevEnrollmentController(EnrollmentRepository enrollmentRepository, CryptoService cryptoService) {
        this.enrollmentRepository = enrollmentRepository;
        this.cryptoService = cryptoService;
    }

    @PostMapping("/enrollments/{enrollmentId}/activate")
    public Map<String, Object> activate(@PathVariable String enrollmentId,
                                        @RequestBody ActivateRequest request,
                                        HttpServletRequest httpRequest) {
        String principalId = (String) httpRequest.getAttribute(AgentAuthFilter.PRINCIPAL_ID_ATTRIBUTE);
        EnrollmentEntity enrollment = enrollmentRepository.findByIdAndPrincipalId(enrollmentId, principalId)
                .orElseThrow(() -> new IllegalArgumentException("Enrollment not found"));
        enrollment.activate(
                cryptoService.encryptVaultToken(request.vaultToken()),
                request.brand(),
                request.last4()
        );
        enrollmentRepository.save(enrollment);
        return Map.of(
                "enrollmentId", enrollment.getId(),
                "status", enrollment.getStatus(),
                "brand", enrollment.getBrand(),
                "last4", enrollment.getLast4()
        );
    }

    public record ActivateRequest(String vaultToken, String brand, String last4) {
    }
}
