package com.example.chat.conversation.repo;

import com.example.chat.conversation.entity.Conversation;
import com.example.chat.conversation.enums.ConversationType;
import com.example.chat.dto.ConversationListDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findById(Long id);
    Conversation save(Conversation conversation);
    Optional<Conversation> findByPairKey(String pairKey);
    Optional<Conversation> findByTypeAndPairKey(ConversationType conversationType,String pairKey);
    Optional<Conversation> findByIdAndType(Long id, ConversationType type);

    @Query("""
    SELECT c FROM Conversation c
    WHERE c.id IN :ids
    ORDER BY c.lastMessageAt DESC
""")
    List<Conversation> findAllByIdsOrderByLastMessage(List<Long> ids);

    @Query("""
    SELECT new com.example.chat.dto.ConversationListDTO(
        c.id,
        u.id,
        u.username,
        c.lastMessageAt
    )
    FROM Conversation c
    JOIN ConversationParticipant cp ON cp.conversation = c
    JOIN User u ON u.id = cp.userId
    WHERE c.id IN :conversationIds
      AND u.id <> :userId
    ORDER BY c.lastMessageAt DESC
""")
    List<ConversationListDTO> findConversationListForUser(
            List<Long> conversationIds,
            Long userId
    );
}