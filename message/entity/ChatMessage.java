package com.example.chat.message.entity;

import com.example.chat.conversation.entity.Conversation;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_message",
        indexes = {
                @Index(
                        name = "idx_msg_conv_del_id",
                        columnList = "conversation_id, deleted_for_everyone, id"
                ),
                @Index(name = "idx_msg_sender", columnList = "sender_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id",nullable = false)
    private Conversation conversation;

    @Column(nullable = false,length = 2000)
    private String content;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    @Column(
            name = "deleted_for_everyone",
            nullable = false
    )
    private boolean deletedForEveryone = false;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }


}