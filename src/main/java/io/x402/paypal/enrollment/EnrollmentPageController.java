package io.x402.paypal.enrollment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/enroll")
public class EnrollmentPageController {

    private final EnrollmentService enrollmentService;

    public EnrollmentPageController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String token) throws IOException {
        String html = new String(
                new ClassPathResource("static/enroll.html").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        return ResponseEntity.ok(html);
    }

    @GetMapping("/{token}/config")
    public EnrollmentService.CardSetupConfig config(@PathVariable String token) {
        return enrollmentService.cardSetupConfig(token);
    }

    @PostMapping("/{token}/exchange")
    public EnrollmentService.EnrollmentView exchange(@PathVariable String token,
                                                     @RequestBody ExchangeBody body) {
        return enrollmentService.exchangeByLink(token, body.vaultSetupToken());
    }

    @GetMapping(value = "/{token}/return", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> returned(@PathVariable String token) {
        try {
            EnrollmentService.EnrollmentView view = enrollmentService.exchangeByLinkIfApproved(token);
            return ResponseEntity.ok(successPage(view));
        } catch (EnrollmentPendingException exception) {
            return ResponseEntity.ok(pendingPage(exception.getMessage(), exception.approveUrl()));
        } catch (RuntimeException exception) {
            return ResponseEntity.badRequest().body(errorPage(exception.getMessage()));
        }
    }

    @GetMapping(value = "/{token}/cancel", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> cancel(@PathVariable String token) {
        return ResponseEntity.ok(errorPage("Enrollment was canceled."));
    }

    private String successPage(EnrollmentService.EnrollmentView view) {
        String detail = view.brand() == null ? "" : " (" + view.brand() + " " + view.last4() + ")";
        return pageTemplate("Payment method saved" + detail, "You can close this tab and return to the agent.");
    }

    private String pendingPage(String message, String approveUrl) {
        String link = approveUrl == null ? "" : "<p><a href=\"" + approveUrl + "\">Approve on PayPal</a></p>";
        return pageTemplate("Approval pending", "<p>" + message + "</p>" + link);
    }

    private String errorPage(String message) {
        return pageTemplate("Enrollment", "<p>" + message + "</p>");
    }

    private String pageTemplate(String title, String body) {
        return """
                <!doctype html>
                <html lang="en">
                <head><meta charset="utf-8"><title>%s</title>
                <style>body{font-family:system-ui,sans-serif;max-width:640px;margin:4rem auto;padding:0 1rem}</style>
                </head>
                <body><h1>%s</h1>%s</body>
                </html>
                """.formatted(title, title, body);
    }

    public record ExchangeBody(String vaultSetupToken) {
    }
}
