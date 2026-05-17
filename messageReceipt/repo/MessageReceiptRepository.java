package com.example.chat.messageReceipt.repo;

import com.example.chat.messageReceipt.entity.MessageReceipt;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


@Repository
public interface MessageReceiptRepository
        extends JpaRepository<MessageReceipt, Long> {

    MessageReceipt save(MessageReceipt receipt);

    @Query("""
SELECT r FROM MessageReceipt r
WHERE r.message.id IN :messageIds
AND r.userId = :userId
""")
    List<MessageReceipt> findByMessageIdsAndUserId(List<Long> messageIds, Long userId);

    @Modifying
    @Query("""
    UPDATE MessageReceipt mr
    SET mr.delivered = true
    WHERE mr.userId = :userId
    AND mr.delivered = false
    AND mr.message.conversation.id = :conversationId
    AND mr.message.createdAt >= :joinedAt
""")
    void markDeliveredWithinWindow(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId,
            @Param("joinedAt") LocalDateTime joinedAt
    );

    @Modifying
    @Query("""
    UPDATE MessageReceipt mr
    SET mr.seen = true,
        mr.delivered = true
    WHERE mr.userId = :userId
    AND mr.seen = false
    AND mr.message.conversation.id = :conversationId
    AND mr.message.createdAt >= :joinedAt
""")
    void markSeenWithinWindow(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId,
            @Param("joinedAt") LocalDateTime joinedAt
    );

    Optional<MessageReceipt> findByMessageIdAndUserId(Long messageId, Long userId);
}