package com.example.chat.messageReceipt.entity;

import com.example.chat.message.entity.ChatMessage;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "message_receipt",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_message_user",
                        columnNames = {"message_id", "user_id"}
                )
        },
        indexes = {
                @Index(name = "idx_receipt_message", columnList = "message_id"),
                @Index(name = "idx_receipt_user", columnList = "user_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id",nullable = false)
    private ChatMessage message;

    @Column(nullable = false)
    private boolean delivered = false;

    @Column(nullable = false)
    private boolean seen = false;

    @Column(
            name = "deleted_for_me",
            nullable = false
    )
    private boolean deletedForMe = false;

}