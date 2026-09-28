package com.byyourside.backend.postresponse;

import com.byyourside.backend.notification.NotificationService;
import com.byyourside.backend.notification.NotificationType;
import com.byyourside.backend.post.Post;
import com.byyourside.backend.post.PostAccessPolicy;
import com.byyourside.backend.post.PostRepository;
import com.byyourside.backend.postresponse.dto.PostResponseSummaryResponse;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Backend Debt B1: reemplaza PostSupportService. Punto UNICO de mutacion
// sobre PostResponse -- tanto los endpoints nuevos (PUT/DELETE .../response)
// como los legacy (POST/DELETE .../support) llaman aca, nunca a
// PostResponseRepository directamente desde el controller. Una sola fuente
// de verdad: los cuatro endpoints leen/escriben la misma fila.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostResponseService {

    private final PostResponseRepository postResponseRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final PostAccessPolicy postAccessPolicy;

    // Upsert real: sin respuesta previa -> INSERT + notifica (solo la
    // PRIMERA respuesta de este usuario a este post). Con respuesta previa
    // del MISMO tipo -> no-op idempotente (UPDATE que no cambia nada, sin
    // notificar). Con respuesta previa de OTRO tipo -> UPDATE de la misma
    // fila (nunca una fila nueva), sin notificar (cambiar de tipo no es una
    // respuesta nueva). Usado por PUT /response (cualquier tipo) y por la
    // compatibilidad legacy de POST /support (fija WITH_YOU).
    @Transactional
    public PostResponseSummaryResponse upsertResponse(UserPrincipal principal, UUID postId, PostResponseType type) {
        Post post = findViewablePostOrThrow(principal.getId(), postId);
        rejectSelfResponse(principal.getId(), post);

        User actor = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Optional<PostResponse> existing = postResponseRepository.findByPostIdAndUserId(postId, principal.getId());
        boolean isNew = existing.isEmpty();

        if (existing.isPresent()) {
            PostResponse response = existing.get();
            response.setType(type);
            postResponseRepository.save(response);
        } else {
            try {
                postResponseRepository.save(PostResponse.builder().post(post).user(actor).type(type).build());
            } catch (DataIntegrityViolationException e) {
                // Carrera: otro PUT concurrente ya inserto la fila entre el
                // chequeo de arriba y este save -- la reusamos y aplicamos
                // igual el tipo pedido por ESTA request como UPDATE, en vez
                // de perder la intencion del usuario o propagar un 500
                // (mismo criterio de catch que BlockService.blockUser/
                // MuteService.muteUser, pero acá con un dato -- el type --
                // que sí necesita aplicarse aunque la fila ya exista).
                PostResponse response = postResponseRepository.findByPostIdAndUserId(postId, principal.getId())
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.CONFLICT, "Could not save your response, please retry"));
                response.setType(type);
                postResponseRepository.save(response);
                // La fila ya existia (la creo la request que gano la
                // carrera) -- esta request solo actualizo el tipo, no
                // notifica una segunda vez.
                isNew = false;
            }
        }

        if (isNew) {
            notificationService.notify(post.getAuthor(), actor, NotificationType.NEW_POST_RESPONSE, postId);
        }

        return toSummary(postId, principal.getId());
    }

    // Idempotente: borrar sin respuesta previa no falla, solo no hace nada.
    // No hace falta un chequeo de auto-respuesta acá -- el autor nunca pudo
    // tener una fila propia en primer lugar (upsertResponse la rechaza), asi
    // que borrar "su" respuesta inexistente ya es un no-op por construccion.
    @Transactional
    public PostResponseSummaryResponse deleteResponse(UserPrincipal principal, UUID postId) {
        if (!postRepository.existsById(postId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found");
        }

        postResponseRepository.findByPostIdAndUserId(postId, principal.getId())
                .ifPresent(postResponseRepository::delete);

        return toSummary(postId, principal.getId());
    }

    // Compatibilidad legacy: POST /api/posts/{postId}/support. A proposito
    // preserva el contrato ORIGINAL exacto de PostSupportService.addSupport
    // -- 409 si ya existia CUALQUIER respuesta propia (nunca la actualiza a
    // WITH_YOU silenciosamente) -- para no sorprender a un consumidor que ya
    // integraba contra ese comportamiento. El upsert real de verdad (cambiar
    // de tipo sin conflicto) solo existe via PUT /response. Opera sobre la
    // misma fila/repositorio que el resto -- nunca una segunda fuente de
    // verdad.
    @Transactional
    public PostResponseSummaryResponse addLegacySupport(UserPrincipal principal, UUID postId) {
        Post post = findViewablePostOrThrow(principal.getId(), postId);
        rejectSelfResponse(principal.getId(), post);

        if (postResponseRepository.existsByPostIdAndUserId(postId, principal.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already sent support to this post");
        }

        return upsertResponse(principal, postId, PostResponseType.WITH_YOU);
    }

    // Compatibilidad legacy: DELETE /api/posts/{postId}/support. Preserva el
    // contrato ORIGINAL exacto de PostSupportService.removeSupport -- 404 si
    // no habia ninguna respuesta propia (a diferencia de deleteResponse,
    // idempotente). Borra la respuesta sin importar su tipo actual (si el
    // usuario la habia cambiado a HUG via el endpoint nuevo, este endpoint
    // legacy igual la borra -- "quitar mi respuesta" es una sola operacion
    // para toda la fila, no algo exclusivo de WITH_YOU).
    @Transactional
    public PostResponseSummaryResponse removeLegacySupport(UserPrincipal principal, UUID postId) {
        if (!postRepository.existsById(postId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found");
        }

        PostResponse response = postResponseRepository.findByPostIdAndUserId(postId, principal.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "You hadn't sent support to this post"));

        postResponseRepository.delete(response);

        return toSummary(postId, principal.getId());
    }

    // Mismo criterio 404 que PostService.getPost/CommentService: no revelar
    // que un post invisible existe permitiendo responderlo. Cubre perfil
    // PRIVATE, bloqueo (Fase 9.4, via ProfileAccessPolicy dentro de
    // PostAccessPolicy) y visibilidad normal del post -- mute NO se chequea
    // acá a proposito, porque mute nunca es control de acceso (Fase 9.5): si
    // A muteo a B pero puede acceder directamente al post de B, A puede
    // responder con total normalidad.
    private Post findViewablePostOrThrow(UUID viewerId, UUID postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

        if (!postAccessPolicy.canView(viewerId, post)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found");
        }

        return post;
    }

    private void rejectSelfResponse(UUID viewerId, Post post) {
        if (post.getAuthor().getId().equals(viewerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot respond to your own post");
        }
    }

    private PostResponseSummaryResponse toSummary(UUID postId, UUID currentUserId) {
        List<PostResponseCountProjection> counts = postResponseRepository.countGroupedByPostIds(List.of(postId));
        long presenceCount = counts.isEmpty() ? 0 : counts.get(0).getPresenceCount();
        long listeningCount = counts.isEmpty() ? 0 : counts.get(0).getListeningCount();

        String currentType = postResponseRepository.findByPostIdAndUserId(postId, currentUserId)
                .map(r -> r.getType().name())
                .orElse(null);

        return new PostResponseSummaryResponse(
                postId,
                currentType,
                presenceCount,
                listeningCount,
                presenceCount + listeningCount,
                currentType != null
        );
    }
}
