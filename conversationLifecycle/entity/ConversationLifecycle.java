package com.example.chat.conversationLifecycle.entity;

import com.example.chat.conversation.entity.Conversation;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "conversation_lifecycle",
        indexes = {
                @Index(name = "idx_cl_conversation", columnList = "conversation_id"),
                @Index(name = "idx_cl_active", columnList = "conversation_id, ended_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationLifecycle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;


    @PrePersist
    public void prePersist() {
        this.startedAt = LocalDateTime.now();
    }
}
