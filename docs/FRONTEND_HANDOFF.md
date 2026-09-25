# Frontend Handoff — ByYourSide

> Dirigido al agente/equipo que implementa el frontend (React + TypeScript + Vite +
> Axios + STOMP + Playwright) en Cursor. Resume solo lo necesario para integrar contra
> el backend real; el detalle completo de cada endpoint está en `API_CONTRACT.md` y
> `WEBSOCKET_CONTRACT.md`. Generado el 2026-09-25 por auditoría del backend
> (`by-your-side-backend`, `develop` @ `db01de9`). **Este documento no inspeccionó
> ningún repositorio de frontend** — la sección "Known integration gaps" lista
> capacidades del backend que son fáciles de pasar por alto, no un diagnóstico de código
> frontend real.

## Regla de mantenimiento

Este documento debe actualizarse en el mismo commit/PR que cambie el contrato público
del backend (junto con `API_CONTRACT.md` / `WEBSOCKET_CONTRACT.md`).

---

## Base API esperada

- **Base URL**: `http://localhost:8080` en desarrollo (backend vía `docker compose up`,
  puerto `8080`; en producción, la URL real la define el deploy — no hay un valor
  hardcodeado más allá del default local).
- Todos los endpoints de negocio cuelgan de `/api/**`. El WebSocket cuelga de `/ws`
  (mismo host/puerto, protocolo `ws`/`wss`).
- CORS ya está habilitado para `http://localhost:5173` y `http://localhost:3000` por
  default (`CORS_ALLOWED_ORIGINS`) — si Vite corre en otro puerto, pedir que se agregue
  al backend.

## Autenticación y manejo del JWT

1. `POST /api/auth/register` o `POST /api/auth/login` devuelven `{ token, username, role }`.
2. Guardar `token` (ej. `localStorage` o memoria + refresh en boot) y enviarlo en
   **todas** las requests autenticadas como:
   ```
   Authorization: Bearer <token>
   ```
   (configurar como interceptor de Axios, no adjuntarlo a mano en cada llamada).
3. **No hay endpoint de refresh ni de logout server-side.** El logout es 100%
   client-side: borrar el token guardado y, si hay socket abierto, desconectarlo. La
   expiración default es 24hs (`JWT_EXPIRATION_MS`); al expirar, cualquier request
   autenticada devuelve `401` — el interceptor de Axios debe capturar eso y redirigir a
   login.
4. El campo `username` de la respuesta de auth **no es el email** — es un handle
   autogenerado (slug del `displayName`). Si la UI necesita mostrar el email del usuario
   logueado, pedirlo aparte con `GET /api/users/me`.
5. El mismo token JWT sirve para REST y para WebSocket (ver abajo) — no hay tokens
   separados por canal.

### `email` vs `username` — importante, se confunde fácil

```text
email    = credencial visible de autenticación (lo que el usuario escribe para loguearse)
username = handle interno/autogenerado (slug de displayName), usado internamente por
           Spring Security, el JWT y el WebSocket — el usuario nunca lo eligió ni lo vio
           como campo de formulario
```

`AuthResponse.username` (de `register`/`login`) es el **handle**, no el email. No
mostrar ese valor como si fuera el email del usuario en ningún lado de la UI.

## Verificación de email (Fase 1.2)

El envío real de email (vía Resend) ya existe. Esto es lo que el frontend puede
construir:

- **Cómo saber si el usuario verificó su email**: `GET /api/users/me` devuelve
  `emailVerified: boolean` y `emailVerifiedAt: string | null`. Es el único lugar donde
  se expone — no está en `PublicUserProfileResponse`, `DiscoverUserResponse` ni
  `UserSummary` (es información privada de la propia cuenta, igual que `email`).
- **Flujo real de punta a punta**: al registrarse, el backend manda un email con un link
  a `{APP_FRONTEND_URL}/verify-email?token=...`. El frontend necesita una ruta
  `/verify-email` que lea `token` de la query string y llame a
  `POST /api/auth/verify-email` con `{ "token": "..." }`. Ver `API_CONTRACT.md` §1 para
  la respuesta y los casos de error (`400` token inválido/expirado/invalidado, `409`
  token ya usado).
- **Reenvío de verificación**: `POST /api/auth/resend-verification` con
  `{ "email": "..." }` — útil para una pantalla "no recibiste el email" o "reenviar
  verificación". La respuesta es **siempre** el mismo mensaje genérico `200`, sin
  importar si el email existe, ya está verificado, o fue reenviado hace menos de 60s —
  el frontend no debe intentar distinguir estos casos ni mostrar un error específico por
  "email no encontrado" (ver `API_CONTRACT.md` § `resend-verification`, prevención de
  account enumeration).
- **Qué mantiene register**: sigue creando el usuario, devolviendo `token` (JWT) y
  dejando al usuario autenticable de inmediato — **no cambia nada de lo que el frontend
  ya hace hoy** con `POST /api/auth/register`.
- **Qué mantiene login**: sin cambios. Un usuario con `emailVerified: false` puede
  loguearse normalmente — **no hay ninguna restricción de acceso** por email no
  verificado en esta fase (ni en login, ni en ningún otro endpoint).
- **Email de bienvenida**: se manda automáticamente (server-side, sin acción del
  frontend) la primera vez que un usuario verifica su cuenta. No hay nada que construir
  en el frontend para esto.
- **Cómo probar el flujo en dev sin credenciales de Resend**: si `RESEND_API_KEY` no
  está seteada, el backend no manda el email real (no falla, es un no-op) y el token
  sigue sin exponerse por ningún response HTTP — para probar el flujo completo en dev
  hace falta una `RESEND_API_KEY` real o generar el token a mano desde un test/consulta
  a la base.
- **UI opcional**: no hay banner ni bloqueo de UI obligatorio por email sin verificar;
  se puede mostrar opcionalmente un indicador informativo ("verificá tu email") basado
  en `emailVerified`, con un botón que dispare `resend-verification`.
- **Fuera de alcance de esta fase** (no implementar todavía): forgot/reset password,
  cambio de contraseña, refresh tokens.

## Recuperación de contraseña (Fase 1.3)

Flujo completo: `forgot-password` → email con link → `reset-password`. Ambos
endpoints son públicos (sin JWT).

- **Pantalla "olvidé mi contraseña"**: formulario con un campo `email`, llama a
  `POST /api/auth/forgot-password` con `{ "email": "..." }`. La respuesta es
  **siempre** `200` con el mismo mensaje genérico, sin importar si el email existe o
  no — **no mostrar ningún mensaje de error específico de "email no encontrado"**, ni
  siquiera si el backend está en cooldown (60s desde el último pedido). Mostrar
  simplemente el mensaje que devuelve la API o un texto equivalente ("si existe una
  cuenta con ese email, te enviamos instrucciones").
- **Ruta de reset**: el email manda un link a
  `{APP_FRONTEND_URL}/reset-password?token=...`. El frontend necesita una ruta
  `/reset-password` que lea `token` de la query string y un formulario con
  `newPassword` (+ confirmación, solo del lado del frontend — el backend no pide
  confirmación de contraseña). Llama a `POST /api/auth/reset-password` con
  `{ "token": "...", "newPassword": "..." }`.
- **Política de contraseña**: `newPassword` usa la misma regla que el registro (mínimo
  8 caracteres) — reusar la misma validación de formulario que ya existe para
  `RegisterRequest.password`, no inventar una nueva.
- **Después de un reset exitoso**: la API responde `200` con un mensaje, **no** un JWT
  nuevo. El frontend debe redirigir a la pantalla de login (no asumir que el usuario
  queda autenticado) y mostrar un mensaje de éxito.
- **Errores a manejar en la UI de reset** (ver `API_CONTRACT.md` § `reset-password`):
  token inválido/inexistente, expirado (30 minutos de validez), ya usado (`409`), o
  invalidado por un pedido de `forgot-password` más nuevo — en los tres últimos casos
  es razonable mostrar "este link ya no es válido, pedí uno nuevo" con un link de
  vuelta a "olvidé mi contraseña", sin necesidad de distinguir el motivo exacto en la
  UI.
- **Email de confirmación**: tras un reset exitoso el backend manda automáticamente un
  email informativo ("tu contraseña fue cambiada"). No hay nada que construir en el
  frontend para esto.
- **Limitación conocida a comunicar si corresponde**: un JWT emitido antes del reset
  sigue siendo válido hasta su expiración natural (24hs). No hay revocación de
  sesiones todavía (llega en una fase posterior) — no es necesario que el frontend
  haga nada especial por esto, es una limitación de backend documentada.
- **Fuera de alcance de esta fase** (no implementar todavía): cambio de contraseña
  autenticado (desde el perfil, con la contraseña actual), refresh tokens, logout
  global / revocación de sesiones.

## Endpoints disponibles

Ver `API_CONTRACT.md` para el detalle completo (request/response/reglas/errores).
Resumen de superficie por dominio:

| Dominio | Base path | Notas rápidas |
|---|---|---|
| Auth | `/api/auth` | público, register/login/verify-email/forgot-password/reset-password |
| Users | `/api/users` | perfil propio/ajeno, discover, avatar |
| Posts | `/api/posts` | CRUD + feed + apoyo ("like") |
| Comments | `/api/posts/{postId}/comments` | anidado bajo post |
| Follows | `/api/follows` | seguir/dejar de seguir, listas |
| Statuses | `/api/statuses` | "estado de ánimo" efímero (24h) + reacciones |
| Availability | `/api/availability` | "modo compañía" efímero (6h) |
| Chat | `/api/conversations` | conversaciones 1:1, mensajes paginados |
| Notifications | `/api/notifications` | in-app, generadas internamente |
| Reports | `/api/reports` | crear (cualquiera), resolver (moderador/admin) |
| Admin | `/api/admin/users` | cambiar rol, solo admin |

## Contratos importantes a tener presentes

- **`UserSummary`** (`{ id, username, displayName, avatarUrl }`) es la forma que se
  repite en *todas* las respuestas que embeben "un usuario dentro de otra cosa" (autor
  de post/comment, actor de notificación, otro participante de un chat, etc.) — nunca
  trae `email` ni `bio`. Conviene tipar esto una sola vez en el frontend y reusarlo.
- **Paginación Spring Data**: los endpoints paginados devuelven el envelope completo de
  `Page` (`content`, `totalElements`, `totalPages`, `last`, etc.), no un array plano.
  Varios endpoints en cambio devuelven `List<T>` sin envelope (comments, followers/following,
  statuses/feed, availability list, conversations list) — **hay que revisar
  `API_CONTRACT.md` endpoint por endpoint**, no asumir un patrón único.
- **Errores**: siempre el mismo shape `ErrorResponse` (`timestamp, status, error,
  message, path, fieldErrors`). Un interceptor global de Axios puede leer `message` para
  toasts genéricos, y `fieldErrors` específicamente en formularios (solo viene poblado
  en 400 de validación).
- **404 en vez de 403 para ocultar existencia**: por ejemplo, un post privado ajeno
  devuelve 404, no 403 — el frontend no debe intentar distinguir "no existe" de "no
  tengo permiso" en esos casos, el backend los unifica a propósito.
- **PATCH parciales**: en `PATCH /api/users/me`, `PATCH /api/posts/{id}` los campos
  omitidos (`null`) se interpretan como "no tocar", no como "vaciar". No hay forma de
  vaciar `bio`/`displayName` enviando `null` explícito.

## Enums (usar como union types / string literal types en TS, no como número)

Ver tabla completa en `API_CONTRACT.md` § "Enums usados en la API". Se serializan
siempre como el nombre en `STRING` (ej. `"PUBLIC"`), nunca como índice.

## Paginación

Default `page=0`, `size=20` en casi todos los endpoints paginados — **excepto**
`GET /api/conversations/{id}/messages`, cuyo default de `size` es **50**. No hay un
tamaño máximo de página validado por el backend (`size` muy grande no se rechaza).

## Uploads

Único endpoint de upload: `POST /api/users/me/avatar`, `multipart/form-data`, campo
`file`. Límite 5MB, debe ser `image/*`. No hay upload de imágenes para posts/comentarios
(el contenido de post/comment es siempre texto plano, `content: string`).

## WebSocket

Ver `WEBSOCKET_CONTRACT.md` para el contrato completo. Puntos clave para la integración:

- Conectar a `/ws` con un cliente STOMP nativo (ej. `@stomp/stompjs`) — **no** SockJS.
- Enviar el JWT como header STOMP `Authorization: Bearer <token>` en el frame `CONNECT`
  (no en el handshake HTTP, no como query param).
- Suscribirse a `/user/queue/messages` (mensajes de chat nuevos) y
  `/user/queue/notifications` (notificaciones nuevas) tras conectar.
- El cliente **nunca envía** nada por el socket (no hay `@MessageMapping` en el
  backend) — enviar mensajes de chat siempre vía `POST /api/conversations/{id}/messages`.
- Negociar heartbeat (10s/10s), no deshabilitarlo — necesario para sobrevivir proxies
  intermedios en producción.

## Errores comunes a manejar explícitamente

- `401` global → limpiar sesión y redirigir a login (interceptor de Axios).
- `403` en acciones de moderador/admin (`/api/reports/**`, `/api/admin/**`) → ocultar
  o deshabilitar esa UI para roles `USER` directamente en el cliente, además de manejar
  el 403 si igual se llega a intentar.
- `409` en `POST /api/posts/{id}/support` (ya apoyado), `POST /api/follows/{id}` (ya
  seguías), `POST /api/reports/{id}/resolve` (ya resuelto) → tratar como estado ya
  alcanzado, no como error duro (refrescar el estado local en vez de mostrar un toast de
  error genérico).
- `GET /api/availability/mine` puede devolver body `null` con status `200` — **no
  asumir que siempre hay objeto**, chequear explícitamente antes de leer sus campos.

## Limitaciones actuales conocidas (del backend, verificadas en código)

- No hay refresh token ni revocación de tokens — un JWT emitido es válido hasta que
  expira, sin forma de invalidarlo del lado servidor (ej. tras un logout "real" o un
  cambio de contraseña — tampoco existe endpoint de cambio de contraseña).
- No hay endpoint para editar el `username` (handle) una vez generado.
- No hay búsqueda de usuarios por texto — `GET /api/users/discover` es un listado sin
  filtro de búsqueda.
- No hay marcado de notificación individual como leída, solo "marcar todas".
- No hay "typing indicator" ni presencia online/offline en el chat.
- No hay soft-delete recuperable expuesto (los `REMOVED` de post/comment no tienen
  endpoint de "deshacer").
- `PostStatus.FLAGGED` y `CommentStatus.FLAGGED` existen como valores de enum pero
  **ningún flujo del backend los asigna actualmente** — no construir UI que dependa de
  ver contenido en estado `FLAGGED`, hoy es inalcanzable.
- Resolver un reporte (`PATCH /api/reports/{id}/resolve`) **no** oculta/borra
  automáticamente el contenido reportado — si la UI de moderación ofrece "resolver y
  eliminar" como una sola acción, hoy son dos llamadas separadas del lado del cliente.

## Known integration gaps

Esta sección identifica capacidades que el backend **ya soporta** hoy y que, por su
naturaleza (no requieren endpoints nuevos, ya están completas y probadas con integration
tests), son candidatas fáciles a quedar sin aprovechar si el frontend no las conoce.
No se verificó código de frontend — son señales a chequear con el equipo/agente de
Cursor, no hallazgos confirmados de ausencia:

- **Modo compañía completo** (`/api/availability`): declarar disponibilidad, listar
  disponibles por intent en orden aleatorio, y sobre todo la regla especial de chat
  (`POST /api/conversations/{userId}` permite iniciar conversación con alguien
  disponible **sin** relación de follow previa). Si el flujo de "iniciar chat" en el
  frontend solo contempla usuarios ya seguidos/seguidores, esta vía alternativa de
  first-contact quedaría sin UI.
- **Reacciones a estados de ánimo** (`POST/DELETE /api/statuses/{id}/react`, 4 tipos:
  `WITH_YOU`, `WANT_TO_TALK`, `HERE_READING`, `NOT_ALONE`) — es una interacción social
  distinta de "apoyo" a un post (`support`), con su propio contador y su propio tipo de
  notificación (`NEW_STATUS_REACTION`). Fácil de confundir/fusionar con el sistema de
  `support` de posts si no se los trata como features separadas.
- **Cola de moderación priorizada** (`GET /api/reports/queue`): los reportes
  `SELF_HARM_RISK` siempre se devuelven primero server-side, independientemente de la
  fecha. Si el frontend re-ordena o pagina client-side sin respetar el orden que ya
  viene del backend, se pierde esa priorización crítica (es la única mención explícita
  en el backend de manejo especial para contenido de riesgo de autolesión — no hay
  ningún otro flujo automático de derivación a recursos de ayuda, ver README histórico
  del backend, que lo listaba como pendiente).
- **`unreadCount` por conversación** (`GET /api/conversations`) y
  **`unread-count` global** (`GET /api/notifications/unread-count`) — dos contadores
  independientes pensados para badges de UI (lista de chats y campana de notificaciones,
  respectivamente); confirmar que ambos se consumen y no solo uno de los dos.
- **WebSocket para notificaciones**, no solo para chat: `/user/queue/notifications` es
  un canal separado de `/user/queue/messages`. Si el frontend implementó el socket
  pensando solo en chat en tiempo real, las notificaciones (nuevos followers, comentarios,
  apoyos, reacciones a estados) podrían estar llegando solo vía polling REST
  (`GET /api/notifications`) en vez de push inmediato.
- **Rol `MODERATOR`** como rol intermedio real (no solo `USER`/`ADMIN`): tiene permisos
  de moderación (cola de reportes, borrar posts/comentarios ajenos) pero no de gestión de
  roles (`/api/admin/**` es `ADMIN`-only). Si el frontend solo modela dos roles en su
  lógica de permisos, la UI de moderador podría no habilitarse correctamente.

## Documentación desactualizada detectada (no corregida, solo señalada)

`README.md` del backend describe un estado anterior del proyecto (menciona
`SecurityConfig` como "placeholder" sin JWT real, y falta de controllers/services) que
ya no refleja el código actual. No se modificó como parte de esta auditoría (fuera de
alcance), pero el equipo de frontend no debería guiarse por ese README para entender el
estado de autenticación — usar únicamente estos documentos en `docs/`.
