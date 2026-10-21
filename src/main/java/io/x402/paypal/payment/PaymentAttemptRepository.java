package io.x402.paypal.payment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttemptEntity, String> {

    Optional<PaymentAttemptEntity> findByChallengeId(String challengeId);

    Optional<PaymentAttemptEntity> findByIdAndPrincipalId(String id, String principalId);

    Optional<PaymentAttemptEntity> findByCaptureId(String captureId);

    Optional<PaymentAttemptEntity> findByOrderId(String orderId);

    List<PaymentAttemptEntity> findByStatusIn(List<String> statuses);
}
