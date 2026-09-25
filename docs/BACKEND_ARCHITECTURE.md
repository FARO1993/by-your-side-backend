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
│                   PasswordResetService/Token(Repository) — recuperación de contraseña
├── availability    Modo compañía: Availability, CompanionIntent
├── chat            Conversation, Message — REST + push WebSocket
├── comment         Comment, CommentStatus — anidado bajo /api/posts/{postId}/comments
├── config          SecurityConfig, AdminBootstrap
├── email           Email transaccional: EmailService (interfaz) + ResendEmailService
├── exception       GlobalExceptionHandler, ErrorResponse
├── follow          Follow (relación N:N usuario→usuario)
├── notification     Notification, NotificationType — generadas internamente, nunca por API directa
├── post            Post, PostVisibility, PostStatus
├── report          Report, ReportReason/Status/TargetType — moderación
├── security        JWT: JwtService, JwtAuthenticationFilter, UserPrincipal, CustomUserDetailsService
├── status          "Estado de ánimo": Status, StatusMood, StatusReaction, StatusReactionType
├── storage         Cloudinary: ImageStorageService (interfaz) + CloudinaryImageStorageService
├── support         PostSupport — "apoyo" (like) a un post
├── user            User, UserRole, UserStatus — entidad central, referenciada por casi todo
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
 └─ 1:N → PasswordResetToken (password_reset_tokens.user_id)

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
```

Todas las relaciones `@ManyToOne` son `FetchType.LAZY` con `JOIN FETCH` explícito en las
queries que arman listados (para evitar N+1).

## Seguridad

- **Spring Security 6** stateless (`SessionCreationPolicy.STATELESS`), sin sesiones de
  servidor, sin CSRF (deshabilitado — no aplica sin cookies de sesión).
- **JWT** (`io.jsonwebtoken` / jjwt 0.12.6, HMAC-SHA vía `Keys.hmacShaKeyFor`). Un solo
  secreto simétrico (`app.jwt.secret`, mín. 32 chars recomendado, no forzado por código).
  Claims: `sub` (username interno), `userId`, `role`, `iat`, `exp`. Expiración configurable
  (`app.jwt.expiration-ms`, default 24h).
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

**Compatibilidad de `V4` con usuarios existentes**: la columna `email_verified` se agrega
con `DEFAULT TRUE` (así todas las filas ya existentes en el momento del `ALTER TABLE`
quedan verificadas automáticamente — nunca tuvieron la posibilidad de verificar, así que
tratarlas como "pendientes" las bloquearía sin causa) y **recién después** el `DEFAULT`
de la columna se cambia a `FALSE`, de forma que solo afecta a las filas insertadas de ahí
en adelante. `email_verified_at` se backfillea con `created_at` para esas cuentas
preexistentes (fecha aproximada, no una verificación real). Este patrón (agregar con un
default que cubra el pasado, después cambiar el default para el futuro) es el que hay
que repetir si se agrega otra columna `NOT NULL` a `users` más adelante.

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
  `sendPasswordChangedEmail` (tras un `reset-password` exitoso, Fase 1.3).
- **Sin credenciales configuradas** (`RESEND_API_KEY` vacío, default en dev/test): el
  envío es un no-op silencioso (se loguea a nivel `debug`), no un error — igual que
  Cloudinary con sus credenciales, pero sin lanzar excepción, para no requerir mockear
  este service en el resto de la suite de tests.
- **Con credenciales configuradas y falla el proveedor** (Resend devuelve error o no
  responde): `ResendEmailService` lanza `EmailDeliveryException` (unchecked). El
  contenido del email, el `RESEND_API_KEY` y el verification token nunca se loguean —
  solo el motivo del fallo.
- **Resiliencia**: tanto `EmailVerificationService` como `PasswordResetService` (Fase
  1.3) capturan `EmailDeliveryException` alrededor de cada envío y solo loguean un
  warning (`log.warn`) — nunca revierten la operación de negocio que originó el envío.
  Una caída de Resend no tumba un registro válido (`issue`), una verificación válida
  (`verify`), una solicitud de `forgot-password` ni un `reset-password` ya aplicado. El
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
  `JWT_EXPIRATION_MS`, `ADMIN_BOOTSTRAP_USERNAME`, `CORS_ALLOWED_ORIGINS`,
  `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET`.
- El comentario de heartbeat de WebSocket en el código menciona **Railway** como destino
  de despliegue de referencia (proxy intermedio que corta conexiones inactivas sin
  heartbeat).
