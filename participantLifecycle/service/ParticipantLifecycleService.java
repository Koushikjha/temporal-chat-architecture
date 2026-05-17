package com.example.chat.participantLifecycle.service;

import com.example.chat.conversationLifecycle.entity.ConversationLifecycle;
import com.example.chat.dto.ConversationLifecycleDTO;
import com.example.chat.dto.ParticipantLifecycleDTO;
import com.example.chat.participantLifecycle.entity.ParticipantLifecycle;
import com.example.chat.participantLifecycle.repo.ParticipantLifecycleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantLifecycleService {

    private final ParticipantLifecycleRepository lifecycleRepository;

    @Transactional
    public void startIfNotExists(Long conversationId, Long userId) {

        log.debug("[CHECK_PARTICIPANT_LIFECYCLE] conversationId={} userId={}", conversationId, userId);

        if (isParticipationActive(conversationId, userId)) {
            log.debug("[PARTICIPANT_LIFECYCLE_ALREADY_ACTIVE] conversationId={} userId={}", conversationId, userId);
            return;
        }else{
            log.debug("[PARTICIPANT_LIFECYCLE_NOT_ACTIVE] conversationId={} userId={}", conversationId, userId);
        }

        try {
            log.info("[STARTING_PARTICIPANT_LIFECYCLE] conversationId={} userId={}", conversationId, userId);

            ParticipantLifecycle lifecycle = ParticipantLifecycle.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .build();

            lifecycleRepository.save(lifecycle);

            log.info("[PARTICIPANT_LIFECYCLE_STARTED] conversationId={} userId={}", conversationId, userId);

        } catch (DataIntegrityViolationException ex) {
            log.warn("[RACE_CONDITION_AVOIDED_PARTICIPANT] conversationId={} userId={}", conversationId, userId);
        }
    }

    @Transactional
    public void endParticipantLifecycle(Long conversationId, Long userId) {

        log.info("[ENDING_PARTICIPANT_LIFECYCLE] convoId={} userId={}", conversationId, userId);

        int updated = lifecycleRepository
                .endLifecycle(conversationId, userId);

        if (updated == 0) {
            log.warn("[NO_CHANGE_LIFECYCLE_ALREADY_INACTIVE] convoId={} userId={}",
                    conversationId, userId);
        }else{
            log.info("[PARTICIPANT_LIFECYCLE_ENDED] convoId={} userId={}", conversationId, userId);
        }


    }



    public boolean isParticipationActive(Long conversationId, Long userId) {
        return lifecycleRepository
                .existsByConversationIdAndUserIdAndLeftAtIsNull(conversationId, userId);
    }

    public Optional<ParticipantLifecycle> getActiveLifecycle(Long conversationId,Long userId){
        return lifecycleRepository.findByConversationIdAndUserIdAndLeftAtIsNull(conversationId,userId);
    }

    @Transactional(readOnly = true)
    public List<ParticipantLifecycleDTO> getPrivateParticipantLifecyclesOfConversationLifecycle(
            Long conversationId,
            LocalDateTime convStart,
            LocalDateTime convEnd) {

        log.info("[FETCH_PARTICIPANT_LIFECYCLES] conversationId={} start={} end={}",
                conversationId, convStart, convEnd);

        try {

            List<ParticipantLifecycle> lifecycles =
                    lifecycleRepository
                            .findByConversationAndTimeWindow(conversationId, convStart, convEnd);

            if (lifecycles.isEmpty()) {
                log.info("[NO_PARTICIPANT_LIFECYCLES_FOUND] conversationId={} start={} end={}",
                        conversationId, convStart, convEnd);
                return List.of();
            }

            List<ParticipantLifecycleDTO> result = lifecycles.stream()
                    .map(this::toDto)
                    .toList();

            log.info("[PARTICIPANT_LIFECYCLES_SUCCESS] conversationId={} count={}",
                    conversationId, result.size());

            return result;

        } catch (Exception ex) {
            log.error("[PARTICIPANT_LIFECYCLES_FAILED] conversationId={} start={} end={}",
                    conversationId, convStart, convEnd, ex);
            throw ex;
        }
    }

    private ParticipantLifecycleDTO toDto(ParticipantLifecycle pl) {
        return ParticipantLifecycleDTO.builder()
                .userId(pl.getUserId())
                .joinedAt(pl.getJoinedAt())
                .leftAt(pl.getLeftAt())
                .active(pl.getLeftAt() == null)
                .build();
    }

    @Transactional(readOnly = true)
    public ParticipantLifecycle getLifecycleById(Long participantLifecycleId, Long userId) {

        log.info("[FETCH_PL] plId={} userId={}", participantLifecycleId, userId);

        return lifecycleRepository
                .findByIdAndUserId(participantLifecycleId, userId)
                .orElseThrow(() -> {
                    log.error("[PL_NOT_FOUND_OR_UNAUTHORIZED] plId={} userId={}",
                            participantLifecycleId, userId);
                    return new IllegalStateException("ParticipantLifecycle not found or unauthorized");
                });
    }

    @Transactional(readOnly = true)
    public List<ParticipantLifecycle> findActiveByUser(Long userId) {

        log.info("[FETCH_ACTIVE_PARTICIPATIONS] userId={}", userId);

        try {

            List<ParticipantLifecycle> lifecycles =
                    lifecycleRepository.findActiveByUser(userId);

            log.info("[FETCH_ACTIVE_PARTICIPATIONS_SUCCESS] userId={} count={}",
                    userId, lifecycles.size());

            return lifecycles;

        } catch (Exception ex) {
            log.error("[FETCH_ACTIVE_PARTICIPATIONS_FAILED] userId={}", userId, ex);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public void validateActiveParticipant(Long conversationId, Long userId) {

        log.info("[VALIDATE_ACTIVE_PARTICIPANT] conversationId={} userId={}", conversationId, userId);

        try {

            boolean exists = lifecycleRepository
                    .existsActiveParticipant(conversationId, userId);

            if (!exists) {
                log.error("[USER_NOT_ACTIVE_PARTICIPANT] conversationId={} userId={}", conversationId, userId);
                throw new IllegalStateException("User is not an active participant of this conversation");
            }

            log.info("[ACTIVE_PARTICIPANT_VALIDATED] conversationId={} userId={}", conversationId, userId);

        } catch (Exception ex) {
            log.error("[VALIDATION_FAILED] conversationId={} userId={}", conversationId, userId, ex);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public Optional<ParticipantLifecycle> findLastClosedLifecycle(
            Long conversationId,
            Long userId
    ) {

        log.info(
                "[FIND_LAST_CLOSED_LIFECYCLE] conversationId={}, userId={}",
                conversationId, userId
        );

        try {
            Optional<ParticipantLifecycle> lifecycle =
                    lifecycleRepository.findLastClosedLifecycle(
                            conversationId, userId);

            if (lifecycle.isPresent()) {
                log.info(
                        "[LAST_CLOSED_LIFECYCLE_FOUND] lifecycleId={}",
                        lifecycle.get().getId()
                );
            } else {
                log.warn(
                        "[NO_CLOSED_LIFECYCLE_FOUND] conversationId={}, userId={}",
                        conversationId, userId
                );
            }

            return lifecycle;

        } catch (Exception ex) {
            log.error(
                    "[ERROR_FINDING_LAST_CLOSED_LIFECYCLE] conversationId={}, userId={}",
                    conversationId, userId, ex
            );
            throw ex;
        }
    }

    @Transactional
    public void undoLifecycleClose(ParticipantLifecycle lastClosed) {

        log.info("[UNDO_PARTICIPANT_LIFECYCLE_CLOSE] lifecycleId={}, userId={}, conversationId={}",
                lastClosed.getId(),
                lastClosed.getUserId(),
                lastClosed.getConversationId());

        try {
            lastClosed.setLeftAt(null);
            lifecycleRepository.save(lastClosed);

            log.info("[LIFECYCLE_RESTORED_SUCCESSFULLY] lifecycleId={}", lastClosed.getId());

        } catch (Exception ex) {
            log.error("[ERROR_UNDOING_PARTICIPANT_LIFECYCLE_CLOSE] lifecycleId={}",
                    lastClosed.getId(), ex);
            throw new RuntimeException("Failed to restore participant lifecycle", ex);
        }
    }
}
