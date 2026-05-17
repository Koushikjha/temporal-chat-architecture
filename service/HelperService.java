package com.example.chat.service;

import com.example.chat.conversation.service.ConversationService;
import com.example.chat.conversationLifecycle.service.ConversationLifecycleService;
import com.example.chat.conversationParticipant.service.ConversationParticipantService;
import com.example.chat.dto.MessageDTO;
import com.example.chat.message.entity.ChatMessage;
import com.example.chat.message.service.MessageService;
import com.example.chat.messageReceipt.entity.MessageReceipt;
import com.example.chat.messageReceipt.service.MessageReceiptService;
import com.example.chat.participantLifecycle.entity.ParticipantLifecycle;
import com.example.chat.participantLifecycle.service.ParticipantLifecycleService;
import com.example.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HelperService {

    private final ConversationService conversationService;
    private final ConversationParticipantService participantService;
    private final MessageService messageService;
    private final MessageReceiptService receiptService;
    private final ConversationLifecycleService conversationLifecycleService;
    private final ParticipantLifecycleService participantLifecycleService;
    private final UserService userService;


    // ===== SHARED MESSAGE FETCH HELPER =====
    public List<MessageDTO> fetchMessages(Long conversationId, Long userId, Long offsetId) {

        ParticipantLifecycle lifecycle = participantLifecycleService
                .getActiveLifecycle(conversationId, userId)
                .orElse(null);

        if (lifecycle == null) {
            log.warn("[NO_ACTIVE_PARTICIPATION] convoId={} userId={}", conversationId, userId);
            return List.of();
        }

        LocalDateTime visibleFrom = lifecycle.getJoinedAt();

        log.debug("[LIFECYCLE_WINDOW] joinedAt={}", visibleFrom);

        List<ChatMessage> messages = (offsetId == null)
                ? messageService.findMessagesPrivate(conversationId, visibleFrom)
                : messageService.findMessagesPrivate(conversationId, visibleFrom, offsetId);

        if (messages.isEmpty()) {
            log.info("[NO_MESSAGES_VISIBLE] convoId={}", conversationId);
            return List.of();
        }

        return mapToDTO(messages, conversationId, userId);
    }


    // ===== SHARED RECEIPT + DTO MAPPER =====
    public List<MessageDTO> mapToDTO(List<ChatMessage> messages, Long conversationId, Long userId) {

        List<Long> ids = messages.stream().map(ChatMessage::getId).toList();

        Map<Long, MessageReceipt> receiptMap = receiptService
                .getReceiptsForMessageIds(ids, userId)
                .stream()
                .collect(Collectors.toMap(r -> r.getMessage().getId(), r -> r));

        return messages.stream()
                .map(m -> {
                    MessageReceipt r = receiptMap.get(m.getId());
                    return toMessageDTO(
                            m,
                            conversationId,
                            r != null && r.isDelivered(),
                            r != null && r.isSeen()
                    );
                })
                .toList();
    }

    // ===== DTO CONSTRUCTOR HELPER =====
    public MessageDTO toMessageDTO(ChatMessage m, Long conversationId, boolean delivered, boolean seen) {
        return new MessageDTO(
                m.getId(),
                conversationId,
                m.getSenderId(),
                m.getContent(),
                m.getCreatedAt(),
                m.getEditedAt(),
                delivered,
                seen
        );
    }

    // ===== SHARED LIFECYCLE MESSAGE FETCH HELPER =====
    public List<MessageDTO> fetchLifecycleMessages(
            ParticipantLifecycle pl,
            Long userId,
            Long offsetId
    ) {
        Long conversationId = pl.getConversationId();

        // leftAt null = lifecycle still open = no upper bound
        // MessageService query must handle null leftAt safely
        List<ChatMessage> messages = (offsetId == null)
                ? messageService.loadMessagesOfLifecyclePrivate(
                conversationId, pl.getJoinedAt(), pl.getLeftAt())
                : messageService.loadMessagesOfLifecyclePrivate(
                conversationId, pl.getJoinedAt(), pl.getLeftAt(), offsetId);

        if (messages.isEmpty()) {
            log.info("[NO_MESSAGES_VISIBLE] convoId={}", conversationId);
            return List.of();
        }

        return mapToDTO(messages, conversationId, userId);
    }
}
