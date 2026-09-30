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
| `CompanionIntent` | `TALK`, `DISTRACTION`, `WATCH_TOGETHER`, `MUSIC`, `LAUGH`, `JUST_COMPANY` | Modo compañía **legacy** (`/api/availability`, § 7). Backend Debt B4B.3: ya NO es un enum de dominio — solo contrato legacy, traducido a/desde `OfferingType` vía `LegacyAvailabilityMapper` (mapping lossy en el sentido Offering→Intent, ver § 7) |
| `NeedType` (Backend Debt B4B.1) | `LISTEN_TO_ME`, `TALK`, `GET_OPINION`, `DISTRACTION`, `JUST_COMPANY` | "Necesito compañía" (`/api/companion/need`). Nunca se compara ni convierte con `OfferingType`/`CompanionIntent` directamente — la única relación es la matriz de compatibilidad estática (§ 7ter) |
| `OfferingType` (Backend Debt B4B.2) | `LISTEN`, `TALK`, `DISTRACT` | "Cómo puedo acompañar ahora" (`/api/companion/offering`). Dominio real desde B4B.2/B4B.3. Se traduce a/desde `CompanionIntent` únicamente en el adapter legacy (`LegacyAvailabilityMapper`, § 7) — nunca se compara directamente con `NeedType`/`CompanionPreferenceType` futuro |
| `StatusMood` | `WELL`, `NEED_DISTRACTION`, `DIFFICULT_DAY`, `NEED_TO_TALK`, `HERE_FOR_SOMEONE` | Estados de ánimo |
| `StatusReactionType` | `WITH_YOU`, `WANT_TO_TALK`, `HERE_READING`, `NOT_ALONE` | Reacciones a un estado |
| `PostResponseType` (Backend Debt B1) | `WITH_YOU`, `NOT_ALONE`, `HUG`, `READING`, `TELL_ME_MORE`, `LISTENING` | Responder a un post (§3). Dominio independiente de `StatusReactionType` — nunca se comparan ni convierten entre sí, aunque compartan alguna etiqueta |
| `NotificationType` | `NEW_FOLLOWER`, `NEW_COMMENT`, `NEW_POST_RESPONSE`, `NEW_STATUS_REACTION`, `FOLLOW_REQUEST_RECEIVED`, `FOLLOW_REQUEST_ACCEPTED` | Notificaciones (in-app y WebSocket). **Backend Debt B1**: `NEW_SUPPORT` fue renombrado a `NEW_POST_RESPONSE` — representa cualquier `PostResponseType`, no solo el soporte binario anterior |
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
Actualiza el perfil propio — **persistencia real** (ya lo era antes de Backend Debt B2;
esta fase agregó validación/normalización de texto, no el mecanismo de persistir en sí).
Todos los campos son opcionales (solo se aplican los `!= null`; `null`/campo omitido
siempre significa "no tocar", para los 4 campos).
- **Body** (`UpdateProfileRequest`):
  ```json
  { "displayName": "máx 100 chars", "bio": "máx 500 chars", "avatarUrl": "string", "profileVisibility": "PUBLIC | PRIVATE" }
  ```
  - `displayName` (Backend Debt B2 — validación nueva): opcional (`null`/omitido = no
    tocar). Si se envía un valor no-null, se recorta (`trim`) y **debe quedar no-vacío**
    tras el trim — `400 Bad Request` (`"displayName cannot be blank"`) si el resultado es
    `""` o solo espacios. **No** existe una vía para vaciar `displayName` explícitamente
    vía este endpoint (a diferencia de `bio`) — es un campo que, aunque puede ser `null`
    a nivel de todo el sistema (nunca fue obligatorio en el registro, ver
    `POST /api/auth/register`), un PATCH que lo deja en blanco es casi siempre un error
    del cliente, no una intención real de "vaciarlo". Límite máximo sin cambios
    (`@Size(max = 100)`, ya existía).
  - `bio` (Backend Debt B2 — validación nueva): opcional (`null`/omitido = no tocar). Si
    se envía un valor no-null, se recorta (`trim`); si el resultado queda vacío (`""` o
    solo espacios), **se persiste como `null`** (limpiar la bio explícitamente sí es una
    operación válida e intencional, a diferencia de `displayName`) — mismo criterio que
    ya usaba el resto de la API para "sin bio" (`null`, nunca `""`, ver perfil
    limitado/privado más abajo). Límite máximo sin cambios (`@Size(max = 500)`, ya
    existía).
  - `avatarUrl`: sin cambios de validación en esta fase.
  - `profileVisibility` (Fase 9.1): opcional, mismo criterio "`null` = no tocar" que el
    resto de los campos de este DTO. Un valor que no sea `PUBLIC`/`PRIVATE` responde
    `400 Bad Request` (body malformado — mismo manejo genérico que cualquier enum
    inválido en esta API, no un caso especial).
  - **Texto plano, sin sanitización HTML** (Backend Debt B2, decisión explícita):
    `displayName`/`bio` se validan (trim + límites) y persisten tal cual — nunca se
    interpretan como markup en el backend. El único lugar de todo el sistema que
    interpola `displayName` en HTML (el saludo de los emails transaccionales) ya tiene su
    propio escape dedicado, independiente de esto. La UI es responsable de escapar al
    renderizar, como con cualquier texto de usuario en esta API.
- **Response 200**: `UserResponse` (igual forma que `GET /me`) — refleja los valores
  **ya recortados/normalizados** que quedaron persistidos, no el string crudo enviado.
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
    "blockedByCurrentUser": false,
    "mutedByCurrentUser": false,
    "companionPreferences": ["LISTEN", "TALK"]
  }
  ```
  **Cambio de contrato (Backend Debt B4B.5)**: `companionPreferences` es un campo nuevo
  (adición pura al final) — lista de `"LISTEN" | "TALK" | "DISTRACT"` en orden
  determinista (LISTEN, TALK, DISTRACT). Sigue **exactamente** la misma regla de acceso
  que `bio`: `null` cuando el perfil está limitado (PRIVATE sin ser dueño ni follower
  aceptado, o bloqueado por vos) — nunca `[]` en ese caso, para no revelar si hay datos
  reales; `[]` solo cuando el perfil es visible y no configuró ninguna. Es dato estable
  de perfil, independiente de Need/Offering (ver § 7quater).
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
  **Cambio de contrato (Fase 9.5)**: `mutedByCurrentUser` es un campo nuevo (adición pura
  al final) — `true` únicamente si **vos** silenciaste a `userId`. A diferencia de
  `blockedByCurrentUser`, este campo **nunca** afecta el resto de la respuesta (silenciar
  no es control de acceso, ver § 13) y, por ser unilateral e invisible por diseño,
  **tampoco existe** un campo equivalente para el sentido contrario en ningún endpoint de
  la API.
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
  - **Mute (Fase 9.5) — a propósito SIN efecto acá**: silenciar a `userId` **no** oculta
    nada en esta ruta. Entrar explícitamente al perfil de alguien y pedir sus posts es
    acceso directo, no una superficie agregada — mute solo saca contenido del feed
    (ver § Posts) y de discover (ver más abajo), nunca de un acceso directo. Aplican las
    mismas reglas de visibilidad de arriba exactamente igual que si no hubiera mute.
- **Response 200**: `Page<PostResponse>` (ver forma de `PostResponse` en § Posts).
- **Errores**: `404 Not Found` si `userId` no existe (esto sí es 404 real — el usuario en
  sí no existe, no es un tema de privacidad).

### `GET /api/users/{userId}/status` (Backend Debt B2)
Contrato directo para el status/mood **actual** de `userId` — a diferencia de
`GET /api/posts/feed`/`GET /api/statuses/feed`, esta ruta consulta puntualmente por
`userId`, nunca infiere recorriendo el feed. "Actual" tiene exactamente la misma
definición que ya usaba `GET /api/statuses/feed`: el status **no vencido**
(`expiresAt > now`) más reciente (`createdAt DESC`) de ese usuario — sin una segunda
definición de "actual" para esta ruta.
- **Path params**: `userId` (UUID).
- **Response 200** (`StatusResponse`, misma forma que en § Statuses, o **body vacío** si
  el usuario no tiene un status activo ahora mismo — mismo criterio que
  `GET /api/availability/mine`: una ausencia genuina de dato es `200`, nunca `404`; el
  frontend debe manejar `response.data` vacío/`null`, no asumir que siempre hay objeto):
  ```json
  {
    "id": "uuid",
    "user": { "id": "uuid", "username": "...", "displayName": "...", "avatarUrl": "..." },
    "mood": "WELL",
    "createdAt": "...",
    "expiresAt": "...",
    "reactionCount": 2,
    "reactedByCurrentUser": "WITH_YOU o null"
  }
  ```
- **Acceso — reusa exactamente el mismo gate que perfil completo** (`ProfileAccessPolicy.
  canViewFullProfile`, la misma policy que decide `bio` en `GET /api/users/{userId}` y
  acceso a posts): dueño → siempre visible. Perfil `PUBLIC` → siempre visible. Perfil
  `PRIVATE` + follower ya **ACEPTADO** → visible. Perfil `PRIVATE` + `REQUESTED`/`NONE` →
  no visible. Bloqueo (Fase 9.4, en cualquier dirección) → no visible. En todos los casos
  de "no visible", `404 Not Found` genérico (mismo mensaje que un usuario inexistente —
  nunca revela si el motivo fue bloqueo o privacidad, mismo criterio 404-no-403 que
  posts/comments/support/reacciones de estado).
  **Mute (Fase 9.5) — a propósito SIN efecto acá**: silenciar a `userId` no impide
  consultar su status directamente — es acceso directo, no una superficie agregada (mute
  solo saca contenido de `GET /api/statuses/feed`, nunca de un acceso puntual por
  `userId`).
- **Errores**: `404 Not Found` — `userId` no existe, perfil no accesible (ver arriba), o
  bloqueo (ver arriba); los tres casos son indistinguibles desde el status code.
- **No confundir con `GET /api/statuses/feed`**: el feed es una superficie agregada
  (propios + de quienes se sigue, filtrada por mute) pensada para timeline; esta ruta es
  para "quiero saber el status de esta persona en particular", típicamente desde su
  perfil — funcionan con reglas de acceso distintas a propósito (ver arriba) y no deben
  fusionarse.

### `GET /api/users/{userId}/availability` (Backend Debt B4B.4)
Disponibilidad de Companion **actual** de `userId` — "¿está disponible ahora para
acompañar a alguien, y de qué forma?". Lee directamente `CompanionOffering` (el dominio
nuevo, § 7ter) — nunca la tabla legacy `availabilities` (retirada en B4B.3).
- **Path params**: `userId` (UUID).
- **Response 200** (`CompanionAvailabilityResponse`), o **body vacío** si `userId` no tiene
  una Offering activa ahora mismo (ausencia genuina de dato, nunca `404` — mismo criterio
  que `GET /api/companion/offering/mine` y `GET /api/users/{userId}/status`):
  ```json
  { "available": true, "offeringType": "LISTEN", "expiresAt": "..." }
  ```
  `offeringType` es **singular** (`"LISTEN" | "TALK" | "DISTRACT"`) — solo puede existir
  una Offering activa por usuario (`UNIQUE(user_id)`, ver V15). `available` viaja
  explícito a propósito (no solo inferido de que el body no sea `null`), para que el
  frontend no tenga que inferir disponibilidad de la sola presencia del objeto. **No
  incluye**: `UserSummary`, `id`, `createdAt`, email, `Need`, ni ningún dato de perfil.
- **Acceso — DELIBERADAMENTE DISTINTO del gate de `/status` (arriba). No reutiliza
  `ProfileAccessPolicy`** (decisión B4A #3, reafirmada para este endpoint): activar una
  Offering es un consentimiento **específico y temporal** para exponer esta disponibilidad
  mínima en superficies de Companion — no equivale a un accepted follower, nunca
  desbloquea bio/posts/status/perfil completo.
  - Perfil `PUBLIC`, `PRIVATE` + `FOLLOWING`, `PRIVATE` + `REQUESTED`, `PRIVATE` + `NONE`:
    en los cuatro casos, si no hay bloqueo, la disponibilidad es consultable igual —
    `ProfileVisibility`/`FollowState` **no participan** de esta decisión en absoluto.
  - **Bloqueo (única regla que corta el acceso)**: si existe un bloqueo entre viewer y
    `userId` en **cualquier dirección**, `404 Not Found` genérico — igual que el resto de
    la API, nunca revela si el motivo fue "no existe" o "hay un bloqueo", ni cuál de los
    dos bloqueó a cuál.
  - **Mute — SIN efecto acá, a propósito**: silenciar a `userId` no impide consultar su
    disponibilidad directamente (acceso puntual por `userId`, no una superficie agregada).
    Mute sigue excluyendo a `userId` de `GET /api/companion/offering`/`/compatible` del
    viewer (§ 7ter) — ese es el único lugar donde mute filtra algo.
  - El propio usuario puede consultar su disponibilidad por esta misma ruta sin
    tratamiento especial (`blockPolicy.isBlockedBetween` ya devuelve `false` para
    `userId == viewerId`).
- **Errores**: `401` sin autenticación. `404 Not Found` — `userId` no existe, o bloqueo en
  cualquier dirección (indistinguibles).
- **No confundir con `GET /api/users/{userId}/status`**: `/status` sí reutiliza
  `ProfileAccessPolicy.canViewFullProfile` (perfil `PRIVATE` sin accepted follower → no
  visible). `/availability` deliberadamente **no** — son dos gates distintos a propósito,
  no una inconsistencia a "unificar".

### `GET /api/users/discover`
Browse y búsqueda de personas, paginado.
- **Browse** (`q` ausente, vacío o en blanco): usuarios que el autenticado **no sigue
  todavía** (para descubrir gente nueva).
- **Search** (`q` presente, Backend Debt B5.1): busca por `displayName` y `username`,
  *contains* sin distinguir mayúsculas (`q=fac` encuentra "Facundo"). **Nunca** busca por
  email ni por bio. En search los usuarios que ya seguís **sí aparecen**, con
  `followState: "FOLLOWING"`.
- **Orden estable** (B5.1): `displayName` (o `username` si no tiene) sin distinguir
  mayúsculas, ascendente, desempate por `id` — la paginación no repite ni saltea usuarios.
- **Cuentas listadas**: solo `ACTIVE` (las `SUSPENDED`/`DEACTIVATED` no aparecen). El
  `role` **no** filtra: un `MODERATOR`/`ADMIN` activo aparece igual que un `USER`.
- **No filtra por `profileVisibility`**: un usuario `PRIVATE` aparece como identidad
  limitada (`bio: null`, aunque ya lo sigas).
- **Query params**: `q` (opcional, máx. 50 caracteres tras `trim` y colapsar espacios),
  `page` (default `0`, `>= 0`), `size` (default `20`, entre `1` y `50`).
- **Errores `400`**: `page < 0`, `size < 1`, `size > 50`, `q` normalizado > 50 caracteres,
  y valores no numéricos (`?size=abc`, `?page=x`). No hay clamp silencioso.
- **Response 200**: `Page<DiscoverUserResponse>`:
  ```json
  { "id": "uuid", "username": "...", "displayName": "...", "bio": "... o null", "avatarUrl": "...", "profileVisibility": "PUBLIC", "followState": "NONE" }
  ```
  `bio` viaja en `null` cuando `profileVisibility` es `PRIVATE` — mismo criterio que
  `GET /api/users/{userId}`, para no exponer el mismo dato por una ruta lateral.
  `followState` (Fase 9.3) en **browse** es siempre `"NONE"` o `"REQUESTED"` — nunca
  `"FOLLOWING"`, porque browse excluye a quienes se sigue efectivamente. En **search**
  (B5.1) puede ser `"NONE"`, `"REQUESTED"` o `"FOLLOWING"`; `REQUESTED` nunca se colapsa
  en `NONE`.
  **Bloqueo (Fase 9.4)**: excluye bilateralmente a quien el autenticado bloqueó **y** a
  quien lo bloqueó a él (dentro de la propia query, B5.1) — ninguna de las dos
  direcciones aparece nunca en este listado, ni en browse ni en search; el bloqueo
  prevalece sobre el follow.
  **Mute (Fase 9.5)**: excluye, además, a quien el autenticado silenció — **solo esa
  dirección** (también dentro de la query, en browse y en search). A diferencia del bloqueo, no hay exclusión
  recíproca: que alguien te haya silenciado a vos no te saca de **su** discover ni del
  de nadie más, porque mute nunca filtra desde la perspectiva del muted.

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
  "supportedByCurrentUser": false,
  "presenceCount": 2,
  "listeningCount": 1,
  "currentUserResponseType": "HUG"
}
```
`author` es un `UserSummary` (siempre esta misma forma en toda la API: `id`, `username`,
`displayName`, `avatarUrl` — nunca incluye `email` ni `bio`).

**Cambio de contrato (Backend Debt B1)**: `presenceCount`, `listeningCount` y
`currentUserResponseType` son campos nuevos (adición pura al final, no rompe contrato).
`currentUserResponseType` viaja en `null` si el usuario autenticado no respondió a este
post — nunca un string vacío. `supportCount`/`supportedByCurrentUser` **se conservan por
compatibilidad temporal** (LEGACY) — dejan de ser una tabla/concepto binario separado y
pasan a derivarse de los mismos datos: `supportCount = presenceCount + listeningCount`,
`supportedByCurrentUser = currentUserResponseType != null`. Nunca dos fuentes de verdad:
ambos pares de campos salen del mismo conteo. Ver § "Respuestas a un post" más abajo para
el detalle completo (reemplaza el "apoyo" binario anterior).

### `POST /api/posts`
- **Body** (`CreatePostRequest`):
  ```json
  { "content": "máx 2000 chars, obligatorio", "visibility": "PUBLIC | FOLLOWERS_ONLY | PRIVATE (opcional, default PUBLIC)" }
  ```
- **Response 201**: `PostResponse` recién creado (`presenceCount: 0`, `listeningCount: 0`,
  `currentUserResponseType: null`, `supportCount: 0`, `supportedByCurrentUser: false`).

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
- **Mute (Fase 9.5)**: excluye, además, los posts de cualquier autor que el autenticado
  haya silenciado — **unilateral**, filtrado directo en la misma query (`NOT EXISTS`
  sobre `UserMute`, no post-filtrado en memoria). A diferencia del bloqueo, esto **no**
  es redundante con ninguna otra limpieza — silenciar no borra ni modifica `follows`, así
  que este es el único mecanismo que saca esos posts del feed. El autor sigue siendo un
  follower efectivo en todo lo demás (seguís pudiendo entrar a su perfil/posts
  directamente, ver § 2).

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
  **Mute (Fase 9.5) — a propósito SIN efecto acá**: silenciar al autor no cambia nada en
  esta ruta. `PostAccessPolicy.canView` no chequea mute en absoluto — este endpoint es
  acceso directo (el frontend entra desde un link, notificación o el perfil del autor),
  no una superficie agregada.

### `PATCH /api/posts/{postId}`
Solo el autor puede editar. Campos opcionales (solo se aplican los no-null).
- **Body** (`UpdatePostRequest`): `{ "content": "máx 2000 (opcional)", "visibility": "... (opcional)" }`
- **Response 200**: `PostResponse` actualizado (`presenceCount`/`listeningCount`/
  `currentUserResponseType`, y los legacy `supportCount`/`supportedByCurrentUser`, se
  recalculan reales — editar el post nunca resetea las respuestas que ya tenía).
- **Errores**: `403 Forbidden` si no sos el autor. `404 Not Found` si no existe.

### `DELETE /api/posts/{postId}`
Soft delete: pone `status = REMOVED` (no borra el registro). Permitido para el autor **o**
para `MODERATOR`/`ADMIN`.
- **Response**: `204 No Content`.
- **Errores**: `403 Forbidden` (ni autor ni moderador/admin), `404 Not Found`.

### Respuestas a un post (Backend Debt B1) — reemplaza el "apoyo" binario anterior

El frontend ofrece 6 respuestas posibles a un post, agrupadas en 2 categorías
conceptuales (la categoría **no se persiste aparte** — se deriva del `type`, ver
`PostResponseType.isPresence()/isListening()`):

| Categoría | Valores |
|---|---|
| **PRESENCE** | `WITH_YOU`, `NOT_ALONE`, `HUG` |
| **LISTENING** | `READING`, `TELL_ME_MORE`, `LISTENING` |

Una sola respuesta **activa** por usuario/post (`UNIQUE(post_id, user_id)` en DB) — elegir
otro tipo actualiza esa misma fila, nunca crea una segunda. **El autor no puede responder
a su propio post** (`400 Bad Request`, en los 4 endpoints de esta sección).

### `PUT /api/posts/{postId}/response`
Crea tu respuesta a este post, o cambia el tipo de la que ya tenías (upsert real).
- **Body** (`CreatePostResponseRequest`): `{ "type": "WITH_YOU | NOT_ALONE | HUG | READING | TELL_ME_MORE | LISTENING" }`
- **Response 200** (`PostResponseSummaryResponse`):
  ```json
  { "postId": "uuid", "type": "HUG", "presenceCount": 3, "listeningCount": 1, "supportCount": 4, "supportedByCurrentUser": true }
  ```
- **Semántica** (siempre `200`, nunca `201` — es un upsert, no siempre una creación):
  - Sin respuesta previa → crea la fila. Dispara notificación `NEW_POST_RESPONSE` al autor
    (tu **primera** respuesta a este post).
  - Respuesta previa del **mismo** tipo → no-op idempotente, sin notificar de nuevo.
  - Respuesta previa de **otro** tipo → `UPDATE` de esa misma fila, sin notificar (cambiar
    de tipo no es una respuesta nueva).
- **Errores**: `400 Bad Request` si sos el autor del post. `404 Not Found` si el post no
  existe o no es visible para vos (mismo criterio que `GET /api/posts/{postId}`,
  incluyendo perfil `PRIVATE` del autor y bloqueo bilateral — ver `PostAccessPolicy` en
  `BACKEND_ARCHITECTURE.md`). **Mute no afecta esta ruta** — si podés acceder
  directamente al post, podés responder, sin importar si silenciaste al autor.

### `DELETE /api/posts/{postId}/response`
Elimina tu respuesta a este post (sin importar su tipo actual).
- **Response 200**: `PostResponseSummaryResponse` con `type: null`.
- **Idempotente** — si no tenías ninguna respuesta, `200` igual (no `404`), reflejando el
  mismo estado sin cambios. No dispara ninguna notificación. Volver a responder más tarde
  (`PUT` de nuevo) es una respuesta **nueva** a todos los efectos, incluyendo notificación.
- **Errores**: `404 Not Found` solo si el post en sí no existe.

### `POST /api/posts/{postId}/support` (LEGACY, ver decisión más abajo)
Equivale a `PUT .../response` con `type: WITH_YOU`, **preservando el contrato original
exacto** de antes de esta fase: a diferencia del endpoint nuevo, este **sí** es `409` (no
upsert) si ya había cualquier respuesta propia — para no sorprender a un consumidor que ya
integraba contra ese comportamiento. Dispara notificación `NEW_POST_RESPONSE` (antes
`NEW_SUPPORT`) solo en la creación.
- **Response 201** (`PostResponseSummaryResponse`, mismo shape que el endpoint nuevo).
- **Errores**: `400 Bad Request` si sos el autor (regla nueva de esta fase, ver más
  abajo). `409 Conflict` si ya habías respondido (con cualquier tipo). `404 Not Found` si
  el post no existe o no es visible (mismo criterio que arriba).

### `DELETE /api/posts/{postId}/support` (LEGACY)
Equivale a `DELETE .../response`, preservando el contrato original: a diferencia del
endpoint nuevo (idempotente), este **sigue siendo `404`** si no habías respondido — mismo
comportamiento exacto que antes de esta fase.
- **Response 200**: `PostResponseSummaryResponse` con `type: null`.
- **Errores**: `404 Not Found` — post inexistente, o no habías respondido (mismo status
  code para ambos casos, mensaje distinto).

**Una sola fuente de verdad**: los 4 endpoints de arriba leen/escriben la misma fila
(`post_responses`, antes `post_supports`) a través de un único `PostResponseService` — no
existen dos tablas ni dos repositorios. Preferir `PUT`/`DELETE /response` en integraciones
nuevas; `/support` queda documentado como **legacy/deprecated**, mantenido temporalmente
por compatibilidad.

**⚠️ Cambio de comportamiento (Backend Debt B1) — self-response ahora rechazada en TODOS
los endpoints, incluido el legacy**: antes de esta fase, `POST /support` no validaba
autoría — un usuario podía apoyar su propio post. Eso ahora es `400 Bad Request` en los 4
endpoints (nuevo y legacy), sin excepción. Cualquier integración existente que dependiera
de auto-apoyarse debe actualizarse.

**Migración de datos históricos**: la tabla `post_supports` fue evolucionada in-place a
`post_responses` (`ALTER TABLE ... RENAME`, no una tabla nueva con copia de filas) —
conserva `id`/`created_at` originales. Todo registro histórico (que antes solo
representaba presencia binaria) recibió `type = 'WITH_YOU'`. Ver
`V12__add_post_response_types.sql` y `BACKEND_ARCHITECTURE.md`.

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
  9.4) — no se puede comentar un post que no se podría ver. **Mute (Fase 9.5) no afecta
  esta ruta**: `CommentService` delega en `PostAccessPolicy.canView`, que nunca chequea
  mute — silenciar a alguien no impide comentar en sus posts ni que comente en los tuyos.

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

**Acciones desde Novedades/notificaciones (Backend Debt B3)**: la notificación
`FOLLOW_REQUEST_RECEIVED` incluye `followRequestId` (ver § 9) precisamente para que el
frontend pueda ejecutar `accept`/`reject` directo desde ahí — pero siempre llamando a
estos mismos endpoints. **No existe, ni debe crearse, un endpoint paralelo** tipo
`POST /api/notifications/{id}/accept-follow` — `FollowRequestService` sigue siendo la
única fuente de verdad sobre el trámite, la notificación es solo contexto/navegación.

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
- **Mute (Fase 9.5)**: excluye, además, el status de cualquier usuario que el autenticado
  haya silenciado — **unilateral**, filtrado directo en la query (`NOT EXISTS`, mismo
  criterio que el feed de posts, ver §3). Igual que con posts, esto no es redundante con
  ninguna limpieza (mute no toca `follows`).

### `POST /api/statuses/{statusId}/react`
Reacciona a un status. Si ya habías reaccionado, **reemplaza** el tipo de reacción
anterior (no crea una segunda reacción — hay `UNIQUE(status_id, actor_id)` en DB).
Notifica `NEW_STATUS_REACTION` al dueño del status solo si es tu **primera** reacción a
ese status (cambiar el tipo de una reacción existente no vuelve a notificar). **Backend
Debt B3**: esa notificación incluye `statusId` (nunca `postId`, ver § 9) apuntando a
`status.getId()` — sin query extra, ya que el status está cargado en este mismo método.
- **Body** (`ReactToStatusRequest`): `{ "type": "WITH_YOU | WANT_TO_TALK | HERE_READING | NOT_ALONE" }`
- **Response 200**: `StatusResponse` actualizado.
- **Errores**: `404 Not Found` si el status no existe, **o si existe un bloqueo entre el
  autenticado y el dueño del status en cualquier dirección (Fase 9.4)** — mismo mensaje
  genérico que un status inexistente. Este endpoint es una interacción directa
  usuario-a-usuario que no pasa por `PostAccessPolicy` (los estados son un dominio
  separado de los posts), así que necesitó su propio chequeo de bloqueo explícito.
  **Mute (Fase 9.5) no afecta esta ruta** — es interacción directa, no una superficie
  agregada; podés seguir reaccionando al status de alguien que silenciaste (y viceversa).

### `DELETE /api/statuses/{statusId}/react`
Quita tu reacción.
- **Response 200**: `StatusResponse` actualizado (`reactedByCurrentUser: null`).
- **Errores**: `404 Not Found` (status inexistente, o no habías reaccionado).

---

## 7. Availability / "modo compañía" (`/api/availability`) — **LEGACY / DEPRECATED**, requiere autenticación

**Backend Debt B4B.3**: desde este PR, `/api/availability/**` es un **adapter delgado**
(`AvailabilityController`) — no tiene backing store propio. Internamente delega
100% en `CompanionOfferingService`; el backing store real es `companion_offerings`
(dominio nuevo, § 7ter). La tabla `availabilities` fue **retirada** (`V16`, `DROP TABLE`,
sin backfill — pérdida deliberada y aceptada, ver decisión B4A #6). `CompanionIntent`
sigue existiendo, pero **solo como enum de contrato legacy** — ya no es un enum de
dominio, nunca se usa dentro de `CompanionOffering`/`CompanionOfferingService`/
`ChatService`.

**Nuevos clientes deben usar `/api/companion/offering/**` (§ 7ter)** — este contrato
sigue disponible por compatibilidad, **sin fecha de retiro todavía**, pero no recibe
funcionalidad nueva (sin `/compatible`, sin matriz Need→Offering).

Declaración de corto plazo ("estoy disponible para acompañar ahora"), vence a las **6
horas** (igual que `CompanionOffering`, más corto que `Status`, que vence a las 24hs).

### Mapping `CompanionIntent` ↔ `OfferingType` (`LegacyAvailabilityMapper`)

Al escribir (`POST`), `CompanionIntent` → `OfferingType`:

| CompanionIntent | OfferingType |
|---|---|
| `TALK` | `TALK` |
| `DISTRACTION`, `WATCH_TOGETHER`, `MUSIC`, `LAUGH` | `DISTRACT` |
| `JUST_COMPANY` | `LISTEN` |

Al leer (`GET /mine`, `GET ?intent=`), `OfferingType` → `CompanionIntent` — **mapping
deliberadamente LOSSY** (3 valores no pueden representar 6):

| OfferingType | CompanionIntent |
|---|---|
| `TALK` | `TALK` |
| `LISTEN` | `JUST_COMPANY` |
| `DISTRACT` | `DISTRACTION` |

**Consecuencia visible para el cliente**: si declarás `POST {"intent": "MUSIC"}` (o
`WATCH_TOGETHER`/`LAUGH`), una lectura posterior (`GET /mine` o aparecer en un listado)
**siempre** devuelve `"intent": "DISTRACTION"` — el matiz original (`MUSIC` vs
`WATCH_TOGETHER` vs `LAUGH`) se pierde y no se recupera. Esto es comportamiento
**esperado y documentado** del adapter, no un bug — el backend no guarda metadata
adicional para "recordar" el intent original porque eso recrearía una segunda fuente de
verdad (justo lo que esta migración elimina). Este mapping también aplica **en ambas
direcciones de interoperabilidad**: declarar por `/api/companion/offering` (nuevo) y
leer por `/api/availability/mine` (legacy) usa el mismo mapping OfferingType→CompanionIntent,
y viceversa.

### `POST /api/availability`
Reemplaza cualquier disponibilidad activa anterior tuya (solo puede haber una a la vez).
Internamente: traduce `intent`→`OfferingType` y llama `CompanionOfferingService.setOffering`
(misma protección de concurrencia que B4B.2 — `UNIQUE(user_id)` + `CompanionOfferingWriter`
+ reintento acotado, el adapter no reimplementa nada).
- **Body** (`SetAvailabilityRequest`): `{ "intent": "TALK | DISTRACTION | WATCH_TOGETHER | MUSIC | LAUGH | JUST_COMPANY" }`
- **Response 201** (`AvailabilityResponse`):
  ```json
  { "id": "uuid", "user": { /* UserSummary */ }, "intent": "TALK", "createdAt": "...", "expiresAt": "..." }
  ```
  `id`/`createdAt` son los reales de la `CompanionOffering` subyacente.

### `DELETE /api/availability`
Cancela tu disponibilidad activa (si tenías una). Idempotente — no falla si no tenías ninguna.
Delega en `CompanionOfferingService.cancelOffering` — nunca toca una tabla `availabilities`
(ya no existe).
- **Response**: `204 No Content`.

### `GET /api/availability/mine`
Tu disponibilidad activa actual (consulta `CompanionOfferingService.getMine`).
- **Response 200**: `AvailabilityResponse`, **o literalmente el body `null` con status
  200** si no tenés ninguna activa (ausencia genuina de dato, nunca `404`).

### `GET /api/availability?intent=TALK`
Lista hasta 10 personas disponibles ahora mismo con ese `intent` (traducido a
`OfferingType` y delegado en la búsqueda nueva), en **orden aleatorio** (a propósito —
nunca por popularidad), excluyendo al propio usuario autenticado. Sin N+1 (proyección con
`JOIN`, heredada de B4B.2 — mejora respecto al comportamiento legacy original).
- **Query params**: `intent` (obligatorio, uno de `CompanionIntent`).
- **Response 200**: **No pagina** — `List<AvailabilityResponse>` (máx. 10 items, límite
  fijo en el backend, no configurable desde el cliente).
- **Bloqueo**: excluye bilateralmente — ni quien el autenticado bloqueó ni quien lo
  bloqueó a él pueden aparecer, en ninguna dirección.
- **Mute**: excluye, además, a quien el autenticado silenció — **unilateral**. Si A
  silenció a B, B deja de aparecer como sugerencia para A, pero A sigue apareciendo con
  total normalidad en el listado de B. Si ya existe una conversación entre ambos, `POST
  /api/conversations/{userId}` sigue funcionando igual (ver § 8), mute nunca impide
  contactar directamente a alguien.
- **Cambio semántico deliberado respecto al comportamiento legacy original (decisión
  B4A #3)**: este listado **ya no filtra por `ProfileVisibility`/follow** — un perfil
  `PRIVATE` sin accepted follower puede aparecer si tiene una Offering activa (activarla
  es consentimiento específico para Companion). El *shape* del contrato no cambió, pero
  el *conjunto de candidatos* puede incluir perfiles que antes de B4B.3 ya aparecían
  igual (la legacy original tampoco filtraba por privacidad — ver auditoría B4A), así que
  en la práctica no hay regresión de exposición: el comportamiento observable es el mismo
  de siempre, ahora con una base de datos explícitamente distinta y documentada.

**Relación con chat**: el modo compañía sigue siendo la única forma de iniciar una
conversación con alguien que no seguís ni te sigue — ver § 8. `ChatService` ya no
consulta `availabilities` (retirada); consulta `CompanionOfferingService.hasActiveOffering`,
que mira `companion_offerings` (la fuente de verdad real, con o sin pasar por este
endpoint legacy).

---

## 7bis. Companion Need (`/api/companion/need`) — requiere autenticación

Backend Debt B4B.1 — primer PR del rediseño del dominio Companion (ver diseño B4A).
"Necesito compañía ahora": declaración de corto plazo, vence a las **2 horas** (más
corto que `Offering`, que vence a las 6hs, ver § 7ter, y que `Status`, que vence a las
24hs). **Nunca se expone públicamente** — no existe ningún endpoint que muestre el
`CompanionNeed` de otro usuario, solo `GET .../mine` contra el propio usuario
autenticado. Es el input de `GET /api/companion/offering/compatible` (§ 7ter) — nunca
matching automático ni `CompanionMatch` (diferido).

### `PUT /api/companion/need`
Reemplaza cualquier Need activo anterior tuyo (solo puede haber uno a la vez).
- **Body** (`SetCompanionNeedRequest`): `{ "type": "LISTEN_TO_ME | TALK | GET_OPINION | DISTRACTION | JUST_COMPANY" }`
- **Response 200** (`CompanionNeedResponse`):
  ```json
  { "id": "uuid", "type": "TALK", "createdAt": "...", "expiresAt": "..." }
  ```
  Nótese que, a diferencia de `AvailabilityResponse`, **no incluye `UserSummary`** — el
  Need nunca se expone a otro usuario, así que "de quién es" siempre es implícito (el
  usuario autenticado).

### `DELETE /api/companion/need`
Cancela tu Need activo (si tenías uno). Idempotente — no falla si no tenías ninguno.
- **Response**: `204 No Content`.

### `GET /api/companion/need/mine`
Tu Need activo actual.
- **Response 200**: `CompanionNeedResponse`, **o literalmente el body `null` con status
  200** si no tenés ninguno activo — mismo criterio que `GET /api/availability/mine` y
  `GET /api/users/{userId}/status` (ausencia genuina de dato, nunca un 404).

---

## 7ter. Companion Offering + búsqueda (`/api/companion/offering`) — requiere autenticación

Backend Debt B4B.2 — segundo PR del rediseño del dominio Companion (ver diseño B4A).
"Cómo puedo acompañar ahora": declaración de mediano plazo, vence a las **6 horas**
(mismo plazo que `Availability` legacy, más largo que `Need`, § 7bis). Desde este PR,
`companion_offerings` es la fuente de verdad del **nuevo** dominio Companion —
`availabilities` (legacy) sigue existiendo y sigue siendo la fuente de verdad exclusiva
del contrato `/api/availability/**`. Ambas tablas coexisten temporalmente; ningún código
lee las dos para responder la misma operación. Ver `BACKEND_ARCHITECTURE.md` § Companion
Offering para el wording completo de source-of-truth.

### `PUT /api/companion/offering`
Reemplaza cualquier Offering activa anterior tuya (solo puede haber una a la vez).
- **Body** (`SetCompanionOfferingRequest`): `{ "type": "LISTEN | TALK | DISTRACT" }`
- **Response 200** (`CompanionOfferingResponse`):
  ```json
  { "id": "uuid", "type": "LISTEN", "createdAt": "...", "expiresAt": "..." }
  ```
  Sin `UserSummary` — mismo criterio que `CompanionNeedResponse`, `/mine` siempre resuelve
  contra el propio usuario autenticado.

### `DELETE /api/companion/offering`
Cancela tu Offering activa (si tenías una). Idempotente — no falla si no tenías ninguna.
- **Response**: `204 No Content`.

### `GET /api/companion/offering/mine`
Tu Offering activa actual.
- **Response 200**: `CompanionOfferingResponse`, **o literalmente el body `null` con
  status 200** si no tenés ninguna activa — ausencia genuina de dato, nunca un 404.

### `GET /api/companion/offering?type=LISTEN`
Lista hasta 10 candidatos con Offering activa de ese tipo exacto, en **orden aleatorio**
(MVP a propósito — sin ranking, sin relevancia, sin paginación, sin scoring).
- **Query params**: `type` (obligatorio, uno de `LISTEN`, `TALK`, `DISTRACT`).
- **Response 200**: `List<CompanionCandidateResponse>` (sin envelope de paginación):
  ```json
  [{ "user": { /* UserSummary */ }, "offeringType": "LISTEN", "expiresAt": "..." }]
  ```
- **Bloqueo**: excluye bilateralmente (ninguna dirección puede aparecer), mismo criterio
  que la búsqueda legacy de `/api/availability`.
- **Mute**: excluye, unilateralmente, a quien el autenticado silenció. Si A silenció a B,
  B no aparece para A, pero A sigue apareciendo con normalidad para B.
- **Sin filtro de `ProfileVisibility`, sin requerir follow** (decisión B4A #3): activar un
  Offering es consentimiento específico para aparecer en superficies de Companion, incluso
  con perfil `PRIVATE` y sin accepted follower. **Nunca desbloquea** perfil completo, bio,
  posts ni status — `CompanionCandidateResponse` solo expone los 4 campos de `UserSummary`
  más el tipo de Offering y su vencimiento.

### `GET /api/companion/offering/compatible`
Busca candidatos cuyo Offering sea compatible con tu **propio Need activo**, vía una
matriz estática (nunca matching inteligente, sin scoring):

| Tu Need | Offering compatible |
|---|---|
| `LISTEN_TO_ME` | `LISTEN` |
| `TALK` | `TALK` |
| `GET_OPINION` | `TALK` |
| `DISTRACTION` | `DISTRACT` |
| `JUST_COMPANY` | `LISTEN` |

- **Response 200**: `List<CompanionCandidateResponse>`, mismas reglas de bloqueo/mute/
  self-exclusion/límite que la búsqueda por tipo exacto.
- **Sin Need activo**: `200` con **lista vacía** — no es un recurso inexistente, es la
  ausencia de un criterio de búsqueda; nunca `404`.
- Tu `Need` **nunca viaja en la respuesta** — ni el propio ni el de ningún candidato. Se
  usa únicamente del lado del servidor para resolver el `OfferingType` compatible. El
  `Need` de otros usuarios sigue sin exponerse por ningún endpoint (decisión B4A #10).

**Flujo MVP completo** (decisión B4A #4): `PUT /api/companion/need` → `GET
/api/companion/offering/compatible` → el usuario elige un candidato → `POST
/api/conversations/{userId}` (§ 8, ya existente, sin cambios).

**Actualización (Backend Debt B4B.3/B4B.4)**: `/api/availability/**` ya es un adapter
legacy sobre este mismo dominio (§ 7, arriba) y `ChatService` ya consulta
`companion_offerings` (§ 8, abajo) — `companion_offerings` es la única fuente de verdad de
disponibilidad en todo el backend desde B4B.3. `GET /api/users/{userId}/availability` (§ 2)
es el endpoint público de disponibilidad para `CompanionOffering`, agregado en B4B.4.

---

## 7quater. Companion Preferences (`/api/users/me/companion-preferences`) — requiere autenticación (Backend Debt B4B.5)

"Cómo suelo estar para otros": dato **estable** de perfil, sin expiración. Totalmente
independiente de Need/Offering (nunca se infiere ni se sincroniza con ellos).

### `GET /api/users/me/companion-preferences`
- **Response 200**: `{ "types": ["LISTEN", "TALK"] }`. Siempre `200`; `types: []` (nunca
  `null`) si no configuraste ninguna. Orden fijo: LISTEN, TALK, DISTRACT.

### `PATCH /api/users/me/companion-preferences`
Reemplaza el set **completo** (nunca add/remove incremental).
- **Body**: `{ "types": ["LISTEN", "TALK"] }`. `types` es obligatorio (`null` o ausente →
  `400`); `[]` es válido y borra todo. Duplicados se normalizan. Un valor de enum
  inválido → `400`.
- **Response 200**: mismo shape que el `GET`. Sin token → `401`.
- Dos PATCH concurrentes del mismo usuario se serializan: el resultado es siempre uno de
  los dos sets completos, nunca una mezcla.

---

## 8. Chat (`/api/conversations`) — requiere autenticación

### `POST /api/conversations/{userId}`
Obtiene la conversación existente con `userId`, o la crea si no existe.
- **Regla de autorización para crear/obtener** (no es solo "cualquiera con cualquiera"):
  permitido si `currentUser` sigue a `userId`, **o** `userId` sigue a `currentUser`, **o**
  `userId` tiene una `CompanionOffering` activa en este momento (declaró estar disponible
  para acompañar — cualquier `OfferingType`, sin distinción). Si ninguna de las tres se
  cumple → `403 Forbidden`. La disponibilidad nunca habilita el sentido inverso (que
  alguien le escriba a quien está disponible sí, pero no al revés sin ese consentimiento).
  **Backend Debt B4B.3**: `ChatService` consulta `CompanionOfferingService.hasActiveOffering`
  — `companion_offerings` es la fuente de verdad (la extinta tabla `availabilities` fue
  retirada). El `Need` del solicitante **nunca** participa de esta decisión — este chequeo
  es autorización de primer contacto, no matching (ver § 7bis/7ter).
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
- **Mute (Fase 9.5) — a propósito SIN ningún efecto en todo § 8**: silenciar a alguien no
  impide crear/obtener la conversación, no oculta la conversación ni sus mensajes, no
  bloquea el envío de mensajes nuevos en ninguna dirección. Ambos pueden seguir
  chateando con total normalidad — mute nunca corta interacción directa, solo afecta
  superficies agregadas de descubrimiento/contenido (feed, discover, status/presence,
  disponibilidad — ver §§ 3, 2, 6, 7).

---

## 9. Notifications (`/api/notifications`) — requiere autenticación

Las notificaciones se generan internamente desde otros módulos (follow, comment, post
response, status reaction) — no hay endpoint para crearlas manualmente.

- **Bloqueo (Fase 9.4)**: una guarda centralizada en `NotificationService.notify()`
  suprime cualquier notificación **nueva** entre dos usuarios con un bloqueo activo (en
  cualquier dirección) — en la práctica esto ya es redundante con que la acción que
  dispararía la notificación (follow, comentario, respuesta a un post, reacción de
  estado) ya se rechaza antes de llegar a `notify()`, pero queda como defensa en
  profundidad centralizada en un solo lugar en vez de duplicada en cada caller. **No
  afecta notificaciones ya existentes** — bloquear a alguien nunca borra notificaciones
  históricas generadas antes del bloqueo, sólo evita que se generen nuevas.
- **Mute (Fase 9.5) — decisión explícita, sin efecto, reconfirmada en Backend Debt B3**:
  silenciar a alguien **no** suprime sus notificaciones. `NotificationService.notify()`
  no chequea mute en absoluto — seguís recibiendo notificaciones de
  follow/comentario/respuesta/reacción de quien silenciaste, exactamente igual que si no
  lo hubieras silenciado. Esto es "notification preferences", una semántica distinta y
  explícitamente fuera de alcance de esta fase (ver § 13) — no una omisión.
- **WebSocket**: cada notificación nueva se empuja por `/user/queue/notifications` con el
  **mismo** `NotificationResponse` que expone `GET /api/notifications` — un único DTO
  para REST y WS, nunca payloads divergentes. Ver `WEBSOCKET_CONTRACT.md`.

### `NotificationResponse` (forma común)
```json
{
  "id": "uuid",
  "actor": { /* UserSummary — quien generó la acción */ },
  "type": "NEW_FOLLOWER",
  "postId": "uuid o null",
  "statusId": "uuid o null",
  "followRequestId": "uuid o null",
  "read": false,
  "createdAt": "..."
}
```
**Cambio de contrato (Backend Debt B3)**: `statusId` y `followRequestId` son campos
nuevos (adición pura al final, no rompe contrato). Cada `NotificationType` puebla **como
máximo uno** de los tres campos de referencia (`postId`/`statusId`/`followRequestId`) —
nunca se reusa `postId` para un status, ni viceversa:

| `type` | Campo poblado | Notas |
|---|---|---|
| `NEW_FOLLOWER` | ninguno | |
| `NEW_COMMENT` | `postId` | el post comentado |
| `NEW_POST_RESPONSE` | `postId` | el post respondido |
| `NEW_STATUS_REACTION` | `statusId` | el status reaccionado — **nunca** `postId` |
| `FOLLOW_REQUEST_RECEIVED` | `followRequestId` | el trámite recién creado, `PENDING` en ese momento |
| `FOLLOW_REQUEST_ACCEPTED` | `followRequestId` | el trámite recién aceptado (contexto, ya no hay acción posible sobre él) |

**Referencias sin FK — pueden apuntar a un recurso ya resuelto/vencido**: ni `postId`
(sin cambios), ni `statusId`, ni `followRequestId` tienen foreign key en la base (ver
`V13`, mismo criterio que `postId` desde `V1`). Ninguno de los tres recursos se borra
nunca (`Post` es soft-delete, `Status` solo expira, `FollowRequest` conserva
ACCEPTED/REJECTED/CANCELLED como historial) — una notificación vieja puede señalar
legítimamente a un post ya `REMOVED`, un status ya vencido, o un `FollowRequest` ya
resuelto. La API nunca rompe deserializando estos casos; el endpoint del recurso
referenciado responde según sus propias reglas (ej. `POST /api/follow-requests/{id}/accept`
sobre un trámite ya no `PENDING` → `409 Conflict`, igual que si se hubiera llegado ahí
por cualquier otra vía). El frontend no debe asumir que el recurso referenciado sigue en
el mismo estado que cuando se generó la notificación.

### `GET /api/notifications`
- **Query params**: `page` (default `0`), `size` (default `20`).
- **Response 200**: `Page<NotificationResponse>` (envelope completo de Spring Data
  `Page`: `content`, `totalElements`, `totalPages`, `number`, `size`, `last`, etc.).
- **Orden estable (Backend Debt B3)**: `createdAt DESC, id DESC` — el desempate por `id`
  fija un orden determinista incluso si dos notificaciones se crean con el mismo
  timestamp (mismo milisegundo), evitando que un empate se reordene entre página y
  página bajo paginación por offset.
- **Paginación por offset, no cursor**: se mantiene `Page`/`Pageable` (ya establecido en
  toda la API) — no se introdujo cursor pagination. Ver `FRONTEND_HANDOFF.md` § Websocket
  y notificaciones para la estrategia recomendada ante notificaciones nuevas llegando por
  WebSocket mientras el usuario pagina.

### `PATCH /api/notifications/{notificationId}/read` (Backend Debt B3)
Marca **una** notificación puntual como leída.
- **Response 200**: `NotificationResponse` actualizado (`read: true`).
- **Ownership**: la query de mark-one incluye el filtro por `recipientId` del JWT en el
  mismo `WHERE` (nunca un `findById()` + chequeo aparte) — no existe forma de marcar una
  notificación ajena.
- **Errores**: `404 Not Found` — la notificación no existe **o** no es del usuario
  autenticado; ambos casos son indistinguibles desde el status code (misma query,
  mismo resultado para los dos — no hay un `403` que confirme que la notificación existe
  pero es de otro usuario).
- **Idempotente**: marcar una notificación ya leída no falla — responde `200` igual, sin
  volver a escribir en la fila.

### `GET /api/notifications/unread-count`
- **Response 200**: `{ "count": 3 }` — objeto plano `Map<String, Long>`, no un DTO
  tipado. Consulta real (`COUNT` agregado), no un contador denormalizado — siempre
  refleja el estado actual, incluyendo después de `PATCH .../read` o `.../read-all`.

### `PATCH /api/notifications/read-all`
Marca **todas** las notificaciones no leídas del usuario como leídas — `UPDATE` bulk en
una sola sentencia (no itera fila por fila). Sigue siendo el único mecanismo para marcar
en lote; `PATCH .../{id}/read` (arriba) es puntual, sin reemplazar a este.
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

Ocultar un post puntual, ocultar a un usuario sin bloquearlo, reporte automático al
bloquear, motivo de bloqueo, bloqueo temporizado, "amigos cercanos"/audiencias
personalizadas/círculos, bloqueo por dispositivo, y cualquier mecanismo de
anti-abuso/rate-limiting más allá de lo que ya existía. (Silenciar sin bloquear ("mute")
dejó de estar en esta lista — ver § 13.)

---

## 13. User Muting (`/api/users/{userId}/mute`, `/api/users/me/muted`) — requiere autenticación

Silenciar (mute) de usuario a usuario (Fase 9.5). A diferencia del bloqueo (§ 12,
**bilateral** en efecto), esta relación es estrictamente **unilateral**: que A silencie a
B **no implica nada** sobre si B silencia a A, y el efecto nunca se consulta
bilateralmente en ningún lado del código. Mutear **no es control de acceso** — no
modifica `Follow`/`FollowRequest`, no afecta `ProfileAccessPolicy` ni `PostAccessPolicy`,
no bloquea chat ni ninguna interacción directa. Solo cambia lo que el **muter** ve en
superficies agregadas de descubrimiento/contenido: feed (§ 3), discover (§ 2),
status/presence agregada (§ 6), disponibilidad/companion (§ 7). El `muterId` **nunca** se
acepta desde el body — siempre es el usuario del JWT, y `userId` en el path es siempre el
**target**.

**El usuario silenciado nunca se entera**: no existe ningún endpoint ni campo en toda la
API para que un usuario averigüe si otro lo silenció, ni cuántos lo silenciaron.

### `POST /api/users/{userId}/mute`
Silencia a `userId`. **Idempotente** — silenciar a alguien ya silenciado no falla (`204`
igual, sin crear una segunda fila).
- **Response**: `204 No Content`.
- **Errores**: `400 Bad Request` (intentar silenciarte a vos mismo), `404 Not Found`
  (`userId` no existe).
- **Sin efectos secundarios sobre otras relaciones** (a propósito, a diferencia de
  `POST .../block`): no toca `Follow`, no toca `FollowRequest`, no toca `UserBlock`. Si
  ya seguías a `userId`, seguís siguiéndolo después de silenciarlo — silenciar y seguir a
  alguien no son mutuamente excluyentes en ningún orden.

### `DELETE /api/users/{userId}/mute`
Deja de silenciar a `userId`. Solo quien silenció puede dejar de silenciar (el
path/principal son siempre el mismo `muterId`). **Idempotente** — dejar de silenciar a
alguien no silenciado no falla (`204` igual).
- **Response**: `204 No Content`.
- **Decisión explícita — no hay nada que "restaurar"**: a diferencia de `DELETE
  .../block`, acá no hace falta aclarar que no se recrea nada, porque silenciar nunca
  eliminó ni modificó ninguna otra relación en primer lugar. El contenido de `userId`
  vuelve a aparecer en feed/discover/status/disponibilidad exactamente según las reglas
  normales de esas superficies (como si nunca hubiera existido el mute).

### `GET /api/users/me/muted`
Lista de usuarios que el **autenticado** silenció — **nunca** quién lo silenció a él (no
existe ningún endpoint para consultar eso).
- **Query params**: `page` (default `0`), `size` (default `20`).
- **Response 200**: `Page<MutedUserResponse>`:
  ```json
  { "userId": "uuid", "username": "...", "displayName": "...", "avatarUrl": "...", "mutedAt": "..." }
  ```
  DTO mínimo a propósito — **nunca incluye `email`**, mismo criterio que
  `BlockedUserResponse`/`UserSummary`.

### Decisiones de diseño explícitas (para el frontend y para no repetir el debate)

- **`mutedByCurrentUser` sí, `mutingCurrentUser` no**: mismo criterio que
  `blockedByCurrentUser`/`blockingCurrentUser` en § 12 — `GET /api/users/{userId}` expone
  si **vos** silenciaste al `userId` consultado, pero **jamás** si `userId` te silenció a
  **vos**. A diferencia del bloqueo, acá no hay ni siquiera un 404 que insinúe la
  relación inversa: el perfil de alguien que te silenció se ve exactamente igual que el
  de cualquiera que no te silenció.
- **Acceso directo intacto, siempre**: perfil (`GET /api/users/{userId}`), posts por
  usuario (`GET /api/users/{userId}/posts`), post individual (`GET /api/posts/{postId}`),
  comentarios, apoyo/reacciones y chat **no** chequean mute en ningún punto del código —
  ver las notas "Mute (Fase 9.5) — a propósito SIN efecto acá" repartidas en §§ 2, 3, 4,
  6, 8. La diferencia de fondo es "no quiero verlo en superficies que arma el sistema por
  mí" vs. "no tengo acceso" — mute es lo primero, nunca lo segundo.
- **Notifications, decisión explícita**: silenciar a alguien **no** silencia sus
  notificaciones (ver § 9) — eso es "notification preferences", semántica distinta y
  fuera de alcance de esta fase.
- **Interacción con Block**: mutear y después bloquear al mismo usuario elimina el mute
  propio hacia esa persona (queda redundante — el bloqueo ya oculta todo lo que ese mute
  ocultaba, y más). El sentido inverso **no** se toca: si `userId` te había silenciado a
  vos y ahora lo bloqueás, ese mute de `userId` hacia vos queda intacto — es una
  preferencia suya, ajena a tu acción, y no debilita el bloqueo en absoluto (el filtrado
  de `UserBlock` en feed/discover/status/disponibilidad no depende de ningún `UserMute`
  para excluirte de lo que ve `userId`). Desbloquear **no** revive ningún mute borrado
  por este mecanismo.

### Fuera de alcance de esta fase (explícitamente no implementado)

Ocultar un post puntual sin silenciar a su autor ("hide post"), silenciar una
conversación puntual ("mute conversation"), silenciar notificaciones, mute temporizado,
mute de temas/topics, "amigos cercanos"/audiencias personalizadas/círculos, preferencias
de recomendación, anti-spam, reporte automático al silenciar.

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
