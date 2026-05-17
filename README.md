# Chat Module — Lifecycle-Driven Architecture for an Event-Sourced Real-Time Messaging System

> This module is a core submodule of a larger event-sourced, real-time messaging system.
> It is not a tutorial-grade chat implementation. Every design decision here exists to solve
> a real architectural problem — versioning, time-travel, receipt correctness, and lifecycle
> accuracy under concurrent, distributed conditions.

---

## Table of Contents

- [Module Overview](#module-overview)
- [Problems With Naive Chat Services](#problems-with-naive-chat-services)
- [Scope of This Module](#scope-of-this-module)
- [Architecture](#architecture)
- [Entity Design](#entity-design)
- [Lifecycle Design — The Core Insight](#lifecycle-design--the-core-insight)
- [Time-Travel](#time-travel)
- [Data Flow and Control Flow](#data-flow-and-control-flow)
- [Methods](#methods)
- [Key Decisions](#key-decisions)
- [Problems Encountered](#problems-encountered)
- [Phase Roadmap](#phase-roadmap)

---

**Current Status:** Phase 2 complete. Private chat only.
Group chat, real-time delivery, presence, and search are planned in subsequent phases.


## Module Overview

This chat module is built around **when a user was part of a conversation**, not just who is part of it.

Message visibility, authorization, history, and receipts are all derived from **time windows stored in lifecycles** — not from a simple membership check. This transforms chat from a state machine into a **temporal record of participation**.

The module is designed as a submodule of a larger platform currently consisting of:

- **Phase 1** — Auth and User Management
- **Phase 2** — Chat Module *(this module)*
- **Phase 3** — Event Log and Event Sourcing *(planned)*
- **Phase 4** — WebSocket Real-Time Layer *(planned)*
- **Phase 5** — Presence System via Redis *(planned)*
- **Phase 6** — Search via Elasticsearch *(planned)*

---

## Problems With Naive Chat Services

Most chat implementations found in tutorials and open-source repositories share the same structural weakness:

- **Membership check only** — "Is this user in the conversation?" determines everything. There is no concept of when they joined or left.
- **Message visibility is global** — A user who joins a group sees all messages since the beginning of time, regardless of when they joined.
- **Delete is destructive** — Deleting a message removes the row. History is gone.
- **No versioning** — A conversation has no concept of lifecycle versions. There is no way to reconstruct what the conversation looked like at a specific point in time.
- **Receipt state is fragile** — Seen and delivered flags are often stored on the message itself, making per-user state impossible without row duplication.
- **Re-join is not modelled** — If a user leaves and rejoins, the system has no record of the gap. They either see everything or nothing.

These are not edge cases. They are fundamental limitations that surface in any production chat system at scale.

---

## Scope of This Module

This module currently covers **private (1-to-1) chat** only. Group chat is explicitly out of scope for this phase to keep the lifecycle model clean and verifiable before extending it.

**What this module handles:**
- Creating and managing private conversations
- Sending, editing, and soft-deleting messages
- Per-user message receipt tracking (delivered, seen, deleted for me)
- Lifecycle-bounded message visibility
- Conversation and participant lifecycle management
- Time-travel reads across historical lifecycle windows
- Lifecycle restore (undo delete for me)
- Chat list retrieval with N+1 elimination

**What is intentionally deferred:**
- Real-time WebSocket delivery (Phase 4)
- Presence (online/offline/typing) via Redis (Phase 5)
- Full-text search via Elasticsearch (Phase 6)
- Group chat

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                    ChatService                       │
│         (Orchestration and business logic)           │
└────────────────────────┬────────────────────────────┘
                         │
          ┌──────────────┼──────────────┐
          │              │              │
          ▼              ▼              ▼
  ConversationService  MessageService  ReceiptService
          │
  ┌───────┴────────┐
  │                │
  ▼                ▼
ConversationLife  ParticipantLife
cycleService      cycleService
          │
          ▼
   ConversationParticipantService
          │
          ▼
     UserService
```

**Rule:** Services never communicate with each other directly. All cross-service orchestration happens exclusively inside `ChatService`. Individual services receive only entity-centric primitive parameters — no cross-service objects cross service boundaries.

Helper methods (DTO mapping, message fetching, lifecycle message fetching) are extracted into a `HelperService` in the same package to keep `ChatService` clean and readable.

---

## Entity Design

### `Conversation`
**Purpose:** Metadata container for a chat session.

| Column | Reason |
|---|---|
| `type` | Distinguishes PRIVATE from future GROUP |
| `pairKey` | Canonical `min(id)_max(id)` — guarantees uniqueness of private chat between two users at DB level |
| `createdAt` | Immutable creation timestamp |
| `lastMessageAt` | Used by frontend to order chat list descending — updated on every send |

Unique constraint on `(type, pairKey)` prevents duplicate private conversations at the database engine level, not just application level.

---

### `ConversationParticipant`
**Purpose:** Present-state membership record.

| Column | Reason |
|---|---|
| `userId` | Who is currently in the conversation |
| `conversation` | Which conversation |
| `joinedAt` | When they were added — immutable |

Unique constraint on `(userId, conversationId)` — a user can only have one participant record per conversation. This is **current state only**. It does not track history. History is the job of `ParticipantLifecycle`.

Used primarily for: finding the other participant in a private chat, and authorization checks.

---

### `ConversationLifecycle`
**Purpose:** Validity window of the conversation itself.

| Column | Reason |
|---|---|
| `conversation` | Which conversation this window belongs to |
| `startedAt` | When this version of the conversation became active — immutable |
| `endedAt` | When it was ended — null means currently active |

One `ConversationLifecycle` per active conversation. When a conversation is deleted for everyone, `endedAt` is set. If the conversation is later restored, a new `ConversationLifecycle` row is inserted — the old one remains for time-travel. This is a **write-based** entity — it records what happened to the conversation itself.

---

### `ParticipantLifecycle`
**Purpose:** Historical truth of user participation windows.

| Column | Reason |
|---|---|
| `conversationId` | Which conversation |
| `userId` | Which user |
| `joinedAt` | When this participation window opened — immutable |
| `leftAt` | When this window closed — null means currently active |

A user can have multiple `ParticipantLifecycle` rows for the same conversation — one per join/leave cycle. This is a **read-based** entity — it controls what messages a user is allowed to see. Message visibility is gated by:

```sql
message.createdAt >= lifecycle.joinedAt
AND (lifecycle.leftAt IS NULL OR message.createdAt <= lifecycle.leftAt)
```

This is the core of the lifecycle-window model.

---

### `ChatMessage`
**Purpose:** Immutable fact of a message existing.

| Column | Reason |
|---|---|
| `senderId` | Who sent it — stored as raw ID, not FK, for performance |
| `conversation` | Which conversation |
| `content` | Message text — mutable only via edit |
| `createdAt` | Immutable creation timestamp — used for lifecycle window queries |
| `editedAt` | Null until first edit — signals to clients that content changed |
| `deletedForEveryone` | Soft delete flag — content replaced with "message deleted" on both sides |

Messages are never removed from the database. `deletedForEveryone` hides content globally. `deletedForMe` lives on the receipt, not here. The two are independent — a user can delete for me even after the sender deleted for everyone.

Index on `(conversationId, deletedForEveryone, id)` supports the primary message fetch query pattern efficiently.

---

### `MessageReceipt`
**Purpose:** Per-user delivery and visibility state.

| Column | Reason |
|---|---|
| `userId` | Which user this receipt belongs to |
| `message` | Which message |
| `delivered` | Whether the message reached the user's device |
| `seen` | Whether the user opened and read it |
| `deletedForMe` | Whether this user removed it from their view |

Unique constraint on `(messageId, userId)` — exactly one receipt per user per message. Created eagerly at send time for both participants so delivery and seen updates are always `UPDATE` operations, never `INSERT` from nothing.

Receipts are **never reset** on message edit or delete for everyone. They record delivery facts that happened — mutating them after the fact would corrupt history and create loopholes (e.g. resetting seen count by editing a message).

---

## Lifecycle Design — The Core Insight

Most chat systems operate on a single question:

> **Is this user a member of this conversation?**

This module operates on a different question:

> **Was this user a member of this conversation when this message was sent?**

### ConversationLifecycle — The Room

Think of `ConversationLifecycle` as the **room itself being open or closed**. When a conversation is created, a lifecycle row is inserted with `startedAt = now`. When deleted for everyone, `endedAt` is set. The room is gone. If restored, a new room (new lifecycle row) is created. The old room's record is preserved for time-travel.

This is a **write-based** lifecycle. It tracks what happened to the conversation container.

### ParticipantLifecycle — Time in the Room

Think of `ParticipantLifecycle` as **how long a user was inside the room**. Every time a user joins, a new window opens (`joinedAt = now`, `leftAt = null`). When they leave or delete for me, the window closes (`leftAt = now`).

This is a **read-based** lifecycle. It gates what messages the user is allowed to see. If a user deletes a chat and rejoins later, they see only messages from their new `joinedAt` onward. Messages from before are outside their window — invisible, but never deleted.

This is why the system can support accurate re-join semantics, undo (restore), and time-travel simultaneously.

---

## Time-Travel

Because `ConversationLifecycle` and `ParticipantLifecycle` are append-only historical records, any past state of any conversation can be reconstructed.

**How it works:**

1. Fetch all `ConversationLifecycle` rows for a conversation — these are the versions of the conversation's existence in chronological order.
2. Select a specific `ConversationLifecycle` window by its `startedAt` and `endedAt`.
3. Fetch all `ParticipantLifecycle` rows whose windows overlap with that conversation lifecycle window.
4. Fetch all `ChatMessage` rows whose `createdAt` falls within the intersection of participant and conversation windows.

This gives an exact reconstruction of what the conversation looked like, who was present, and what was visible to each participant — at any point in the past.

**The room metaphor applied to time-travel:**

Each `ConversationLifecycle` is a room that existed between two timestamps. Each `ParticipantLifecycle` is how long someone was inside that room. To time-travel to a specific version of a conversation, you pick the room (conversation lifecycle) and look at who was inside (participant lifecycles within that window) and what was said (messages within that window).

---

## Data Flow and Control Flow

### Send Message Flow

```
Client sends message
        │
        ▼
ChatService.sendPrivateMessage(senderId, receiverId, content, conversationId)
        │
        ├── conversationId == null?
        │       │
        │       ├── YES → derive pairKey → find or create Conversation
        │       │         → ensure ConversationLifecycle active
        │       │         → ensure ParticipantLifecycle for both users
        │       │
        │       └── NO  → validate conversation exists
        │                 → validateActiveParticipant(senderId)
        │                 → startIfNotExists(receiverId lifecycle)
        │                   (receiver may have deleted for me — reopen independently)
        │
        ├── messageService.savePrivateMessage()
        ├── receiptService.createInitialReceipts() — eager, both participants
        └── conversationService.updateLastTime()
```

### Message Fetch Flow

```
Client requests messages
        │
        ▼
ChatService.getMessagesPrivate(conversationId, userId, offsetId?)
        │
        ├── validate conversation exists and lifecycle is active
        ├── getActiveLifecycle(conversationId, userId) → joinedAt, leftAt
        │
        ├── offsetId == null?
        │       ├── YES → findMessagesPrivate(conversationId, joinedAt, leftAt)
        │       └── NO  → findMessagesPrivate(conversationId, joinedAt, leftAt, offsetId)
        │                  (fetch messages with id < offsetId within window)
        │
        ├── getReceiptsForMessageIds(ids, userId)
        └── map to MessageDTO with delivered, seen per receipt
```

### Mark All Delivered Flow

```
User comes online
        │
        ▼
ChatService.markAllDelivered(userId)
        │
        ├── findActiveByUser(userId) → list of ParticipantLifecycle
        ├── build Map<conversationId, joinedAt> in ChatService
        │   (no lifecycle objects cross service boundary)
        └── receiptService.markAllDelivered(userId, map)
            → single bulk update within each active lifecycle window
            → messages before joinedAt are not marked delivered
```

---

## Methods

| Method | Description |
|---|---|
| `sendPrivateMessage` | Unified send — handles new conversation creation and subsequent messages in one method |
| `deletePrivateConversationForMe` | Closes sender's participant lifecycle — chat hidden, history preserved |
| `deletePrivateConversationForEveryone` | Ends conversation lifecycle and all participant lifecycles |
| `restoreLifecycle` | Reopens last closed participant lifecycle for sender — undo of delete for me |
| `userHasLastClosedChat` | Checks if sender has a restorable lifecycle before showing restore option in UI |
| `getMessagesPrivate` | Fetches latest messages within active lifecycle window — overloaded with offsetId for pagination |
| `loadMessagesOfLifecyclePrivate` | Time-travel read — fetches messages within a specific historical participant lifecycle window |
| `getPrivateConversationLifecycleHistory` | Returns all conversation lifecycle versions for time-travel navigation |
| `getPrivateParticipantLifecyclesOfConversationLifecycle` | Returns participant windows within a specific conversation lifecycle version |
| `getUserConversations` | Returns active chat list ordered by lastMessageAt — N+1 eliminated via single joined query |
| `markAllDelivered` | Bulk marks all undelivered receipts as delivered within active lifecycle windows — fires on user online |
| `markSeen` | Bulk marks all unseen receipts as seen within active lifecycle window for a conversation — fires on chat open |
| `editMessage` | Updates message content and editedAt — receipts untouched |
| `deleteMessageForEveryone` | Sets deletedForEveryone flag — content hidden globally, receipts untouched |
| `deleteMessageForMe` | Sets deletedForMe on sender's receipt — ChatMessage untouched |
| `searchMessages` | *(Used in future — Elasticsearch implementation pending)* |

---

## Key Decisions

**Independent participant lifecycles**
Each user's lifecycle is their own. If User A deletes a chat, User B's lifecycle is unaffected. User A's window closes independently. This matches Telegram-style behaviour and keeps lifecycle logic simple and symmetric.

**Receiver lifecycle reopened on message send**
When a sender sends a message to a receiver who deleted the chat, the receiver's participant lifecycle is reopened via `startIfNotExists`. This is intentional — delete for me is not a block. It is a visibility reset. New messages should be visible from the new window onward.

**Eager receipt creation**
Receipts are created for both participants at send time, not lazily at delivery or seen time. This means delivery and seen updates are always `UPDATE` operations against an existing row — never an `UPSERT` from nothing. Cleaner event-driven consumers, no race conditions on first delivery.

**Receipt state is never reset**
Editing a message does not reset seen or delivered. Deleting for everyone does not reset receipt state. Receipts record delivery facts — they are not re-evaluated based on content changes. Resetting them would corrupt unread counts and open loopholes.

**`seen` implies `delivered`**
`markAllSeen` sets both `seen = true` and `delivered = true` in a single update. A message cannot be seen without being delivered. The two flags are never inconsistent.

**N+1 elimination in chat list**
`getUserConversations` previously made 2 DB calls per conversation (find other participant + find user). Replaced with a single joined query in `ConversationService.findConversationListForUser` that returns all required data in one shot.

**Map-based bulk delivered update**
`markAllDelivered` assembles a `Map<conversationId, joinedAt>` in `ChatService` from active lifecycle data before passing it to `receiptService`. This keeps lifecycle objects inside `ChatService` and gives `receiptService` only primitives — modularity preserved, N+1 avoided.

**`pairKey` canonical form**
`pairKey` is always `min(userId)_max(userId)` — enforced at service layer before any DB operation. This prevents duplicate private conversations from two users initiating simultaneously.

**`ConversationLifecycle` as strict one-to-one in practice**
Conversations cannot be restarted. Only new conversations can be created. Each `ConversationLifecycle` row is a historical version of a conversation's existence — preserved for time-travel, never mutated.

**`HelperService` extraction**
DTO mapping, message fetching, and lifecycle message fetching are extracted into a `HelperService` in the same package as `ChatService`. This keeps `ChatService` focused on orchestration and business logic only. `HelperService` has no business rules — it is a pure utility layer.

---

## Problems Encountered

- **Chat versioning problem** — The core challenge this entire lifecycle architecture exists to solve. In naive chat systems, there is no concept of conversation versions. A user who deletes and rejoins sees all historical messages with no way to gate visibility. There is no record of the gap. Time-travel is impossible because there is no temporal record of participation. This was solved by introducing `ConversationLifecycle` and `ParticipantLifecycle` as first-class entities — making participation a time-windowed record rather than a boolean membership flag. Every other feature in this module flows from this single architectural decision.

- **Time-jumping conversation lifecycle vs user presence window** — Multiple `ConversationLifecycle` can exist for the same pair key across years, but user participation is not continuous. Early logic checked only `conversationId`, causing old messages to leak into restored chats and breaking visibility rules. This was fixed by making `ParticipantLifecycle (joinedAt → leftAt)` the sole authority for message visibility, turning it into the temporal gatekeeper for queries, receipts, restore logic, and chat list construction. This separation clearly distinguishes conversation existence from user presence within that conversation over time.

- **Lazy private conversation creation caused duplicate threads** — Using a missing conversationId from the client as a signal to create a chat led to multiple conversations for the same user pair due to race conditions and retries. The solution was to always resolve by pair key on the server at message-send time, creating a conversation only if none exists. This centralized responsibility, enforced the `(type, pair_key)` uniqueness rule, prevented fragmented histories, and ensured all lifecycles and message visibility map back to a single, consistent conversation timeline.

- **How are idempotent and failure-safe database operations ensured in complex chat flows?** — By wrapping critical service methods in `@Transactional` boundaries and guarding them with structured try–catch logging, operations like lifecycle updates, message receipts, and restore actions become atomic, retry-safe, and consistent even if partial failures occur.

- **How is pagination handled without breaking lifecycle-based visibility rules?** — The chat first loads a fixed batch of recent messages within the user’s active `ParticipantLifecycle` window. The topmost message’s `id` becomes the `offsetId`, and subsequent fetches iteratively load older messages above this id, always constrained by the same lifecycle time window. This ensures infinite scroll pagination never leaks messages from periods when the user was not part of the conversation.

- **How is chat restore safely offered without misidentifying active chats?** — The server does not try to detect whether a chat is “new”. Instead, it relies on a stronger guarantee: if an active `ParticipantLifecycle` existed, the chat would already appear in the user’s chat list. When opening a chat from search, the system only checks for the latest closed `ParticipantLifecycle` for that pair. If found, a restore option is shown. Restoring simply flips `leftAt → null` on that lifecycle, reactivating the exact previous participation window without recreating history or breaking temporal visibility rules.

- **How is the chat list accurately built without leaking inactive or historical conversations?** — The chat list is constructed only from conversations where the user has an active `ParticipantLifecycle`. For private chats, entries are sorted in descending order of `lastMessageAt` from the `Conversation` entity. The display name is derived from the receiver’s `username`, and the backend sends `conversationId`, `receiverId`, and `lastMessageAt` to the frontend. This ensures the list reflects only currently active participations while still preserving historical data separately through lifecycles.

*(More problems will be documented as the system evolves)*

---

## Phase Roadmap

| Phase | Description | Status |
|---|---|---|
| Phase 1 | Auth and User Management | ✅ Complete |
| Phase 2 | Chat Module — Lifecycle-driven private chat, message operations, receipt tracking, time-travel | ✅ Complete |
| Phase 3 | Event Log — Append-only event sourcing, idempotency, versioning, outbox pattern, Kafka publication | 🔜 Planned |
| Phase 4 | WebSocket Real-Time Layer — Live message delivery, typing indicators | 🔜 Planned |
| Phase 5 | Presence System — Online/offline/typing via Redis | 🔜 Planned |
| Phase 6 | Search — Full-text message search via Elasticsearch | 🔜 Planned |
