package com.byyourside.backend.game;

import com.byyourside.backend.game.dto.CreateGameRoomRequest;
import com.byyourside.backend.game.dto.GameEventRequest;
import com.byyourside.backend.game.dto.GameEventResponse;
import com.byyourside.backend.game.dto.GameRoomResponse;
import com.byyourside.backend.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/game-rooms")
@RequiredArgsConstructor
public class GameRoomController {

    private final GameRoomService gameRoomService;

    @PostMapping
    public ResponseEntity<GameRoomResponse> invite(@AuthenticationPrincipal UserPrincipal principal,
                                                   @Valid @RequestBody CreateGameRoomRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(gameRoomService.invite(principal, request.guestId(), request.game()));
    }

    @GetMapping
    public ResponseEntity<List<GameRoomResponse>> listOpen(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(gameRoomService.listOpen(principal));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<GameRoomResponse> get(@AuthenticationPrincipal UserPrincipal principal,
                                                @PathVariable UUID roomId) {
        return ResponseEntity.ok(gameRoomService.get(principal, roomId));
    }

    @PostMapping("/{roomId}/accept")
    public ResponseEntity<GameRoomResponse> accept(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable UUID roomId) {
        return ResponseEntity.ok(gameRoomService.accept(principal, roomId));
    }

    @PostMapping("/{roomId}/decline")
    public ResponseEntity<GameRoomResponse> decline(@AuthenticationPrincipal UserPrincipal principal,
                                                    @PathVariable UUID roomId) {
        return ResponseEntity.ok(gameRoomService.decline(principal, roomId));
    }

    @PostMapping("/{roomId}/leave")
    public ResponseEntity<GameRoomResponse> leave(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable UUID roomId) {
        return ResponseEntity.ok(gameRoomService.leave(principal, roomId));
    }

    @GetMapping("/{roomId}/events")
    public ResponseEntity<List<GameEventResponse>> events(@AuthenticationPrincipal UserPrincipal principal,
                                                          @PathVariable UUID roomId,
                                                          @RequestParam(defaultValue = "0") int after) {
        return ResponseEntity.ok(gameRoomService.events(principal, roomId, after));
    }

    @GetMapping("/{roomId}/history")
    public ResponseEntity<List<GameEventResponse>> history(@AuthenticationPrincipal UserPrincipal principal,
                                                           @PathVariable UUID roomId) {
        return ResponseEntity.ok(gameRoomService.history(principal, roomId));
    }

    @PostMapping("/{roomId}/events")
    public ResponseEntity<GameEventResponse> addEvent(@AuthenticationPrincipal UserPrincipal principal,
                                                      @PathVariable UUID roomId,
                                                      @Valid @RequestBody GameEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(gameRoomService.addEvent(principal, roomId, request.type(), request.payload()));
    }
}
