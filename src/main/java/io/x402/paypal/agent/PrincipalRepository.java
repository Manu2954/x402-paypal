package io.x402.paypal.agent;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PrincipalRepository extends JpaRepository<PrincipalEntity, String> {

    Optional<PrincipalEntity> findByApiKeyHash(String apiKeyHash);

    @Modifying
    @Transactional
    @Query("""
            update PrincipalEntity p
               set p.spentCents = p.spentCents + :amount
             where p.id = :id
               and p.spentCents + :amount <= p.budgetCents
            """)
    int reserveBudget(@Param("id") String id, @Param("amount") long amount);

    @Modifying
    @Transactional
    @Query("""
            update PrincipalEntity p
               set p.spentCents = p.spentCents - :amount
             where p.id = :id
               and p.spentCents >= :amount
            """)
    int releaseBudget(@Param("id") String id, @Param("amount") long amount);

    @Modifying
    @Transactional
    @Query("""
            update PrincipalEntity p
               set p.spentCents = 0,
                   p.periodStart = :periodStart
             where p.id = :id
               and p.periodStart < :periodStart
            """)
    int resetPeriod(@Param("id") String id, @Param("periodStart") LocalDate periodStart);
}
