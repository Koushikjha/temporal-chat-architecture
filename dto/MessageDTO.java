package com.example.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageDTO {
    private Long messageId;
    private Long conversationId;

    private Long senderId;
    private String content;

    private LocalDateTime createdAt;
    private LocalDateTime editedAt;

    private boolean delivered;
    private boolean seen;
}
