# API Contract — ByYourSide Backend

> Fuente de verdad para el frontend (Cursor). Generado por auditoría directa del código
> (controllers, services, DTOs, entidades) el 2026-09-25, sobre `develop`
> (`db01de9`). No contiene campos inventados: todo lo documentado existe en la
> implementación real.

## Regla de mantenimiento

**Cuando un cambio futuro modifique el contrato público del backend (endpoint nuevo,
campo agregado/quitado/renombrado en un DTO, cambio de status code, cambio de reglas de
autorización), el mismo commit/PR que lo introduce debe actualizar este archivo** (y
`WEBSOCKET_CONTRACT.md` / `FRONTEND_HANDOFF.md` si corresponde). Cambios puramente
internos (refactors, nombres de variables, queries SQL que no cambian el resultado
observable) no requieren tocar esta documentación.

---

## Convenciones generales

- **Base URL**: `http://localhost:8080` en desarrollo (configurable vía `PORT`). No hay
  `context-path`; todos los endpoints de negocio cuelgan de `/api/**`, salvo el
  WebSocket (`/ws`, ver `WEBSOCKET_CONTRACT.md`).
- **Autenticación**: JWT Bearer. Header `Authorization: Bearer <token>` en cada request
  autenticado. Ver detalle en `FRONTEND_HANDOFF.md`.
- **Content-Type**: `application/json` salvo el endpoint de subida de avatar
  (`multipart/form-data`).
- **IDs**: todos los recursos usan `UUID` (string en JSON, formato estándar con guiones).
- **Timestamps**: `Instant` de Java, serializado por Jackson como ISO-8601 UTC
  (`"2026-09-25T14:30:00Z"`), no epoch millis.
- **Paginación**: los endpoints que devuelven `Page<T>` (Spring Data) aceptan
  query params `page` (0-indexed, default `0`) y `size` (default `20`, salvo que se
  indique otro default abajo). La respuesta es el envelope estándar de Spring Data:
  ```json
  {
    "content": [ /* array de items */ ],
    "totalElements": 0,
    "totalPages": 0,
    "size": 20,
    "number": 0,
    "numberOfElements": 0,
    "first": true,
    "last": true,
    "empty": true,
    "sort": { "sorted": false, "empty": true, "unsorted": true },
    "pageable": { "pageNumber": 0, "pageSize": 20, "offset": 0, "paged": true, "unpaged": false, "sort": { } }
  }
  ```
  El frontend debe leer `content` para los items y `totalElements`/`totalPages`/`last`
  para el control de paginación. No hay parámro `sort` custom expuesto: el orden lo fija
  el backend en cada query (siempre documentado abajo).
- **Endpoints que NO paginan**: devuelven `List<T>` plano (sin envelope). Están marcados
  explícitamente en cada sección.
- **Errores**: formato uniforme, ver sección "Formato de error" al final.
- **CORS**: orígenes permitidos configurables vía `CORS_ALLOWED_ORIGINS` (default
  `http://localhost:5173,http://localhost:3000`). Métodos permitidos:
  `GET, POST, PATCH, PUT, DELETE, OPTIONS` (nota: ningún endpoint actual usa `PUT`,
  el método está habilitado en CORS pero no implementado). Headers permitidos:
  `Authorization, Content-Type`. `credentials: true`.

### Enums usados en la API

| Enum | Valores | Usado en |
|---|---|---|
| `UserRole` | `USER`, `MODERATOR`, `ADMIN` | `UserResponse.role`, admin role update |
| `UserStatus` | `ACTIVE`, `SUSPENDED`, `DEACTIVATED` | Interno (controla login vía `UserDetails.isEnabled/isAccountNonLocked`); no expuesto directamente en ningún DTO de respuesta |
| `ProfileVisibility` (Fase 9.1) | `PUBLIC`, `PRIVATE` | `UserResponse`/`PublicUserProfileResponse`/`DiscoverUserResponse.profileVisibility`, `PATCH /api/users/me` |
| `PostVisibility` | `PUBLIC`, `FOLLOWERS_ONLY`, `PRIVATE` | Crear/editar/leer posts |
| `PostStatus` | `VISIBLE`, `FLAGGED`, `REMOVED` | Interno. **`FLAGGED` existe en el enum pero ningún código lo asigna actualmente** (ver `FRONTEND_HANDOFF.md` § gaps). No se expone en `PostResponse`. |
| `CommentStatus` | `VISIBLE`, `FLAGGED`, `REMOVED` | Interno, mismo caso que `PostStatus.FLAGGED` (no asignado nunca) |
| `CompanionIntent` | `TALK`, `DISTRACTION`, `WATCH_TOGETHER`, `MUSIC`, `LAUGH`, `JUST_COMPANY` | Modo compañía (availability) |
| `StatusMood` | `WELL`, `NEED_DISTRACTION`, `DIFFICULT_DAY`, `NEED_TO_TALK`, `HERE_FOR_SOMEONE` | Estados de ánimo |
| `StatusReactionType` | `WITH_YOU`, `WANT_TO_TALK`, `HERE_READING`, `NOT_ALONE` | Reacciones a un estado |
| `NotificationType` | `NEW_FOLLOWER`, `NEW_COMMENT`, `NEW_SUPPORT`, `NEW_STATUS_REACTION` | Notificaciones (in-app y WebSocket) |
| `ReportTargetType` | `POST`, `COMMENT`, `USER` | Crear un reporte |
| `ReportReason` | `SELF_HARM_RISK`, `HARASSMENT`, `SPAM`, `HATE_SPEECH`, `OTHER` | Crear un reporte |
| `ReportStatus` | `PENDING`, `REVIEWED`, `ACTION_TAKEN`, `DISMISSED` | Resolver un reporte |

Todos los enums se serializan como su nombre `STRING` (ej. `"PUBLIC"`), no como índice
numérico.

---

## 1. Auth (`/api/auth`) — público, sin autenticación

### `POST /api/auth/register`

Crea una cuenta nueva. El `username` (handle interno) se **autogenera** a partir de
`displayName` (slug sin acentos/espacios, máx. 24 caracteres, sufijo numérico si hay
colisión) — el usuario nunca lo elige ni lo ve como campo de formulario en el registro.

- **Auth**: no requerida.
- **Body** (`RegisterRequest`):
  ```json
  {
    "email": "persona@example.com",
    "password": "al menos 8 caracteres",
    "displayName": "Nombre visible (opcional)"
  }
  ```
  - `email`: obligatorio, formato email válido.
  - `password`: obligatorio, mínimo 8 caracteres.
  - `displayName`: opcional (puede ser `null`; si es blank el username generado cae en `"usuario"` + sufijo).
- **Response 201** (`AuthResponse`) — **contrato roto en Fase 1.5**, ver aviso abajo:
  ```json
  {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "8xQK3f2n...",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "username": "juanperez",
    "role": "USER"
  }
  ```
  **Importante**: el campo se llama `username` pero es el handle autogenerado, **no**
  el email. El frontend debe guardar `accessToken` (para el header
  `Authorization: Bearer ...` de cada request autenticado) y `refreshToken` (para
  `POST /api/auth/refresh` cuando el access token expire), y puede mostrar `username`
  como handle, pero para mostrar el email debe llamar luego a `GET /api/users/me`.
  - **⚠️ BREAKING CHANGE (Fase 1.5)**: `AuthResponse` ya **no** tiene el campo `token`
    — se reemplazó por `accessToken` + `refreshToken` + `tokenType` + `expiresIn`. No
    conviven ambos nombres. Ver `FRONTEND_HANDOFF.md` para el detalle de qué tiene que
    adaptar el frontend.
- **Errores**:
  - `409 Conflict` — email ya registrado (`"Email already registered"` o, en condición
    de carrera, `"Email already in use"`).
  - `400 Bad Request` — validación fallida (`fieldErrors` con detalle por campo).
- **Verificación de email**: el usuario creado queda con `emailVerified: false` (ver
  `GET /api/users/me` en §2), el backend emite internamente un token de verificación de
  un solo uso (válido por 24hs) y **dispara automáticamente un email real de
  verificación** (Resend, ver `BACKEND_ARCHITECTURE.md` § Email) con un link a
  `{APP_FRONTEND_URL}/verify-email?token=...`. El registro y el login **no se bloquean**
  por tener el email sin verificar. Si el envío del email falla (proveedor caído), el
  registro **igual se completa** — no se pierde la cuenta por eso (ver
  "Resiliencia ante fallos de Resend" en `POST /api/auth/verify-email` más abajo). El
  token nunca se expone en ningún response HTTP ni se loguea en texto plano.
- **Sesión (Fase 1.5)**: un registro exitoso crea una sesión nueva (ver
  `POST /api/auth/refresh` más abajo) — el `refreshToken` devuelto corresponde a esa
  sesión.

### `POST /api/auth/login`

- **Auth**: no requerida.
- **Body** (`LoginRequest`):
  ```json
  { "email": "persona@example.com", "password": "..." }
  ```
- **Response 200** (`AuthResponse`): misma forma que register (`accessToken`,
  `refreshToken`, `tokenType`, `expiresIn`, `username`, `role`).
- **Errores**: `401 Unauthorized` — `"Invalid username or password"` si el email no
  existe o la contraseña no matchea. También puede fallar si la cuenta está
  `SUSPENDED`/`DEACTIVATED` (Spring Security la trata como cuenta bloqueada/deshabilitada
  → 401 genérico también, el backend no distingue ese caso en el mensaje).
- **No requiere email verificado**: un usuario con `emailVerified: false` puede loguearse
  con total normalidad — esta fase no introduce ninguna restricción de acceso por eso.
- **Multi-dispositivo (Fase 1.5)**: cada login exitoso crea una sesión **independiente**
  (login desde el celular y desde la PC = dos sesiones separadas). Loguearse de nuevo
  **no** cierra las sesiones ya abiertas en otros dispositivos/pestañas.

**JWT (access token) emitido**: contiene `sub` (username interno), claim `userId` (UUID
string), claim `role`, `iat`, `exp`. Expira a los **15 minutos** por default
(`JWT_ACCESS_EXPIRATION_MS`, configurable) — antes de Fase 1.5 expiraba a las 24hs; ese
valor largo ahora lo cubre el refresh token. Stateless: su validez se verifica
únicamente por firma criptográfica, **no** se consulta la base en cada request
autenticado.

**Refresh token**: opaco (no es JWT), aleatorio (32 bytes `SecureRandom`, Base64
URL-safe), expira a los **30 días** por default (`REFRESH_TOKEN_EXPIRATION_MS`). Se
persiste únicamente su hash SHA-256 (`auth_sessions.token_hash`) — el valor real nunca
se guarda en la base, nunca se loguea, nunca aparece en otro response que no sea el que
lo emite. Ver `POST /api/auth/refresh` para el ciclo de vida completo (rotación,
detección de reuse) y `BACKEND_ARCHITECTURE.md` § Sesiones para el diseño interno.

### `POST /api/auth/verify-email`

Verifica el email de una cuenta a partir del token emitido en el registro. Endpoint
**público** (no requiere `Authorization`) — el token en sí es la credencial.

- **Auth**: no requerida.
- **Body** (`VerifyEmailRequest`):
  ```json
  { "token": "el valor recibido por link en el email de verificación" }
  ```
  - `token`: obligatorio (`@NotBlank`).
- **Response 200** (`EmailVerificationResponse`):
  ```json
  { "emailVerified": true, "emailVerifiedAt": "2026-09-25T14:30:00Z" }
  ```
- **Reglas**:
  - El token es de **un solo uso**: una vez verificado exitosamente, ese mismo token no
    puede volver a usarse.
  - Expira a las **24hs** de emitido.
  - Es **idempotente respecto al usuario**: si el usuario ya estaba verificado (por
    ejemplo, por otro token válido emitido antes) y se presenta un segundo token todavía
    válido y sin usar, la respuesta sigue siendo `200` (el token se consume igual) y
    `emailVerifiedAt` **no se pisa** — conserva la fecha de la primera verificación real.
    El email de bienvenida (ver abajo) tampoco se reenvía en este caso.
  - Un token **invalidado** (superado por un `resend-verification` posterior, ver abajo)
    se rechaza aunque no haya expirado ni se haya usado nunca.
- **Errores**:
  - `400 Bad Request` — token inexistente/inválido (`"Invalid verification token"`), o
    body vacío/`blank` (`fieldErrors.token`).
  - `400 Bad Request` — token válido pero expirado (`"Verification token has expired"`).
  - `400 Bad Request` — token invalidado por un reenvío posterior (`"Verification token
    is no longer valid; a newer one may have been requested"`).
  - `409 Conflict` — token válido pero ya usado antes (`"Verification token has already
    been used"`) — este es el caso de "reintentar el mismo link dos veces".
- **Email de bienvenida**: la primera vez que un usuario verifica exitosamente (no en
  reintentos idempotentes), el backend dispara un email de bienvenida real vía Resend.
  Mismo comportamiento de resiliencia que el de verificación: si falla, no revierte la
  verificación (ver "Resiliencia ante fallos de Resend" abajo).
- **Resiliencia ante fallos de Resend**: tanto el email de verificación (en `register` y
  en `resend-verification`) como el de bienvenida (acá) se intentan enviar de forma
  "best effort" — si Resend falla o no está configurado (`RESEND_API_KEY` vacío, default
  en dev/test), el backend **nunca** revierte ni bloquea la operación de negocio que
  disparó el envío (el registro sigue creando la cuenta, la verificación sigue marcando
  `emailVerified: true`). El fallo solo queda logueado server-side. Ver
  `BACKEND_ARCHITECTURE.md` § Email para el detalle de la abstracción.

### `POST /api/auth/resend-verification`

Reenvía el email de verificación para una cuenta que todavía no verificó su email.
Endpoint **público** (sin JWT) — a propósito, para que un usuario que no puede loguearse
con comodidad igual pueda pedir un nuevo link.

- **Auth**: no requerida.
- **Body** (`ResendVerificationRequest`):
  ```json
  { "email": "persona@example.com" }
  ```
  - `email`: obligatorio, formato email válido.
- **Response 200** (`ResendVerificationResponse`), **siempre**, sin importar el caso real:
  ```json
  { "message": "If an account with that email needs verification, we've sent a new email." }
  ```
- **Prevención de account enumeration**: la respuesta `200` con el mismo mensaje
  genérico se devuelve tanto si el email no existe, como si existe pero ya está
  verificado, como si está dentro del cooldown anti-abuso (ver abajo), como si el envío
  fue exitoso. El frontend **no puede** distinguir estos casos a partir de la respuesta
  HTTP — no hay ningún código de error para "email no encontrado" en este endpoint.
- **Qué hace internamente cuando el email existe y no está verificado ni en cooldown**:
  invalida (no borra) cualquier token de verificación pendiente anterior del usuario
  (`invalidated_at`) y emite uno nuevo, con un nuevo email de verificación.
- **Antiabuso**: cooldown de 60 segundos por usuario, basado en el `created_at` del
  último token emitido (no hay estado en memoria ni Redis). Un reenvío pedido dentro del
  cooldown es un no-op silencioso (misma respuesta genérica `200`, no se emite token
  nuevo ni se manda email).
- **Errores**: `400 Bad Request` — validación fallida (`fieldErrors.email`) si el body
  no trae un email con formato válido. No hay otros códigos de error posibles (ver
  prevención de account enumeration arriba).

### `POST /api/auth/forgot-password` (Fase 1.3)

Solicita la recuperación de contraseña de una cuenta. Endpoint **público** (sin JWT).
No requiere que el email esté verificado — perder acceso a la cuenta no depende de ese
estado.

- **Auth**: no requerida.
- **Body** (`ForgotPasswordRequest`):
  ```json
  { "email": "persona@example.com" }
  ```
  - `email`: obligatorio, formato email válido.
- **Response 200** (`ForgotPasswordResponse`), **siempre**, sin importar el caso real:
  ```json
  { "message": "If an account with that email exists, we've sent password reset instructions." }
  ```
- **Prevención de account enumeration**: la respuesta `200` con el mismo mensaje
  genérico se devuelve tanto si el email no existe, como si está dentro del cooldown
  anti-abuso (ver abajo), como si el envío fue exitoso. El frontend **no puede**
  distinguir estos casos a partir de la respuesta HTTP — no hay ningún código de error
  para "email no encontrado" en este endpoint. A diferencia de `resend-verification`,
  acá no importa si el email ya está verificado o no.
- **Qué hace internamente cuando el email existe y no está en cooldown**: invalida (no
  borra) cualquier `PasswordResetToken` pendiente anterior del usuario
  (`invalidated_at`) y emite uno nuevo — mismo patrón que `resend-verification` pero
  sobre una tabla independiente (`password_reset_tokens`, ver
  `BACKEND_ARCHITECTURE.md`). El email se envía con un link a
  `{APP_FRONTEND_URL}/reset-password?token=...`. El token vence a los **30 minutos**.
- **Antiabuso**: cooldown de 60 segundos por usuario, mismo mecanismo que
  `resend-verification` (basado en `created_at` del último token, sin Redis). Un pedido
  dentro del cooldown es un no-op silencioso (misma respuesta genérica `200`).
- **Resiliencia ante fallos de Resend**: si el envío falla o `RESEND_API_KEY` no está
  configurada, la respuesta HTTP sigue siendo la misma genérica `200` — el fallo solo
  queda logueado server-side, nunca se filtra al cliente.
- **Errores**: `400 Bad Request` — validación fallida (`fieldErrors.email`). No hay
  otros códigos de error posibles (ver prevención de account enumeration arriba).

### `POST /api/auth/reset-password` (Fase 1.3)

Aplica el cambio de contraseña a partir del token recibido por email en
`forgot-password`. Endpoint **público** (sin JWT) — el token en sí es la credencial.

- **Auth**: no requerida.
- **Body** (`ResetPasswordRequest`):
  ```json
  { "token": "el valor recibido por link en el email de recuperación", "newPassword": "al menos 8 caracteres" }
  ```
  - `token`: obligatorio (`@NotBlank`).
  - `newPassword`: obligatorio, mínimo 8 caracteres — **misma política que
    `RegisterRequest.password`**, no hay una segunda regla distinta.
- **Response 200** (`ResetPasswordResponse`):
  ```json
  { "message": "Your password has been reset successfully." }
  ```
  No devuelve un JWT nuevo — el frontend debe redirigir a login después de un reset
  exitoso, no asume que el usuario queda autenticado.
- **Reglas**:
  - El token es de **un solo uso**: una vez usado exitosamente, no puede volver a
    usarse.
  - Expira a los **30 minutos** de emitido.
  - Un token **invalidado** (superado por un `forgot-password` posterior) se rechaza
    aunque no haya expirado ni se haya usado nunca.
- **Errores**:
  - `400 Bad Request` — token inexistente/inválido (`"Invalid password reset token"`),
    body inválido (`fieldErrors.token` / `fieldErrors.newPassword`), token expirado
    (`"Password reset token has expired"`), o token invalidado por un pedido posterior
    (`"Password reset token is no longer valid; a newer one may have been requested"`).
  - `409 Conflict` — token válido pero ya usado antes (`"Password reset token has
    already been used"`).
- **Email de confirmación**: tras un reset exitoso se dispara automáticamente un email
  informativo ("tu contraseña fue cambiada"), sin contraseña ni token en el contenido.
  Si ese envío falla, **no revierte** el cambio de contraseña ya aplicado (mismo
  patrón "best effort" que el resto de los emails de esta fase).
- **Revoca todas las sesiones (Fase 1.5)**: un reset exitoso invalida **todos** los
  refresh tokens del usuario (todos los dispositivos/sesiones, no solo uno). El access
  JWT que estuviera en uso en cualquier dispositivo sigue siendo válido hasta su
  expiración natural (máximo 15 minutos, ver `BACKEND_ARCHITECTURE.md` § Sesiones), pero
  ningún dispositivo puede volver a renovarlo vía `/api/auth/refresh` — todos van a
  necesitar loguearse de nuevo con la contraseña nueva. Esto es intencional: si alguien
  pudo resetear la contraseña es porque tenía acceso al email, y cualquier sesión ya
  abierta con la contraseña vieja (por ejemplo, la de un atacante con la contraseña
  comprometida) no debe sobrevivir al reset.

### `POST /api/auth/change-password` (Fase 1.4) — requiere autenticación

Cambia la contraseña de la cuenta **ya autenticada**. Distinto de `reset-password`:
este flujo es para un usuario que todavía tiene acceso a su cuenta y conoce su
contraseña actual, no para alguien que la perdió. Es el **único** endpoint bajo
`/api/auth` que requiere JWT — todos los demás (`register`, `login`, `verify-email`,
`resend-verification`, `forgot-password`, `reset-password`) siguen siendo públicos.

- **Auth**: **requerida**. Header `Authorization: Bearer <token>`. Sin token o con un
  token inválido/expirado, responde `401 Unauthorized` (mismo formato que cualquier
  otro endpoint autenticado) y la request nunca llega al service — ver
  `BACKEND_ARCHITECTURE.md` § Seguridad.
- **Método**: `POST`, no `PATCH`, por consistencia con el resto de `/api/auth` — ese
  namespace modela **acciones/comandos** (`register`, `login`, `verify-email`, etc.),
  todas con `POST`, a diferencia de `/api/users/me` que sí usa `PATCH` porque modela la
  actualización de un recurso (el perfil). `change-password` es conceptualmente más
  cercana a un comando ("ejecutá el cambio de contraseña") que a una actualización de
  recurso parcial.
- **Identidad**: se resuelve **exclusivamente** del `Authentication` en el
  `SecurityContext` (vía `@AuthenticationPrincipal UserPrincipal`), nunca de un campo
  del body. El `Body` no tiene `userId`, `email` ni `username` — no hay forma de pedir
  el cambio de contraseña de otra cuenta manipulando el request; cualquier campo extra
  que el cliente agregue es ignorado.
- **Body** (`ChangePasswordRequest`):
  ```json
  { "currentPassword": "...", "newPassword": "al menos 8 caracteres" }
  ```
  - `currentPassword`: obligatorio (`@NotBlank`).
  - `newPassword`: obligatorio, mínimo 8 caracteres — **misma política que
    `RegisterRequest.password`/`ResetPasswordRequest.newPassword`**, no hay una tercera
    regla distinta.
  - El backend **no** pide una confirmación de la nueva contraseña
    (`confirmNewPassword`) — esa validación de "ambos campos coinciden" es
    responsabilidad exclusiva del frontend, no existe como campo en este contrato.
- **Response 200** (`ChangePasswordResponse`):
  ```json
  { "message": "Password changed successfully." }
  ```
  No devuelve un JWT/tokens nuevos.
- **Reglas**:
  - `currentPassword` debe matchear (`PasswordEncoder.matches`) el hash actualmente
    guardado.
  - `newPassword` **no puede ser igual** a `currentPassword` (comparado también con
    `PasswordEncoder.matches`, nunca comparando hashes directamente — BCrypt genera un
    salt distinto en cada `encode`, así que dos hashes de la misma contraseña nunca son
    iguales entre sí).
- **Errores**:
  - `401 Unauthorized` — sin JWT o JWT inválido/expirado.
  - `400 Bad Request` — `currentPassword` incorrecta (`"Current password is
    incorrect"`), `newPassword` inválida (`fieldErrors.newPassword`), o `newPassword`
    igual a la actual (`"New password must be different from the current password"`).
    Ninguno de estos casos modifica la contraseña ni dispara el email de confirmación.
- **Email de confirmación**: reutiliza `EmailService.sendPasswordChangedEmail` (el
  mismo método que ya usa `reset-password` desde Fase 1.3 — no se creó una segunda
  abstracción). Mismo comportamiento "best effort": si Resend falla, **no revierte** el
  cambio de contraseña ya aplicado ni reactiva las sesiones recién cerradas (ver abajo),
  el fallo solo queda logueado server-side.
- **Revoca todas las sesiones (Fase 1.5)**: igual que `reset-password`, un cambio de
  contraseña exitoso invalida **todos** los refresh tokens del usuario en todos los
  dispositivos — **incluida la sesión que hizo este mismo request**. El access JWT en
  uso en cualquier dispositivo (incluido el que se usó para autenticar este request)
  sigue siendo válido hasta su expiración natural (máximo 15 minutos, no hay blacklist
  de access tokens en esta fase), pero ningún dispositivo puede renovarlo vía
  `/api/auth/refresh` después de esto: todos necesitan loguearse de nuevo. Ver
  `BACKEND_ARCHITECTURE.md` § Sesiones.

### `POST /api/auth/refresh` (Fase 1.5)

Intercambia un refresh token vigente por un access token nuevo **y un refresh token
nuevo** (rotación obligatoria — el refresh token presentado queda inutilizable después
de esta llamada, sin importar el resultado). Endpoint **público** — se usa
precisamente cuando el access token ya expiró, así que no puede exigir uno válido. La
sesión se identifica exclusivamente por el refresh token del body, nunca por un JWT ni
por ningún identificador enviado por el cliente.

- **Auth**: no requerida.
- **Body** (`RefreshRequest`):
  ```json
  { "refreshToken": "..." }
  ```
  - `refreshToken`: obligatorio (`@NotBlank`).
- **Response 200** (`RefreshResponse`):
  ```json
  { "accessToken": "...", "refreshToken": "...", "tokenType": "Bearer", "expiresIn": 900 }
  ```
  El `refreshToken` devuelto es **siempre distinto** del presentado — el frontend debe
  reemplazar el que tenía guardado por este, nunca reutilizar el viejo.
- **Rotación**: cada refresh exitoso invalida el token presentado y emite uno nuevo
  dentro de la misma sesión (misma "familia", ver `BACKEND_ARCHITECTURE.md` § Sesiones).
  El refresh token nuevo hereda una expiración de **30 días completos** desde este
  momento (ventana deslizante, no un límite absoluto desde el login original) — una
  sesión que se sigue usando activamente no expira nunca por tiempo, solo por logout,
  cambio/reset de contraseña, o reuse detectado.
- **Detección de reuse**: si el refresh token presentado **ya fue rotado antes** (un
  token viejo que alguien volvió a presentar — firma clásica de un token
  copiado/robado), el backend **revoca automáticamente toda la sesión** (todas las
  generaciones de esa familia, pasadas y futuras) y responde `409`. Cualquier refresh
  token descendiente de esa sesión, aunque nunca se haya usado mal, queda inutilizable a
  partir de ese momento. El access token ya emitido antes de esto puede seguir
  funcionando hasta que expire por sí solo (máximo 15 minutos) — esa ventana residual es
  una limitación conocida y aceptada de esta fase (no hay blacklist de access tokens).
- **Errores**:
  - `400 Bad Request` — token inexistente/malformado (`"Invalid refresh token"`),
    expirado (`"Refresh token has expired"`), revocado por logout/cambio de
    contraseña/reset/reuse previo (`"Refresh token has been revoked"`), o body inválido
    (`fieldErrors.refreshToken`).
  - `409 Conflict` — token ya usado/rotado antes (`"Refresh token has already been
    used"`) — este es tanto el caso de una carrera benigna entre dos requests
    concurrentes sobre el mismo token (ver `BACKEND_ARCHITECTURE.md` § Concurrencia)
    como el de reuse real detectado (ver arriba); la respuesta HTTP es la misma en
    ambos casos a propósito.

### `POST /api/auth/logout` (Fase 1.5)

Cierra **la sesión correspondiente al refresh token presentado** (esa sesión
únicamente — otros dispositivos/sesiones de la misma cuenta no se ven afectados).
Endpoint **público**, mismo criterio que `/refresh`: no requiere (ni chequea) un access
JWT vigente, para poder cerrar sesión incluso si el access token ya expiró, mientras se
conserve el refresh token.

- **Auth**: no requerida.
- **Body** (`LogoutRequest`):
  ```json
  { "refreshToken": "..." }
  ```
  - `refreshToken`: obligatorio (`@NotBlank`).
- **Response 200** (`LogoutResponse`), **siempre**, sin importar el caso real:
  ```json
  { "message": "Logged out successfully." }
  ```
- **Idempotente y sin distinguir casos** (mismo criterio anti-enumeration que
  `forgot-password`/`resend-verification`): token inexistente, ya expirado, ya
  rotado o ya revocado antes terminan en la misma respuesta genérica `200`. No hay
  ningún código de error específico de este endpoint.
- **Después de un logout**: el refresh token presentado (y cualquier otro de la misma
  sesión) deja de funcionar en `/refresh` (`400`, `"Refresh token has been revoked"`).
  El access JWT que estuviera en uso puede seguir funcionando hasta que expire por sí
  solo (máximo 15 minutos) — el logout, igual que el resto de Fase 1.5, no revoca
  access tokens ya emitidos, solo impide que la sesión emita nuevos.

---

## 2. Users (`/api/users`) — requiere autenticación

### `GET /api/users/me`
Perfil completo del usuario autenticado, incluye email.
- **Response 200** (`UserResponse`):
  ```json
  {
    "id": "uuid",
    "username": "juanperez",
    "email": "persona@example.com",
    "displayName": "Juan Pérez",
    "bio": "texto o null",
    "avatarUrl": "https://... o null",
    "role": "USER",
    "createdAt": "2026-01-01T00:00:00Z",
    "emailVerified": false,
    "emailVerifiedAt": "2026-01-02T09:00:00Z o null",
    "profileVisibility": "PUBLIC"
  }
  ```
  **Cambio de contrato (Fase 1.1)**: `emailVerified` y `emailVerifiedAt` son campos
  nuevos en `UserResponse` (antes no existían). Adición pura al final del objeto — no
  rompe consumidores existentes que ignoren campos desconocidos. `emailVerifiedAt` es
  `null` mientras `emailVerified` sea `false`. Cuentas creadas **antes** de esta fase
  tienen `emailVerified: true` (ver `BACKEND_ARCHITECTURE.md` § Flyway/compatibilidad),
  con `emailVerifiedAt` igual a su `createdAt` original (fecha aproximada, no una
  verificación real que haya ocurrido).
  **Cambio de contrato (Fase 9.1)**: `profileVisibility` es un campo nuevo (adición pura
  al final), `"PUBLIC"` o `"PRIVATE"`. En `/me` siempre viaja el valor real, sin importar
  quién pregunta (es siempre el propio usuario).

### `PATCH /api/users/me`
Actualiza el perfil propio. Todos los campos son opcionales (solo se aplican los
`!= null`; no hay forma de "vaciar" `bio`/`displayName`/`avatarUrl` enviando `null`
explícito, porque `null` se interpreta como "no tocar").
- **Body** (`UpdateProfileRequest`):
  ```json
  { "displayName": "máx 100 chars", "bio": "máx 500 chars", "avatarUrl": "string", "profileVisibility": "PUBLIC | PRIVATE" }
  ```
  - `profileVisibility` (Fase 9.1): opcional, mismo criterio "`null` = no tocar" que el
    resto de los campos de este DTO. Un valor que no sea `PUBLIC`/`PRIVATE` responde
    `400 Bad Request` (body malformado — mismo manejo genérico que cualquier enum
    inválido en esta API, no un caso especial).
- **Response 200**: `UserResponse` (igual forma que `GET /me`).
- **Nota**: `avatarUrl` puede setearse aquí como URL arbitraria; el endpoint dedicado
  de upload (abajo) es la vía recomendada para subir un archivo real vía Cloudinary.
- **Identidad**: como todo `/me`, opera exclusivamente sobre el usuario del JWT — no hay
  (ni puede haber) forma de cambiar la privacidad de otra cuenta a través de este
  endpoint.

### `GET /api/users/{userId}`
Perfil de **otro** usuario (o el propio, funciona igual). Nunca incluye `email`.
- **Path params**: `userId` (UUID).
- **Response 200** (`PublicUserProfileResponse`):
  ```json
  {
    "id": "uuid",
    "username": "juanperez",
    "displayName": "Juan Pérez",
    "bio": "... o null",
    "avatarUrl": "...",
    "createdAt": "2026-01-01T00:00:00Z",
    "followersCount": 12,
    "followingCount": 5,
    "followedByCurrentUser": false,
    "profileVisibility": "PUBLIC",
    "followState": "NONE",
    "blockedByCurrentUser": false
  }
  ```
  **Cambio de contrato (Fase 9.3)**: `followState` es un campo nuevo (adición pura al
  final) — `"NONE" | "REQUESTED" | "FOLLOWING"`. `followedByCurrentUser` se conserva sin
  romper (sigue siendo `true` únicamente cuando hay una relación `Follow` real/efectiva
  — nunca `true` para una solicitud `PENDING`); ambos campos nunca pueden contradecirse:
  `followedByCurrentUser == (followState == "FOLLOWING")` siempre, porque se derivan
  del mismo chequeo. Viendo el propio perfil, `followState` es siempre `"NONE"`.
  **Cambio de contrato (Fase 9.4)**: `blockedByCurrentUser` es un campo nuevo (adición
  pura al final) — `true` únicamente si **vos** bloqueaste a `userId`. **Nunca** existe
  un campo equivalente para el sentido contrario (si `userId` te bloqueó a vos) — ver
  el punto de bloqueo más abajo, ese caso ni siquiera llega a devolver `200`.
- **Errores**: `404 Not Found` si `userId` no existe, **o si `userId` te bloqueó a vos**
  (Fase 9.4 — ver más abajo, indistinguible a propósito del primer caso).
- **Perfil `PRIVATE` — vista limitada, nunca 404** (Fase 9.1, semántica de acceso
  actualizada en Fase 9.3): si `profileVisibility` del `userId` consultado es `PRIVATE`
  y quien pregunta no es el dueño **ni un follower ya ACEPTADO**, la respuesta sigue
  siendo `200` (el perfil **existe** y eso es visible) pero `bio` viaja en `null`. Un
  follower efectivo (`followState: "FOLLOWING"`) de un perfil `PRIVATE` **sí** ve la
  `bio` completa — antes de Fase 9.3 esto era imposible porque no existía el concepto
  de "follower aceptado" (todo follow era inmediato). El resto de los campos
  (`username`, `displayName`, `avatarUrl`, `followersCount`, `followingCount`,
  `followedByCurrentUser`, `profileVisibility`) se devuelven igual sin importar nada de
  esto — **ocultar contadores de seguidores es una decisión de producto separada, fuera
  de esta fase** (ver `BACKEND_ARCHITECTURE.md` § Privacidad, deuda explícita).
- **Bloqueo (Fase 9.4) — asimétrico a propósito**:
  - Si **`userId` te bloqueó a vos**: el perfil se trata como si no existiera —
    `404 Not Found`, mismo mensaje genérico `"User not found"` que un usuario que nunca
    existió. Nunca `403` (no confirma que el usuario existe pero está bloqueado).
  - Si **vos bloqueaste a `userId`**: seguís viendo `200` con una vista **limitada**
    (mismo tratamiento que un perfil `PRIVATE` del que no sos follower — `bio: null`,
    `followState: "NONE"`) más `blockedByCurrentUser: true`, para que el frontend pueda
    ofrecer la acción de desbloquear desde la propia tarjeta de perfil.
- **`profileVisibility` en el propio perfil**: si `userId` es el propio usuario, esta
  ruta es equivalente a `GET /me` en cuanto a qué tan completo es el perfil — siempre se
  ve completo (mismo criterio "el dueño siempre ve todo" aplicado acá).
- **Seguir un perfil `PRIVATE` ya no es inmediato (Fase 9.3)**: ver
  `POST /api/follows/{userId}` y § 5bis Follow Requests. `followedByCurrentUser` y
  `followState` reflejan el estado real en cada momento — `REQUESTED` mientras está
  pendiente, `FOLLOWING` recién después de que el dueño la acepte.

### `GET /api/users/{userId}/posts`
Posts de un usuario, respetando visibilidad según la relación con quien pregunta.
- **Query params**: `page` (default `0`), `size` (default `20`).
- **Reglas de visibilidad** (evaluadas server-side, no confiar en el frontend; Fase
  9.3 reemplaza la regla de Fase 9.1/9.2 que bloqueaba a CUALQUIER tercero de un perfil
  `PRIVATE` sin excepción):
  - Si `userId` == usuario autenticado → ve todos sus propios posts, cualquiera sea su
    `visibility` (incluye `PRIVATE`), sin importar `profileVisibility` propia.
  - Si `profileVisibility` de `userId` es `PRIVATE`:
    - Viewer **NO** es un follower efectivo (no sigue, o tiene una solicitud
      `PENDING`/`REJECTED`/`CANCELLED`) → **lista vacía** (`200 OK`, `content: []`,
      nunca `404` — la existencia del usuario ya se confirmó al resolver `userId`).
    - Viewer **SÍ** es un follower efectivo (solicitud `ACCEPTED` en algún momento, o
      ya lo seguía desde cuando el perfil era `PUBLIC`) → ve `PUBLIC` + `FOLLOWERS_ONLY`
      del autor, igual que si el perfil fuera `PUBLIC` y lo siguiera. `PRIVATE` sigue
      siendo exclusivamente del autor.
  - Si `profileVisibility` de `userId` es `PUBLIC`:
    - Si el autenticado sigue a `userId` → ve `PUBLIC` + `FOLLOWERS_ONLY`.
    - Si no → solo `PUBLIC`.
  - Siempre excluye posts con `status != VISIBLE`.
  - **Bloqueo (Fase 9.4)**: si existe un bloqueo entre el autenticado y `userId` (en
    cualquier dirección), **lista vacía** (`200 OK`, `content: []`) sin importar
    `profileVisibility` — mismo tratamiento "sin contenido" que un perfil `PRIVATE` sin
    acceso, nunca `404` (no confirma ni niega el bloqueo vía status code).
- **Response 200**: `Page<PostResponse>` (ver forma de `PostResponse` en § Posts).
- **Errores**: `404 Not Found` si `userId` no existe (esto sí es 404 real — el usuario en
  sí no existe, no es un tema de privacidad).

### `GET /api/users/discover`
Lista de usuarios que el autenticado **no sigue todavía** (para descubrir gente nueva).
No hay filtro de búsqueda por texto — es un listado paginado sin criterio de relevancia
explícito más allá del orden default de la tabla. **No filtra por `profileVisibility`**
(ocultar cuentas privadas del discover es "discovery privacy avanzada", explícitamente
fuera de alcance de esta fase) — un usuario `PRIVATE` puede aparecer en el listado.
- **Query params**: `page` (default `0`), `size` (default `20`).
- **Response 200**: `Page<DiscoverUserResponse>`:
  ```json
  { "id": "uuid", "username": "...", "displayName": "...", "bio": "... o null", "avatarUrl": "...", "profileVisibility": "PUBLIC", "followState": "NONE" }
  ```
  `bio` viaja en `null` cuando `profileVisibility` es `PRIVATE` — mismo criterio que
  `GET /api/users/{userId}`, para no exponer el mismo dato por una ruta lateral.
  `followState` (Fase 9.3) es siempre `"NONE"` o `"REQUESTED"` en este listado en
  particular — nunca `"FOLLOWING"`, porque discover ya excluye a quienes se sigue
  efectivamente.
  **Bloqueo (Fase 9.4)**: excluye bilateralmente a quien el autenticado bloqueó **y** a
  quien lo bloqueó a él (2 consultas batch sobre toda la página, no una por fila) —
  ninguna de las dos direcciones aparece nunca en este listado.

### `POST /api/users/me/avatar`
Sube un avatar a Cloudinary y actualiza el perfil propio.
- **Content-Type**: `multipart/form-data`.
- **Form field**: `file` (el archivo de imagen).
- **Validaciones**: archivo no vacío, `Content-Type` debe empezar con `image/`. Tamaño
  máximo de request/archivo **5MB** (`spring.servlet.multipart.max-file-size`/`max-request-size`,
  aplica a nivel de todo el servlet container, no solo este endpoint).
- **Procesamiento**: Cloudinary recorta a 256×256, `crop=fill`, `gravity=face`, carpeta
  `avatars`, `public_id` = `userId` (upsert: subir de nuevo pisa el avatar anterior).
- **Response 200**: `UserResponse` con `avatarUrl` actualizado.
- **Errores**: `400 Bad Request` (archivo vacío o no-imagen), `500 Internal Server Error`
  (fallo de Cloudinary, mensaje genérico `"Failed to upload avatar"`).

---

## 3. Posts (`/api/posts`) — requiere autenticación

### `PostResponse` (forma común de respuesta)
```json
{
  "id": "uuid",
  "author": { "id": "uuid", "username": "...", "displayName": "...", "avatarUrl": "..." },
  "content": "texto",
  "visibility": "PUBLIC",
  "createdAt": "...",
  "updatedAt": "...",
  "followedByCurrentUser": false,
  "supportCount": 3,
  "supportedByCurrentUser": false
}
```
`author` es un `UserSummary` (siempre esta misma forma en toda la API: `id`, `username`,
`displayName`, `avatarUrl` — nunca incluye `email` ni `bio`).

### `POST /api/posts`
- **Body** (`CreatePostRequest`):
  ```json
  { "content": "máx 2000 chars, obligatorio", "visibility": "PUBLIC | FOLLOWERS_ONLY | PRIVATE (opcional, default PUBLIC)" }
  ```
- **Response 201**: `PostResponse` recién creado (`supportCount: 0`, `supportedByCurrentUser: false`).

### `GET /api/posts/feed`
Feed del usuario autenticado: posts propios + de quienes sigue, orden `createdAt DESC`.
- **Query params**: `page` (default `0`), `size` (default `20`).
- **Response 200**: `Page<PostResponse>`.
- Incluye **todos** los posts propios, cualquiera sea su `visibility` (incluido
  `PRIVATE`) — el feed siempre muestra el 100% de lo que uno mismo publicó.
- Para posts de terceros que se siguen efectivamente (relación `Follow` real, sin
  importar si se creó de inmediato por un perfil `PUBLIC` o vía una `FollowRequest`
  aceptada de uno `PRIVATE`, Fase 9.3): `status = VISIBLE`,
  `visibility IN (PUBLIC, FOLLOWERS_ONLY)`. El feed **nunca** incluye `PRIVATE` de
  terceros, sea cual sea el `profileVisibility` del autor.
- **Ya no depende del `profileVisibility` actual del autor** (reemplaza la regla de
  Fase 9.1/9.2): si sos follower efectivo de alguien, sus posts `PUBLIC`/
  `FOLLOWERS_ONLY` aparecen en tu feed aunque su perfil esté en `PRIVATE` — lo que
  importa es si la relación de follow es real, no el estado actual del perfil.
- **Bloqueo (Fase 9.4)**: excluye bilateralmente, filtrado directo en la query del feed
  (`NOT EXISTS` sobre bloqueos, no post-filtrado en memoria) — en la práctica esto ya es
  redundante con la limpieza de `follows` que ocurre al bloquear (ver §12), pero se
  mantiene como defensa en profundidad explícita en la query.

### `GET /api/posts/{postId}`
- **Response 200**: `PostResponse`.
- **Errores**: `404 Not Found` si el post no existe, si existe pero `visibility` no
  autoriza al usuario autenticado a verlo, **o si el perfil del autor es `PRIVATE` y
  quien pregunta no es el autor ni un follower ya ACEPTADO** (Fase 9.1, semántica
  actualizada en 9.3) — nunca `403` en ninguno de estos casos (la API no revela la
  existencia de un post privado ajeno, ni que su autor tiene el perfil en privado). Un
  follower efectivo de un perfil `PRIVATE` ve sus posts `PUBLIC`/`FOLLOWERS_ONLY` con
  total normalidad — solo `PRIVATE` sigue siendo exclusivo del autor.
  **Bloqueo (Fase 9.4)**: si existe un bloqueo entre el autenticado y el autor (en
  cualquier dirección), `404 Not Found` — sin importar `visibility` del post, incluso si
  es `PUBLIC` (ver `ProfileAccessPolicy` en `BACKEND_ARCHITECTURE.md`, el bloqueo se
  chequea antes de mirar la visibilidad del post individual).

### `PATCH /api/posts/{postId}`
Solo el autor puede editar. Campos opcionales (solo se aplican los no-null).
- **Body** (`UpdatePostRequest`): `{ "content": "máx 2000 (opcional)", "visibility": "... (opcional)" }`
- **Response 200**: `PostResponse` actualizado (el `supportCount`/`supportedByCurrentUser`
  se recalculan reales, no se resetean).
- **Errores**: `403 Forbidden` si no sos el autor. `404 Not Found` si no existe.

### `DELETE /api/posts/{postId}`
Soft delete: pone `status = REMOVED` (no borra el registro). Permitido para el autor **o**
para `MODERATOR`/`ADMIN`.
- **Response**: `204 No Content`.
- **Errores**: `403 Forbidden` (ni autor ni moderador/admin), `404 Not Found`.

### `POST /api/posts/{postId}/support`
Da "apoyo" (equivalente a un like) al post. Dispara notificación `NEW_SUPPORT` al autor
(salvo que te apoyes a vos mismo, en cuyo caso no se notifica).
- **Response 201** (`SupportSummaryResponse`): `{ "postId": "uuid", "supportCount": 4, "supportedByCurrentUser": true }`
- **Errores**: `409 Conflict` si ya habías apoyado ese post. `404 Not Found` si el post no
  existe, o si existe pero no es visible para quien pregunta (mismo criterio de
  visibilidad que `GET /api/posts/{postId}`, incluyendo perfil `PRIVATE` del autor,
  Fase 9.1, **y bloqueo bilateral, Fase 9.4**) — no se puede apoyar un post que no se
  podría ver.

### `DELETE /api/posts/{postId}/support`
Quita el apoyo previamente dado.
- **Response 200**: `SupportSummaryResponse` (`supportedByCurrentUser: false`).
- **Errores**: `404 Not Found` — post inexistente, o no habías apoyado ese post
  (mismo status code para ambos casos, mensaje distinto).
- **Nota (Fase 9.4)**: **no** revalida bloqueo — igual que editar/borrar tu propio
  comentario, quitar un apoyo que ya diste es gestionar algo tuyo, no crear una
  interacción nueva. Sigue andando aunque exista un bloqueo con el autor del post.

---

## 4. Comments (`/api/posts/{postId}/comments`) — requiere autenticación

> Nota: aunque `GET` no usa el usuario autenticado en la lógica de negocio, **sí requiere
> JWT válido** igual que el resto de la API — `SecurityConfig` exige `authenticated()`
> para todo lo que no sea `/api/auth/**` o `/ws/**`, sin excepción por verbo HTTP.

### `POST /api/posts/{postId}/comments`
Dispara notificación `NEW_COMMENT` al autor del post (salvo auto-comentario).
- **Body** (`CreateCommentRequest`): `{ "content": "máx 500 chars, obligatorio" }`
- **Response 201** (`CommentResponse`):
  ```json
  { "id": "uuid", "postId": "uuid", "author": { /* UserSummary */ }, "content": "...", "createdAt": "..." }
  ```
- **Errores**: `404 Not Found` si el post no existe, o si existe pero no es visible para
  quien pregunta (Fase 9.1: mismo criterio de visibilidad que `GET
  /api/posts/{postId}`, incluyendo perfil `PRIVATE` del autor y bloqueo bilateral, Fase
  9.4) — no se puede comentar un post que no se podría ver.

### `GET /api/posts/{postId}/comments`
**No pagina** — devuelve `List<CommentResponse>` completa, orden `createdAt ASC` (más
viejo primero), solo `status = VISIBLE`.
- **Errores**: `404 Not Found` si el post no existe, o si existe pero no es visible para
  quien pregunta (mismo criterio que crear un comentario, arriba, incluyendo bloqueo) —
  no se puede listar comentarios de un post que no se podría ver.

### `PATCH /api/posts/{postId}/comments/{commentId}`
Solo el autor del comentario puede editar (no hay excepción para moderador/admin acá).
- **Body** (`UpdateCommentRequest`): `{ "content": "máx 500 chars, obligatorio" }`
- **Response 200**: `CommentResponse`.
- **Errores**: `403 Forbidden` (no sos el autor), `404 Not Found` (comentario no existe,
  o existe pero no pertenece a `postId` — mismo mensaje "Comment not found" en ambos casos).
- **Nota (Fase 9.1, extendida en 9.4)**: esta ruta **no** vuelve a validar la
  visibilidad actual del post — si sos el autor del comentario, podés editarlo/borrarlo
  aunque el post se haya vuelto invisible para vos después de comentarlo (ej. el autor
  del post cambió su perfil a `PRIVATE`, **o** ahora hay un bloqueo entre ambos).
  Gestionar tu propio comentario ya escrito es distinto de poder ver contenido nuevo —
  un bloqueo impide **crear** interacciones nuevas, no gestionar las que ya hiciste.

### `DELETE /api/posts/{postId}/comments/{commentId}`
Soft delete (`status = REMOVED`). Permitido para el autor **o** `MODERATOR`/`ADMIN`
(la capacidad de moderación no se ve afectada por la privacidad del post/perfil, ver
`BACKEND_ARCHITECTURE.md` § Privacidad, admin/moderator).
- **Response**: `204 No Content`.
- **Errores**: `403 Forbidden`, `404 Not Found` (mismos criterios que PATCH).

---

## 5. Follows (`/api/follows`) — requiere autenticación

> **⚠️ Breaking change de comportamiento (Fase 9.3)**: `POST /api/follows/{userId}`
> **ya no es siempre inmediato**. Reemplaza la limitación documentada en Fase 9.1/9.2
> ("el follow sigue siendo inmediato, sin aprobación, no existe todavía un sistema de
> solicitud de seguimiento pendiente") — ese sistema ahora existe. El campo nuevo
> `followState` en la respuesta le dice al frontend cuál de los dos casos ocurrió.

### `POST /api/follows/{userId}`
Seguir a un usuario, **o solicitar seguirlo** si su perfil es `PRIVATE`.
- **Response 201** (`FollowResponse`):
  ```json
  { "followerId": "uuid", "followingId": "uuid", "createdAt": "...", "followState": "FOLLOWING", "requestId": null }
  ```
  - Perfil objetivo `PUBLIC` → `followState: "FOLLOWING"`, `requestId: null`, `Follow`
    creado de inmediato (comportamiento histórico sin cambios), notificación
    `NEW_FOLLOWER` al objetivo.
  - Perfil objetivo `PRIVATE` → `followState: "REQUESTED"`, `requestId` con el UUID de
    la `FollowRequest` recién creada (o de la ya existente, ver abajo), **sin** crear
    `Follow` todavía, notificación `FOLLOW_REQUEST_RECEIVED` al objetivo. El frontend
    puede usar ese `requestId` para ofrecer "cancelar solicitud" sin tener que llamar
    primero a `GET /api/follow-requests/outgoing`.
- **Idempotencia**: llamar de nuevo mientras ya existe una solicitud `PENDING` **no
  duplica** — devuelve `201` con los mismos `requestId`/`createdAt` de la solicitud ya
  existente. Distinto del caso "ya te sigue" (ver abajo), que sigue siendo `409`.
- **Errores**: `400 Bad Request` (intentar seguirte a vos mismo), `404 Not Found`
  (usuario objetivo no existe, **o existe un bloqueo entre ambos en cualquier
  dirección, Fase 9.4** — mismo mensaje genérico "Target user not found", nunca `403`,
  para no confirmar que el usuario existe pero está bloqueado), `409 Conflict` (ya existe
  una relación `Follow` **efectiva** — sin importar el `profileVisibility` actual del
  objetivo; si vos ya lo seguías de antes y ahora puso su perfil en privado, seguís
  siguiendolo igual, sin necesidad de una solicitud nueva).

### `DELETE /api/follows/{userId}`
Deja de seguir a alguien que **ya seguís efectivamente** (relación `Follow` real).
- **Response**: `204 No Content`.
- **Errores**: `404 Not Found` si no lo seguís.
- **No cancela solicitudes pendientes**: si lo que tenés con `userId` es una
  `FollowRequest` `PENDING` (todavía no te aceptó), este endpoint devuelve `404` (no hay
  `Follow` que borrar) — hay que usar `DELETE /api/follow-requests/{requestId}` para
  cancelar una solicitud enviada. Responsabilidades separadas a propósito: "dejar de
  seguir" y "cancelar una solicitud" son acciones distintas, sobre recursos distintos
  (ver `GET /api/follow-requests/outgoing` para encontrar el `requestId` si no se
  guardó el que devolvió el `POST /api/follows/{userId}` original).

### `DELETE /api/follows/followers/{userId}` (Fase 9.3)
Elimina a `userId` de **tus propios seguidores** — dirección inversa a `unfollow`
(acá `userId` es alguien que te sigue a vos, no alguien a quien vos seguís).
- **Response**: `204 No Content`.
- **Errores**: `404 Not Found` si `userId` no te sigue actualmente.
- **No afecta la relación inversa**: si vos también seguís a `userId`, eso queda
  intacto — sacar a alguien de tus seguidores no es lo mismo que dejar de seguirlo vos.
- **Corta el acceso de inmediato**: si `userId` tenía acceso a contenido
  `FOLLOWERS_ONLY`/a tu perfil `PRIVATE` completo por ser tu follower, ese acceso
  desaparece en el mismo momento (es la misma fila de `follows` que consultan
  `ProfileAccessPolicy`/`PostAccessPolicy`, sin ventana de gracia ni caché).

### `GET /api/follows/{userId}/followers`
**No pagina** — `List<UserSummary>` de quienes siguen efectivamente a `userId` (nunca
incluye solicitudes `PENDING`). No requiere que `userId` sea el usuario autenticado
(cualquier autenticado puede ver los followers de cualquiera).
- **Errores**: `404 Not Found` si `userId` no existe.

### `GET /api/follows/{userId}/following`
Igual que arriba pero a quiénes sigue `userId`. **No pagina**.

---

## 5bis. Follow Requests (`/api/follow-requests`) — requiere autenticación

Todas las solicitudes son creadas por `POST /api/follows/{userId}` (ver arriba) cuando
el objetivo tiene el perfil en `PRIVATE` — este namespace es exclusivamente para
**gestionar** una solicitud ya creada (aceptar, rechazar, cancelar, listar). No hay un
`POST /api/follow-requests` directo.

### `FollowRequestResponse` (forma común)
```json
{ "requestId": "uuid", "otherUser": { /* UserSummary */ }, "createdAt": "...", "status": "PENDING" }
```
`otherUser` es el **requester** en un listado de incoming, o el **target** en uno de
outgoing — siempre "la otra persona involucrada". `status` es
`PENDING`/`ACCEPTED`/`REJECTED`/`CANCELLED` (el historial completo del trámite — no
confundir con `followState`, que es el resumen de "cómo estoy parado hoy" usado en
perfil/discover/`POST /api/follows/{userId}`).

### `POST /api/follow-requests/{requestId}/accept`
Solo el **target** de la solicitud puede aceptarla.
- **Response 200**: `FollowRequestResponse` con `status: "ACCEPTED"`.
- **Efecto**: crea la relación `Follow` real (si no existía ya) y dispara notificación
  `FOLLOW_REQUEST_ACCEPTED` al requester.
- **Errores**: `404 Not Found` (solicitud inexistente), `403 Forbidden` (no sos el
  target), `409 Conflict` (ya no está `PENDING` — ya fue aceptada/rechazada/cancelada,
  o una request concurrente ya la resolvió primero — **incluyendo el caso en que alguno
  de los dos bloqueó al otro después de enviarla, Fase 9.4**: bloquear cancela
  automáticamente cualquier solicitud `PENDING` entre ambos, así que intentar aceptarla
  después da el mismo `409` que cualquier otra solicitud ya resuelta, sin un mensaje
  distinto que revele el bloqueo).

### `POST /api/follow-requests/{requestId}/reject`
Solo el **target**.
- **Response 200**: `FollowRequestResponse` con `status: "REJECTED"`.
- **Efecto**: nunca crea `Follow`. **No dispara notificación** al requester (decisión de
  producto: rechazar no aporta valor suficiente como para justificar avisarle).
- **Errores**: mismos criterios que `accept` (404/403/409).
- **Después de un rechazo**: el requester puede enviar una solicitud nueva más adelante
  sin restricciones — un rechazo no bloquea reintentos futuros.

### `DELETE /api/follow-requests/{requestId}`
Cancela una solicitud propia. Solo el **requester**.
- **Response**: `204 No Content`.
- **Errores**: `404 Not Found`, `403 Forbidden` (no sos el requester), `409 Conflict`
  (ya no está `PENDING`).

### `GET /api/follow-requests/incoming`
Solicitudes `PENDING` que **otros te enviaron a vos** (target). **No pagina** —
`List<FollowRequestResponse>`, `otherUser` = el requester.

### `GET /api/follow-requests/outgoing`
Solicitudes `PENDING` que **vos enviaste** (requester). **No pagina** —
`List<FollowRequestResponse>`, `otherUser` = el target.

---

## 6. Statuses (`/api/statuses`) — "estado de ánimo", requiere autenticación

### `StatusResponse` (forma común)
```json
{
  "id": "uuid",
  "user": { /* UserSummary */ },
  "mood": "WELL",
  "createdAt": "...",
  "expiresAt": "...",
  "reactionCount": 2,
  "reactedByCurrentUser": "WITH_YOU"
}
```
`reactedByCurrentUser` es `null` si el usuario autenticado no reaccionó a ese status
todavía, o el nombre del enum `StatusReactionType` si sí.

### `POST /api/statuses`
Publica un nuevo estado de ánimo propio. **No hay límite de uno-a-la-vez a nivel de
validación** — cada llamada crea un registro nuevo (a diferencia de `Availability`, que
sí reemplaza el anterior). El feed solo muestra el más reciente no vencido por usuario.
- **Body** (`SetStatusRequest`): `{ "mood": "WELL | NEED_DISTRACTION | DIFFICULT_DAY | NEED_TO_TALK | HERE_FOR_SOMEONE" }`
- **Response 201**: `StatusResponse` (`reactionCount: 0`, `reactedByCurrentUser: null`).
- **Expiración**: `expiresAt = now + 24h`. Un status vencido deja de aparecer en el feed
  pero **no se borra** de la base.

### `GET /api/statuses/feed`
**No pagina** — `List<StatusResponse>`. Un único status "actual" (el más reciente no
vencido) por cada usuario que sigo + el propio, orden `createdAt DESC`.
- **Bloqueo (Fase 9.4)**: excluye bilateralmente, filtrado en la query (mismo criterio
  de defensa en profundidad que el feed de posts — en la práctica ya es redundante con
  la limpieza de `follows` al bloquear, ver §12).

### `POST /api/statuses/{statusId}/react`
Reacciona a un status. Si ya habías reaccionado, **reemplaza** el tipo de reacción
anterior (no crea una segunda reacción — hay `UNIQUE(status_id, actor_id)` en DB).
Notifica `NEW_STATUS_REACTION` al dueño del status solo si es tu **primera** reacción a
ese status (cambiar el tipo de una reacción existente no vuelve a notificar).
- **Body** (`ReactToStatusRequest`): `{ "type": "WITH_YOU | WANT_TO_TALK | HERE_READING | NOT_ALONE" }`
- **Response 200**: `StatusResponse` actualizado.
- **Errores**: `404 Not Found` si el status no existe, **o si existe un bloqueo entre el
  autenticado y el dueño del status en cualquier dirección (Fase 9.4)** — mismo mensaje
  genérico que un status inexistente. Este endpoint es una interacción directa
  usuario-a-usuario que no pasa por `PostAccessPolicy` (los estados son un dominio
  separado de los posts), así que necesitó su propio chequeo de bloqueo explícito.

### `DELETE /api/statuses/{statusId}/react`
Quita tu reacción.
- **Response 200**: `StatusResponse` actualizado (`reactedByCurrentUser: null`).
- **Errores**: `404 Not Found` (status inexistente, o no habías reaccionado).

---

## 7. Availability / "modo compañía" (`/api/availability`) — requiere autenticación

Declaración de corto plazo ("estoy disponible para acompañar ahora"), vence a las **6
horas** (más corto que `Status`, que vence a las 24hs).

### `POST /api/availability`
Reemplaza cualquier disponibilidad activa anterior tuya (solo puede haber una a la vez).
- **Body** (`SetAvailabilityRequest`): `{ "intent": "TALK | DISTRACTION | WATCH_TOGETHER | MUSIC | LAUGH | JUST_COMPANY" }`
- **Response 201** (`AvailabilityResponse`):
  ```json
  { "id": "uuid", "user": { /* UserSummary */ }, "intent": "TALK", "createdAt": "...", "expiresAt": "..." }
  ```

### `DELETE /api/availability`
Cancela tu disponibilidad activa (si tenías una). Idempotente — no falla si no tenías ninguna.
- **Response**: `204 No Content`.

### `GET /api/availability/mine`
Tu disponibilidad activa actual.
- **Response 200**: `AvailabilityResponse`, **o literalmente el body `null` con status
  200** si no tenés ninguna activa (el service devuelve `null` en vez de lanzar 404 — ver
  `FRONTEND_HANDOFF.md` § gaps, el frontend debe manejar `response.data === null`
  explícitamente, no asumir que siempre hay objeto).

### `GET /api/availability?intent=TALK`
Lista hasta 10 personas disponibles ahora mismo con ese `intent`, en **orden aleatorio**
(a propósito — nunca por popularidad), excluyendo al propio usuario autenticado.
- **Query params**: `intent` (obligatorio, uno de `CompanionIntent`).
- **Response 200**: **No pagina** — `List<AvailabilityResponse>` (máx. 10 items, límite
  fijo en el backend, no configurable desde el cliente).
- **Bloqueo (Fase 9.4)**: excluye bilateralmente en la query nativa (`NOT EXISTS` sobre
  bloqueos) — ni quien el autenticado bloqueó ni quien lo bloqueó a él pueden aparecer,
  en ninguna dirección.

**Relación con chat**: el modo compañía es la única forma de iniciar una conversación
con alguien que no seguís ni te sigue — ver § 8 y reglas de `POST /api/conversations/{userId}`.

---

## 8. Chat (`/api/conversations`) — requiere autenticación

### `POST /api/conversations/{userId}`
Obtiene la conversación existente con `userId`, o la crea si no existe.
- **Regla de autorización para crear/obtener** (no es solo "cualquiera con cualquiera"):
  permitido si `currentUser` sigue a `userId`, **o** `userId` sigue a `currentUser`, **o**
  `userId` tiene una `Availability` activa en este momento (declaró estar disponible para
  acompañar). Si ninguna de las tres se cumple → `403 Forbidden`. La disponibilidad nunca
  habilita el sentido inverso (que alguien le escriba a quien está disponible sí, pero no
  al revés sin ese consentimiento).
- **Bloqueo (Fase 9.4)**: si existe un bloqueo entre ambos en cualquier dirección, tanto
  la conexión por follow como la disponibilidad de compañía se anulan — se trata
  exactamente igual que "no conectados, no disponible" (mismo `403` genérico de arriba,
  sin un mensaje distinto que revele el bloqueo). Esto se re-evalúa en **cada llamada**
  a este endpoint (no solo al crear la conversación por primera vez — mismo criterio que
  ya existía para follow/disponibilidad antes de esta fase), así que también cubre el
  caso de "ya teníamos una conversación, pero ahora uno de los dos bloqueó al otro": este
  endpoint específico deja de funcionar para ese par, aunque la conversación siga
  existiendo en la base y siga siendo legible (ver abajo).
- **Errores**: `400 Bad Request` (intentar chatear con vos mismo), `404 Not Found`
  (usuario objetivo no existe), `403 Forbidden` (regla de arriba, incluyendo bloqueo).
- **Response 200** (`ConversationResponse`):
  ```json
  {
    "id": "uuid",
    "otherUser": { /* UserSummary */ },
    "lastMessageContent": "texto o null",
    "lastMessageAt": "... o null",
    "unreadCount": 0
  }
  ```
  Nota: en este endpoint específico `unreadCount` **siempre viene en `0`**, sin
  importar el estado real — no está calculado acá (sí lo está en `GET /api/conversations`).

### `GET /api/conversations`
**No pagina** — `List<ConversationResponse>` de todas las conversaciones del usuario,
orden `lastMessageAt DESC` (las que nunca tuvieron mensajes van al final, `NULLS LAST`).
Acá `unreadCount` sí refleja el conteo real de mensajes no leídos enviados por la otra persona.
- **Bloqueo (Fase 9.4)**: **no** filtra nada — una conversación con una persona
  bloqueada (en cualquier dirección) sigue apareciendo en este listado con total
  normalidad. Decisión explícita: el historial de chat se preserva sin excepción (ver
  §12), y ocultar la conversación de este listado la haría inalcanzable desde el
  frontend sin borrar nada — peor que simplemente dejarla visible en modo solo lectura
  (ver `POST .../messages` abajo para el bloqueo real de mensajes nuevos).

### `GET /api/conversations/{conversationId}/messages`
- **Query params**: `page` (default `0`), `size` (default **`50`**, distinto al default
  `20` del resto de la API).
- **Efecto secundario**: llamar este endpoint **marca como leídos** todos los mensajes
  no propios de esa conversación (`markAsRead`) — no es una operación de solo lectura a
  nivel de estado, y no hay forma de "peek" sin marcar leído.
- **Response 200**: `Page<MessageResponse>`, orden `createdAt ASC` (más viejo primero,
  a diferencia de casi toda la demás API que ordena descendente).
  ```json
  {
    "id": "uuid", "conversationId": "uuid",
    "sender": { /* UserSummary */ },
    "content": "...", "read": true, "createdAt": "..."
  }
  ```
- **Errores**: `403 Forbidden` si no sos parte de la conversación. `404 Not Found` si no existe.
- **Bloqueo (Fase 9.4)**: **no** se revalida acá — el historial completo (mensajes de
  antes **y** de después de que exista un bloqueo, si los hubiera) sigue siendo legible
  por ambas partes sin ninguna restricción. Un bloqueo nunca borra ni oculta mensajes ya
  enviados.

### `POST /api/conversations/{conversationId}/messages`
Envía un mensaje. Además de persistirlo, lo empuja por WebSocket al destinatario (ver
`WEBSOCKET_CONTRACT.md`) — el POST es la única forma de enviar (no hay envío vía STOMP).
- **Body** (`SendMessageRequest`): `{ "content": "máx 2000 chars, obligatorio" }`
- **Response 201**: `MessageResponse` recién creado.
- **Errores**: `403 Forbidden` (no sos parte de la conversación, **o existe un bloqueo
  entre ambos en cualquier dirección, Fase 9.4** — mismo status code, mensaje distinto,
  nunca revela cuál de los dos motivos aplicó), `404 Not Found`.
- **WebSocket (Fase 9.4)**: el servidor STOMP es exclusivamente push (`convertAndSendToUser`
  hacia `/queue/messages`) — no existe ningún `@MessageMapping` que acepte mensajes
  entrantes del cliente por WebSocket, así que este `POST` es el único punto de entrada
  para enviar un mensaje y el único lugar donde hace falta el chequeo de bloqueo. No hay
  forma de bypassear este gate por WS.

---

## 9. Notifications (`/api/notifications`) — requiere autenticación

Las notificaciones se generan internamente desde otros módulos (follow, comment, support,
status reaction) — no hay endpoint para crearlas manualmente.

- **Bloqueo (Fase 9.4)**: una guarda centralizada en `NotificationService.notify()`
  suprime cualquier notificación **nueva** entre dos usuarios con un bloqueo activo (en
  cualquier dirección) — en la práctica esto ya es redundante con que la acción que
  dispararía la notificación (follow, comentario, apoyo, reacción de estado) ya se
  rechaza antes de llegar a `notify()`, pero queda como defensa en profundidad centralizada
  en un solo lugar en vez de duplicada en cada caller. **No afecta notificaciones ya
  existentes** — bloquear a alguien nunca borra notificaciones históricas generadas
  antes del bloqueo, sólo evita que se generen nuevas.

### `GET /api/notifications`
- **Query params**: `page` (default `0`), `size` (default `20`).
- **Response 200**: `Page<NotificationResponse>`, orden `createdAt DESC`.
  ```json
  {
    "id": "uuid",
    "actor": { /* UserSummary — quien generó la acción */ },
    "type": "NEW_FOLLOWER",
    "postId": "uuid o null (null para NEW_FOLLOWER)",
    "read": false,
    "createdAt": "..."
  }
  ```

### `GET /api/notifications/unread-count`
- **Response 200**: `{ "count": 3 }` — objeto plano `Map<String, Long>`, no un DTO tipado.

### `PATCH /api/notifications/read-all`
Marca **todas** las notificaciones no leídas del usuario como leídas. No existe endpoint
para marcar una sola notificación como leída individualmente.
- **Response**: `204 No Content`.

---

## 10. Reports (`/api/reports`) — moderación

### `POST /api/reports`
Cualquier usuario autenticado puede reportar un post, comentario o usuario.
- **Auth**: requiere solo estar autenticado (cualquier rol).
- **Body** (`CreateReportRequest`):
  ```json
  {
    "targetType": "POST | COMMENT | USER",
    "targetId": "uuid",
    "reason": "SELF_HARM_RISK | HARASSMENT | SPAM | HATE_SPEECH | OTHER",
    "description": "máx 1000 chars, opcional"
  }
  ```
  **Nota**: `targetId` no se valida contra la existencia real del post/comment/usuario
  referenciado (no es FK en DB, es una referencia polimórfica) — se puede crear un
  reporte apuntando a un `targetId` inexistente sin error.
- **Response 201** (`ReportResponse`):
  ```json
  {
    "id": "uuid", "reporterId": "uuid", "targetType": "POST", "targetId": "uuid",
    "reason": "SELF_HARM_RISK", "description": "...", "status": "PENDING",
    "reviewedById": null, "createdAt": "...", "reviewedAt": null
  }
  ```

### `GET /api/reports/queue`
- **Auth**: requiere rol `MODERATOR` o `ADMIN` (`403 Forbidden` si no).
- **Query params**: `page` (default `0`), `size` (default `20`).
- **Response 200**: `Page<ReportResponse>`, solo `status = PENDING`, **priorizado**:
  los reportes con `reason = SELF_HARM_RISK` siempre aparecen primero,
  independientemente de la fecha; dentro de cada grupo, orden `createdAt ASC` (más
  antiguo primero).

### `PATCH /api/reports/{reportId}/resolve`
- **Auth**: requiere rol `MODERATOR` o `ADMIN`.
- **Body** (`ResolveReportRequest`): `{ "status": "REVIEWED | ACTION_TAKEN | DISMISSED" }`
  (enviar `PENDING` es inválido, ver errores).
- **Response 200**: `ReportResponse` con `status` actualizado, `reviewedById` = el
  moderador que resolvió, `reviewedAt` seteado.
- **Errores**: `400 Bad Request` si `status = PENDING`. `409 Conflict` si el reporte ya
  había sido resuelto antes (`status != PENDING` al momento de resolver — no se puede
  re-resolver). `404 Not Found` si no existe.
- **Importante — no hay efecto en cascada**: resolver un reporte (incluso con
  `ACTION_TAKEN`) **no** borra ni oculta automáticamente el post/comentario/usuario
  reportado. Si la acción de moderación implica remover contenido, el moderador debe
  además llamar explícitamente a `DELETE /api/posts/{id}` o
  `DELETE /api/posts/{postId}/comments/{commentId}`. Ver `FRONTEND_HANDOFF.md` § gaps.

---

## 11. Admin (`/api/admin/users`) — solo `ADMIN`

### `PATCH /api/admin/users/{userId}/role`
Cambia el rol de un usuario.
- **Auth**: requiere rol `ADMIN` (`@PreAuthorize` a nivel de clase, todo el controller).
- **Body** (`UpdateUserRoleRequest`): `{ "role": "USER | MODERATOR | ADMIN" }`
- **Response 200**: `UserResponse` del usuario actualizado (incluye su `email` — este
  endpoint es admin-only, no expone email a terceros no-admin).
- **Errores**: `409 Conflict` — intentar degradar al **último** `ADMIN` restante del
  sistema (`"Cannot remove the last remaining admin"`), protección para no dejar el
  sistema sin ningún admin. `404 Not Found` si `userId` no existe.
- **Nota**: no hay listado de usuarios (`GET /api/admin/users`) — este es el único
  endpoint admin implementado además de la cola de reportes (§10). No hay endpoints para
  suspender/desactivar cuentas (`UserStatus.SUSPENDED`/`DEACTIVATED` existen en el enum
  y afectan login, pero nada en la API permite setearlos actualmente).

---

## 12. User Blocking (`/api/users/{userId}/block`, `/api/users/me/blocked`) — requiere autenticación

Bloqueo de usuario a usuario (Fase 9.4). La tabla `user_blocks` es **direccional**
(`blocker_id`, `blocked_id`), pero el **acceso** en toda la API se trata como
**bilateral**: un bloqueo de cualquiera de los dos lados corta la relación completa para
ambos (ver `BlockPolicy.isBlockedBetween` en `BACKEND_ARCHITECTURE.md`). El `blockerId`
**nunca** se acepta desde el body — siempre es el usuario del JWT, y `userId` en el path
es siempre el **target**.

### `POST /api/users/{userId}/block`
Bloquea a `userId`. **Idempotente** — bloquear a alguien ya bloqueado no falla (`204`
igual, sin crear una segunda fila).
- **Response**: `204 No Content`.
- **Errores**: `400 Bad Request` (intentar bloquearte a vos mismo), `404 Not Found`
  (`userId` no existe).
- **Efecto secundario transaccional** (todo en una sola operación, ver
  `BlockService.blockUser`):
  - Borra cualquier fila `Follow` real en **ambas** direcciones (`blocker→target` y
    `target→blocker`) — no importa quién seguía a quién.
  - Cancela (`status = CANCELLED`, no borra) cualquier `FollowRequest` `PENDING` en
    ambas direcciones. **No toca** filas históricas `ACCEPTED`/`REJECTED` — quedan
    intactas como registro de que existieron.
  - **Después de bloquear**: no se puede crear un `Follow` ni una `FollowRequest` nueva
    entre ambos en ninguna dirección (`POST /api/follows/{userId}` responde `404`), no
    se puede aceptar una solicitud vieja que quedó cancelada por el bloqueo, no se puede
    iniciar una conversación nueva ni enviar mensajes nuevos, no se ve el perfil/posts
    completo del otro, no aparece en el feed/discover/disponibilidad del otro.

### `DELETE /api/users/{userId}/block`
Desbloquea a `userId`. Solo quien bloqueó puede desbloquear (el path/principal son
siempre el mismo `blockerId` — no hay forma de desbloquear "en nombre" de otro).
**Idempotente** — desbloquear a alguien no bloqueado no falla (`204` igual).
- **Response**: `204 No Content`.
- **Decisión explícita — desbloquear es *solo* borrar la fila**: no recrea el `Follow`
  que existía antes de bloquear, no reactiva la `FollowRequest` que quedó `CANCELLED`,
  no restaura conversaciones ni disponibilidad a ningún estado previo. El estado
  "post-desbloqueo" es el mismo que el de dos desconocidos que nunca se siguieron.

### `GET /api/users/me/blocked`
Lista de usuarios que el **autenticado** bloqueó — **nunca** quién lo bloqueó a él (no
existe ningún endpoint para consultar eso, ver más abajo).
- **Query params**: `page` (default `0`), `size` (default `20`).
- **Response 200**: `Page<BlockedUserResponse>`:
  ```json
  { "userId": "uuid", "username": "...", "displayName": "...", "avatarUrl": "...", "blockedAt": "..." }
  ```
  DTO mínimo a propósito — **nunca incluye `email`**, mismo criterio que `UserSummary`.

### Decisiones de diseño explícitas (para el frontend y para no repetir el debate)

- **`blockedByCurrentUser` sí, `blockingCurrentUser` no**: `GET /api/users/{userId}`
  expone si **vos** bloqueaste al `userId` consultado, pero **jamás** expone si
  `userId` te bloqueó a **vos** — ese caso simplemente resulta en `404 Not Found` en
  ese mismo endpoint (ver §2). No hay ninguna otra vía en la API para que un usuario
  averigüe si otro lo bloqueó.
- **Perfil bloqueado → 404, no 403**: igual que un post invisible, nunca se confirma
  "existe pero no podés verlo" — se trata como si no existiera. Esto es una extensión
  puntual del criterio 404-no-403 ya usado en toda la API para contenido oculto; **no**
  reemplaza el criterio ya existente de que un perfil `PRIVATE` sin bloqueo de por medio
  sigue devolviendo `200` con vista limitada (ver §2) — son dos ejes distintos
  (visibilidad vs. bloqueo) que conviven sin contradecirse.
- **Chat: historial se preserva siempre, nunca se borra**: bloquear NO borra
  conversaciones ni mensajes. Solo impide crear una conversación nueva o enviar
  mensajes nuevos (ver §8). El frontend puede seguir mostrando la conversación bloqueada
  en la lista y su historial completo — solo debe deshabilitar el campo de "escribir un
  mensaje nuevo" si la creación/envío devuelve `403`.
- **Moderación/reportes no se ven afectados**: bloquear no impide reportar a alguien
  (§10) ni le da ni le quita capacidades a moderadores/admins — un moderador puede
  seguir borrando un post aunque su autor lo haya bloqueado a él.

### Fuera de alcance de esta fase (explícitamente no implementado)

Silenciar sin bloquear ("mute"), ocultar un post puntual, ocultar a un usuario sin
bloquearlo, reporte automático al bloquear, motivo de bloqueo, bloqueo temporizado,
"amigos cercanos"/audiencias personalizadas/círculos, bloqueo por dispositivo, y
cualquier mecanismo de anti-abuso/rate-limiting más allá de lo que ya existía.

---

## Formato de error

Todas las respuestas de error (4xx/5xx) devueltas por el backend usan esta forma
(`ErrorResponse`):

```json
{
  "timestamp": "2026-09-25T14:30:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Post not found",
  "path": "/api/posts/...",
  "fieldErrors": null
}
```

- `fieldErrors` solo viene poblado (como `{ "campo": "mensaje" }`) en errores `400` de
  validación de body (`@Valid` fallido) — el resto de los errores lo traen `null`.
- `401 Unauthorized`: sin token, token inválido/expirado, o credenciales incorrectas en
  login.
- `403 Forbidden`: autenticado pero sin permiso (rol insuficiente, o dueño distinto del
  recurso).
- `404 Not Found`: recurso inexistente, **o** ruta que no matchea ningún endpoint
  (`"No endpoint found for GET /api/algo"`).
- `500 Internal Server Error`: cualquier excepción no controlada — el mensaje al
  cliente es siempre el genérico `"An unexpected error occurred"` (el detalle real
  queda solo en el log del servidor, nunca se filtra al cliente).
