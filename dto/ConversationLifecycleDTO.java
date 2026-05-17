package com.example.chat.dto;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ConversationLifecycleDTO {

    private Long lifecycleId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private boolean active;   // endAt == null
}