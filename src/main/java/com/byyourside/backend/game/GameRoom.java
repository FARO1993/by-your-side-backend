package com.byyourside.backend.game;

import com.byyourside.backend.user.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "game_rooms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GameType game;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_id", nullable = false)
    private User host;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guest_id", nullable = false)
    private User guest;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GameRoomStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 20)
    private GameRoomEndReason endReason;

    @Column(nullable = false)
    private long seed;

    @Column(name = "event_count", nullable = false)
    private int eventCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.lastActivityAt == null) {
            this.lastActivityAt = now;
        }
    }

    public boolean hasParticipant(UUID userId) {
        return host.getId().equals(userId) || guest.getId().equals(userId);
    }

    public User otherParticipant(UUID userId) {
        return host.getId().equals(userId) ? guest : host;
    }

    public void end(GameRoomEndReason reason) {
        this.status = GameRoomStatus.ENDED;
        this.endReason = reason;
        this.endedAt = Instant.now();
    }
}
