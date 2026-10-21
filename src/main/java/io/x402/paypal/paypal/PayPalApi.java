package io.x402.paypal.paypal;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PayPalApi {

    private final RestClient restClient;
    private final PayPalAuthClient authClient;
    private final ObjectMapper objectMapper;

    public PayPalApi(RestClient payPalRestClient, PayPalAuthClient authClient, ObjectMapper objectMapper) {
        this.restClient = payPalRestClient;
        this.authClient = authClient;
        this.objectMapper = objectMapper;
    }

    public <T> T get(String path, Class<T> responseType) {
        try {
            return restClient.get()
                    .uri(path)
                    .headers(this::authorize)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientResponseException exception) {
            throw PayPalApiException.from(exception, objectMapper);
        }
    }

    public <T> T post(String path, Object body, Class<T> responseType, String requestId) {
        try {
            return restClient.post()
                    .uri(path)
                    .headers(headers -> {
                        authorize(headers);
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        if (requestId != null && !requestId.isBlank()) {
                            headers.set("PayPal-Request-Id", requestId);
                        }
                    })
                    .body(body == null ? Map.of() : body)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientResponseException exception) {
            throw PayPalApiException.from(exception, objectMapper);
        }
    }

    public void delete(String path) {
        try {
            restClient.delete()
                    .uri(path)
                    .headers(this::authorize)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw PayPalApiException.from(exception, objectMapper);
        }
    }

    private void authorize(HttpHeaders headers) {
        headers.setBearerAuth(authClient.accessToken());
    }
}
