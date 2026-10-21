package io.x402.paypal.agent;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.x402.paypal.security.CryptoService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(10)
public class AgentAuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ID_ATTRIBUTE = "x402.principalId";

    private final PrincipalRepository principalRepository;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public AgentAuthFilter(PrincipalRepository principalRepository, CryptoService cryptoService,
                           ObjectMapper objectMapper) {
        this.principalRepository = principalRepository;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/actuator") || path.startsWith("/h2-console")
                || path.startsWith("/webhooks") || path.startsWith("/enroll")
                || path.startsWith("/v1/diagnostics")) {
            return true;
        }
        return "POST".equalsIgnoreCase(request.getMethod()) && "/v1/agents".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            unauthorized(response, "Missing bearer token");
            return;
        }

        String apiKey = authorization.substring("Bearer ".length()).trim();
        Optional<PrincipalEntity> principal = principalRepository
                .findByApiKeyHash(cryptoService.sha256Hex(apiKey))
                .filter(value -> "ACTIVE".equals(value.getStatus()));

        if (principal.isEmpty()) {
            unauthorized(response, "Invalid API key");
            return;
        }

        request.setAttribute(PRINCIPAL_ID_ATTRIBUTE, principal.get().getId());
        filterChain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
                "error", "UNAUTHORIZED",
                "message", message
        ));
    }
}
