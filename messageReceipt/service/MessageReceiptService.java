package com.example.chat.messageReceipt.service;

import com.example.chat.message.entity.ChatMessage;
import com.example.chat.messageReceipt.entity.MessageReceipt;
import com.example.chat.messageReceipt.repo.MessageReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReceiptService {

    private final MessageReceiptRepository messageReceiptRepository;

    @Transactional
    public void createInitialReceipts(ChatMessage message,
                                      Long senderId,
                                      Long receiverId) {

        log.debug("[RECEIPT_INIT_PRIVATE] messageId={} sender={} receiver={}",
                message.getId(), senderId, receiverId);

        createReceipt(message, senderId, true, true);
        createReceipt(message, receiverId, false, false);

        log.info("[RECEIPTS_CREATED_PRIVATE] messageId={}", message.getId());
    }

    private void createReceipt(ChatMessage message,
                                     Long userId,
                                     boolean delivered,
                                     boolean seen) {
        try {
            MessageReceipt receipt = MessageReceipt.builder()
                    .message(message)
                    .userId(userId)
                    .delivered(delivered)
                    .seen(seen)
                    .deletedForMe(false)
                    .build();

            messageReceiptRepository.save(receipt);

            log.debug("[RECEIPT_CREATED] messageId={} userId={}",
                    message.getId(), userId);

        } catch (DataIntegrityViolationException ex) {
            // Already created by another tx / retry
            log.debug("[RECEIPT_ALREADY_EXISTS_RACE_SAFE] messageId={} userId={}",
                    message.getId(), userId);
        }
    }

    public List<MessageReceipt> getReceiptsForMessageIds(List<Long> messageIds, Long userId){

        log.debug("[FETCH_RECEIPTS] userId={} messageCount={}", userId, messageIds.size());

        try {
            log.info("[FETCH_RECEIPTS_SUCCESS] userId={} messageCount={}",userId,messageIds.size());
            return messageReceiptRepository
                    .findByMessageIdsAndUserId(messageIds, userId);
        } catch (Exception ex) {
            log.error("[DB_RECEIPT_FETCH_FAILED] userId={}", userId, ex);
            throw ex;
        }
    }

    // ReceiptService

    @Transactional
    public void markAllDelivered(Long userId, Map<Long, LocalDateTime> conversationJoinedAtMap) {

        log.info("[RECEIPT_MARK_ALL_DELIVERED] userId={} conversations={}",
                userId, conversationJoinedAtMap.keySet());

        try {
            conversationJoinedAtMap.forEach((conversationId, joinedAt) -> {
                messageReceiptRepository
                        .markDeliveredWithinWindow(conversationId, userId, joinedAt);
            });

            log.info("[RECEIPT_MARK_ALL_DELIVERED_SUCCESS] userId={}", userId);

        } catch (Exception ex) {
            log.error("[RECEIPT_MARK_ALL_DELIVERED_FAILED] userId={} error={}",
                    userId, ex.getMessage(), ex);
            throw ex; // IMPORTANT → triggers rollback
        }
    }

    @Transactional
    public void markAllSeen(Long conversationId, Long userId, LocalDateTime joinedAt) {

        log.info("[RECEIPT_MARK_ALL_SEEN] convoId={} userId={}", conversationId, userId);

        try {
            messageReceiptRepository
                    .markSeenWithinWindow(conversationId, userId, joinedAt);

            log.info("[RECEIPT_MARK_ALL_SEEN_SUCCESS] convoId={} userId={}", conversationId, userId);

        } catch (Exception ex) {
            log.error("[RECEIPT_MARK_ALL_SEEN_FAILED] convoId={} userId={} error={}",
                    conversationId, userId, ex.getMessage(), ex);
            throw ex; // rollback
        }
    }

    public MessageReceipt getReceiptByMessageAndUser(Long messageId, Long userId) {
        try {
            log.info("[GET_RECEIPT_REQUEST] messageId={}, userId={}", messageId, userId);

            MessageReceipt receipt = messageReceiptRepository
                    .findByMessageIdAndUserId(messageId, userId)
                    .orElseThrow(() -> {
                        log.warn("[RECEIPT_NOT_FOUND] messageId={}, userId={}", messageId, userId);
                        return new RuntimeException(
                                "Receipt not found for messageId=" + messageId + ", userId=" + userId
                        );
                    });

            log.info("[GET_RECEIPT_SUCCESS] messageId={}, userId={}", messageId, userId);
            return receipt;

        } catch (RuntimeException e) {
            // expected business case → log + rethrow
            log.error("[GET_RECEIPT_BUSINESS_FAIL] messageId={}, userId={}",
                    messageId, userId, e);
            throw e;

        } catch (Exception e) {
            // unexpected system failure
            log.error("[GET_RECEIPT_SYSTEM_FAIL] messageId={}, userId={}",
                    messageId, userId, e);
            throw new RuntimeException("Internal error while fetching receipt", e);
        }
    }

    @Transactional
    public void markDeletedForMe(MessageReceipt receipt) {

        try {
            log.debug("[MARK_DELETE_FOR_ME_INTERNAL] receiptId={}, currentState={}",
                    receipt.getId(), receipt.isDeletedForMe());

            receipt.setDeletedForMe(true);

            messageReceiptRepository.save(receipt);

            log.info("[MARK_DELETE_FOR_ME_INTERNAL_SUCCESS] receiptId={}",
                    receipt.getId());

        } catch (Exception e) {
            log.error("[MARK_DELETE_FOR_ME_INTERNAL_FAILED] receiptId={}",
                    receipt.getId(), e);
            throw e;
        }
    }

}