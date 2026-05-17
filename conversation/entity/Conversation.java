package com.example.chat.conversation.entity;

import com.example.chat.conversation.enums.ConversationType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "conversation",
        indexes = {
                @Index(name = "idx_conversation_created_at", columnList = "created_at"),
                @Index(name = "idx_conversation_pair_key", columnList = "pair_key")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_private_pair", columnNames = {"type", "pair_key"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConversationType type;

    @Column(name = "pair_key")
    private String pairKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_message_at", nullable = false)
    private LocalDateTime lastMessageAt;

    @PrePersist
    public void prePersist() {
        this.lastMessageAt=LocalDateTime.now();
        this.createdAt = LocalDateTime.now();
    }
}