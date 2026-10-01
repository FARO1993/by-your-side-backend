package com.byyourside.backend.postresponse;

import com.byyourside.backend.post.Post;
import com.byyourside.backend.user.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

// Entidad que reemplaza a PostSupport (Backend Debt B1) -- evoluciona el
// mismo `post_supports`/hoy `post_responses` con un `type` tipado en vez de
// la presencia binaria anterior. UNIQUE(post_id, user_id): una sola
// respuesta ACTIVA por usuario/post -- cambiar de tipo es un UPDATE de esta
// misma fila, nunca una fila nueva (ver PostResponseService.upsertResponse,
// ver V12 para la migracion del dato historico).
//
// Nota de nombres: esta clase vive en el paquete `postresponse` para no
// confundirse con `com.byyourside.backend.post.dto.PostResponse` (el DTO
// generico de "un post" que devuelven feed/detail/posts-by-user) -- son dos
// conceptos sin relacion que comparten nombre por coincidencia historica de
// vocabulario ("post" + "response"). Ningun archivo necesita importar
// ambas clases a la vez.
@Entity
@Table(name = "post_responses", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"post_id", "user_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PostResponseType type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
