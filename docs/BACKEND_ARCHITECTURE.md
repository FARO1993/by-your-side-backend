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
├── chat            Conversation, Message — REST + push WebSocket
├── comment         Comment, CommentStatus — anidado bajo /api/posts/{postId}/comments
├── config          SecurityConfig, AdminBootstrap
├── email           Email transaccional: EmailService (interfaz) + ResendEmailService
├── exception       GlobalExceptionHandler, ErrorResponse
├── follow          Follow (relación N:N usuario→usuario), FollowRequest/Status,
│                   FollowState, FollowRequestService/Controller (Fase 9.3: solicitudes
│                   de seguimiento para perfiles PRIVATE + gestión de followers)
├── notification     Notification, NotificationType — generadas internamente, nunca por API directa
├── post            Post, PostVisibility, PostStatus, PostAccessPolicy (Fase 9.1: "puede
│                   este viewer ver este post", reutilizado por comment/support)
├── report          Report, ReportReason/Status/TargetType — moderación
├── security        JWT: JwtService, JwtAuthenticationFilter, UserPrincipal, CustomUserDetailsService
├── status          "Estado de ánimo": Status, StatusMood, StatusReaction, StatusReactionType
├── storage         Cloudinary: ImageStorageService (interfaz) + CloudinaryImageStorageService
├── support         PostSupport — "apoyo" (like) a un post
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
 ├─ 1:N → PostSupport (post_supports.user_id)
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
 └─ 1:N → PostSupport

Comment (comments) — N:1 → Post, N:1 → User (author)
Follow (follows) — N:1 → User (follower), N:1 → User (following), UNIQUE(follower_id, following_id)
Report (reports) — N:1 → User (reporter), N:1 → User (reviewedBy, nullable). target_id/target_type
                    son una referencia POLIMÓRFICA (no FK) a Post/Comment/User.
PostSupport (post_supports) — N:1 → Post, N:1 → User, UNIQUE(post_id, user_id)
Notification (notifications) — N:1 → User (recipient), N:1 → User (actor), post_id opcional (no FK)
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
  `PostSupportService.addSupport` -- antes de Fase 9.1, `CommentService` y
  `PostSupportService` solo chequeaban que el post existiera (`existsById`), sin validar
  visibilidad en absoluto.
  - **Cero cambios de código en Fase 9.3**: `canView` ya delegaba en
    `profileAccessPolicy.canViewFullProfile(viewerId, author)` para decidir si el
    perfil `PRIVATE` bloquea al viewer -- al cambiar esa policy (arriba), `canView`
    adopta la nueva semántica de "accepted follower ve posts" sin que haga falta tocar
    ni una línea suya. Exactamente el resultado que busca centralizar la regla en un
    solo lugar.
  - **No gatea `updateComment`/`deleteComment` ni `removeSupport`**: gestionar tu propio
    comentario/apoyo ya existente no vuelve a validar la visibilidad *actual* del post
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
    (detalle de post, comment, support) -- ahí una consulta puntual de follow
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
  WebSocket + persistida) que `NEW_FOLLOWER`/`NEW_COMMENT`/`NEW_SUPPORT`. **Rechazar no
  notifica** -- decisión de producto, no aporta valor suficiente como para justificar
  avisarle al requester que lo rechazaron.
- **`FollowState`** (paquete `follow`, enum `NONE`/`REQUESTED`/`FOLLOWING`): resumen de
  "cómo estoy parado hoy frente a esta persona", expuesto en `PublicUserProfileResponse`,
  `DiscoverUserResponse` y `FollowResponse`. Distinto de `FollowRequestStatus` (el
  historial de un trámite puntual) -- responden preguntas distintas, nunca se
  confunden ni pueden contradecirse entre sí (`followedByCurrentUser` se sigue
  derivando del mismo chequeo que `followState == FOLLOWING`).
- **Deuda explícita (fuera de alcance)**: block, mute, custom audiences/círculos como
  audiencia, privacidad de perfil por campo individual, ocultar contadores de
  seguidores, controles de privacidad de mensajería, discovery privacy avanzada (`GET
  /api/users/discover` no filtra por `profileVisibility` ni por `followState`),
  expiración automática de `FollowRequest`, rate limiting específico para follow
  requests, recomendaciones/anti-spam.

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
| `V1__init_schema.sql` | `users`, `posts`, `comments`, `follows`, `reports`, `post_supports`, `notifications`, `statuses`, `status_reactions` |
| `V2__add_chat_tables.sql` | `conversations`, `messages` |
| `V3__add_availability_table.sql` | `availabilities` |
| `V4__add_email_verification.sql` | `users.email_verified` / `users.email_verified_at`, tabla `email_verification_tokens` |
| `V5__add_email_verification_token_invalidation.sql` | `email_verification_tokens.invalidated_at` |
| `V6__add_password_reset_tokens.sql` | tabla `password_reset_tokens` |
| `V7__add_auth_sessions.sql` | tabla `auth_sessions` |
| `V8__add_profile_visibility.sql` | `users.profile_visibility` |
| `V9__add_follow_requests.sql` | tabla `follow_requests` |

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

- **Integration tests** (`src/test/java/.../*ControllerIntegrationTest.java`) para:
  admin, auth, availability, chat, comment, follow, notification, post, report, status,
  user, más `AdminBootstrapIntegrationTest`. Usan Testcontainers (`postgresql`,
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
