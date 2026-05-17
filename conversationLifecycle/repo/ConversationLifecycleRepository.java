package com.example.chat.conversationLifecycle.repo;

import com.example.chat.conversationLifecycle.entity.ConversationLifecycle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationLifecycleRepository extends JpaRepository<ConversationLifecycle,Long> {
    //Get active lifecycle
    Optional<ConversationLifecycle>
    findByConversationIdAndEndedAtIsNull(Long conversationId);

    Optional<ConversationLifecycle>
    findByIdAndConversationId(Long id, Long conversationId);
    //check Active Lifecycle
    boolean existsByConversationIdAndEndedAtIsNull(Long conversationId);

    @Modifying
    @Query("""
    update ConversationLifecycle c
    set c.endedAt = CURRENT_TIMESTAMP
    where c.conversation.id = :conversationId
    and c.endedAt is null
""")
    int endConversationLifecycle(Long conversationId);

    @Query("""
        SELECT cl
        FROM ConversationLifecycle cl
        WHERE cl.conversation.id = :conversationId
        ORDER BY cl.startedAt DESC
    """)
    List<ConversationLifecycle> findAllByConversationIdOrderByStartDesc(
            @Param("conversationId") Long conversationId
    );

    @Query("""
        SELECT cl FROM ConversationLifecycle cl
        WHERE cl.conversation.id = :conversationId
          AND cl.endedAt IS NULL
    """)
    Optional<ConversationLifecycle> findActiveLifecycle(Long conversationId);
}
