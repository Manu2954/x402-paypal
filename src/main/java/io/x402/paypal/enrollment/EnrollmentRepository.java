package io.x402.paypal.enrollment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EnrollmentRepository extends JpaRepository<EnrollmentEntity, String> {

    List<EnrollmentEntity> findByPrincipalIdOrderByCreatedAtDesc(String principalId);

    Optional<EnrollmentEntity> findFirstByPrincipalIdAndStatusOrderByCreatedAtDesc(String principalId, String status);

    Optional<EnrollmentEntity> findByIdAndPrincipalId(String id, String principalId);

    List<EnrollmentEntity> findByStatus(String status);
}
