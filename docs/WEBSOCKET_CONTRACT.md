# WebSocket Contract — ByYourSide Backend

> Generado por auditoría directa de `WebSocketConfig`, `StompAuthChannelInterceptor`,
> `ChatService` y `NotificationService` el 2026-09-25, sobre `develop` (`db01de9`).

## Regla de mantenimiento

Igual que `API_CONTRACT.md`: cualquier cambio a destinations, payloads o comportamiento
de autenticación debe actualizarse en el mismo commit/PR que lo introduce.

---

## Resumen

El backend usa **STOMP sobre WebSocket** (Spring `spring-boot-starter-websocket`, broker
simple en memoria, no RabbitMQ/ActiveMQ). El uso actual es **exclusivamente server→client
push**: no existe ningún `@MessageMapping` en el backend, es decir, **el cliente nunca
envía mensajes de aplicación por el socket** (ni chat ni nada) — todo el envío de datos
(mensajes de chat, etc.) se hace por REST (`POST /api/conversations/{id}/messages`), y el
socket solo se usa para que el backend empuje updates en tiempo real a quien corresponda.

## Endpoint de conexión

```
ws(s)://<host>/ws
```

- Registrado sin fallback SockJS (`registry.addEndpoint("/ws")`, sin `.withSockJS()`) —
  el frontend debe conectar con un cliente STOMP nativo sobre WebSocket (ej.
  `@stomp/stompjs`), no con `sockjs-client`.
- `setAllowedOrigins` usa la misma lista que CORS (`CORS_ALLOWED_ORIGINS`, default
  `http://localhost:5173,http://localhost:3000`).
- A nivel de Spring Security HTTP, `/ws/**` está en `permitAll()` — el handshake HTTP
  inicial no requiere `Authorization` header. **La autenticación real ocurre en el
  primer frame STOMP `CONNECT`**, no en el handshake.

## Autenticación

Un WebSocket nativo no permite headers custom en el handshake HTTP, así que el JWT viaja
como **header STOMP** dentro del frame `CONNECT` (no como query param ni como header
HTTP del upgrade):

```
CONNECT
Authorization:Bearer eyJhbGciOi...
accept-version:1.1,1.2
heart-beat:10000,10000

^@
```

Comportamiento (`StompAuthChannelInterceptor`, intercepta el canal inbound solo en el
comando `CONNECT`):

- Si falta el header `Authorization` o no empieza con `Bearer ` → la conexión se rechaza
  (`MessagingException`, el cliente ve el CONNECT fallar).
- Si el token es inválido/expirado, o el username que contiene no resuelve a un usuario
  existente → también se rechaza.
- Si es válido, se resuelve el `Authentication` (mismo `UserPrincipal` que en REST) y
  queda asociado a la sesión STOMP para toda su duración — es lo que permite que
  `convertAndSendToUser(username, ...)` en el backend encuentre la sesión correcta de
  ese usuario más adelante.
- El token es el mismo JWT que se usa en REST (mismo endpoint de login, mismo secreto,
  misma expiración). No hay un token separado para WebSocket.

**Reconexión**: si el JWT expira mientras el socket sigue abierto, la conexión STOMP en
sí **no se cae automáticamente** (la validación solo ocurre en `CONNECT`, no en cada
frame). En la práctica, el frontend debería reconectar el socket cada vez que renueva
sesión, y manejar el caso de reconexión fallida por token vencido (nueva `CONNECT`
rechazada) redirigiendo a login.

## Broker y prefijos

```java
registry.enableSimpleBroker("/topic", "/queue")
registry.setApplicationDestinationPrefixes("/app")   // sin uso actual: no hay @MessageMapping
registry.setUserDestinationPrefix("/user")
```

- Broker **simple** (in-memory, un solo proceso backend — no apto tal cual para
  múltiples instancias del backend en paralelo sin un broker externo).
- Prefijo `/app`: reservado por Spring, pero **no hay ningún endpoint `@MessageMapping`
  implementado** — no enviar frames `SEND` a `/app/**`, no van a ser procesados.
- Todas las destinations reales usan el prefijo `/user/**` (mensajería dirigida a un
  usuario específico vía `convertAndSendToUser`).

## Heartbeat

```java
.setHeartbeatValue(new long[]{10000, 10000})
```

Heartbeat cada 10 segundos en ambas direcciones, con un `ThreadPoolTaskScheduler`
dedicado. Esto es deliberado (ver comentario en el código): sin heartbeat explícito,
proxies intermedios (Railway incluido, donde se despliega) pueden cortar la conexión por
inactividad sin que ninguna de las dos puntas se entere hasta el próximo intento fallido.
El cliente STOMP del frontend debe **negociar heartbeat, no deshabilitarlo** (con
`@stomp/stompjs`, configurar `heartbeatIncoming`/`heartbeatOutgoing` a un valor ≥0; si el
cliente pide `0,0` el negociado real igual será cada 10s del lado servidor).

## Subscriptions (lo que el cliente debe suscribir)

No hay un catálogo de "topics públicos" (`/topic/**`) en uso actual — todo es privado
por usuario vía `/user/**`. Tras un `CONNECT` exitoso, el frontend debe suscribirse a:

### `/user/queue/messages`
Nuevo mensaje de chat dirigido al usuario conectado. Emitido por `ChatService` cada vez
que **otro** usuario envía un mensaje vía `POST /api/conversations/{conversationId}/messages`
en una conversación donde el usuario conectado es el destinatario.

**Payload** — exactamente la forma de `MessageResponse` (ver `API_CONTRACT.md` §8):
```json
{
  "id": "uuid",
  "conversationId": "uuid",
  "sender": { "id": "uuid", "username": "...", "displayName": "...", "avatarUrl": "..." },
  "content": "texto",
  "read": false,
  "createdAt": "2026-09-25T14:30:00Z"
}
```

- El **remitente no recibe** este evento por su propio mensaje enviado (no hay eco a
  uno mismo) — el emisor ya tiene la respuesta directa del `POST`.
- No hay evento de "mensaje leído" ni de "usuario escribiendo" (typing indicator) — no
  implementados.

### `/user/queue/notifications`
Nueva notificación in-app (follow, comentario, apoyo a un post, reacción a un status).
Emitido por `NotificationService.notify(...)`, llamado internamente desde `FollowService`,
`CommentService`, `PostSupportService` y `StatusService`.

**Payload** — exactamente la forma de `NotificationResponse` (ver `API_CONTRACT.md` §9):
```json
{
  "id": "uuid",
  "actor": { "id": "uuid", "username": "...", "displayName": "...", "avatarUrl": "..." },
  "type": "NEW_FOLLOWER",
  "postId": "uuid o null",
  "read": false,
  "createdAt": "2026-09-25T14:30:00Z"
}
```

- Nunca se autonotifica (si `recipient.id == actor.id`, `notify()` corta antes de
  guardar/emitir — no debería poder pasar en la práctica, pero está protegido).
- `type` es uno de `NEW_FOLLOWER | NEW_COMMENT | NEW_POST_RESPONSE | NEW_STATUS_REACTION`
  (ver tabla de enums en `API_CONTRACT.md`). **Backend Debt B1**: `NEW_SUPPORT` fue
  renombrado a `NEW_POST_RESPONSE` — representa cualquier `PostResponseType`, no solo el
  soporte binario anterior. Cambio de contrato: un consumidor que compare contra el
  string literal `"NEW_SUPPORT"` debe actualizarse.

## Cómo se resuelve el destinatario

`convertAndSendToUser(username, "/queue/messages", payload)` usa el **`username`**
interno del usuario (el handle autogenerado, el mismo que identifica la sesión STOMP
autenticada) — no el `userId` (UUID) ni el `email`. Esto es interno al backend y
transparente para el frontend (el frontend solo se suscribe a `/user/queue/...`, sin
necesidad de saber su propio username), pero es relevante para entender por qué el login
por email igual preserva toda la lógica de sesión basada en username.

## Errores / desconexión

No hay un canal STOMP de errores custom (`/user/queue/errors` u similar) — un
`MessagingException` lanzado en el interceptor de auth simplemente hace fallar el
`CONNECT` a nivel de protocolo STOMP (el cliente ve el frame `ERROR` estándar de STOMP,
o el intento de conexión rechazado, según el cliente usado). El frontend debe manejar
ese caso como "sesión inválida, ir a login" igual que un 401 de REST.

## Lo que NO existe (para evitar asumir funcionalidad)

- No hay salas/canales públicos (`/topic/**` sin usar).
- No hay indicador de presencia (online/offline) vía WebSocket.
- No hay "typing indicator".
- No hay confirmación de lectura push (el estado `read` de un mensaje se actualiza solo
  vía REST, al hacer `GET /api/conversations/{id}/messages`, y no se re-emite por socket
  al remitente cuando el destinatario lee).
- No hay reconexión automática implementada en el backend (es responsabilidad 100% del
  cliente STOMP).
