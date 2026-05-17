package com.example.chat.conversationLifecycle.service;

import com.example.chat.conversation.entity.Conversation;
import com.example.chat.conversationLifecycle.entity.ConversationLifecycle;
import com.example.chat.conversationLifecycle.repo.ConversationLifecycleRepository;
import com.example.chat.dto.ConversationLifecycleDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Service
public class ConversationLifecycleService {

    private final ConversationLifecycleRepository lifecycleRepository;

    @Transactional
    public void startIfNotExists(Conversation conversation) {

        Long convoId = conversation.getId();
        log.debug("[CHECK_CONVERSATION_LIFECYCLE] conversationId={}", convoId);

        if (isConversationActive(convoId)) {
            log.debug("[CONVERSATION_LIFECYCLE_ALREADY_ACTIVE] conversationId={}", convoId);
            return;
        }else{
            log.debug("[CONVERSATION_LIFECYCLE_NOT_ACTIVE] conversationId={}", convoId);
        }

        try {
            log.info("[STARTING_NEW_LIFECYCLE] conversationId={}", convoId);

            lifecycleRepository.save(
                    ConversationLifecycle.builder()
                            .conversation(conversation)
                            .build()
            );

            log.info("[CONVERSATION_LIFECYCLE_STARTED] conversationId={}", convoId);

        } catch (DataIntegrityViolationException ex) {
            log.warn("[RACE_CONDITION_AVOIDED] conversationId={}", convoId);
        }
    }

    @Transactional
    public void endConversationLifecycle(Long conversationId) {

        log.info("[ENDING_CONVERSATION_LIFECYCLE] convoId={}", conversationId);

        int updated = lifecycleRepository.endConversationLifecycle(conversationId);

        if (updated == 0) {
            log.warn("[CONVERSATION_ALREADY_ENDED_OR_NOT_FOUND] convoId={}", conversationId);
        } else {
            log.info("[CONVERSATION_LIFECYCLE_ENDED] convoId={}", conversationId);
        }
    }

    @Transactional(readOnly = true)
    public List<ConversationLifecycleDTO> getPrivateConversationLifecycleHistory(Long conversationId) {

        log.info("[FETCH_CONVERSATION_LIFECYCLE_HISTORY] conversationId={}", conversationId);

        try {

            List<ConversationLifecycle> lifecycles =
                    lifecycleRepository
                            .findAllByConversationIdOrderByStartDesc(conversationId);

            if (lifecycles.isEmpty()) {
                log.info("[NO_LIFECYCLE_FOUND] conversationId={}", conversationId);
                return List.of();
            }

            List<ConversationLifecycleDTO> result = lifecycles.stream()
                    .map(this::toDto)
                    .toList();

            log.info("[LIFECYCLE_HISTORY_SUCCESS] conversationId={} count={}",
                    conversationId, result.size());

            return result;

        } catch (Exception ex) {
            log.error("[LIFECYCLE_HISTORY_FAILED] conversationId={}", conversationId, ex);
            throw ex;
        }
    }


    public boolean isConversationActive(Long conversationId){
        return lifecycleRepository.existsByConversationIdAndEndedAtIsNull(conversationId);
    }

    private ConversationLifecycleDTO toDto(ConversationLifecycle cl) {
        return ConversationLifecycleDTO.builder()
                .lifecycleId(cl.getId())
                .startAt(cl.getStartedAt())
                .endAt(cl.getEndedAt())
                .active(cl.getEndedAt() == null)
                .build();
    }


    public ConversationLifecycle getConversationLifeCycle(Long conversationId,Long conversationLifecycleId) {
        log.info("[CONVERSATION_LIFECYCLE_FETCH] conversationLifecycleId={}",conversationLifecycleId);

        ConversationLifecycle conversationLifecycle=lifecycleRepository.findByIdAndConversationId
                        (conversationLifecycleId,conversationId)
                .orElse(null);
        if(conversationLifecycle==null){
            log.error("[NO_CONVERSATION_LIFECYCLE_FOUND] conversationLifecycleId={}",conversationLifecycleId);
            throw new IllegalStateException("no conversationLifecycle found for id");
        }
        log.info("[CONVERSATION_LIFECYCLE_FETCH_SUCCESS] conversationLifecycleId={}",
                conversationLifecycleId);
        return conversationLifecycle;
    }

    public void validateActive(Long conversationId) {

        log.info("[CONVO_LIFECYCLE_VALIDATE] conversationId={}", conversationId);

        try {
            lifecycleRepository.findActiveLifecycle(conversationId)
                    .orElseThrow(() -> {
                        log.error("[CONVO_NOT_ACTIVE] conversationId={}", conversationId);
                        return new RuntimeException("Conversation is not active");
                    });

            log.info("[CONVO_ACTIVE_OK] conversationId={}", conversationId);

        } catch (Exception ex) {
            log.error("[CONVO_LIFECYCLE_VALIDATE_FAILED] conversationId={}", conversationId, ex);
            throw ex;
        }
    }
}
