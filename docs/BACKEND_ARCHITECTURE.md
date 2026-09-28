# Backend Architecture — ByYourSide

> Mapa técnico, no documentación teórica. Generado por auditoría directa del código el
> 2026-09-25, sobre `develop` (`db01de9`).

## Regla de mantenimiento

Cambios estructurales (módulo nuevo, entidad nueva, cambio de relación entre entidades,
cambio de estrategia de seguridad/storage/broker) deben reflejarse en este archivo en el
mismo commit/PR.

---

## Visión general

Monolito modular en **Spring Boot 3.3.4 / Java 21**, organizado por dominio (un paquete
por feature, no por capa técnica). Cada paquete de dominio es más o menos autocontenido:
entidad(es), enums, `*Repository` (Spring Data JPA), `*Service`, `*Controller`, y un
subpaquete `dto/` con records de request/response.

```
com.byyourside.backend
├── admin           AdminUserController (+ dto) — gestión de roles, solo ADMIN
├── auth            AuthController, AuthService (+ dto) — registro/login, público.
│                   EmailVerificationService/Token(Repository) — verificación de email.
│                   PasswordResetService/Token(Repository) — recuperación de contraseña.
│                   ChangePasswordService — cambio de contraseña autenticado (único
│                   endpoint de /api/auth que requiere JWT).
│                   AuthSessionService/Session(Repository) + AuthSessionRevocationGuard
│                   — refresh tokens con rotación y deteccion de reuse (Fase 1.5)
├── availability    Modo compañía: Availability, CompanionIntent
├── block           UserBlock, BlockPolicy (isBlockedBetween, punto central reutilizado
│                   por profile/post/follow/chat/notification/status/discover/
│                   availability), BlockService (bloqueo + limpieza transaccional) — Fase 9.4
├── chat            Conversation, Message — REST + push WebSocket
├── comment         Comment, CommentStatus — anidado bajo /api/posts/{postId}/comments
├── config          SecurityConfig, AdminBootstrap
├── email           Email transaccional: EmailService (interfaz) + ResendEmailService
├── exception       GlobalExceptionHandler, ErrorResponse
├── follow          Follow (relación N:N usuario→usuario), FollowRequest/Status,
│                   FollowState, FollowRequestService/Controller (Fase 9.3: solicitudes
│                   de seguimiento para perfiles PRIVATE + gestión de followers)
├── mute            UserMute, UserMuteRepository (findMutedIdsByMuter, unilateral),
│                   MuteService (mute/unmute/lista) — Fase 9.5. Deliberadamente NO tiene
│                   un "MutePolicy" equivalente a BlockPolicy: mute no es control de
│                   acceso, cada query que lo necesita filtra directo (ver más abajo)
├── notification     Notification (postId/statusId/followRequestId, ninguno con FK),
│                   NotificationType, NotificationService (notify/markAsRead/
│                   markAllAsRead) — generadas internamente, nunca por API directa
│                   salvo mark-one/read-all/unread-count (Backend Debt B3)
├── post            Post, PostVisibility, PostStatus, PostAccessPolicy (Fase 9.1: "puede
│                   este viewer ver este post", reutilizado por comment/postresponse)
├── postresponse    PostResponse, PostResponseType (WITH_YOU/NOT_ALONE/HUG/READING/
│                   TELL_ME_MORE/LISTENING), PostResponseService — respuesta tipada a un
│                   post, reemplaza al antiguo paquete `support`/PostSupport (Backend
│                   Debt B1). Sin "PostResponsePolicy": reutiliza PostAccessPolicy tal
│                   cual, la respuesta nunca es su propio control de acceso
├── report          Report, ReportReason/Status/TargetType — moderación
├── security        JWT: JwtService, JwtAuthenticationFilter, UserPrincipal, CustomUserDetailsService
├── status          "Estado de ánimo": Status, StatusMood, StatusReaction, StatusReactionType
├── storage         Cloudinary: ImageStorageService (interfaz) + CloudinaryImageStorageService
├── user            User, UserRole, UserStatus, ProfileVisibility, ProfileAccessPolicy
│                   (Fase 9.1) — entidad central, referenciada por casi todo
└── websocket       WebSocketConfig, StompAuthChannelInterceptor
```

No hay capa de "controller → DTO de dominio → service → repository" separada
estrictamente: los `Service` arman los DTO de respuesta directamente desde las
entidades JPA (métodos privados `toResponse(...)` en cada service), sin un mapper
dedicado (ni MapStruct ni similar).

## Entidades y relaciones

Todas las entidades usan `UUID` (`GenerationType.UUID`) como PK, timestamps `Instant`, y
el patrón Lombok `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`.

```
User (users)
 ├─ 1:N → Post (posts.author_id)
 ├─ 1:N → Comment (comments.author_id)
 ├─ 1:N → Follow como follower (follows.follower_id)
 ├─ 1:N → Follow como following (follows.following_id)
 ├─ 1:N → Report como reporter (reports.reporter_id)
 ├─ 1:N → Report como reviewedBy (reports.reviewed_by_id, nullable)
 ├─ 1:N → PostResponse (post_responses.user_id, antes post_supports)
 ├─ 1:N → Notification como recipient / actor (notifications.recipient_id / actor_id)
 ├─ 1:N → Status (statuses.user_id)
 ├─ 1:N → StatusReaction como actor (status_reactions.actor_id)
 ├─ 1:N → Availability (availabilities.user_id)
 ├─ 1:N → Conversation como userA / userB (conversations.user_a_id / user_b_id)
 ├─ 1:N → Message como sender (messages.sender_id)
 ├─ 1:N → EmailVerificationToken (email_verification_tokens.user_id)
 ├─ 1:N → PasswordResetToken (password_reset_tokens.user_id)
 ├─ 1:N → AuthSession (auth_sessions.user_id)
 └─ 1:N → FollowRequest como requester / target (follow_requests.requester_id / target_id)

Post (posts)
 ├─ N:1 → User (author)
 ├─ 1:N → Comment
 └─ 1:N → PostResponse

Comment (comments) — N:1 → Post, N:1 → User (author)
Follow (follows) — N:1 → User (follower), N:1 → User (following), UNIQUE(follower_id, following_id)
Report (reports) — N:1 → User (reporter), N:1 → User (reviewedBy, nullable). target_id/target_type
                    son una referencia POLIMÓRFICA (no FK) a Post/Comment/User.
PostResponse (post_responses, Backend Debt B1 — tabla `post_supports` renombrada/
              evolucionada in-place, ver V12) — N:1 → Post, N:1 → User, `type`
              (`PostResponseType`), UNIQUE(post_id, user_id): una sola respuesta activa
              por usuario/post, cambiar de tipo es UPDATE de la misma fila
Notification (notifications) — N:1 → User (recipient), N:1 → User (actor). post_id/
                        status_id/follow_request_id opcionales, sin FK ninguno (Backend
                        Debt B3 agrega los dos ultimos en V13, mismo criterio que post_id
                        desde V1) -- cada NotificationType puebla como maximo uno de los
                        tres
Status (statuses) — N:1 → User
StatusReaction (status_reactions) — N:1 → Status, N:1 → User (actor), UNIQUE(status_id, actor_id)
Availability (availabilities) — N:1 → User
Conversation (conversations) — N:1 → User (userA), N:1 → User (userB), UNIQUE(user_a_id, user_b_id)
                                 (userA/userB en orden canónico por UUID string, para no duplicar
                                 la conversación sin importar quién la inició)
Message (messages) — N:1 → Conversation, N:1 → User (sender)
EmailVerificationToken (email_verification_tokens) — N:1 → User. Guarda tokenHash
                        (SHA-256, no el valor real), expiresAt, usedAt (nullable =
                        no consumido), invalidatedAt. UNIQUE(token_hash).
PasswordResetToken (password_reset_tokens) — N:1 → User. Misma forma que
                        EmailVerificationToken (tokenHash SHA-256, expiresAt, usedAt,
                        invalidatedAt), pero en tabla independiente: son dos
                        credenciales de un solo uso con ciclos de vida distintos.
                        Expira a los 30 minutos (vs. 24hs de verificación de email).
                        UNIQUE(token_hash).
AuthSession (auth_sessions) — N:1 → User. Una fila = una GENERACION de refresh token
                        (no "una sesion" en si misma); familyId agrupa todas las
                        generaciones de una misma sesion/dispositivo a medida que se
                        rota. tokenHash SHA-256 (nunca el valor real), expiresAt,
                        lastUsedAt, rotatedAt (nullable = generacion vigente),
                        revokedAt (nullable = no revocada). Expira a los 30 dias desde
                        la ULTIMA rotacion (ventana deslizante). UNIQUE(token_hash),
                        INDEX(user_id), INDEX(family_id). Ver § Sesiones.
FollowRequest (follow_requests) — N:1 → User (requester), N:1 → User (target). El
                        TRAMITE de una solicitud a un perfil PRIVATE -- status
                        (PENDING/ACCEPTED/REJECTED/CANCELLED), createdAt, respondedAt
                        (nullable). CHECK(requester_id <> target_id). Indice UNICO
                        PARCIAL (requester_id, target_id) WHERE status = 'PENDING' --
                        una sola solicitud activa por par, pero historial ilimitado de
                        filas resueltas. Ver § Privacidad, Follow requests.
```

Todas las relaciones `@ManyToOne` son `FetchType.LAZY` con `JOIN FETCH` explícito en las
queries que arman listados (para evitar N+1).

## Seguridad

- **Spring Security 6** stateless (`SessionCreationPolicy.STATELESS`), sin sesiones de
  servidor, sin CSRF (deshabilitado — no aplica sin cookies de sesión).
- **JWT = access token únicamente** (`io.jsonwebtoken` / jjwt 0.12.6, HMAC-SHA vía
  `Keys.hmacShaKeyFor`). Un solo secreto simétrico (`app.jwt.secret`, mín. 32 chars
  recomendado, no forzado por código). Claims: `sub` (username interno), `userId`,
  `role`, `iat`, `exp`. Expiración configurable (`app.jwt.access-expiration-ms`, default
  **15 minutos** desde Fase 1.5 — antes era `app.jwt.expiration-ms` con default 24h;
  esa vida larga ahora la cubre el refresh token, ver § Sesiones). Sigue siendo
  puramente stateless: `JwtService.isTokenValid` valida solo firma + expiración, **nunca
  consulta la base** — ningún endpoint protegido por JWT hace una query extra por esto.
- **Login es por email** (`LoginRequest.email`), pero **todo el resto del pipeline de
  Spring Security sigue siendo por username interno**: `AuthService.login` resuelve
  `email → User → username` y autentica con `UsernamePasswordAuthenticationToken(username, password)`
  contra `CustomUserDetailsService` (que carga por `findByUsername`). El JWT emitido tiene
  el username en el `sub`, y tanto `JwtAuthenticationFilter` (REST) como
  `StompAuthChannelInterceptor` (WebSocket) resuelven la identidad por username, no por
  email ni por UUID directamente.
- **Username interno**: nunca elegido por el usuario. Se autogenera en `AuthService`
  desde `displayName` (slug ASCII, sin acentos, minúsculas, máx. 24 chars, sufijo
  numérico ante colisión). Sirve como handle público (`@username` implícito en
  `UserSummary`), como identidad de sesión de Spring Security, y como key de
  enrutamiento de WebSocket (`convertAndSendToUser`).
- **Roles**: `USER`, `MODERATOR`, `ADMIN` — un solo `GrantedAuthority` por usuario
  (`ROLE_<role>`, sin roles múltiples). Autorización declarativa con
  `@PreAuthorize("hasRole(...)")` / `hasAnyRole(...)` (`@EnableMethodSecurity`), usada en
  `ReportController` (cola + resolución) y `AdminUserController` (a nivel de clase).
- **401 vs 403 diferenciado explícitamente**: `AccessDeniedHandler` custom en
  `SecurityConfig` chequea si hay `Authentication` en el contexto — si no la hay, delega
  al `AuthenticationEntryPoint` (401); si la hay pero falta el rol, devuelve 403 real.
  Antes de introducir `@PreAuthorize` (módulo Report) todo denial era 401 indistintamente.
- **Bootstrap del primer admin**: `AdminBootstrap` (`CommandLineRunner`) promueve a
  `ADMIN` al usuario cuyo `username` coincide con `ADMIN_BOOTSTRAP_USERNAME`, solo si
  **no existe ya ningún ADMIN** en la base. No hace nada en arranques subsiguientes. Es
  la única vía para crear el primer admin (`AdminUserController` ya requiere ser ADMIN
  para promover a otros — problema del huevo y la gallina que este bootstrap resuelve).
- **CORS**: configurado centralizadamente en `SecurityConfig` (orígenes desde
  `CORS_ALLOWED_ORIGINS`), reutilizado también por `WebSocketConfig` para el handshake
  del endpoint `/ws`.
- **`/api/auth/**` no es uniformemente público**: la regla base es
  `requestMatchers("/api/auth/**").permitAll()`, pero **antes** de esa regla (Spring
  Security evalúa `requestMatchers` en orden, primera coincidencia gana) hay una regla
  específica `requestMatchers(HttpMethod.POST, "/api/auth/change-password").authenticated()`
  (Fase 1.4). Es el único endpoint del namespace `/api/auth` que requiere JWT. Si se
  agrega un endpoint nuevo bajo `/api/auth` que también deba requerir autenticación, hay
  que repetir este patrón (regla específica antes del `permitAll` amplio), no asumir que
  alcanza con chequear la autenticación a mano dentro del controller/service.
  `/api/auth/refresh` y `/api/auth/logout` (Fase 1.5) caen bajo el `permitAll` amplio a
  propósito -- ninguno de los dos requiere ni chequea un JWT, se identifican
  exclusivamente por el refresh token del body (ver § Sesiones).

## Sesiones (refresh tokens, Fase 1.5)

Modelo de dos tokens: el **access token** (JWT, stateless, 15 min) autentica requests
normales; el **refresh token** (opaco, persistido, 30 días) es lo único que permite
obtener access tokens nuevos sin volver a loguearse. `AuthSessionService` (paquete
`auth`) concentra toda la lógica.

- **Familia = sesión**: cada login/register genera un `familyId` (UUID) nuevo. Una
  familia agrupa TODAS las generaciones de refresh token de un mismo dispositivo/login a
  medida que se rota -- rotar **nunca** sobreescribe el hash de la fila existente, crea
  una fila nueva con el mismo `familyId`. Esto es lo que permite reconocer una
  generación vieja si reaparece más tarde (reuse), sin importar cuántos saltos de
  rotación haya habido desde entonces. Un login nuevo siempre arranca una familia propia
  -- nunca reutiliza ni cierra la de otro dispositivo ya conectado (`AuthService`
  inyecta `AuthSessionService.createSession(user)` tanto en `register` como en `login`).
- **Rotación obligatoria** (`AuthSessionService.refresh`): cada `POST /api/auth/refresh`
  exitoso marca la fila presentada como `rotatedAt = now` y crea una fila hija en la
  misma familia con un refresh token nuevo. El token presentado queda inutilizable de
  inmediato -- no hay ventana de gracia para reusarlo "por las dudas".
- **Reuse detection**: si una fila con `rotatedAt` ya seteado vuelve a presentarse
  (`session.isRotated()` true al momento del lookup), es la firma clásica de un token
  copiado/robado -- alguien tiene una copia de una generación que el dueño legítimo ya
  dejó atrás. Se revoca toda la familia (`revokeFamily`, bulk update por `familyId`) y
  se responde `409`. **Cualquier descendiente de esa familia**, incluida la generación
  más reciente que nunca se usó indebidamente, queda inutilizable a partir de ahí.
- **`AuthSessionRevocationGuard` — bean separado a propósito**: la revocación por reuse
  tiene que sobrevivir aunque `refresh()` termine lanzando la `ResponseStatusException`
  que informa el `409` al cliente (por default, una excepción no atrapada revierte TODA
  la transacción del método, lo que borraría la revocación junto con el resto). La
  solución es `@Transactional(propagation = REQUIRES_NEW)` -- pero esa anotación **solo
  funciona si la llamada pasa por el proxy de Spring**. Un primer intento la puso como
  método propio de `AuthSessionService` y la llamó como `this.revokeFamilyIndependently(...)`
  desde `refresh()`: ese patrón es **self-invocation**, la llamada nunca pasa por el
  proxy, `REQUIRES_NEW` se ignora en silencio, y la revocación se revertía igual (bug
  real, detectado por los tests de esta misma fase: `shouldRevokeWholeFamily_whenReuseDetected`
  fallaba porque NINGUNA fila terminaba revocada). La solución fue mover ese único
  método a un bean `@Component` separado (`AuthSessionRevocationGuard`), inyectado en
  `AuthSessionService` -- al ser una llamada a OTRO bean, sí atraviesa el proxy y la
  nueva transacción se confirma de verdad, independiente de que la de `refresh()` se
  revierta después. **Lección para el resto del código**: cualquier necesidad futura de
  `REQUIRES_NEW` (u otra propagación no-default) tiene que vivir en un bean distinto del
  que la invoca, nunca como llamada `this.metodo()` dentro de la misma clase.
- **Concurrencia — claim atómico, sin locking explícito**: dos requests de refresh
  simultáneas sobre el mismo token no deben poder producir dos hijos válidos.
  `AuthSessionRepository.claimForRotation` es un `UPDATE ... WHERE rotated_at IS NULL
  AND revoked_at IS NULL` que devuelve la cantidad de filas afectadas -- PostgreSQL
  serializa la evaluación del `WHERE` y la escritura de cada fila individual, así que
  como mucho UNA de dos llamadas concurrentes puede tener éxito (afectar 1 fila), sin
  necesitar `@Version` (optimistic locking) ni `SELECT ... FOR UPDATE` (pessimistic
  locking). El caller (`refresh()`) siempre hace primero un `findByTokenHash` de lectura
  para decidir el resto de las validaciones (expirado/revocado/rotado) y recién después
  intenta el claim atómico; si el claim devuelve `0`, alguien más ganó la carrera entre
  esa lectura y este `UPDATE` -- se trata igual que un token ya usado (`409`), pero **sin
  revocar la familia** (a diferencia del reuse real de arriba): perder una carrera
  contra una request concurrente legítima no es evidencia de un token robado, es ruido
  de timing benigno (doble click, reintento de red), y no amerita matar la sesión
  entera. Test de esta protección:
  `AuthSessionIntegrationTest.shouldOnlyAllowOneWinner_whenTwoConcurrentRotationsClaimSameToken`,
  dos hilos reales contra la misma fila via `TransactionTemplate` explícito por hilo.
- **`revokeAllForUser`**: usado por `ChangePasswordService` y `PasswordResetService` --
  decisión de producto, cambiar o resetear la contraseña cierra TODAS las sesiones del
  usuario (todas las familias), no solo la actual. Se llama dentro de la MISMA
  transacción que persiste la contraseña nueva (propagación REQUIRED normal, a
  diferencia del caso de reuse) -- si algo fallara, ambas cosas se revierten juntas. El
  envío del email de confirmación sigue siendo un intento aparte de siempre, nunca
  revierte ni la contraseña ni la revocación.
- **Logout**: revoca la familia completa del refresh token presentado, idempotente y sin
  distinguir casos en la respuesta (mismo criterio anti-enumeration que
  `forgot-password`).
- **Ventana residual de access tokens**: ninguna operación de esta fase (logout, cambio
  de contraseña, reset, reuse detectado) revoca access tokens ya emitidos -- son
  stateless, no hay blacklist. Un access token sigue funcionando hasta su expiración
  natural (máximo 15 minutos) sin importar qué le haya pasado a la sesión que lo emitió.
  Esto es una limitación conocida y aceptada de Fase 1.5, no un descuido -- una
  blacklist de access tokens convertiría el modelo en stateful (requeriría consultar la
  base en cada request autenticado) y queda fuera de alcance.
- **Sin cleanup automático**: las filas de `auth_sessions` (rotadas, revocadas o
  expiradas) no se borran nunca -- son la única fuente de auditoría de qué pasó con cada
  sesión. No hay scheduler/cron de limpieza en esta fase; puede agregarse más adelante
  si el volumen de filas lo justifica.

## Privacidad (perfil y posts, Fase 9.1/9.2/9.3)

Dos capas independientes, con una regla de dominancia entre ellas.

- **`ProfileVisibility`** (paquete `user`, campo `User.profileVisibility`): `PUBLIC` o
  `PRIVATE` únicamente. Sin `followers-only-profile`, sin listas/círculos
  personalizados -- deliberadamente fuera de alcance (ver "Deuda explícita" abajo).
  Default `PUBLIC` para cuentas nuevas y viejas (`V8`).
- **`PostVisibility`** (paquete `post`, campo `Post.visibility`): `PUBLIC`,
  `FOLLOWERS_ONLY`, `PRIVATE`. Esta capa **ya existía** antes de Fase 9.1 (creación/edición
  de posts, filtro del feed y del detalle por post ya estaban implementados) -- Fase
  9.1/9.2 no la reimplementó, solo la hizo interactuar correctamente con la privacidad de
  perfil nueva.
- **Regla de dominancia (actualizada en Fase 9.3)**: el perfil `PRIVATE` de un autor
  oculta sus posts a cualquiera que no sea el propio autor **o un follower ya
  ACEPTADO**. Antes de Fase 9.3 (cuando no existía el concepto de "seguidor aceptado",
  todo follow era inmediato) esto bloqueaba a CUALQUIER tercero sin excepción -- ahora
  un follower efectivo de un perfil `PRIVATE` ve sus posts `PUBLIC`/`FOLLOWERS_ONLY` con
  normalidad; `PRIVATE` sigue siendo exclusivo del autor sin importar quién sea el
  viewer. El dueño siempre ve el 100% de lo suyo, sin importar ninguna de las dos
  visibilidades.
- **`ProfileAccessPolicy`** (paquete `user`): unico punto de decisión para "¿puede este
  viewer ver el perfil completo de este usuario?" (`canViewFullProfile`). Reutilizado
  por `UserService` (perfil propio/ajeno, discover) y por `PostAccessPolicy` (abajo).
  **Cambio de Fase 9.3**: la rama "perfil `PRIVATE`" pasó de devolver siempre `false`
  para terceros a delegar en `FollowRepository.existsByFollowerIdAndFollowingId` --
  como una fila en `follows` **solo** existe para una relación ya aceptada (nunca para
  una `FollowRequest` `PENDING`/`REJECTED`/`CANCELLED`, ver más abajo), este único
  chequeo ya es exactamente "¿es un follower efectivo?", sin necesidad de consultar
  `FollowRequest` para nada. Este es el ÚNICO cambio de código que hizo falta para que
  `PostAccessPolicy.canView` (sin tocarla) adoptara automáticamente la nueva semántica
  de posts -- ver "Cero cambios en PostAccessPolicy" más abajo.
- **`PostAccessPolicy`** (paquete `post`): unico punto de decisión para "¿puede este
  viewer ver este post?" (`canView`), combinando estado (`PostStatus.VISIBLE`), dueño,
  `ProfileAccessPolicy` del autor, y `PostVisibility` + relación de follow. Reutilizado
  por `PostService.getPost`, `CommentService` (`createComment`, `getComments`) y
  `PostResponseService` (Backend Debt B1, antes `PostSupportService.addSupport`) -- antes
  de Fase 9.1, `CommentService` y `PostSupportService` solo chequeaban que el post
  existiera (`existsById`), sin validar visibilidad en absoluto.
  - **Cero cambios de código en Fase 9.3**: `canView` ya delegaba en
    `profileAccessPolicy.canViewFullProfile(viewerId, author)` para decidir si el
    perfil `PRIVATE` bloquea al viewer -- al cambiar esa policy (arriba), `canView`
    adopta la nueva semántica de "accepted follower ve posts" sin que haga falta tocar
    ni una línea suya. Exactamente el resultado que busca centralizar la regla en un
    solo lugar.
  - **No gatea `updateComment`/`deleteComment` ni `deleteResponse`**: gestionar tu propio
    comentario/respuesta ya existente no vuelve a validar la visibilidad *actual* del post
    (podés seguir borrando tu comentario aunque el post ya no sea visible para vos) --
    es una decisión deliberada, distinta de crear contenido nuevo o listar el existente.
- **Estrategia de queries (feed y "posts por usuario") -- sin N+1**: la visibilidad se
  aplica dentro de la misma consulta JPQL con `JOIN FETCH`, no post-filtrado en Java.
  - **Feed** (`findFeedForUser`): `:followedUserIds` ya viene construido en
    `PostService.getFeed` a partir de filas REALES de `follows` -- es decir, ya son
    todos followers efectivos, sin importar si la relación se creó por un follow
    inmediato (perfil `PUBLIC`) o por una `FollowRequest` aceptada (perfil `PRIVATE`).
    Por eso la query de Fase 9.3 **ya no necesita mirar `profileVisibility` del autor en
    absoluto** (a diferencia de Fase 9.1/9.2, que sí lo hacía): pertenecer a
    `:followedUserIds` YA implica acceso. La rama `a.id = :currentUserId` sigue
    separada: el dueño ve TODO lo suyo (incluido `PRIVATE`), sin importar nada más.
  - **Posts por usuario** (`findVisiblePostsByAuthor`): acá SÍ hace falta el gate de
    `profileVisibility`, porque `:canSeeFollowersOnly` puede ser `true` para un perfil
    `PUBLIC` sin que eso signifique nada especial (cualquiera ve lo `PUBLIC` de un
    perfil `PUBLIC`). La condición es
    `(a.profileVisibility = 'PUBLIC' OR :canSeeFollowersOnly = true) AND (...)` --
    perfil público O follower efectivo abre acceso a `PUBLIC`+`FOLLOWERS_ONLY`;
    `:isOwner` sigue siendo la única rama que además incluye `PRIVATE`.
  - `PostAccessPolicy.canView`, en cambio, opera sobre una sola entidad ya cargada
    (detalle de post, comment, respuesta) -- ahí una consulta puntual de follow
    (`existsByFollowerIdAndFollowingId`) es aceptable, no hay bucle.
- **`getUserPosts` nunca devuelve 404 por privacidad**: si `userId` existe pero su perfil
  es `PRIVATE` y el viewer no es el dueño ni un follower efectivo, la query no matchea
  ninguna fila -- lista vacía, `200 OK`. Mismo criterio en `GET /api/users/{userId}`
  (perfil, no posts): nunca 404 solo por ser privado, la existencia de la cuenta es
  visible, el contenido no.
- **Admin/moderator**: no se amplió ni se redujo su capacidad de moderación.
  `deleteComment`/`deletePost` siguen sin pasar por `PostAccessPolicy` -- un moderador
  puede borrar un comentario o post que ya sabía que existía (ej. por un reporte), sin
  necesidad de que la política de visibilidad se lo confirme de nuevo. `ReportService`
  referencia posts/comments de forma polimórfica (`targetId`/`targetType`, sin FK) y
  nunca devuelve el contenido en sí en `ReportResponse`, solo metadata del reporte --
  fuera del alcance de este cambio.

### Follow requests (Fase 9.3)

- **`FollowRequest`** (paquete `follow`): el TRÁMITE de una solicitud de seguimiento a
  un perfil `PRIVATE` -- distinto de `Follow`, que es la relación efectiva resultante
  (creada recién cuando la solicitud se acepta). Estados: `PENDING`, `ACCEPTED`,
  `REJECTED`, `CANCELLED`. Ninguna fila se borra nunca -- se conserva como historial
  mínimo, igual que el resto de los tokens de un solo uso de este esquema.
- **`follows` solo contiene relaciones aceptadas, por construcción**: ninguna operación
  de `FollowService`/`FollowRequestService` crea una fila en `follows` salvo (a) un
  follow inmediato a un perfil `PUBLIC`, o (b) `FollowRequestService.accept` sobre una
  solicitud a un perfil `PRIVATE`. Una solicitud `PENDING`/`REJECTED`/`CANCELLED` nunca
  produce una fila ahí. Esta invariante es la que permite que `ProfileAccessPolicy`
  (arriba) reduzca "¿es un follower efectivo?" a un solo `existsBy...` sobre `follows`,
  sin tener que consultar `FollowRequest` para nada.
  - **`removeFollower`** (`DELETE /api/follows/followers/{userId}`): borra esa misma
    fila desde el otro lado (el target expulsa a un follower) -- corta el acceso de
    inmediato, sin estado adicional, sin afectar la relación inversa.
- **Sin duplicar `PENDING` -- índice único parcial**: `idx_follow_requests_one_pending_per_pair`
  (`V9`, `UNIQUE (requester_id, target_id) WHERE status = 'PENDING'`) permite historial
  ilimitado de filas `ACCEPTED`/`REJECTED`/`CANCELLED` para el mismo par (ej. rechazar
  una solicitud y que el requester pueda volver a pedir más adelante sin chocar con la
  fila vieja ya resuelta), pero solo UNA `PENDING` activa a la vez. También es la
  defensa contra la carrera de dos `POST /api/follows/{id}` concurrentes creando la
  misma solicitud dos veces -- `FollowService.requestFollow` atrapa el
  `DataIntegrityViolationException` resultante y devuelve la solicitud que ganó la
  carrera, en vez de propagar un 500 (mismo patrón que `AuthService.register` con el
  email duplicado).
- **Concurrencia en accept/reject/cancel -- claim atómico**: mismo patrón que
  `AuthSessionRepository.claimForRotation` (Fase 1.5). `FollowRequestRepository.claimAccept`/
  `claimReject`/`claimCancel` son `UPDATE ... WHERE status = 'PENDING'`, que solo puede
  tener éxito para UNA de dos requests concurrentes sobre la misma fila (aceptar dos
  veces, aceptar+cancelar a la vez, etc). El caller (`FollowRequestService`) hace
  primero una lectura de validación (existe/soy el target o requester/está `PENDING`,
  para poder devolver 404/403/409 específicos) y recién después el claim atómico; si el
  claim devuelve `0`, alguien más ya la resolvió entre la lectura y el `UPDATE` -- se
  trata como `409 Conflict`, igual que si ya no estuviera `PENDING` desde el principio.
- **`FollowResponse.requestId`**: nulo cuando `followState` es `"FOLLOWING"` (no hay
  trámite), poblado cuando es `"REQUESTED"` -- evita que el frontend tenga que llamar a
  `GET /api/follow-requests/outgoing` solo para descubrir el id que el propio `POST`
  acaba de crear, para poder ofrecer "cancelar" de inmediato.
- **Notificaciones**: `FOLLOW_REQUEST_RECEIVED` (al crear la solicitud) y
  `FOLLOW_REQUEST_ACCEPTED` (al aceptarla), mismo mecanismo (`NotificationService.notify`,
  WebSocket + persistida) que `NEW_FOLLOWER`/`NEW_COMMENT`/`NEW_POST_RESPONSE`.
  **Rechazar no notifica** -- decisión de producto, no aporta valor suficiente como para
  justificar avisarle al requester que lo rechazaron.
- **`FollowState`** (paquete `follow`, enum `NONE`/`REQUESTED`/`FOLLOWING`): resumen de
  "cómo estoy parado hoy frente a esta persona", expuesto en `PublicUserProfileResponse`,
  `DiscoverUserResponse` y `FollowResponse`. Distinto de `FollowRequestStatus` (el
  historial de un trámite puntual) -- responden preguntas distintas, nunca se
  confunden ni pueden contradecirse entre sí (`followedByCurrentUser` se sigue
  derivando del mismo chequeo que `followState == FOLLOWING`).
- **Deuda explícita (fuera de alcance)**: custom audiences/círculos como
  audiencia, privacidad de perfil por campo individual, ocultar contadores de
  seguidores, controles de privacidad de mensajería, discovery privacy avanzada (`GET
  /api/users/discover` no filtra por `profileVisibility` ni por `followState`),
  expiración automática de `FollowRequest`, rate limiting específico para follow
  requests, recomendaciones/anti-spam. (`block` y `mute` dejaron de estar en esta lista —
  ver "Bloqueo de usuarios (Fase 9.4)" y "Silenciar usuarios (Fase 9.5)" más abajo.)

## Bloqueo de usuarios (Fase 9.4)

- **`UserBlock`** (paquete `block`): `blocker`, `blocked`, `createdAt`. Direccional en la
  tabla (`blocker_id`, `blocked_id`, `UNIQUE(blocker_id, blocked_id)`,
  `CHECK(blocker_id <> blocked_id)`) — a diferencia de `FollowRequest`, no tiene campo de
  estado: desbloquear es un `DELETE` liso de la fila, sin historial (decisión explícita,
  ver `V10`). `V10__add_user_blocks.sql`.
- **`BlockPolicy.isBlockedBetween(userAId, userBId)`** (paquete `block`): único punto
  central para "¿hay un bloqueo entre estos dos usuarios, en cualquier dirección?" — una
  sola consulta (`existsBilateral`, `OR` sobre ambos sentidos) en vez de que cada caller
  reimplemente el `OR`. **Todo** el resto del código llama acá: `ProfileAccessPolicy`,
  `FollowService`, `ChatService`, `NotificationService`, `StatusService`, discover,
  availability. Mismo criterio de centralización que `ProfileAccessPolicy`/
  `PostAccessPolicy` en fases anteriores.
- **`BlockService.blockUser`/`unblockUser`** (paquete `block`): la operación de bloqueo
  completa (crear la fila + toda la limpieza asociada) vive en un único método
  `@Transactional`, tal como pide la fase — no hay pasos sueltos que puedan quedar a
  medio hacer. `blockUser` es idempotente (bloquear dos veces no falla, atrapa el
  `DataIntegrityViolationException` de la carrera igual que `FollowService.requestFollow`)
  y, en la misma transacción:
  - Borra cualquier `Follow` real en ambas direcciones.
  - Cancela (`claimCancel`, mismo claim atómico que usa `FollowRequestService`) cualquier
    `FollowRequest` `PENDING` en ambas direcciones, sin tocar filas históricas
    `ACCEPTED`/`REJECTED`.
  - `unblockUser` es *solo* el `DELETE` de la fila — no reconstruye nada de lo anterior
    (ver `API_CONTRACT.md` §12 para la justificación completa de esta asimetría).
- **Un solo cambio de código propaga el bloqueo a perfil + posts + comentarios/apoyo**:
  igual que en Fase 9.3 con "accepted follower", agregar el chequeo de bloqueo **una
  sola vez** dentro de `ProfileAccessPolicy.canViewFullProfile` (antes de mirar
  `profileVisibility`) hace que `PostAccessPolicy.canView` (que ya delegaba ahí para
  `FOLLOWERS_ONLY`/`PRIVATE`) empiece a bloquear también el acceso a posts `PUBLIC` de un
  perfil `PUBLIC` — sin tocar `PostAccessPolicy`, `CommentService` ni
  `PostResponseService` (Backend Debt B1, antes `PostSupportService`). Cero duplicación de
  lógica.
- **Asimetría deliberada en `getPublicProfile`**: el chequeo de bloqueo tiene dos ramas
  con tratamiento distinto, y **no** es un descuido:
  - Si el **target** bloqueó al viewer → `404 Not Found` antes de siquiera calcular
    `followState`/`fullProfile` (mismo mensaje genérico que un usuario inexistente).
  - Si el **viewer** bloqueó al target → sigue devolviendo `200`, delegando en
    `ProfileAccessPolicy.canViewFullProfile` (que al ver el bloqueo devuelve `false`) para
    la vista limitada — igual tratamiento que un perfil `PRIVATE` sin follow. Esto es lo
    que permite exponer `blockedByCurrentUser: true` en la respuesta (para que el
    frontend ofrezca "desbloquear" desde la propia tarjeta de perfil) sin nunca exponer
    el campo equivalente en el otro sentido, que ni siquiera llegaría a esta rama del
    código porque ya cortó en el `404` de arriba.
- **`getUserPosts` — chequeo previo, no query-level**: a diferencia del feed (ver abajo),
  acá se hace un único `isBlockedBetween` antes de tocar `findVisiblePostsByAuthor` y se
  devuelve `Page.empty(pageable)` de inmediato si hay bloqueo — más eficiente que embeber
  el `OR` de bloqueo en esa query (evita ejecutarla directamente) para el caso de un solo
  autor, y no duplica el join de `UserBlock` en ella.
- **Feed (posts y status) — filtro explícito en la query, aunque ya sea redundante**:
  bloquear ya limpia `follows` bilateralmente y el feed solo mira
  `:followedUserIds`/gente que se sigue, así que por construcción un bloqueado nunca
  debería aparecer — pero la fase pidió el filtro explícito en la query del feed (no
  post-filtrado en Java) como defensa en profundidad, así que `findFeedForUser`
  (`PostRepository`) y `findActiveStatusesForUsers` (`StatusRepository`) llevan además un
  `NOT EXISTS` sobre `UserBlock` bilateral. Documentado así a propósito: si algún día la
  limpieza de `follows` al bloquear tuviera un bug, el feed seguiría protegido igual.
- **Discover y disponibilidad — exclusión bilateral batch**: `UserService.discoverUsers`
  agrega los ids bloqueados-por-mí y bloqueadores-de-mí (2 consultas batch,
  `findBlockedIdsByBlocker`/`findBlockerIdsByBlocked`) al mismo `excludedIds` que ya usa
  para followers, antes de paginar — ninguna consulta por fila.
  `AvailabilityRepository.findRandomAvailable` (query nativa) lleva el mismo `NOT EXISTS`
  bilateral que el feed, ya que no hay forma de reusar `BlockPolicy` ahí sin volverlo
  N+1 (una consulta por candidato).
- **Chat — decisión explícita, documentada en `API_CONTRACT.md` §12**: bloquear impide
  `POST /api/conversations/{userId}` (crear una conversación nueva **o** reabrir una ya
  existente vía este endpoint específico) y `POST .../messages` (enviar un mensaje
  nuevo), tratando el bloqueo igual que "no conectados, no disponible" en
  `ChatService.getOrCreateConversation` (mismo `403` genérico, sin mensaje distinto).
  **`GET /api/conversations` y `GET .../messages` no se tocan** — el historial completo
  sigue siendo legible por ambas partes sin ninguna restricción, y la conversación sigue
  apareciendo en el listado. Se evaluó ocultar la conversación bloqueada del listado en
  vez de dejarla visible en modo solo lectura, y se descartó: ocultarla la volvería
  inalcanzable desde el frontend sin borrar nada, lo cual es peor UX que simplemente
  dejarla visible y deshabilitar únicamente el envío de mensajes nuevos.
- **WebSocket — confirmado push-only, sin superficie de bypass**: no existe ningún
  `@MessageMapping` en todo el código — el único canal de salida es
  `SimpMessagingTemplate.convertAndSendToUser` (server → cliente), nunca al revés. El
  único punto de entrada para enviar un mensaje es el `POST` REST de arriba, así que el
  chequeo de bloqueo ahí es suficiente; no hay forma de bypassear el bloqueo por WS
  porque no hay ningún endpoint WS que reciba mensajes del cliente.
- **`NotificationService.notify()` — guarda centralizada**: un único `if
  (blockPolicy.isBlockedBetween(...)) return;` al principio del método, antes de crear la
  fila `Notification` y antes del push por WS. En la práctica, hoy es redundante (todos
  los callers actuales — follow, comentario, apoyo, reacción de estado, follow request —
  ya rechazan la acción que dispara la notificación antes de llegar acá), pero centraliza
  la protección en un solo lugar en vez de confiar en que cada futuro caller la reimplemente.
  **No hay tipo de notificación de sistema/admin en este esquema** todavía, así que no
  hizo falta una excepción para no silenciar avisos administrativos — si se agrega uno en
  el futuro, debe evitar pasar por este `notify()` genérico o esta guarda lo silenciaría
  también.
- **`StatusService.react()` — interacción directa fuera de `PostAccessPolicy`**: los
  estados son un dominio separado de los posts y `react()` no tenía (ni tiene, fuera del
  bloqueo) ningún chequeo de visibilidad — es el único punto de interacción directa
  usuario-a-usuario detectado en la auditoría de esta fase que no pasa por ninguna policy
  existente, así que necesitó su propio `isBlockedBetween` explícito (mismo `404`
  genérico que un status inexistente). `removeReaction` (quitar tu propia reacción) no se
  tocó — mismo criterio que `PostResponseService.deleteResponse`/editar tu comentario:
  gestionar algo tuyo ya hecho no es crear una interacción nueva.
- **Moderación/reportes intactos**: `ReportService`/`deletePost`/`deleteComment` no pasan
  por `BlockPolicy` en absoluto — un bloqueo nunca impide reportar a alguien ni le
  quita/agrega capacidades a un moderador/admin. Verificado explícitamente con un test
  (`moderatorCanStillDeletePost_evenIfAuthorBlockedTheModerator`).
- **Concurrencia — carrera aceptada, no cerrada por completo**: bloquear-mientras-se-
  sigue (A llama `follow(B)` mientras B llama `block(A)` casi al mismo tiempo) tiene una
  ventana angosta de TOCTOU entre el chequeo `isBlockedBetween` de `FollowService.follow`
  y el `save()` del `Follow`, versus el `DELETE` de limpieza de `BlockService.blockUser` —
  en el peor caso podría quedar una fila `Follow` residual post-bloqueo. Cerrar esto por
  completo requeriría un lock explícito o un trigger de DB que vincule ambas tablas, que
  se consideró sobre-ingeniería para el alcance de esta fase (mismo criterio de "no
  sobrecomplicar" aplicado en fases anteriores a otras carreras cross-tabla). Accept/
  reject-vs-bloqueo, en cambio, sí queda completamente cerrado por el claim atómico
  compartido (`claimCancel`/`claimAccept` sobre la misma fila `FollowRequest`).
- **Deuda explícita (fuera de alcance)**: ocultar un post puntual, ocultar a un usuario
  sin bloquearlo, reporte automático al bloquear, motivo de bloqueo, bloqueo temporizado,
  "amigos cercanos"/audiencias personalizadas/círculos, bloqueo por dispositivo, rate
  limiting/anti-abuso más allá de lo ya existente. (`mute` dejó de estar en esta lista —
  ver "Silenciar usuarios (Fase 9.5)" más abajo.)

## Silenciar usuarios (Fase 9.5)

- **`UserMute`** (paquete `mute`): `muter`, `muted`, `createdAt`. Direccional en la tabla
  y, a diferencia de `UserBlock`, **también direccional en el efecto** — no existe
  ningún `MutePolicy.isMutedBetween` bilateral, porque mute nunca se consulta en las dos
  direcciones a la vez en ningún punto del código. `UNIQUE(muter_id, muted_id)`,
  `CHECK(muter_id <> muted_id)`, mismo criterio de "unmute = `DELETE` liso, sin
  historial" que `UserBlock` (ver `V11`). `V11__add_user_mutes.sql`.
- **Por qué no hay un `MutePolicy` centralizado como `BlockPolicy`**: `BlockPolicy`
  existe porque el bloqueo es **control de acceso** consultado desde `boolean` checks
  dispersos (`ProfileAccessPolicy`, `ChatService`, `FollowService`, etc.) antes de decidir
  si algo se puede hacer. Mute **no es control de acceso** — nunca decide si algo está
  permitido, solo si algo aparece en una lista agregada. Cada superficie que lo necesita
  agrega su propio filtro directamente en la query (mismo patrón que ya usaban esas
  queries para bloqueo), en vez de introducir una capa de indirección para un chequeo
  que en la práctica es siempre `NOT EXISTS (... muter = viewerId ...)`, nunca reusado
  como decisión booleana en medio de lógica de negocio.
- **`MuteService.muteUser`/`unmuteUser`** (paquete `mute`): igual de simple que
  `BlockService`, pero **sin ningún efecto secundario** — `muteUser` es idempotente
  (mismo manejo de `DataIntegrityViolationException` por carrera que
  `BlockService.blockUser`/`FollowService.requestFollow`) y no toca `Follow`,
  `FollowRequest` ni `UserBlock` en absoluto. `unmuteUser` es el `DELETE` liso de la fila
  — no hace falta documentar una asimetría "no restaura nada" como en block, porque acá
  nunca hubo nada que mutar en primer lugar.
- **Ningún cambio en `ProfileAccessPolicy` ni `PostAccessPolicy`**: a propósito, ninguna
  de las dos policies de acceso fue tocada en esta fase. Perfil completo/limitado, acceso
  a un post individual, posts por usuario y comentarios/apoyo siguen resolviéndose
  exactamente igual con o sin mute de por medio — mutear a alguien no cambia ni un bit de
  lo que esas policies deciden.
- **Feed (posts y status) — filtro unilateral en la query, mismo patrón que bloqueo pero
  sin la contraparte transitiva**: `findFeedForUser` (`PostRepository`) y
  `findActiveStatusesForUsers` (`StatusRepository`) llevan un `NOT EXISTS` adicional
  sobre `UserMute` con `muter.id = :currentUserId` — a diferencia del `NOT EXISTS` de
  `UserBlock` (que documenta ser "redundante" porque bloquear ya limpia `follows`), este
  **no es redundante con nada**: mutear no toca `follows`, así que esta query es el único
  mecanismo que saca esos posts/estados del feed. El autor sigue siendo un follower
  efectivo en todo lo demás.
- **Discover — exclusión unilateral batch, sin contraparte**: `UserService.discoverUsers`
  agrega `findMutedIdsByMuter(principal.getId())` (1 consulta batch más) al mismo
  `excludedIds` que ya arma para follows/bloqueos. A diferencia de bloqueo, **no** existe
  un `findMuterIdsByMuted` — que alguien me haya muteado a mí no me excluye de nada.
- **Disponibilidad/Companion — mismo criterio unilateral en la query nativa**:
  `AvailabilityRepository.findRandomAvailable` lleva un `NOT EXISTS` adicional sobre
  `user_mutes` con `muter_id = :excludeUserId` (una sola dirección, sin el `OR` que sí
  tiene el de `user_blocks`). No borra ninguna fila de `Availability` — solo saca al
  muted del listado de sugerencias para el muter. Si ya existe una conversación entre
  ambos, `ChatService.getOrCreateConversation` sigue funcionando igual (no fue tocado en
  esta fase) — mute nunca corta la posibilidad de contacto directo ya establecida.
- **Chat, notificaciones, comentarios/respuestas, acceso directo a perfil/post — sin
  cambios, por decisión explícita**: ninguno de estos módulos fue tocado. `ChatService`,
  `NotificationService.notify()`, `CommentService`, `PostResponseService` (Backend Debt
  B1, antes `PostSupportService`), `PostAccessPolicy.canView` y
  `ProfileAccessPolicy.canViewFullProfile` no importan nada del paquete `mute` ni lo
  chequean (verificado de nuevo para `PostResponseService` en Backend Debt B1, ver más
  abajo). La razón de fondo, documentada también en
  `API_CONTRACT.md` § 13: mute afecta *lo que el sistema arma para vos* (feed, discover,
  listados agregados), nunca *lo que vos accedés directamente* ni *lo que otros pueden
  seguir haciendo con vos* (comentar, apoyar, escribirte). Silenciar notificaciones
  específicamente es una semántica distinta ("notification preferences"), fuera de
  alcance a propósito, no una omisión.
- **Interacción con `Block` — un solo punto de acoplamiento, unidireccional**:
  `BlockService.blockUser` borra el `UserMute` propio del blocker hacia el target al
  final de la misma transacción de bloqueo (queda redundante: block ya oculta todo lo que
  ese mute ocultaba, y además corta acceso/interacción, que mute nunca tocó). Es el
  **único** lugar donde `block` conoce a `mute` — `MuteService` no importa nada de
  `block`, evitando una dependencia circular entre paquetes. El sentido inverso (mute del
  target hacia el blocker) no se toca a propósito: es una preferencia ajena a la acción
  de bloquear, y no debilita el bloqueo — el filtrado de `UserBlock` en feed/discover/
  status/disponibilidad ya excluye al blocker de lo que ve el target sin depender de
  ningún `UserMute`. `unblockUser` no revive ningún mute borrado por este mecanismo.
- **Concurrencia**: mismo criterio que bloqueo — el `UNIQUE(muter_id, muted_id)` más el
  `catch` de `DataIntegrityViolationException` alcanzan para dos `mute` simultáneos entre
  el mismo par; no se identificó ninguna carrera nueva mute-vs-block más allá de la ya
  aceptada (y documentada) para follow-vs-block, porque mute no compite por ninguna fila
  que block también escriba (salvo la limpieza unidireccional de arriba, que ocurre
  dentro de la transacción de `blockUser` y por ende no tiene ventana propia).
- **Deuda explícita (fuera de alcance)**: ocultar un post puntual sin silenciar a su
  autor ("hide post"), silenciar una conversación puntual ("mute conversation"),
  silenciar notificaciones, mute temporizado, mute de temas/topics, "amigos
  cercanos"/audiencias personalizadas/círculos, preferencias de recomendación,
  anti-spam, reporte automático al silenciar.

## Respuestas a un post (Backend Debt B1)

Reemplaza el "apoyo" binario anterior (`PostSupport`/`post_supports`) por una respuesta
tipada por usuario/post. El frontend ofrece 6 respuestas posibles; antes de esta fase el
backend solo persistía una de ellas ("Estoy con vos" == presencia binaria) y las otras 5
eran estado local sin backend.

- **`PostResponseType`** (paquete `postresponse`, enum `WITH_YOU`/`NOT_ALONE`/`HUG`/
  `READING`/`TELL_ME_MORE`/`LISTENING`): dominio **independiente** de
  `StatusReactionType` a propósito — posts y statuses son conceptos distintos aunque
  compartan alguna etiqueta (`WITH_YOU`/`NOT_ALONE` existen en ambos enums, nunca se
  comparan ni convierten entre sí). La categoría conceptual (PRESENCE: `WITH_YOU`/
  `NOT_ALONE`/`HUG`; LISTENING: `READING`/`TELL_ME_MORE`/`LISTENING`) se **deriva** del
  enum (`isPresence()`/`isListening()`) en vez de persistirse aparte — evita redundancia
  en DB, y la agrupación PRESENCE/LISTENING de la query de conteos (ver más abajo) debe
  actualizarse junto con el enum si algún día cambia.
- **`PostResponse`** (paquete `postresponse`) reemplaza a `PostSupport`: `post`, `user`,
  `type`, `createdAt`, `updatedAt`. `UNIQUE(post_id, user_id)`: una sola respuesta
  **activa** por usuario/post — cambiar de tipo es un `UPDATE` de esa misma fila, nunca
  una fila nueva. Nombrada igual que `com.byyourside.backend.post.dto.PostResponse` (el
  DTO de "un post" que devuelven feed/detail/posts-by-user) por coincidencia de
  vocabulario — son dos conceptos sin relación, y ningún archivo necesita importar ambas
  clases a la vez (la entidad vive en `postresponse`, el DTO en `post.dto`).
- **Evolución de tabla in-place, no tabla paralela (`V12`)**: `post_supports` se
  renombra a `post_responses` (`ALTER TABLE ... RENAME`) en vez de crear una tabla nueva
  y migrar filas — conserva `id`/`created_at` originales sin reescritura de PK, evita
  duplicar temporalmente la fuente de verdad, y el `UNIQUE(post_id, user_id)` ya
  existente ya era exactamente la regla que necesita `PostResponse`. Toda fila histórica
  (que antes solo representaba presencia binaria) recibe `type = 'WITH_YOU'` — único tipo
  que existía conceptualmente antes de esta fase. Sin `CHECK` constraint sobre `type`
  (mismo criterio que `notifications.type`/`status_reactions.type`, ver `V1`).
  - **Detalle crítico encontrado en la auditoría**: `NotificationType.NEW_SUPPORT` deja
    de existir en el enum de Java (se reemplaza por `NEW_POST_RESPONSE`, ver más abajo).
    Sin migrar también `notifications.type = 'NEW_SUPPORT'` → `'NEW_POST_RESPONSE'` en
    la misma `V12`, cualquier notificación histórica de apoyo rompía la deserialización
    de `@Enumerated(EnumType.STRING)` al leerla (`GET /api/notifications` tiraba
    `IllegalArgumentException` para cualquier usuario con notificaciones de apoyo
    previas a esta fase). Ver `V12__add_post_response_types.sql`.
- **`PostResponseService`** (paquete `postresponse`) reemplaza a `PostSupportService`,
  único punto de mutación sobre `PostResponse` -- tanto los endpoints nuevos (`PUT`/
  `DELETE .../response`) como los legacy (`POST`/`DELETE .../support`) llaman acá, nunca
  a `PostResponseRepository` directamente desde el controller. Una sola fuente de
  verdad: los 4 endpoints leen/escriben la misma fila.
  - **`upsertResponse`**: sin fila previa → `INSERT` + notifica (`NEW_POST_RESPONSE`,
    solo la primera respuesta de este usuario a este post). Con fila previa del mismo
    tipo → no-op idempotente (`UPDATE` sin cambios), sin notificar. Con fila previa de
    otro tipo → `UPDATE` de esa misma fila, sin notificar (cambiar de tipo no es una
    respuesta nueva).
  - **`deleteResponse`**: idempotente, no notifica. Volver a responder después de un
    delete (`upsertResponse` de nuevo) es una respuesta activa nueva y sí notifica —
    decisión explícita: el delete real borró la fila, así que no hay estado que
    distinga "nunca respondió" de "respondió y borró", ni falta hacerlo.
  - **`rejectSelfResponse`**: el autor no puede responder a su propio post (`400`).
    **Cambio de comportamiento**: antes de esta fase, `PostSupportService.addSupport` no
    validaba autoría — un usuario podía apoyarse a sí mismo. Ahora se rechaza en los 4
    endpoints (nuevo y legacy) sin excepción — no se confía en que el frontend nunca
    mande esa request.
  - **Compatibilidad legacy (`addLegacySupport`/`removeLegacySupport`)**: preservan el
    contrato ORIGINAL exacto de `PostSupportService` (`409` si ya existía cualquier
    respuesta propia en vez de upsert silencioso; `404` si no había ninguna al borrar,
    en vez del `200` idempotente del endpoint nuevo) -- para no sorprender a un
    consumidor que ya integraba contra ese comportamiento, mientras ambos pares de
    endpoints operan sobre la misma fila/repositorio (nunca una segunda fuente de
    verdad).
- **Counts -- agregado batch, nunca N+1**: `PostResponseRepository.countGroupedByPostIds`
  trae `presenceCount`/`listeningCount` para toda una página en una sola query
  (`SUM(CASE WHEN type IN (...) THEN 1 ELSE 0 END)` agrupado por post, mismo patrón que
  `PostSupportCountProjection` antes pero con dos agregados en vez de uno). El "tipo de
  respuesta del usuario actual" por página usa `findByUserIdAndPostIds` -- devuelve las
  entidades (no una projection con el enum) porque `r.getPost().getId()` no dispara una
  query adicional sobre un proxy `LAZY` (Hibernate resuelve el id del proxy sin
  inicializarlo) y evita cualquier ambigüedad de mapeo enum-vs-`String` en una interface
  projection. `PostService.enrichAndMap`/`getPost` resuelven ambas queries una sola vez
  por página/detalle, igual que ya hacían con `supportCounts`/`supportedPostIds`.
- **`PostAccessPolicy` sin cambios**: `PostResponseService` reutiliza `canView` tal cual
  -- no existe un "PostResponsePolicy" separado. Bloqueo (Fase 9.4, vía
  `ProfileAccessPolicy` dentro de `canView`) y visibilidad normal cortan la respuesta
  antes de llegar a `rejectSelfResponse`; mute (Fase 9.5) **no** se chequea ahí a
  propósito -- si A silenció a B pero puede acceder directamente al post de B (mute
  nunca es control de acceso), A puede responder con total normalidad.
- **`NotificationType.NEW_SUPPORT` → `NEW_POST_RESPONSE`**: una sola semántica clara en
  vez de mantener dos nombres para la misma acción -- ahora representa cualquier
  `PostResponseType`, no solo el soporte binario anterior. **Cambio de contrato**:
  cualquier consumidor que compare contra el string literal `"NEW_SUPPORT"` debe
  actualizarse (ver `API_CONTRACT.md`, `FRONTEND_HANDOFF.md`, `WEBSOCKET_CONTRACT.md`).
  `NotificationService.notify()` no cambió de forma -- mismo método genérico
  (`recipient, actor, type, postId`), mismo canal WS (`/user/queue/notifications`), sin
  canal nuevo. Se evaluó agregar un campo `responseType` estructurado al payload de
  notificación (para que el frontend muestre algo más contextual que "tenés una
  respuesta nueva") y se **difirió**: hubiera requerido tocar la firma de `notify()`
  compartida por 5+ llamadores (follow, comment, status, follow request) para un solo
  caso de uso, y el frontend ya puede pedir el detalle del post (`presenceCount`/
  `listeningCount`/`currentUserResponseType`) usando el `postId` que sí viaja. Deuda
  técnica considerada, no descuido.
- **Concurrencia**: `UNIQUE(post_id, user_id)` + `catch(DataIntegrityViolationException)`
  en el `INSERT` de `upsertResponse` (mismo patrón que `BlockService`/`MuteService`) --
  pero a diferencia de esos, acá la carrera SÍ aplica el `type` pedido por la request que
  pierde la carrera (como `UPDATE` sobre la fila que ganó), en vez de tratarla como
  no-op: el payload de esta acción importa (no es un simple booleano bloqueado/no
  bloqueado), así que perder la carrera de inserción no debe perder la intención del
  usuario. Solo la request que efectivamente insertó notifica -- ninguna notificación
  duplicada bajo esta carrera. `PUT`+`DELETE` simultáneos no tienen manejo especial más
  allá de la integridad transaccional normal (row-level locking de Postgres en
  `UPDATE`/`DELETE`) -- carrera aceptada, mismo criterio de "no sobrecomplicar" que otras
  carreras ya documentadas en este archivo.
- **`deletePost`/user deletion -- sin cambios de política**: `PostService.deletePost` es
  un soft-delete (`status = REMOVED`, la fila `Post` nunca se borra), así que no hay
  ninguna cascada que gestionar para `PostResponse` -- las respuestas de un post
  "borrado" quedan como filas históricas inertes (el post ya no aparece en ninguna
  superficie que lo requeriría, vía `PostAccessPolicy`/`PostStatus.VISIBLE`), mismo
  tratamiento que ya tenían comments/supports antes de esta fase. `post_responses.user_id
  REFERENCES users(id)` sin cascade, consistente con el resto del esquema (no existe una
  feature de borrado de usuario).
- **Deuda técnica restante**: notification preferences (silenciar notificaciones de
  respuestas específicamente), historial de respuestas (solo se guarda la activa),
  múltiples respuestas simultáneas por usuario, reaction emojis/custom response, ranking,
  gamificación, refactor de `StatusReaction` para compartir código con `PostResponse`
  (evaluado y descartado -- dominios distintos a propósito), campo `responseType`
  estructurado en `Notification` (ver arriba).

## Status directo + edición de perfil (Backend Debt B2)

- **`GET /api/users/{userId}/status` -- query que ya existía sin usar**: la auditoría de
  esta fase encontró que `StatusRepository.findTopByUserIdAndExpiresAtAfterOrderByCreatedAtDesc`
  ya existía en el repositorio (mismo patrón que
  `AvailabilityRepository.findTopByUserIdAndExpiresAtAfterOrderByCreatedAtDesc`, usado por
  `AvailabilityService.getMine`), pero **nunca estaba conectada a ningún endpoint** -- el
  dominio `status` solo la tenía declarada. `StatusService.getCurrentStatus` (método
  nuevo) es la primera vez que se usa: consulta directo por `userId`, nunca recorre
  `getFeed()`/`findActiveStatusesForUsers` para filtrar en memoria. Misma definición de
  "actual" en los dos lugares (`expiresAt > now`, más reciente por `createdAt`) -- ninguna
  segunda definición.
- **Acceso -- reutiliza `ProfileAccessPolicy.canViewFullProfile` tal cual, sin policy
  nueva**: el gate es exactamente el mismo que decide si `bio` viaja completo en
  `GET /api/users/{userId}` (dueño, perfil `PUBLIC`, o `PRIVATE` + follower ya ACEPTADO
  -- nunca `REQUESTED`/`NONE`). Esto cubre bloqueo automáticamente (Fase 9.4, vía
  `BlockPolicy` dentro de `canViewFullProfile`) sin ningún chequeo adicional. Mute (Fase
  9.5) no se chequea a propósito -- mismo criterio que el resto de los accesos directos
  (post detail, comments, support): mute nunca es control de acceso, solo filtra
  superficies agregadas (acá, `GET /api/statuses/feed`, que sigue aplicando el `NOT
  EXISTS` de `UserMute` sin cambios). "No accesible" → `404` genérico (mismo mensaje que
  usuario inexistente, mismo criterio 404-no-403 que el resto de la API); "accesible pero
  sin status activo" → `200` con body vacío (mismo criterio que
  `GET /api/availability/mine` -- una ausencia genuina de dato no es un error de acceso).
- **Por qué el endpoint vive en `UserController`, no en `StatusController`**: el path
  (`/api/users/{userId}/status`) está bajo el prefijo de Users, y Spring no soporta
  declarar una ruta absoluta que "escape" del prefijo de clase de un controller ya
  mapeado a otro path base. Mismo patrón ya establecido por
  `GET /api/users/{userId}/posts` (vive en `UserController`, delega en `PostService`) y
  por los endpoints de block/mute (delegan en `BlockService`/`MuteService`) -- la lógica
  de negocio se queda en el dominio (`StatusService`), el controller solo enruta.
- **No se incrustó `currentStatus` dentro de `PublicUserProfileResponse`**: se evaluó y
  se descartó para esta fase -- el endpoint separado ya es N+1-safe (una consulta
  puntual por `userId`, no hay página de perfiles a batchear en ningún flujo actual) y
  evita acoplar dos dominios (`user`/`status`) en un solo DTO por una mejora de UX que no
  es estrictamente necesaria ahora. Documentado para que el frontend sepa que debe pedir
  el status con una llamada aparte, no esperarlo embebido en el perfil.
- **`PATCH /api/users/me` -- el mecanismo de persistencia ya existía**: la auditoría
  confirmó que `UserService.updateProfile` ya persistía `displayName`/`bio`/`avatarUrl`/
  `profileVisibility` de verdad (no un placeholder), con semántica "`null` = no tocar"
  para los 4 campos, devolviendo el `UserResponse` actualizado -- nada de esto era nuevo
  en esta fase. Lo que faltaba era **normalización de texto**, agregada directo en
  `UserService.updateProfile` (no en el DTO vía Bean Validation, ver razón abajo):
  - `displayName`: `trim()` + rechazo (`400`) si el resultado queda vacío. `displayName`
    puede ser `null` en cualquier otro punto del sistema (nunca fue obligatorio en
    `POST /api/auth/register`, ver `AuthService`/`RegisterRequest`) -- el rechazo es
    solo para el caso específico de un PATCH que lo deja en blanco explícitamente
    (`""`/solo espacios), que es casi siempre un error de cliente, no una intención real.
  - `bio`: `trim()` + blank → `null` (limpiar bio es una operación válida e
    intencional). No se agregó una librería de sanitización HTML -- `displayName`/`bio`
    se tratan y persisten como texto plano en todo el sistema; el único punto que
    interpola `displayName` en markup (`ResendEmailService`, el saludo de los emails
    transaccionales) ya tenía su propio `escape()` dedicado e independiente de este
    cambio, así que no hay riesgo real que justifique agregar dependencias nuevas.
  - **Por qué en el service y no en el DTO**: `@NotBlank` en `UpdateProfileRequest`
    rechazaría también el `null` legítimo ("no tocar este campo"), rompiendo la
    semántica PATCH ya establecida -- Bean Validation no tiene una forma limpia de decir
    "si no es null, no puede ser blank". El chequeo manual en el service (mismo patrón
    que el self-check de `BlockService.blockUser`/`MuteService.muteUser`) es la opción
    más simple que no sobrecomplica.
- **Consistencia Profile/Discover/Follow -- auditada, ya era correcta**: `followState`
  (`NONE`/`REQUESTED`/`FOLLOWING`), `followersCount`/`followingCount` (cuentan filas
  `Follow` reales, nunca `FollowRequest` -- un `PENDING` nunca infla ningún contador, ya
  que solo una request `ACCEPTED` produce una fila `Follow`), y el tratamiento de
  bloqueo/mute en `GET /api/users/{userId}` ya estaban implementados correctamente desde
  Fase 9.3/9.4/9.5 y ya tenían tests -- esta fase solo agregó la cobertura explícita que
  faltaba (`ProfilePrivacyIntegrationTest`: accepted-follower ve perfil `PRIVATE`
  completo, `PENDING` no infla contadores) en vez de reimplementar nada. `GET
  /api/users/discover` usa el mismo enum `FollowState` con la misma semántica -- ninguna
  inconsistencia encontrada entre las tres rutas (`GET /me`, `GET /{userId}`, `GET
  /discover`).
- **Sin migración nueva en esta fase (B2)**: `displayName`/`bio` ya existían en `users`
  desde `V1` (`VARCHAR(255)`/`TEXT`, ambos ya nullable) con margen de sobra para los
  límites de aplicación sin cambiar (`@Size(max = 100)`/`@Size(max = 500)`, ninguno de
  los dos se modificó) -- Backend Debt B2 no necesitó ninguna migración. (`V13` sí
  existe, pero es de Backend Debt B3 -- ver más abajo.)

## Notificaciones — mark-one, referencias navegables, orden estable (Backend Debt B3)

- **Auditoría**: `GET /api/notifications` ya paginaba de verdad (`Page`/`Pageable`, sin
  cargar todo en memoria), `PATCH /api/notifications/read-all` ya era un `UPDATE` bulk
  (`@Modifying` + JPQL, no iteraba filas), y `GET .../unread-count` ya era un `COUNT`
  real (nunca un contador denormalizado) -- ninguno de los tres necesitó cambios de
  fondo. Lo que faltaba: marcar una notificación puntual, una referencia navegable real
  para `NEW_STATUS_REACTION`/`FOLLOW_REQUEST_RECEIVED` (antes viajaban con `postId: null`
  sin ningún otro dato útil para navegar), y un desempate estable en el orden de la
  lista.
- **`PATCH /api/notifications/{id}/read` — ownership resuelto en una sola query**:
  `NotificationRepository.findByIdAndRecipientId(id, recipientId)` filtra por owner
  DENTRO del `WHERE` (mismo criterio que pedía la fase: nunca `findById()` + chequeo
  aparte en el service) -- no existe una fila intermedia donde un caller pueda "olvidarse"
  de validar ownership. Not-found y not-owned son indistinguibles a propósito (una sola
  query, un solo resultado posible: `404` genérico) -- mismo criterio 404-no-403 que el
  resto de la API para recursos ajenos. Idempotente: si `read` ya era `true`, no vuelve a
  escribir la fila (evita un `UPDATE` innecesario, aunque tampoco sería incorrecto).
- **Orden estable — `createdAt DESC, id DESC`**: `findByRecipientId` (usada por el
  listado paginado) agrega `id DESC` como desempate secundario. Dos notificaciones
  creadas en el mismo milisegundo (posible bajo escritura concurrente -- ej. dos
  reacciones casi simultáneas a distintos posts del mismo usuario) ya no dependen del
  orden físico de la tabla para mantenerse estables entre página y página bajo paginación
  por offset.
- **`statusId`/`followRequestId` -- campos explícitos, nunca metadata genérica**: se
  evaluó una columna JSON/metadata genérica y se descartó a favor de columnas `UUID`
  explícitas, mismo patrón que `postId` ya establecido (`Notification` ya distinguía
  recursos por campos tipados, no por un blob) -- más simple de leer, más simple de
  indexar si hiciera falta en el futuro, y consistente con cómo ya se modela el resto de
  la entidad. Ninguno de los dos tiene FK (ver `V13` y la nota en Entidades y relaciones
  más arriba) -- mismo criterio que `postId`, y por una razón aún más fuerte acá:
  ni `Status` ni `FollowRequest` tienen siquiera un escenario real de fila eliminada
  (`Status` solo expira, `FollowRequest` conserva su historial terminal) que una FK
  necesitara resolver con `ON DELETE SET NULL`.
- **`notify()` -- overload en vez de romper la firma existente**: se agregó
  `notify(recipient, actor, type, postId, statusId, followRequestId)` como la firma
  completa, y se conservó `notify(recipient, actor, type, postId)` como overload de
  compatibilidad (delega en la completa con `null, null`) -- los tres callers que solo
  necesitaban `postId` o ningún recurso (`CommentService`, `PostResponseService`,
  `FollowService.follow` para `NEW_FOLLOWER`) no cambiaron ni una línea. Los tres que
  necesitaban el dato nuevo (`StatusService.react` → `statusId`,
  `FollowService.requestFollow` → `followRequestId` para `FOLLOW_REQUEST_RECEIVED`,
  `FollowRequestService.accept` → `followRequestId` para `FOLLOW_REQUEST_ACCEPTED`, este
  último para contexto, no porque haya una acción pendiente sobre ese trámite) ya tenían
  la entidad/id cargado en el mismo método -- ninguna query adicional.
- **WebSocket -- mismo DTO, sin canal nuevo**: `NotificationService.notify()` sigue
  siendo el único punto de push (`convertAndSendToUser` a `/user/queue/notifications`,
  sin cambios de canal) y sigue usando el mismo `NotificationResponse` que
  `GET /api/notifications` -- extender el DTO con `statusId`/`followRequestId` los deja
  disponibles en WS automáticamente, sin trabajo adicional ni riesgo de que REST y WS
  diverjan.
- **`FOLLOW_REQUEST_RECEIVED`/`FOLLOW_REQUEST_ACCEPTED` -- sin endpoint paralelo,
  `FollowRequestService` sigue siendo la única fuente de verdad**: el objetivo de esta
  fase era que la notificación llevara suficiente contexto (`followRequestId`) para que
  el frontend pueda llamar a `POST /api/follow-requests/{id}/accept`/`reject` (endpoints
  ya existentes desde Fase 9.3, sin tocar) -- nunca crear una ruta nueva tipo
  `POST /api/notifications/{id}/accept-follow`. Un `followRequestId` de una notificación
  vieja puede apuntar a un trámite ya `ACCEPTED`/`REJECTED`/`CANCELLED` -- `Notification`
  no duplica ese estado (no hay un campo `followRequestStatus` en la entidad); el
  `409 Conflict` que ya devuelve `FollowRequestService` para un trámite no-`PENDING` es
  la única fuente de verdad, sin importar por qué vía se llegó ahí.
- **Nuevas notificaciones durante paginación por offset -- estrategia documentada, no
  implementada como snapshot**: se evaluó cursor pagination y se descartó -- la API ya
  usa `Page`/`Pageable` de forma consistente en todos los endpoints, y sobrediseñar un
  mecanismo de snapshot/cursor solo para notificaciones rompería esa consistencia sin
  una necesidad real todavía. La estrategia queda documentada para el frontend en
  `FRONTEND_HANDOFF.md` (prepend en memoria + deduplicar por `id`, nunca asumir que las
  páginas son un snapshot inmutable).
- **Deuda técnica restante**: notification preferences, mute de notificaciones (mute de
  usuario sigue sin afectar notifications, verificado de nuevo explícitamente en esta
  fase), push notifications mobile, preferencias de notificación por email, borrar/
  archivar notificaciones individualmente, agrupamiento de notificaciones (ej. "3
  personas respondieron tu post").

## Persistencia

- **PostgreSQL** vía Spring Data JPA / Hibernate. `ddl-auto: validate` — el esquema
  **nunca se genera desde las entidades**, Hibernate solo valida que coincida con lo que
  ya migró Flyway. Cualquier cambio de esquema debe pasar por una migración Flyway nueva.
- `open-in-view: false` — no hay sesión de Hibernate abierta durante el renderizado de
  la vista/serialización JSON; los `Service` deben resolver todo lo que necesitan
  (incluidos lazy loads) dentro de la transacción, antes de mapear a DTO. Esto explica el
  patrón consistente de `JOIN FETCH` en las queries de listado y de por qué los
  `toResponse(...)` arman DTOs planos (`UserSummary`, etc.) en vez de serializar
  entidades directamente.

## Flyway

Migraciones versionadas en `src/main/resources/db/migration/`:

| Versión | Contenido |
|---|---|
| `V1__init_schema.sql` | `users`, `posts`, `comments`, `follows`, `reports`, `post_supports` (renombrada a `post_responses` en `V12`), `notifications`, `statuses`, `status_reactions` |
| `V2__add_chat_tables.sql` | `conversations`, `messages` |
| `V3__add_availability_table.sql` | `availabilities` |
| `V4__add_email_verification.sql` | `users.email_verified` / `users.email_verified_at`, tabla `email_verification_tokens` |
| `V5__add_email_verification_token_invalidation.sql` | `email_verification_tokens.invalidated_at` |
| `V6__add_password_reset_tokens.sql` | tabla `password_reset_tokens` |
| `V7__add_auth_sessions.sql` | tabla `auth_sessions` |
| `V8__add_profile_visibility.sql` | `users.profile_visibility` |
| `V9__add_follow_requests.sql` | tabla `follow_requests` |
| `V10__add_user_blocks.sql` | tabla `user_blocks` |
| `V11__add_user_mutes.sql` | tabla `user_mutes` |
| `V12__add_post_response_types.sql` | `post_supports` → `post_responses` (`RENAME` in-place), `post_responses.type`/`updated_at`, migra `notifications.type = 'NEW_SUPPORT'` → `'NEW_POST_RESPONSE'` |
| `V13__add_notification_resource_references.sql` | `notifications.status_id`, `notifications.follow_request_id` — ambas nullable, sin FK |

**Compatibilidad de `V4` con usuarios existentes**: la columna `email_verified` se agrega
con `DEFAULT TRUE` (así todas las filas ya existentes en el momento del `ALTER TABLE`
quedan verificadas automáticamente — nunca tuvieron la posibilidad de verificar, así que
tratarlas como "pendientes" las bloquearía sin causa) y **recién después** el `DEFAULT`
de la columna se cambia a `FALSE`, de forma que solo afecta a las filas insertadas de ahí
en adelante. `email_verified_at` se backfillea con `created_at` para esas cuentas
preexistentes (fecha aproximada, no una verificación real). Este patrón (agregar con un
default que cubra el pasado, después cambiar el default para el futuro) es el que hay
que repetir si se agrega otra columna `NOT NULL` a `users` más adelante **siempre que el
comportamiento pasado y futuro deban diferir**. `V8` (`profile_visibility`) es el
contraejemplo: un único `DEFAULT 'PUBLIC'` alcanza, porque `PUBLIC` es simultáneamente
el default correcto para filas viejas (nunca existieron perfiles privados antes de Fase
9.1) y para filas nuevas — no hace falta la danza de dos pasos.

`baseline-on-migrate: true`, `baseline-version: 1`. Los campos `VARCHAR` que respaldan
enums (`type`, `status`, `reason`, etc.) **no tienen `CHECK` constraint** a propósito —
la validez la garantiza solo `@Enumerated(EnumType.STRING)` del lado Java; un comentario
en `V1` explica que un `CHECK` redundante rompió en el pasado cuando `NotificationType`
creció con `NEW_STATUS_REACTION` y Hibernate no pudo reconciliarlo automáticamente. Esto
implica: **agregar un valor nuevo a cualquier enum respaldado en DB no requiere
migración**, pero si alguna vez se agrega un `CHECK` a mano, hay que recordar este
antecedente.

## Storage (Cloudinary)

- Interfaz `ImageStorageService` con una única implementación real,
  `CloudinaryImageStorageService` (sin implementación local/mock para dev — si las
  credenciales de Cloudinary no están seteadas, el upload falla en runtime, no hay
  fallback a disco).
- Único caso de uso actual: avatar de usuario (`POST /api/users/me/avatar`). Transform
  fija: 256×256, `crop=fill`, `gravity=face`, carpeta `avatars/`, `public_id = userId`
  (upsert — no se acumulan versiones viejas del avatar en Cloudinary).
- Límite de tamaño de archivo: 5MB, a nivel de `spring.servlet.multipart` (aplica a
  cualquier multipart request del servlet container, aunque hoy solo hay un endpoint que
  lo usa).

## Email (Resend)

- Interfaz `EmailService` (paquete `email`) con una única implementación real,
  `ResendEmailService`, sobre el SDK `com.resend:resend-java`. Mismo patrón que
  `ImageStorageService`/Cloudinary: nada fuera del paquete `email` conoce Resend
  directamente — `EmailVerificationService` solo depende de la interfaz.
- Cuatro casos de uso actuales: `sendVerificationEmail` (tras registro y tras
  `resend-verification`), `sendWelcomeEmail` (tras la primera verificación exitosa),
  `sendPasswordResetEmail` (tras `forgot-password`, Fase 1.3) y
  `sendPasswordChangedEmail`, que se dispara desde **dos services distintos**:
  `PasswordResetService` (tras un `reset-password` exitoso, Fase 1.3) y
  `ChangePasswordService` (tras un `change-password` exitoso, Fase 1.4) — mismo método
  reutilizado, sin una segunda abstracción, porque el mensaje ("tu contraseña fue
  cambiada") es idéntico sin importar cuál de los dos flujos lo originó.
- **Sin credenciales configuradas** (`RESEND_API_KEY` vacío, default en dev/test): el
  envío es un no-op silencioso (se loguea a nivel `debug`), no un error — igual que
  Cloudinary con sus credenciales, pero sin lanzar excepción, para no requerir mockear
  este service en el resto de la suite de tests.
- **Con credenciales configuradas y falla el proveedor** (Resend devuelve error o no
  responde): `ResendEmailService` lanza `EmailDeliveryException` (unchecked). El
  contenido del email, el `RESEND_API_KEY` y el verification token nunca se loguean —
  solo el motivo del fallo.
- **Resiliencia**: `EmailVerificationService`, `PasswordResetService` (Fase 1.3) y
  `ChangePasswordService` (Fase 1.4) capturan `EmailDeliveryException` alrededor de
  cada envío y solo loguean un warning (`log.warn`) — nunca revierten la operación de
  negocio que originó el envío. Una caída de Resend no tumba un registro válido
  (`issue`), una verificación válida (`verify`), una solicitud de `forgot-password`, un
  `reset-password` ya aplicado, ni un `change-password` ya aplicado. El
  usuario/token/verificación/contraseña ya quedaron persistidos antes del intento de
  envío.
- Variables de entorno: `RESEND_API_KEY` (vacío por default), `MAIL_FROM` (dirección
  `from`, vacío por default — debe setearse en cualquier ambiente que efectivamente
  envíe correo), `APP_FRONTEND_URL` (base del link de verificación, default
  `http://localhost:5173`).

## WebSocket

Ver `WEBSOCKET_CONTRACT.md` para el detalle completo. Resumen arquitectónico: broker
STOMP simple in-memory (`spring-boot-starter-websocket`, sin RabbitMQ/ActiveMQ externo),
autenticación JWT en el frame `CONNECT` (no en el handshake HTTP), uso exclusivamente
server→client (`convertAndSendToUser`) para dos casos: mensajes de chat nuevos y
notificaciones nuevas. No hay `@MessageMapping` — ningún flujo client→server pasa por el
socket.

**Nota de escalabilidad**: el broker simple es en memoria y por proceso — si el backend
llegara a correr en más de una instancia (horizontal scaling), los mensajes/notificaciones
solo llegarían a un usuario conectado a la instancia que originó el evento. Hoy es un
único proceso (`docker-compose.yml` levanta un solo container `backend`), así que no es
un problema actual, pero es una limitación a tener en cuenta antes de escalar.

## Testing

- **Integration tests** (`src/test/java/.../*ControllerIntegrationTest.java` y afines)
  para: admin, auth, availability, chat, comment, follow (`FollowControllerIntegrationTest`,
  `FollowRequestIntegrationTest`), notification, post (`PostControllerIntegrationTest`,
  `PostPrivacyIntegrationTest`), report, status, user (`UserControllerIntegrationTest`,
  `ProfilePrivacyIntegrationTest`), `block.BlockIntegrationTest` (Fase 9.4), más
  `AdminBootstrapIntegrationTest`. Usan Testcontainers (`postgresql`,
  `spring-boot-testcontainers`, `junit-jupiter`) — levantan un Postgres real en Docker
  para cada corrida, no H2 ni mocks de DB.
- **CI** (`.github/workflows/ci.yml`, GitHub Actions): en cada push/PR a `develop`
  corre `mvn clean verify` (job `test`) y, si pasa, un build de la imagen Docker sin
  push (job `docker-build`). No hay job de deploy en este workflow.

## Deploy / entorno

- `Dockerfile` multi-stage: build con `maven:3.9-eclipse-temurin-21`, runtime con
  `eclipse-temurin:21-jre-alpine`. Expone puerto `8080`.
- `docker-compose.yml` local: `postgres:16-alpine` + `backend`, con `.env` para
  variables (no versionado, ver `.env` local del repo — nunca commitear secretos reales).
- Variables de entorno relevantes (todas con default de desarrollo en `application.yml`):
  `PORT`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`,
  `JWT_ACCESS_EXPIRATION_MS` (Fase 1.5, reemplaza a `JWT_EXPIRATION_MS`),
  `REFRESH_TOKEN_EXPIRATION_MS` (Fase 1.5, nueva), `ADMIN_BOOTSTRAP_USERNAME`,
  `CORS_ALLOWED_ORIGINS`, `APP_FRONTEND_URL`, `MAIL_FROM`, `RESEND_API_KEY`,
  `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET`.
- El comentario de heartbeat de WebSocket en el código menciona **Railway** como destino
  de despliegue de referencia (proxy intermedio que corta conexiones inactivas sin
  heartbeat).
