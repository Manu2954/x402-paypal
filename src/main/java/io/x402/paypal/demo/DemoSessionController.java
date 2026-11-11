package io.x402.paypal.demo;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/demo")
public class DemoSessionController {

    private final DemoSessionService demoSessionService;

    public DemoSessionController(DemoSessionService demoSessionService) {
        this.demoSessionService = demoSessionService;
    }

    @GetMapping("/info")
    public DemoSessionService.DemoInfo info() {
        return demoSessionService.info();
    }

    @PostMapping("/session")
    public DemoSessionService.DemoSession create(HttpServletRequest request) {
        return demoSessionService.create(remoteAddress(request));
    }

    private String remoteAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        forwarded = request.getHeader("CF-Connecting-IP");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.trim();
        }
        return request.getRemoteAddr();
    }
}
