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
- **Response 201** (`AuthResponse`):
  ```json
  {
    "token": "eyJhbGciOi...",
    "username": "juanperez",
    "role": "USER"
  }
  ```
  **Importante**: el campo se llama `username` pero es el handle autogenerado, **no**
  el email. El frontend debe guardar `token` y puede mostrar `username` como handle,
  pero para mostrar el email debe llamar luego a `GET /api/users/me`. **Sin cambios en
  esta forma** — la infraestructura de verificación de email no agrega ni quita campos
  de `AuthResponse`.
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

### `POST /api/auth/login`

- **Auth**: no requerida.
- **Body** (`LoginRequest`):
  ```json
  { "email": "persona@example.com", "password": "..." }
  ```
- **Response 200** (`AuthResponse`): igual forma que register (`token`, `username`, `role`).
- **Errores**: `401 Unauthorized` — `"Invalid username or password"` si el email no
  existe o la contraseña no matchea. También puede fallar si la cuenta está
  `SUSPENDED`/`DEACTIVATED` (Spring Security la trata como cuenta bloqueada/deshabilitada
  → 401 genérico también, el backend no distingue ese caso en el mensaje).
- **No requiere email verificado**: un usuario con `emailVerified: false` puede loguearse
  con total normalidad — esta fase no introduce ninguna restricción de acceso por eso.

**JWT emitido**: contiene `sub` (username interno), claim `userId` (UUID string), claim
`role`, `iat`, `exp`. Expira a las 24hs por default (`JWT_EXPIRATION_MS`, configurable).
No hay endpoint de refresh ni de logout — el logout es puramente client-side (descartar
el token).

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
- **Limitación conocida — JWT preexistentes**: un JWT emitido **antes** del reset sigue
  siendo válido hasta su expiración natural (`JWT_EXPIRATION_MS`). Fase 1.3 no
  implementa revocación/versionado de tokens ni sesiones — eso queda para
  **Fase 1.5 — Session Security**. En la práctica, un atacante con un JWT robado
  emitido antes del reset conserva acceso hasta que ese JWT expire por sí solo, aunque
  la contraseña ya haya sido cambiada.

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
    "emailVerifiedAt": "2026-01-02T09:00:00Z o null"
  }
  ```
  **Cambio de contrato (Fase 1.1)**: `emailVerified` y `emailVerifiedAt` son campos
  nuevos en `UserResponse` (antes no existían). Adición pura al final del objeto — no
  rompe consumidores existentes que ignoren campos desconocidos. `emailVerifiedAt` es
  `null` mientras `emailVerified` sea `false`. Cuentas creadas **antes** de esta fase
  tienen `emailVerified: true` (ver `BACKEND_ARCHITECTURE.md` § Flyway/compatibilidad),
  con `emailVerifiedAt` igual a su `createdAt` original (fecha aproximada, no una
  verificación real que haya ocurrido).

### `PATCH /api/users/me`
Actualiza el perfil propio. Todos los campos son opcionales (solo se aplican los
`!= null`; no hay forma de "vaciar" `bio`/`displayName`/`avatarUrl` enviando `null`
explícito, porque `null` se interpreta como "no tocar").
- **Body** (`UpdateProfileRequest`):
  ```json
  { "displayName": "máx 100 chars", "bio": "máx 500 chars", "avatarUrl": "string" }
  ```
- **Response 200**: `UserResponse` (igual forma que `GET /me`).
- **Nota**: `avatarUrl` puede setearse aquí como URL arbitraria; el endpoint dedicado
  de upload (abajo) es la vía recomendada para subir un archivo real vía Cloudinary.

### `GET /api/users/{userId}`
Perfil público de **otro** usuario (o el propio, funciona igual). Nunca incluye `email`.
- **Path params**: `userId` (UUID).
- **Response 200** (`PublicUserProfileResponse`):
  ```json
  {
    "id": "uuid",
    "username": "juanperez",
    "displayName": "Juan Pérez",
    "bio": "...",
    "avatarUrl": "...",
    "createdAt": "2026-01-01T00:00:00Z",
    "followersCount": 12,
    "followingCount": 5,
    "followedByCurrentUser": false
  }
  ```
- **Errores**: `404 Not Found` si `userId` no existe.

### `GET /api/users/{userId}/posts`
Posts de un usuario, respetando visibilidad según la relación con quien pregunta.
- **Query params**: `page` (default `0`), `size` (default `20`).
- **Reglas de visibilidad** (evaluadas server-side, no confiar en el frontend):
  - Si `userId` == usuario autenticado → ve todos sus propios posts (incluye `PRIVATE`).
  - Si el autenticado sigue a `userId` → ve `PUBLIC` + `FOLLOWERS_ONLY`.
  - Si no → solo `PUBLIC`.
  - Siempre excluye posts con `status != VISIBLE`.
- **Response 200**: `Page<PostResponse>` (ver forma de `PostResponse` en § Posts).
- **Errores**: `404 Not Found` si `userId` no existe.

### `GET /api/users/discover`
Lista de usuarios que el autenticado **no sigue todavía** (para descubrir gente nueva).
No hay filtro de búsqueda por texto — es un listado paginado sin criterio de relevancia
explícito más allá del orden default de la tabla.
- **Query params**: `page` (default `0`), `size` (default `20`).
- **Response 200**: `Page<DiscoverUserResponse>`:
  ```json
  { "id": "uuid", "username": "...", "displayName": "...", "bio": "...", "avatarUrl": "..." }
  ```

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
- Solo incluye posts con `status = VISIBLE` y `visibility IN (PUBLIC, FOLLOWERS_ONLY)`
  (un post `PRIVATE` de alguien que sigo no aparece en el feed, solo en su perfil si soy
  el dueño).

### `GET /api/posts/{postId}`
- **Response 200**: `PostResponse`.
- **Errores**: `404 Not Found` tanto si el post no existe **como** si existe pero el
  usuario autenticado no tiene permiso para verlo (nunca `403` acá — la API no revela
  la existencia de un post privado ajeno).

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
- **Errores**: `409 Conflict` si ya habías apoyado ese post. `404 Not Found` si el post no existe.

### `DELETE /api/posts/{postId}/support`
Quita el apoyo previamente dado.
- **Response 200**: `SupportSummaryResponse` (`supportedByCurrentUser: false`).
- **Errores**: `404 Not Found` — post inexistente, o no habías apoyado ese post
  (mismo status code para ambos casos, mensaje distinto).

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
- **Errores**: `404 Not Found` si el post no existe.

### `GET /api/posts/{postId}/comments`
**No pagina** — devuelve `List<CommentResponse>` completa, orden `createdAt ASC` (más
viejo primero), solo `status = VISIBLE`.
- **Errores**: `404 Not Found` si el post no existe.

### `PATCH /api/posts/{postId}/comments/{commentId}`
Solo el autor del comentario puede editar (no hay excepción para moderador/admin acá).
- **Body** (`UpdateCommentRequest`): `{ "content": "máx 500 chars, obligatorio" }`
- **Response 200**: `CommentResponse`.
- **Errores**: `403 Forbidden` (no sos el autor), `404 Not Found` (comentario no existe,
  o existe pero no pertenece a `postId` — mismo mensaje "Comment not found" en ambos casos).

### `DELETE /api/posts/{postId}/comments/{commentId}`
Soft delete (`status = REMOVED`). Permitido para el autor **o** `MODERATOR`/`ADMIN`.
- **Response**: `204 No Content`.
- **Errores**: `403 Forbidden`, `404 Not Found` (mismos criterios que PATCH).

---

## 5. Follows (`/api/follows`) — requiere autenticación

### `POST /api/follows/{userId}`
Seguir a un usuario. Dispara notificación `NEW_FOLLOWER`.
- **Response 201** (`FollowResponse`): `{ "followerId": "uuid", "followingId": "uuid", "createdAt": "..." }`
- **Errores**: `400 Bad Request` (intentar seguirte a vos mismo), `404 Not Found`
  (usuario objetivo no existe), `409 Conflict` (ya lo seguías).

### `DELETE /api/follows/{userId}`
- **Response**: `204 No Content`.
- **Errores**: `404 Not Found` si no lo seguías.

### `GET /api/follows/{userId}/followers`
**No pagina** — `List<UserSummary>` de quienes siguen a `userId`. No requiere que
`userId` sea el usuario autenticado (cualquier autenticado puede ver los followers de
cualquiera).
- **Errores**: `404 Not Found` si `userId` no existe.

### `GET /api/follows/{userId}/following`
Igual que arriba pero a quiénes sigue `userId`. **No pagina**.

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

### `POST /api/statuses/{statusId}/react`
Reacciona a un status. Si ya habías reaccionado, **reemplaza** el tipo de reacción
anterior (no crea una segunda reacción — hay `UNIQUE(status_id, actor_id)` en DB).
Notifica `NEW_STATUS_REACTION` al dueño del status solo si es tu **primera** reacción a
ese status (cambiar el tipo de una reacción existente no vuelve a notificar).
- **Body** (`ReactToStatusRequest`): `{ "type": "WITH_YOU | WANT_TO_TALK | HERE_READING | NOT_ALONE" }`
- **Response 200**: `StatusResponse` actualizado.
- **Errores**: `404 Not Found` si el status no existe.

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
- **Errores**: `400 Bad Request` (intentar chatear con vos mismo), `404 Not Found`
  (usuario objetivo no existe), `403 Forbidden` (regla de arriba).
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

### `POST /api/conversations/{conversationId}/messages`
Envía un mensaje. Además de persistirlo, lo empuja por WebSocket al destinatario (ver
`WEBSOCKET_CONTRACT.md`) — el POST es la única forma de enviar (no hay envío vía STOMP).
- **Body** (`SendMessageRequest`): `{ "content": "máx 2000 chars, obligatorio" }`
- **Response 201**: `MessageResponse` recién creado.
- **Errores**: `403 Forbidden` (no sos parte de la conversación), `404 Not Found`.

---

## 9. Notifications (`/api/notifications`) — requiere autenticación

Las notificaciones se generan internamente desde otros módulos (follow, comment, support,
status reaction) — no hay endpoint para crearlas manualmente.

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
