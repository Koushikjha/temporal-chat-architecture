package com.example.chat.conversationParticipant.service;

import com.example.chat.conversation.entity.Conversation;
import com.example.chat.conversation.enums.ConversationType;
import com.example.chat.conversation.service.ConversationService;
import com.example.chat.conversationParticipant.repo.ConversationParticipantRepository;
import com.example.chat.conversation.repo.ConversationRepository;
import com.example.chat.conversationParticipant.entity.ConversationParticipant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationParticipantService {

    private final ConversationParticipantRepository participantRepository;

    @Transactional
    public void addParticipantsInPrivate(Conversation conversation, Long senderId, Long receiverId) {

        log.debug("[ADD_PARTICIPANTS] conversationId={}, users=({}, {})",
                conversation.getId(), senderId, receiverId);

        addParticipant(conversation, senderId);
        addParticipant(conversation, receiverId);
    }

    private void addParticipant(Conversation conversation, Long userId) {

        try {
            ConversationParticipant participant = ConversationParticipant.builder()
                    .conversation(conversation)
                    .userId(userId)
                    .joinedAt(LocalDateTime.now())
                    .build();

            participantRepository.save(participant);

            log.info("[PARTICIPANT_ADDED] conversationId={}, userId={}",
                    conversation.getId(), userId);

        } catch (DataIntegrityViolationException ex) {
            // another thread already inserted
            log.debug("[PARTICIPANT_ALREADY_EXISTS_RACE_SAFE] conversationId={}, userId={}",
                    conversation.getId(), userId);
        }
    }

    @Transactional(readOnly = true)
    public Long findOtherParticipant(Long conversationId, Long userId) {

        log.info("[FIND_OTHER_PARTICIPANT] convoId={} userId={}", conversationId, userId);

        try {

            Long otherUserId = participantRepository
                    .findOtherParticipant(conversationId, userId)
                    .orElseThrow(() -> {
                        log.error("[OTHER_PARTICIPANT_NOT_FOUND] convoId={} userId={}",
                                conversationId, userId);
                        return new IllegalStateException("Other participant not found");
                    });

            log.info("[FIND_OTHER_PARTICIPANT_SUCCESS] convoId={} otherUserId={}",
                    conversationId, otherUserId);

            return otherUserId;

        } catch (Exception ex) {
            log.error("[FIND_OTHER_PARTICIPANT_FAILED] convoId={} userId={}",
                    conversationId, userId, ex);
            throw ex;
        }
    }

    public void validateParticipant(Long conversationId, Long userId) {

        log.info("[PARTICIPANT_VALIDATE] convoId={} userId={}", conversationId, userId);

        try {
            participantRepository.findByConversationAndUser(conversationId, userId)
                    .orElseThrow(() -> {
                        log.error("[USER_NOT_PARTICIPANT] convoId={} userId={}", conversationId, userId);
                        return new RuntimeException("User is not a participant of this conversation");
                    });

            log.info("[PARTICIPANT_VALIDATE_SUCCESS] convoId={} userId={}", conversationId, userId);

        } catch (Exception ex) {
            log.error("[PARTICIPANT_VALIDATE_FAILED] convoId={} userId={}", conversationId, userId, ex);
            throw ex;
        }
    }
}