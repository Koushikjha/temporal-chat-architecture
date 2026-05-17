package com.example.chat.message.service;

import com.example.chat.conversation.entity.Conversation;
import com.example.chat.conversationLifecycle.repo.ConversationLifecycleRepository;
import com.example.chat.message.entity.ChatMessage;
import com.example.chat.message.repo.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.ObjectNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {
    private final ChatMessageRepository messageRepository;
    private final ConversationLifecycleRepository conversationLifecycleRepository;

    @Transactional
    public ChatMessage saveFirstMessage(Conversation conversation,
                                   Long senderId,
                                   String content) {

        // ️⃣ Save message
        ChatMessage message = ChatMessage.builder()
                .conversation(conversation)
                .senderId(senderId)
                .content(content)
                .build();

        try {
            ChatMessage saved = messageRepository.save(message);

            log.info("[MESSAGE_SAVED] id={} convoId={} sender={}",
                    saved.getId(), conversation.getId(), senderId);

            return saved;

        } catch (Exception ex) {
            log.error("[DB_SAVE_FAILED] convoId={} sender={}", conversation.getId(), senderId, ex);
            throw ex; // let transaction rollback
        }
    }

    @Transactional
    public ChatMessage savePrivateMessage(Conversation conversation,
                                            Long senderId,
                                            String content) {

        Long convoId = conversation.getId();

        log.debug("[WRITE_SAFE_SAVE_ATTEMPT] convoId={} sender={}", convoId, senderId);

        // 🔒 WRITE-SAFETY CHECK (time-of-use check)
        boolean convoStillActive =
                conversationLifecycleRepository
                        .existsByConversationIdAndEndedAtIsNull(convoId);

        if (!convoStillActive) {
            log.error("[WRITE_BLOCKED_CONVO_ENDED] convoId={}", convoId);
            throw new IllegalStateException("Conversation ended while sending message");
        }

        ChatMessage message = ChatMessage.builder()
                .conversation(conversation)
                .senderId(senderId)
                .content(content)
                .build();

        try {
            ChatMessage saved = messageRepository.save(message);

            log.info("[MESSAGE_SAVED_WRITE_SAFE] id={} convoId={} sender={}",
                    saved.getId(), convoId, senderId);

            return saved;

        } catch (Exception ex) {
            log.error("[DB_SAVE_FAILED] convoId={} sender={}", convoId, senderId, ex);
            throw ex;
        }
    }

    public List<ChatMessage> findMessagesPrivate(Long conversationId,
                                                 LocalDateTime visibleFrom) {

        log.debug("[FETCH_VISIBLE_MESSAGES] convoId={} from={}", conversationId, visibleFrom);

        try {
            Pageable pageable=PageRequest.of(0,30);
            return messageRepository.findLastMessages(conversationId, visibleFrom, pageable);
        } catch (Exception ex) {
            log.error("[DB_MSG_FETCH_FAILED] convoId={}", conversationId, ex);
            throw ex;
        }
    }

    public List<ChatMessage> findMessagesPrivate(Long conversationId,
                                                    LocalDateTime visibleFrom,Long offsetId) {

        log.debug("[FETCH_OLDER_MESSAGES] convoId={} from={} offsetId={}", conversationId, visibleFrom,offsetId);

        try {
            Pageable pageable=PageRequest.of(0,30);
            return messageRepository.findOlderMessages(conversationId, visibleFrom, offsetId,pageable);
        } catch (Exception ex) {
            log.error("[DB_MSG_FETCH_FAILED] convoId={}", conversationId, ex);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> loadMessagesOfLifecyclePrivate(
            Long conversationId,
            LocalDateTime joinedAt,
            LocalDateTime leftAt
    ) {
        log.info("[FETCH_MSG_LIFECYCLE] convoId={} joinedAt={} leftAt={}",
                conversationId, joinedAt, leftAt);

        try {
            Pageable pageable=PageRequest.of(0,30);
            List<ChatMessage> messages = messageRepository.findMessagesInsideWindow(
                    conversationId,
                    joinedAt,
                    leftAt,
                    pageable
            );

            log.info("[FETCH_MSG_LIFECYCLE_SUCCESS] convoId={} count={}",
                    conversationId, messages.size());

            return messages;

        } catch (Exception ex) {

            log.error("[FETCH_MSG_LIFECYCLE_FAILED] convoId={} joinedAt={} leftAt={}",
                    conversationId, joinedAt, leftAt, ex);

            throw new IllegalStateException("Failed to fetch lifecycle messages", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> loadMessagesOfLifecyclePrivate(
            Long conversationId,
            LocalDateTime joinedAt,
            LocalDateTime leftAt,
            Long offsetId
    ) {

        Pageable pageable = PageRequest.of(0, 30);

        log.info("[FETCH_OLDER_LIFECYCLE_MESSAGES] convoId={} offsetId={}", conversationId, offsetId);

        try {
            return messageRepository.findOlderMessagesInsideLifecycle(
                    conversationId,
                    joinedAt,
                    leftAt,
                    offsetId,
                    pageable
            );
        } catch (Exception ex) {
            log.error("[FETCH_FAILED] convoId={} offsetId={}", conversationId, offsetId, ex);
            throw ex;
        }
    }


    public ChatMessage getByIdAndSender(Long messageId, Long senderId) {
        try {
            log.info("[FETCH_MESSAGE] messageId={}, senderId={}", messageId, senderId);

            return messageRepository
                    .findByIdAndSenderId(messageId, senderId)
                    .orElseThrow(() -> {
                        log.warn("[MESSAGE_NOT_FOUND_OR_UNAUTHORIZED] messageId={}, senderId={}",
                                messageId, senderId);
                        return new IllegalStateException(
                                "Message not found or access denied. messageId=" + messageId
                        );
                    });

        } catch (IllegalStateException e) {
            // expected business exception → just rethrow after logging
            log.error("[GET_MESSAGE_FAILED] Business exception messageId={}, senderId={}",
                    messageId, senderId, e);
            throw e;

        } catch (Exception e) {
            // unexpected system failure
            log.error("[GET_MESSAGE_FAILED] Unexpected error messageId={}, senderId={}",
                    messageId, senderId, e);
            throw new RuntimeException("Internal server error while fetching message", e);
        }
    }

    @Transactional
    public void editMessage(ChatMessage message, String newContent) {

        try {
            if (newContent == null || newContent.trim().isEmpty()) {
                throw new IllegalArgumentException("Message content cannot be empty");
            }

            log.debug("[EDIT_MESSAGE_INTERNAL] messageId={}, oldContent={}, newContent={}",
                    message.getId(), message.getContent(), newContent);

            message.setContent(newContent);
            message.setEditedAt(LocalDateTime.now());

            messageRepository.save(message);

        } catch (Exception e) {
            log.error("[EDIT_MESSAGE_INTERNAL_FAILED] messageId={}", message.getId(), e);
            throw e;
        }
    }

    @Transactional
    public void markDeletedForEveryone(ChatMessage message) {

        try {
            log.debug("[MARK_DELETE_INTERNAL] messageId={}, currentState={}",
                    message.getId(), message.isDeletedForEveryone());

            message.setDeletedForEveryone(true);
            message.setEditedAt(LocalDateTime.now()); // optional but useful audit trail

            messageRepository.save(message);

            log.info("[MARK_DELETE_INTERNAL_SUCCESS] messageId={}", message.getId());

        } catch (Exception e) {
            log.error("[MARK_DELETE_INTERNAL_FAILED] messageId={}", message.getId(), e);
            throw e;
        }
    }
}