package com.example.chat.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Builder
@Getter
@Setter
public class ParticipantLifecycleDTO {

    private Long userId;
    private LocalDateTime joinedAt;
    private LocalDateTime leftAt;
    private boolean active;
}
