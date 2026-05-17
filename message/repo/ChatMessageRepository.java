package com.example.chat.message.repo;

import com.example.chat.message.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMessageRepository
        extends JpaRepository<ChatMessage, Long> {

    ChatMessage save(ChatMessage message);

    Optional<ChatMessage> findById(Long id);

    @Query("""
    SELECT m
    FROM ChatMessage m
    WHERE m.conversation.id = :conversationId
      AND m.createdAt >= :visibleFrom
    ORDER BY m.createdAt DESC
""")
    List<ChatMessage> findLastMessages(
            @Param("conversationId") Long conversationId,
            @Param("visibleFrom") LocalDateTime visibleFrom,
            Pageable pageable
    );

    @Query("""
    SELECT m
    FROM ChatMessage m
    WHERE m.conversation.id = :conversationId
      AND m.createdAt >= :visibleFrom
      AND m.id < :offsetId
    ORDER BY m.createdAt DESC
""")
    List<ChatMessage> findOlderMessages(
            Long conversationId,
            LocalDateTime visibleFrom,
            Long offsetId,
            Pageable pageable
    );

    @Query("""
    SELECT m
    FROM ChatMessage m
    WHERE m.conversation.id = :conversationId
      AND m.createdAt >= :joinedAt
      AND (:leftAt IS NULL OR m.createdAt <= :leftAt)
    ORDER BY m.createdAt DESC
""")
    List<ChatMessage> findMessagesInsideWindow(
            @Param("conversationId") Long conversationId,
            @Param("joinedAt") LocalDateTime joinedAt,
            @Param("leftAt") LocalDateTime leftAt,
            Pageable pageable
    );

    @Query("""
    SELECT m FROM ChatMessage m
    WHERE m.conversation.id = :conversationId
      AND m.createdAt >= :joinedAt
      AND (:leftAt IS NULL OR m.createdAt <= :leftAt)
      AND m.id < :offsetId
    ORDER BY m.id DESC
""")
    List<ChatMessage> findOlderMessagesInsideLifecycle(
            Long conversationId,
            LocalDateTime joinedAt,
            LocalDateTime leftAt,
            Long offsetId,
            Pageable pageable
    );

    Optional<ChatMessage> findByIdAndSenderId(Long messageId, Long senderId);
}