package com.byyourside.backend.game;

import com.byyourside.backend.block.BlockPolicy;
import com.byyourside.backend.chat.ConversationRepository;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.game.dto.GameEventResponse;
import com.byyourside.backend.game.dto.GameRoomMessage;
import com.byyourside.backend.game.dto.GameRoomResponse;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.dto.UserSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Salas de "jugar acompañado". El backend no conoce las reglas de ningun
// juego: valida QUIEN puede jugar con quien, ordena las jugadas (seq) y las
// reparte por WebSocket. Las reglas viven en el frontend, que arma el mismo
// tablero en ambos lados a partir de `seed`.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GameRoomService {

    static final String QUEUE = "/queue/game-rooms";
    /** Una invitacion es para jugar ahora: vence sola. */
    static final Duration INVITE_TTL = Duration.ofMinutes(30);
    /** Una partida sin jugadas durante este tiempo se da por terminada. */
    static final Duration IDLE_TTL = Duration.ofHours(6);
    static final int MAX_PENDING_INVITES = 5;
    static final int MAX_EVENTS_PER_ROOM = 5000;
    static final int MAX_PAYLOAD_CHARS = 2000;
    static final int MAX_EVENTS_PER_PAGE = 1000;

    private static final Set<GameRoomStatus> OPEN = Set.of(GameRoomStatus.INVITED, GameRoomStatus.ACTIVE);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GameRoomRepository roomRepository;
    private final GameRoomEventRepository eventRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final ConversationRepository conversationRepository;
    private final BlockPolicy blockPolicy;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public GameRoomResponse invite(UserPrincipal principal, UUID guestId, GameType game) {
        UUID hostId = principal.getId();
        if (hostId.equals(guestId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot invite yourself");
        }
        User host = userRepository.findById(hostId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        User guest = userRepository.findById(guestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Target user not found"));

        // Solo vinculos conocidos: mismo criterio que el chat (follow en
        // cualquier direccion) o una charla ya existente (por ejemplo, una
        // que empezo en Modo compañía). Un bloqueo se trata igual que "no se
        // conocen", con el mismo mensaje, para no revelarlo.
        if (blockPolicy.isBlockedBetween(hostId, guestId) || !areConnected(hostId, guestId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only invite people you already know");
        }

        GameRoom pending = roomRepository.findByHostIdAndGuestIdAndStatus(hostId, guestId, GameRoomStatus.INVITED)
                .map(this::expireIfStale)
                .filter(room -> room.getStatus() == GameRoomStatus.INVITED)
                .orElse(null);
        if (pending != null) {
            if (pending.getGame() == game) {
                return toResponse(pending);
            }
            // Otra invitacion al mismo par para otro juego: reemplaza la vieja.
            pending.end(GameRoomEndReason.CANCELLED);
            roomRepository.saveAndFlush(pending);
            notifyBoth(pending, GameRoomMessage.ofRoom(toResponse(pending)));
        }

        if (roomRepository.countByHostIdAndStatus(hostId, GameRoomStatus.INVITED) >= MAX_PENDING_INVITES) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many pending invitations");
        }

        GameRoom room = roomRepository.save(GameRoom.builder()
                .game(game)
                .host(host)
                .guest(guest)
                .status(GameRoomStatus.INVITED)
                .seed(RANDOM.nextInt(Integer.MAX_VALUE))
                .eventCount(0)
                .build());

        GameRoomResponse response = toResponse(room);
        messagingTemplate.convertAndSendToUser(guest.getUsername(), QUEUE, GameRoomMessage.ofInvitation(response));
        return response;
    }

    @Transactional
    public List<GameRoomResponse> listOpen(UserPrincipal principal) {
        return roomRepository.findByParticipantAndStatusIn(principal.getId(), OPEN).stream()
                .map(this::expireIfStale)
                .filter(room -> room.getStatus() != GameRoomStatus.ENDED)
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public GameRoomResponse get(UserPrincipal principal, UUID roomId) {
        GameRoom room = findForParticipant(roomId, principal.getId(), false);
        return toResponse(expireIfStale(room));
    }

    @Transactional
    public GameRoomResponse accept(UserPrincipal principal, UUID roomId) {
        GameRoom room = expireIfStale(findForParticipant(roomId, principal.getId(), true));
        if (!room.getGuest().getId().equals(principal.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the invited person can accept");
        }
        if (room.getStatus() != GameRoomStatus.INVITED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This invitation is no longer available");
        }
        if (blockPolicy.isBlockedBetween(room.getHost().getId(), room.getGuest().getId())) {
            endAndNotify(room, GameRoomEndReason.UNAVAILABLE);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This invitation is no longer available");
        }

        Instant now = Instant.now();
        room.setStatus(GameRoomStatus.ACTIVE);
        room.setStartedAt(now);
        room.setLastActivityAt(now);
        roomRepository.save(room);

        GameRoomResponse response = toResponse(room);
        notifyBoth(room, GameRoomMessage.ofRoom(response));
        return response;
    }

    @Transactional
    public GameRoomResponse decline(UserPrincipal principal, UUID roomId) {
        GameRoom room = expireIfStale(findForParticipant(roomId, principal.getId(), true));
        if (!room.getGuest().getId().equals(principal.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the invited person can decline");
        }
        if (room.getStatus() == GameRoomStatus.INVITED) {
            endAndNotify(room, GameRoomEndReason.DECLINED);
        }
        return toResponse(room);
    }

    // Salir siempre se puede y siempre es idempotente: nadie queda atrapado
    // en una partida.
    @Transactional
    public GameRoomResponse leave(UserPrincipal principal, UUID roomId) {
        GameRoom room = expireIfStale(findForParticipant(roomId, principal.getId(), true));
        if (room.getStatus() == GameRoomStatus.INVITED) {
            boolean isHost = room.getHost().getId().equals(principal.getId());
            endAndNotify(room, isHost ? GameRoomEndReason.CANCELLED : GameRoomEndReason.DECLINED);
        } else if (room.getStatus() == GameRoomStatus.ACTIVE) {
            endAndNotify(room, GameRoomEndReason.LEFT);
        }
        return toResponse(room);
    }

    @Transactional
    public GameEventResponse addEvent(UserPrincipal principal, UUID roomId, String type, JsonNode payload) {
        String serialized = serialize(payload);
        if (serialized.length() > MAX_PAYLOAD_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payload too large");
        }

        GameRoom room = expireIfStale(findForParticipant(roomId, principal.getId(), true));
        if (room.getStatus() != GameRoomStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This game is not active");
        }
        if (blockPolicy.isBlockedBetween(room.getHost().getId(), room.getGuest().getId())) {
            endAndNotify(room, GameRoomEndReason.UNAVAILABLE);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This game is not active");
        }
        if (room.getEventCount() >= MAX_EVENTS_PER_ROOM) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This game has reached its limit");
        }

        User actor = principal.getId().equals(room.getHost().getId()) ? room.getHost() : room.getGuest();
        int seq = room.getEventCount() + 1;
        GameRoomEvent event = eventRepository.save(GameRoomEvent.builder()
                .room(room)
                .seq(seq)
                .actor(actor)
                .type(type)
                .payload(serialized)
                .build());

        room.setEventCount(seq);
        room.setLastActivityAt(Instant.now());
        roomRepository.save(room);

        GameEventResponse response = toEventResponse(event, roomId);
        notifyBoth(room, GameRoomMessage.ofEvent(response));
        return response;
    }

    public List<GameEventResponse> events(UserPrincipal principal, UUID roomId, int after) {
        findForParticipant(roomId, principal.getId(), false);
        return eventRepository.findAfter(roomId, Math.max(0, after), PageRequest.of(0, MAX_EVENTS_PER_PAGE)).stream()
                .map(event -> toEventResponse(event, roomId))
                .toList();
    }

    // Llamado por BlockService dentro de su transaccion: un bloqueo cierra
    // cualquier invitacion o partida abierta entre las dos personas.
    @Transactional
    public void endRoomsBetween(UUID a, UUID b) {
        roomRepository.findBetweenAndStatusIn(a, b, OPEN)
                .forEach(room -> endAndNotify(room, GameRoomEndReason.UNAVAILABLE));
    }

    private boolean areConnected(UUID a, UUID b) {
        if (followRepository.existsByFollowerIdAndFollowingId(a, b) || followRepository.existsByFollowerIdAndFollowingId(b, a)) {
            return true;
        }
        UUID first = a.toString().compareTo(b.toString()) <= 0 ? a : b;
        UUID second = first.equals(a) ? b : a;
        return conversationRepository.findByUserAIdAndUserBId(first, second).isPresent();
    }

    // Quien no participa recibe 404 (no 403): no se revela que la sala existe.
    private GameRoom findForParticipant(UUID roomId, UUID userId, boolean forUpdate) {
        GameRoom room = (forUpdate ? roomRepository.findByIdForUpdate(roomId) : roomRepository.findById(roomId))
                .filter(r -> r.hasParticipant(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found"));
        return room;
    }

    private GameRoom expireIfStale(GameRoom room) {
        Instant now = Instant.now();
        boolean staleInvite = room.getStatus() == GameRoomStatus.INVITED
                && room.getCreatedAt().plus(INVITE_TTL).isBefore(now);
        boolean idle = room.getStatus() == GameRoomStatus.ACTIVE
                && room.getLastActivityAt().plus(IDLE_TTL).isBefore(now);
        if (staleInvite || idle) {
            room.end(GameRoomEndReason.EXPIRED);
            roomRepository.saveAndFlush(room);
        }
        return room;
    }

    private void endAndNotify(GameRoom room, GameRoomEndReason reason) {
        room.end(reason);
        roomRepository.saveAndFlush(room);
        notifyBoth(room, GameRoomMessage.ofRoom(toResponse(room)));
    }

    private void notifyBoth(GameRoom room, GameRoomMessage message) {
        messagingTemplate.convertAndSendToUser(room.getHost().getUsername(), QUEUE, message);
        messagingTemplate.convertAndSendToUser(room.getGuest().getUsername(), QUEUE, message);
    }

    private String serialize(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payload");
        }
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            return objectMapper.nullNode();
        }
    }

    private GameRoomResponse toResponse(GameRoom room) {
        Instant expiresAt = room.getStatus() == GameRoomStatus.INVITED ? room.getCreatedAt().plus(INVITE_TTL) : null;
        return new GameRoomResponse(
                room.getId(),
                room.getGame(),
                room.getStatus(),
                room.getEndReason(),
                summary(room.getHost()),
                summary(room.getGuest()),
                room.getSeed(),
                room.getEventCount(),
                room.getCreatedAt(),
                room.getStartedAt(),
                room.getEndedAt(),
                expiresAt
        );
    }

    private GameEventResponse toEventResponse(GameRoomEvent event, UUID roomId) {
        return new GameEventResponse(
                roomId,
                event.getSeq(),
                event.getActor().getId(),
                event.getType(),
                parse(event.getPayload()),
                event.getCreatedAt()
        );
    }

    private static UserSummary summary(User user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getDisplayName(), user.getAvatarUrl());
    }
}
