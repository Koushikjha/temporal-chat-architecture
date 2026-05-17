package com.example.chat.conversationParticipant.repo;

import com.example.chat.conversationParticipant.entity.ConversationParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationParticipantRepository
        extends JpaRepository<ConversationParticipant, Long> {

    List<ConversationParticipant> findByConversationId(Long conversationId);

    List<ConversationParticipant> findByUserId(Long userId);

    boolean existsByConversationIdAndUserId(Long conversationId, Long userId);

    void deleteByConversationIdAndUserId(Long conversationId, Long userId);

    ConversationParticipant save(ConversationParticipant cp);

    @Query("""
        SELECT cp.conversation.id
        FROM ConversationParticipant cp
        WHERE cp.userId IN (:u1, :u2)
        GROUP BY cp.conversation.id
        HAVING COUNT(cp.userId) = 2
    """)
    List<Long> findCommonConversationIds(Long u1, Long u2);

    @Query("""
    SELECT p.userId FROM ConversationParticipant p
    WHERE p.conversation.id = :conversationId
      AND p.userId <> :userId
""")
    Optional<Long> findOtherParticipant(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId
    );

    @Query("""
    SELECT cp FROM ConversationParticipant cp
    WHERE cp.conversation.id = :conversationId
      AND cp.userId = :userId
""")
    Optional<ConversationParticipant> findByConversationAndUser(
            Long conversationId,
            Long userId
    );
}