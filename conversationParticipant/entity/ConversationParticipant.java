package com.example.chat.conversationParticipant.entity;

import com.example.chat.conversation.entity.Conversation;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "conversation_participant",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_conversation",
                        columnNames = {"user_id", "conversation_id"}
                )
        },
        indexes = {
                @Index(name = "idx_cp_conversation", columnList = "conversation_id")
        })
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
public class ConversationParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id",nullable = false)
    private Conversation conversation;

    @Column(nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    @PrePersist
    public void prePersist() {
        this.joinedAt = LocalDateTime.now();
    }
}