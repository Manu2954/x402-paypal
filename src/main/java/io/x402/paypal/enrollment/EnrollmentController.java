package io.x402.paypal.enrollment;

import java.util.List;

import io.x402.paypal.agent.AgentAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/enrollments")
public class EnrollmentController {

    private final EnrollmentService enrollmentService;

    public EnrollmentController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentService.EnrollmentCreated create(HttpServletRequest request,
                                                      @Valid @RequestBody CreateEnrollmentRequest body) {
        return enrollmentService.createAt(principalId(request), body.sourceType(), externalBaseUrl(request));
    }

    @GetMapping
    public List<EnrollmentService.EnrollmentView> list(HttpServletRequest request) {
        return enrollmentService.list(principalId(request));
    }

    @GetMapping("/{enrollmentId}")
    public EnrollmentService.EnrollmentView get(HttpServletRequest request,
                                                @PathVariable String enrollmentId) {
        return enrollmentService.get(principalId(request), enrollmentId);
    }

    @PostMapping("/{enrollmentId}/exchange")
    public EnrollmentService.EnrollmentView exchange(HttpServletRequest request,
                                                     @PathVariable String enrollmentId,
                                                     @RequestBody(required = false) ExchangeRequest body) {
        String vaultSetupToken = body == null ? null : body.vaultSetupToken();
        return enrollmentService.exchange(principalId(request), enrollmentId, vaultSetupToken);
    }

    @DeleteMapping("/{enrollmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(HttpServletRequest request, @PathVariable String enrollmentId) {
        enrollmentService.revoke(principalId(request), enrollmentId);
    }

    private String principalId(HttpServletRequest request) {
        return (String) request.getAttribute(AgentAuthFilter.PRINCIPAL_ID_ATTRIBUTE);
    }

    private String externalBaseUrl(HttpServletRequest request) {
        String forwardedHost = firstNonBlank(request.getHeader("X-Forwarded-Host"));
        if (forwardedHost != null) {
            forwardedHost = forwardedHost.split(",")[0].trim();
        }
        String hostHeader = request.getHeader("Host");
        String authority = forwardedHost != null ? forwardedHost : hostHeader;

        String host = firstNonBlank(forwardedHost, request.getServerName());
        String proto = firstNonBlank(request.getHeader("X-Forwarded-Proto"), request.getHeader("X-Forwarded-Scheme"));
        if (proto != null) {
            proto = proto.split(",")[0].trim();
        } else {
            proto = request.getScheme();
            int port = request.getServerPort();
            boolean localHost = host == null || host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                    || host.equals("[::1]") || host.equals("::1");
            // TLS-terminating proxies (tunnels, load balancers) reach the app over plain HTTP
            // with a public Host header; assume https when no explicit proto is forwarded.
            if (!localHost && "http".equalsIgnoreCase(proto) && (port == 80 || port == 443 || port <= 0)) {
                proto = "https";
            }
        }

        if (authority == null || authority.isBlank()) {
            int port = request.getServerPort();
            boolean defaultPort = port == 80 || port == 443 || port <= 0;
            authority = request.getServerName() + (defaultPort ? "" : ":" + port);
        }
        return proto.toLowerCase() + "://" + authority;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record CreateEnrollmentRequest(@NotBlank String sourceType) {
    }

    public record ExchangeRequest(String vaultSetupToken) {
    }
}
