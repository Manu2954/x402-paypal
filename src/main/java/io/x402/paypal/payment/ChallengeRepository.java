package io.x402.paypal.payment;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ChallengeRepository extends JpaRepository<ChallengeEntity, String> {

    Optional<ChallengeEntity> findByNonce(String nonce);

    @Modifying
    @Transactional
    @Query("""
            update ChallengeEntity c
               set c.consumedAt = :now
             where c.id = :id
               and c.consumedAt is null
            """)
    int consumeIfUnused(@Param("id") String id, @Param("now") LocalDateTime now);
}
