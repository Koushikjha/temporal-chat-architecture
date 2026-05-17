package com.example.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Builder
@Getter
@Setter
@AllArgsConstructor
public class ConversationListDTO {
    private Long conversationId;
    private Long otherUserId;
    private String username;
    private LocalDateTime lastMessageAt;
}
