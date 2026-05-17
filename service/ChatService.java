package com.example.chat.service;

import com.example.auth.service.AuthService;
import com.example.chat.conversation.entity.Conversation;
import com.example.chat.conversation.enums.ConversationType;
import com.example.chat.conversation.service.ConversationService;
import com.example.chat.conversationLifecycle.entity.ConversationLifecycle;
import com.example.chat.conversationLifecycle.service.ConversationLifecycleService;
import com.example.chat.conversationParticipant.service.ConversationParticipantService;
import com.example.chat.dto.ConversationLifecycleDTO;
import com.example.chat.dto.ConversationListDTO;
import com.example.chat.dto.MessageDTO;
import com.example.chat.dto.ParticipantLifecycleDTO;
import com.example.chat.message.entity.ChatMessage;
import com.example.chat.message.service.MessageService;
import com.example.chat.messageReceipt.entity.MessageReceipt;
import com.example.chat.messageReceipt.service.MessageReceiptService;
import com.example.chat.participantLifecycle.entity.ParticipantLifecycle;
import com.example.chat.participantLifecycle.service.ParticipantLifecycleService;
import com.example.user.entity.User;
import com.example.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final ConversationService conversationService;
    private final ConversationParticipantService participantService;
    private final MessageService messageService;
    private final MessageReceiptService receiptService;
    private final ConversationLifecycleService conversationLifecycleService;
    private final ParticipantLifecycleService participantLifecycleService;
    private final UserService userService;
    private final HelperService helperService;

    // ===== SEND PRIVATE MESSAGE (handles both first message and subsequent) =====
    @Transactional
    public MessageDTO sendPrivateMessage(Long senderId, Long receiverId, String content, Long conversationId) {

        log.info("[SEND_PRIVATE_MESSAGE] sender={} receiver={} conversationId={}",
                senderId, receiverId, conversationId);

        // 1 — Validate receiver exists
        userService.validateExists(receiverId);

        Conversation conversation;

        if (conversationId == null) {

            // NEW CHAT — find or create conversation
            String pairKey = Math.min(senderId, receiverId) + "_" + Math.max(senderId, receiverId);
            log.debug("[PAIR_KEY] {}", pairKey);

            conversation = conversationService
                    .findByTypeAndPairKey(ConversationType.PRIVATE, pairKey)
                    .orElse(null);

            if (conversation == null) {
                log.info("[NO_CONVO] Creating new conversation pairKey={}", pairKey);

                conversation = conversationService.createPrivateConversation(senderId, receiverId);
                participantService.addParticipantsInPrivate(conversation, senderId, receiverId);
                conversationLifecycleService.startIfNotExists(conversation);
                participantLifecycleService.startIfNotExists(conversation.getId(), senderId);
                participantLifecycleService.startIfNotExists(conversation.getId(), receiverId);

            } else {
                log.info("[CONVO_EXISTS] id={} ensuring lifecycles", conversation.getId());

                // Conversation row exists but lifecycle may be closed — restart if needed
                conversationLifecycleService.startIfNotExists(conversation);
                participantLifecycleService.startIfNotExists(conversation.getId(), senderId);
                participantLifecycleService.startIfNotExists(conversation.getId(), receiverId);
            }

        } else {

            // EXISTING CHAT — validate and send
            conversation = conversationService
                    .getByIdAndType(conversationId, ConversationType.PRIVATE);

            // 2 — Sender must be an active participant
            participantLifecycleService.validateActiveParticipant(conversationId, senderId);

            // 3 — Receiver lifecycle: reopen if they had deleted for me (independent lifecycles)
            participantLifecycleService.startIfNotExists(conversationId, receiverId);
        }

        // 4 — Save message
        ChatMessage saved = messageService.savePrivateMessage(conversation, senderId, content);

        // 5 — Eager receipt creation for both participants
        receiptService.createInitialReceipts(saved, senderId, receiverId);

        // 6 — Update conversation ordering timestamp
        conversationService.updateLastTime(conversation, saved.getCreatedAt());

        log.info("[SEND_PRIVATE_MESSAGE_SUCCESS] messageId={} convoId={}",
                saved.getId(), conversation.getId());

        return helperService.toMessageDTO(saved, conversation.getId(), false, false);
    }

    // ===== DELETE CHAT FOR ME =====
    @Transactional
    public void deletePrivateConversationForMe(Long conversationId, Long userId) {

        log.info("[DELETE_CHAT_FOR_ME] convoId={} userId={}", conversationId, userId);

        conversationService.getByIdAndType(conversationId, ConversationType.PRIVATE);

        // Authorization — only active participant can delete for themselves
        participantLifecycleService.validateActiveParticipant(conversationId, userId);
        participantLifecycleService.endParticipantLifecycle(conversationId, userId);

        log.info("[CHAT_HIDDEN_FOR_USER] convoId={} userId={}", conversationId, userId);
    }

    // ===== DELETE CHAT FOR EVERYONE =====
    @Transactional
    public void deletePrivateConversationForEveryone(Long conversationId, Long userId) {

        log.info("[DELETE_PRIVATE_CHAT_FOR_EVERYONE] convoId={} userId={}", conversationId, userId);

        conversationService.getByIdAndType(conversationId, ConversationType.PRIVATE);

        // Authorization — only active participant can delete for everyone
        participantLifecycleService.validateActiveParticipant(conversationId, userId);

        // End conversation lifecycle globally
        conversationLifecycleService.endConversationLifecycle(conversationId);

        // End both participant lifecycles
        participantLifecycleService.endParticipantLifecycle(conversationId, userId);

        Long otherUserId = participantService.findOtherParticipant(conversationId, userId);
        participantLifecycleService.endParticipantLifecycle(conversationId, otherUserId);

        log.info("[DELETE_PRIVATE_CHAT_FOR_EVERYONE_COMPLETED] convoId={}", conversationId);
    }

    // ===== GET MESSAGES (latest window, no offset) =====
    @Transactional(readOnly = true)
    public List<MessageDTO> getMessagesPrivate(Long conversationId, Long userId) {

        log.info("[GET_MESSAGES] convoId={} userId={}", conversationId, userId);

        conversationService.getByIdAndType(conversationId, ConversationType.PRIVATE);
        conversationLifecycleService.validateActive(conversationId);
        participantService.validateParticipant(conversationId,userId);

        return helperService.fetchMessages(conversationId, userId, null);
    }

    // ===== GET MESSAGES (paginated with offsetId) =====
    @Transactional(readOnly = true)
    public List<MessageDTO> getMessagesPrivate(Long conversationId, Long userId, Long offsetId) {

        log.info("[GET_OLDER_MESSAGES] convoId={} userId={} offsetId={}", conversationId, userId, offsetId);

        conversationService.getByIdAndType(conversationId, ConversationType.PRIVATE);
        conversationLifecycleService.validateActive(conversationId);

        return helperService.fetchMessages(conversationId, userId, offsetId);
    }

    // ===== CONVERSATION LIFECYCLE HISTORY (time-travel) =====
    @Transactional(readOnly = true)
    public List<ConversationLifecycleDTO> getPrivateConversationLifecycleHistory(Long conversationId) {

        log.info("[LIFECYCLE_HISTORY] conversationId={}", conversationId);

        return conversationLifecycleService.getPrivateConversationLifecycleHistory(conversationId);
    }

    // ===== PARTICIPANT LIFECYCLES OF A CONVERSATION LIFECYCLE (time-travel) =====
    @Transactional(readOnly = true)
    public List<ParticipantLifecycleDTO> getPrivateParticipantLifecyclesOfConversationLifecycle(
            Long conversationId,
            Long conversationLifecycleId,
            Long requestingUserId
    ) {
        log.info("[PARTICIPANT_LIFECYCLES] convoId={} lifecycleId={} requestingUser={}",
                conversationId, conversationLifecycleId, requestingUserId);

        // Authorization — requesting user must be a participant of this conversation
        participantService.validateParticipant(conversationId, requestingUserId);

        // Service already validates conversationLifecycleId belongs to conversationId
        ConversationLifecycle conversationLifecycle = conversationLifecycleService
                .getConversationLifeCycle(conversationId, conversationLifecycleId);

        return participantLifecycleService
                .getPrivateParticipantLifecyclesOfConversationLifecycle(
                        conversationId,
                        conversationLifecycle.getStartedAt(),
                        conversationLifecycle.getEndedAt()
                );
    }

    // ===== LOAD MESSAGES OF A SPECIFIC PARTICIPANT LIFECYCLE (time-travel, latest) =====
    @Transactional(readOnly = true)
    public List<MessageDTO> loadMessagesOfLifecyclePrivate(Long participantLifecycleId, Long userId) {

        log.info("[LOAD_LIFECYCLE_MESSAGES] plId={} userId={}", participantLifecycleId, userId);

        // getLifecycleById validates ownership (userId must match lifecycle's userId)
        ParticipantLifecycle pl = participantLifecycleService
                .getLifecycleById(participantLifecycleId, userId);

        return helperService.fetchLifecycleMessages(pl, userId, null);
    }

    // ===== LOAD MESSAGES OF A SPECIFIC PARTICIPANT LIFECYCLE (time-travel, paginated) =====
    @Transactional(readOnly = true)
    public List<MessageDTO> loadMessagesOfLifecyclePrivate(
            Long participantLifecycleId,
            Long userId,
            Long offsetId
    ) {
        log.info("[LOAD_OLDER_LIFECYCLE_MESSAGES] plId={} userId={} offsetId={}",
                participantLifecycleId, userId, offsetId);

        ParticipantLifecycle pl = participantLifecycleService
                .getLifecycleById(participantLifecycleId, userId);

        return helperService.fetchLifecycleMessages(pl, userId, offsetId);
    }



    // ===== GET USER CONVERSATION LIST =====
    @Transactional(readOnly = true)
    public List<ConversationListDTO> getUserConversations(Long userId) {

        log.info("[CHAT_LIST_FETCH] userId={}", userId);

        List<ParticipantLifecycle> lifecycles =
                participantLifecycleService.findActiveByUser(userId);

        if (lifecycles.isEmpty()) {
            log.info("[NO_ACTIVE_CONVERSATIONS] userId={}", userId);
            return List.of();
        }

        List<Long> conversationIds = lifecycles.stream()
                .map(ParticipantLifecycle::getConversationId)
                .toList();

        // Single joined query — returns conversationId, otherUserId, username, lastMessageAt
        // Eliminates N+1: no per-row calls to participantService or userService
        List<ConversationListDTO> result = conversationService
                .findConversationListForUser(conversationIds, userId);

        log.info("[CHAT_LIST_SUCCESS] userId={} count={}", userId, result.size());

        return result;
    }


    @Transactional
    public void restoreLifecycle(Long senderId, Long receiverId) {

        log.info("[RESTORE_LIFECYCLE] senderId={} receiverId={}", senderId, receiverId);

        // 1 — Derive pairKey
        String pairKey = Math.min(senderId, receiverId) + "_" + Math.max(senderId, receiverId);

        // 2 — Find conversation — nothing to restore if it never existed
        Conversation conversation = conversationService
                .findByTypeAndPairKey(ConversationType.PRIVATE, pairKey)
                .orElseThrow(() -> new IllegalStateException("No conversation found to restore"));

        Long conversationId = conversation.getId();

        // 3 — Reject if sender already has an active lifecycle
        boolean alreadyActive = participantLifecycleService
                .getActiveLifecycle(conversationId, senderId)
                .isPresent();

        if (alreadyActive) {
            log.warn("[RESTORE_REJECTED] Active lifecycle already exists. convoId={} userId={}",
                    conversationId, senderId);
            throw new IllegalStateException("Chat is already active — nothing to restore");
        }

        // 4 — Find last closed lifecycle for sender
        ParticipantLifecycle lastClosed = participantLifecycleService
                .findLastClosedLifecycle(conversationId, senderId)
                .orElseThrow(() -> new IllegalStateException("No closed lifecycle found to restore"));

        // 5 — If conversation lifecycle ended, restore it
        conversationLifecycleService.startIfNotExists(conversation);

        // 6 — Undo the delete: set leftAt = null on the last closed lifecycle
        participantLifecycleService.undoLifecycleClose(lastClosed);

        log.info("[RESTORE_LIFECYCLE_SUCCESS] convoId={} senderId={}", conversationId, senderId);
    }

    @Transactional(readOnly = true)
    public boolean userHasLastClosedChat(Long senderId, Long receiverId) {

        log.info("[CHECK_RESTORE_ELIGIBILITY] userId={}, otherUserId={}", senderId, receiverId);

        try {
            // 1 — Build pair key (same logic you already use everywhere)
            String pairKey = Math.min(senderId, receiverId) + "_" + Math.max(senderId, receiverId);

            // 2 — Find conversation by pair key
            Optional<Conversation> optionalConversation =
                    conversationService.findByTypeAndPairKey(ConversationType.PRIVATE, pairKey);

            if (optionalConversation.isEmpty()) {
                log.info("[CHECK_RESTORE_ELIGIBILITY] No conversation found for pairKey={}", pairKey);
                return false;
            }

            Long conversationId = optionalConversation.get().getId();

            // 3 — Check if user has a last closed lifecycle
            boolean hasClosedLifecycle =
                    participantLifecycleService
                            .findLastClosedLifecycle(conversationId, senderId)
                            .isPresent();

            log.info("[CHECK_RESTORE_ELIGIBILITY] result={}, conversationId={}",
                    hasClosedLifecycle, conversationId);

            return hasClosedLifecycle;

        } catch (Exception ex) {
            log.error("[CHECK_RESTORE_ELIGIBILITY_ERROR] userId={}, otherUserId={}",
                    senderId, receiverId, ex);
            throw ex; // let global handler deal with it
        }
    }

    @Transactional
    public void markAllDelivered(Long userId) {

        log.info("[MARK_ALL_DELIVERED] userId={}", userId);

        List<ParticipantLifecycle> activeLifecycles =
                participantLifecycleService.findActiveByUser(userId);

        if (activeLifecycles.isEmpty()) {
            log.info("[MARK_ALL_DELIVERED_SKIP] No active lifecycles. userId={}", userId);
            return;
        }

        // ChatService assembles the window map — no lifecycle objects cross service boundary
        Map<Long, LocalDateTime> conversationJoinedAtMap = activeLifecycles.stream()
                .collect(Collectors.toMap(
                        ParticipantLifecycle::getConversationId,
                        ParticipantLifecycle::getJoinedAt
                ));

        // Single bulk update across all conversations in one query
        receiptService.markAllDelivered(userId, conversationJoinedAtMap);

        log.info("[MARK_ALL_DELIVERED_SUCCESS] userId={}", userId);
    }

    @Transactional
    public void markSeen(Long conversationId, Long userId) {

        log.info("[MARK_SEEN] convoId={} userId={}", conversationId, userId);

        conversationService.getByIdAndType(conversationId, ConversationType.PRIVATE);

        ParticipantLifecycle lifecycle = participantLifecycleService
                .getActiveLifecycle(conversationId, userId)
                .orElse(null);

        if (lifecycle == null) {
            log.warn("[MARK_SEEN_SKIP] No active lifecycle. convoId={} userId={}", conversationId, userId);
            return;
        }

        receiptService.markAllSeen(conversationId, userId, lifecycle.getJoinedAt());

        log.info("[MARK_SEEN_SUCCESS] convoId={} userId={}", conversationId, userId);
    }

    @Transactional
    public void editMessage(Long conversationId, Long messageId, Long userId, String newContent) {

        log.info("[EDIT_MESSAGE] convoId={} messageId={} userId={}", conversationId, messageId, userId);

        // 1 — Validate conversation
        conversationService.getByIdAndType(conversationId, ConversationType.PRIVATE);

        // 2 — Validate sender has active lifecycle
        participantLifecycleService.validateActiveParticipant(conversationId, userId);

        // 3 — Fetch message and validate ownership
        ChatMessage message = messageService.getByIdAndSender(messageId, userId);

        // 4 — Cannot edit a deleted message
        if (message.isDeletedForEveryone()) {
            log.warn("[EDIT_REJECTED] Message deleted for everyone. messageId={}", messageId);
            throw new IllegalStateException("Cannot edit a message deleted for everyone");
        }

        // 5 — Update content and editedAt only — receipts are never touched
        messageService.editMessage(message, newContent);

        log.info("[EDIT_MESSAGE_SUCCESS] messageId={}", messageId);
    }

    @Transactional
    public void deleteMessageForEveryone(Long conversationId, Long messageId, Long userId) {

        log.info("[DELETE_MESSAGE_FOR_EVERYONE] convoId={} messageId={} userId={}",
                conversationId, messageId, userId);

        // 1 — Validate conversation
        conversationService.getByIdAndType(conversationId, ConversationType.PRIVATE);

        // 2 — Validate sender has active lifecycle
        participantLifecycleService.validateActiveParticipant(conversationId, userId);

        // 3 — Fetch message and validate sender ownership
        ChatMessage message = messageService.getByIdAndSender(messageId, userId);

        // 4 — Sender's own receipt check — if deleted for me, they have no visibility over it
        MessageReceipt senderReceipt = receiptService.getReceiptByMessageAndUser(messageId, userId);

        if (senderReceipt.isDeletedForMe()) {
            log.warn("[DELETE_FOR_EVERYONE_REJECTED] Message already deleted for sender. messageId={} userId={}",
                    messageId, userId);
            throw new IllegalStateException("Cannot delete for everyone a message you have already deleted for yourself");
        }

        // 5 — Already deleted for everyone
        if (message.isDeletedForEveryone()) {
            log.warn("[DELETE_FOR_EVERYONE_REJECTED] Already deleted for everyone. messageId={}", messageId);
            throw new IllegalStateException("Message is already deleted for everyone");
        }

        // 6 — Set deletedForEveryone = true
        messageService.markDeletedForEveryone(message);

        log.info("[DELETE_MESSAGE_FOR_EVERYONE_SUCCESS] messageId={}", messageId);
    }

    @Transactional
    public void deleteMessageForMe(Long conversationId, Long messageId, Long userId) {

        log.info("[DELETE_MESSAGE_FOR_ME] convoId={} messageId={} userId={}",
                conversationId, messageId, userId);

        // 1 — Validate conversation
        conversationService.getByIdAndType(conversationId, ConversationType.PRIVATE);

        // 2 — Validate user has active lifecycle
        participantLifecycleService.validateActiveParticipant(conversationId, userId);

        // 3 — Fetch user's receipt
        MessageReceipt receipt = receiptService.getReceiptByMessageAndUser(messageId, userId);

        // 4 — Already deleted for me
        if (receipt.isDeletedForMe()) {
            log.warn("[DELETE_FOR_ME_REJECTED] Already deleted for me. messageId={} userId={}",
                    messageId, userId);
            throw new IllegalStateException("Message already deleted for me");
        }

        // 5 — Set deletedForMe = true on receipt only — ChatMessage untouched
        receiptService.markDeletedForMe(receipt);

        log.info("[DELETE_MESSAGE_FOR_ME_SUCCESS] messageId={} userId={}", messageId, userId);
    }


}