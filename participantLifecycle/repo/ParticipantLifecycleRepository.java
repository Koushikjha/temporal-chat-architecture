package com.example.chat.participantLifecycle.repo;

import com.example.chat.participantLifecycle.entity.ParticipantLifecycle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ParticipantLifecycleRepository extends JpaRepository<ParticipantLifecycle,Long> {

    boolean existsByConversationIdAndUserIdAndLeftAtIsNull(Long conversationId, Long userId);


    Optional<ParticipantLifecycle> findByConversationIdAndUserIdAndLeftAtIsNull(
            Long conversationId,
            Long userId
    );

    @Modifying
    @Query("""
    update ParticipantLifecycle p
    set p.leftAt = CURRENT_TIMESTAMP
    where p.conversationId = :conversationId
      and p.userId = :userId
      and p.leftAt is null
""")
    int endLifecycle(Long conversationId, Long userId);



    @Query("""
    SELECT p FROM ParticipantLifecycle p
    WHERE p.conversationId = :conversationId
      AND p.joinedAt < :convEnd
      AND p.leftAt   > :convStart
""")
    List<ParticipantLifecycle> findByConversationAndTimeWindow(
            Long conversationId,
            LocalDateTime convStart,
            LocalDateTime convEnd
    );

    @Query("""
    SELECT p
    FROM ParticipantLifecycle p
    WHERE p.id = :plId
      AND p.userId = :userId
""")
    Optional<ParticipantLifecycle> findByIdAndUserId(Long plId, Long userId);

    @Query("""
    SELECT p FROM ParticipantLifecycle p
    WHERE p.userId = :userId
      AND p.leftAt IS NULL
""")
    List<ParticipantLifecycle> findActiveByUser(Long userId);

    @Query("""
    SELECT CASE WHEN COUNT(pl) > 0 THEN true ELSE false END
    FROM ParticipantLifecycle pl
    WHERE pl.conversationId = :conversationId
      AND pl.userId = :userId
      AND pl.leftAt IS NULL
""")
    boolean existsActiveParticipant(Long conversationId, Long userId);

    @Query("""
        SELECT pl FROM ParticipantLifecycle pl
        WHERE pl.conversationId = :conversationId
          AND pl.userId = :userId
          AND pl.leftAt IS NOT NULL
        ORDER BY pl.leftAt DESC
    """)
    Optional<ParticipantLifecycle> findLastClosedLifecycle(
            Long conversationId,
            Long userId
    );
}
