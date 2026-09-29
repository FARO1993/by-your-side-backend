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

## Autenticación y manejo de sesión

> ## ⚠️ BREAKING CHANGE — Fase 1.5
> `AuthResponse` (de `register`/`login`) **ya no tiene el campo `token`**. Se reemplazó
> por `accessToken` + `refreshToken` + `tokenType` + `expiresIn`. Cualquier código de
> frontend que lea `response.token` va a romperse (va a quedar `undefined`) hasta que se
> adapte. **AuthContext/el API client de Cursor va a necesitar cambios** para:
> - leer `accessToken` (no `token`) del response de `register`/`login`,
> - guardar también `refreshToken`,
> - llamar a `POST /api/auth/refresh` cuando el access token expire (ya no alcanza con
>   "reintentar login" ni con esperar 24hs de vida como antes),
> - manejar el logout llamando a `POST /api/auth/logout` (antes era 100% client-side).
>
> **La estrategia de almacenamiento del `refreshToken` en el browser todavía NO está
> decidida** (`localStorage` vs. cookie `HttpOnly` vs. otra cosa) — eso se define en
> **Fase 1.6**. No asumir `localStorage` como decisión final; implementar de forma que
> sea fácil de migrar (ej. no esparcir `localStorage.getItem` por todos lados, centralizar
> el acceso al refresh token en un solo módulo).

1. `POST /api/auth/register` o `POST /api/auth/login` devuelven:
   ```json
   { "accessToken": "...", "refreshToken": "...", "tokenType": "Bearer", "expiresIn": 900, "username": "...", "role": "USER" }
   ```
2. Guardar `accessToken` y enviarlo en **todas** las requests autenticadas como:
   ```
   Authorization: Bearer <accessToken>
   ```
   (configurar como interceptor de Axios, no adjuntarlo a mano en cada llamada).
3. **El access token dura poco: 15 minutos** (`expiresIn: 900`, segundos). Esto es a
   propósito — antes duraba 24hs, ahora esa vida larga la cubre el `refreshToken`
   (30 días). El frontend necesita manejar la renovación:
   - Cuando una request autenticada devuelve `401`, llamar a
     `POST /api/auth/refresh` con `{ "refreshToken": "..." }`.
   - La respuesta trae `accessToken` **y `refreshToken` nuevos** — reemplazar **ambos**
     en el storage, nunca reusar el `refreshToken` viejo (queda inutilizado
     automáticamente apenas se usa una vez — ver "Rotación" en `API_CONTRACT.md` §
     `/api/auth/refresh`).
   - Si el refresh también falla (`400`/`409`), la sesión ya no es recuperable — limpiar
     el storage y redirigir a login. No reintentar el mismo `refreshToken` de nuevo.
   - Patrón recomendado: interceptor de Axios que, ante un `401`, dispare el refresh una
     única vez y reintente la request original con el `accessToken` nuevo; si el refresh
     falla, recién ahí redirigir a login.
4. **Logout real**: llamar a `POST /api/auth/logout` con `{ "refreshToken": "..." }`
   antes de limpiar el storage local y desconectar el WebSocket si hay uno abierto. Esto
   invalida la sesión del lado del servidor (antes, Fase <1.5, el logout era 100%
   client-side y no invalidaba nada server-side). El endpoint es tolerante — llamarlo con
   un `refreshToken` ya vencido o inexistente no es un error, siempre responde `200`.
5. **Multi-dispositivo**: cada login (celular, PC, otra pestaña) es una sesión
   independiente con su propio `refreshToken`. Loguearse de nuevo en un dispositivo
   **no** cierra la sesión de otro. Logout en un dispositivo tampoco afecta a los demás.
6. **Cambiar o resetear la contraseña cierra TODAS las sesiones** (todos los
   dispositivos, incluido el que hizo el cambio) — después de un `change-password` o
   `reset-password` exitoso, cualquier intento de `/api/auth/refresh` con un
   `refreshToken` emitido antes va a fallar con `400` (`"Refresh token has been
   revoked"`). El frontend debe tratar ese caso igual que cualquier otro refresh
   fallido: limpiar sesión y mandar a login. Esto es intencional (ver
   `API_CONTRACT.md` § `change-password`/`reset-password`), no un bug a reportar.
7. El campo `username` de la respuesta de auth **no es el email** — es un handle
   autogenerado (slug del `displayName`). Si la UI necesita mostrar el email del usuario
   logueado, pedirlo aparte con `GET /api/users/me`.
8. El access token (JWT) sigue sirviendo para REST y para WebSocket (ver abajo) — no hay
   tokens separados por canal. El `refreshToken` **nunca** se usa para WebSocket ni para
   ninguna request REST directamente (no es un Bearer token, es solo el input de
   `/refresh` y `/logout`).

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
- **Qué mantiene register**: sigue creando el usuario y devolviendo tokens (`accessToken`
  + `refreshToken`, ver § Autenticación y manejo de sesión arriba), dejando al usuario
  autenticable de inmediato.
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
  cambio de contraseña. (Refresh tokens ya existen desde Fase 1.5, ver arriba.)

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
  sigue siendo válido hasta su expiración natural (máximo 15 minutos desde Fase 1.5).
  **Desde Fase 1.5, un reset SÍ revoca todas las sesiones** (todos los `refreshToken`
  del usuario) — ver § Autenticación y manejo de sesión arriba para cómo debe
  reaccionar el frontend cuando un refresh falla por esto.

## Cambio de contraseña autenticado (Fase 1.4)

Distinto de "olvidé mi contraseña": esta pantalla es para un usuario que **ya está
logueado** y **conoce** su contraseña actual — típicamente dentro de
cuenta/seguridad. No confundir los dos flujos ni reusar la misma pantalla.

- **Endpoint**: `POST /api/auth/change-password`, **requiere JWT** (`Authorization:
  Bearer <token>` — es el único endpoint bajo `/api/auth` que lo requiere, todos los
  demás de esa sección son públicos).
- **Formulario**: tres campos visualmente — Current password, New password, Confirm
  new password — pero el backend **solo** recibe dos:
  ```json
  { "currentPassword": "...", "newPassword": "..." }
  ```
  La confirmación de "las dos contraseñas nuevas coinciden" es una validación
  **puramente del frontend**, antes de llamar a la API — el backend no tiene (ni va a
  tener) un campo `confirmNewPassword`.
- **Identidad**: nunca se manda `email`/`username`/`userId` en el body — la cuenta a
  modificar es siempre la del JWT usado en el header. No hay forma (ni necesidad) de
  parametrizar de quién es la cuenta.
- **Política de `newPassword`**: misma regla que registro y reset (mínimo 8
  caracteres) — reusar la misma validación de formulario que ya existe.
- **Response 200 exitosa**: `{ "message": "Password changed successfully." }` — **no**
  devuelve tokens nuevos.
- **Errores a manejar en la UI**:
  - `401 Unauthorized` — el JWT expiró o no es válido; tratarlo igual que cualquier
    otro 401 (redirigir a login), no es específico de este endpoint.
  - `400 Bad Request` con `"Current password is incorrect"` — mostrar el error debajo
    del campo "Current password", **no** revelar más detalle.
  - `400 Bad Request` con `"New password must be different from the current
    password"` — mostrar el error debajo del campo "New password".
  - `400 Bad Request` con `fieldErrors.newPassword` — la política de longitud no se
    cumplió (debería quedar cubierto por la validación del propio formulario antes de
    llamar a la API, pero el backend la vuelve a validar de todos modos).
- **Email de confirmación**: tras un cambio exitoso el backend manda automáticamente
  un email informativo ("tu contraseña fue cambiada") — mismo email que ya dispara
  `reset-password`. No hay nada que construir en el frontend para esto.
- **Revoca todas las sesiones (Fase 1.5)**: un cambio exitoso cierra **todas** las
  sesiones del usuario, incluida la que hizo este mismo request — el access token
  (JWT) en uso sigue funcionando hasta que expire naturalmente (máximo 15 minutos),
  pero **el frontend debe tratar este caso como un logout inmediato de todas las
  sesiones**: limpiar `accessToken`/`refreshToken` guardados y redirigir a login
  apenas se reciba la respuesta `200` de `change-password`, sin esperar a que el
  próximo refresh falle — ya se sabe de antemano que el `refreshToken` guardado va a
  quedar inválido.

## Privacidad de perfil, publicaciones y follow requests (Fase 9.1/9.2/9.3)

Dos controles de privacidad, más un flujo de solicitudes que los conecta.

- **Perfil** (`UserResponse`/`PublicUserProfileResponse`/`DiscoverUserResponse.profileVisibility`):
  enum `"PUBLIC" | "PRIVATE"` — **exactamente esos dos valores**, sin
  `"FOLLOWERS_ONLY"` a nivel de perfil (eso solo existe a nivel de post, ver abajo,
  cuidado con confundirlos). Default `PUBLIC` para cuentas nuevas y viejas.
- **Publicación** (`PostResponse.visibility`, `CreatePostRequest`/`UpdatePostRequest.visibility`):
  enum `"PUBLIC" | "FOLLOWERS_ONLY" | "PRIVATE"` — esto **ya existía** antes de Fase 9.1
  (no es nuevo), solo se documenta acá por completitud. Default `PUBLIC` si no se manda
  `visibility` al crear.
- **Cómo cambiar la privacidad del perfil**: `PATCH /api/users/me` con
  `{ "profileVisibility": "PRIVATE" }` (o `"PUBLIC"`). Mismo endpoint que ya se usa para
  editar `displayName`/`bio`/`avatarUrl` — no hay un endpoint separado. Un valor
  distinto de esos dos responde `400`.

### ⚠️ Breaking change de comportamiento (Fase 9.3): seguir ya no es siempre inmediato

Reemplaza la limitación documentada en Fase 9.1/9.2 ("el follow sigue siendo inmediato,
no hay solicitud pendiente"). Ahora:

- **Perfil `PUBLIC`** → `POST /api/follows/{userId}` sigue creando el follow de
  inmediato. Respuesta: `{ "followState": "FOLLOWING", "requestId": null, ... }`.
- **Perfil `PRIVATE`** → el mismo `POST /api/follows/{userId}` crea una **solicitud
  pendiente**, no un follow. Respuesta: `{ "followState": "REQUESTED", "requestId":
  "uuid", ... }`. **Guardar ese `requestId`** — es lo que se necesita para poder
  cancelar la solicitud después sin tener que ir a buscarlo a otro lado.
- Volver a llamar `POST /api/follows/{userId}` mientras ya hay una solicitud pendiente
  **no falla ni duplica** — devuelve la misma solicitud (mismo `requestId`).

**Estados de UI a manejar** (`followState`, expuesto en el perfil, en discover, y en la
respuesta del propio follow): `"NONE"` | `"REQUESTED"` | `"FOLLOWING"`.

| Estado | Botón/acción esperada |
|---|---|
| `NONE` | "Seguir" → `POST /api/follows/{userId}` |
| `REQUESTED` | "Solicitud enviada" (deshabilitado o con opción "Cancelar" → `DELETE /api/follow-requests/{requestId}`) |
| `FOLLOWING` | "Dejar de seguir" → `DELETE /api/follows/{userId}` |

- **Aceptar/rechazar solicitudes recibidas** (pantalla nueva a implementar, ej. dentro
  de notificaciones o una sección "Solicitudes" del perfil propio):
  - `GET /api/follow-requests/incoming` — lista de solicitudes pendientes que ME
    llegaron (`otherUser` = quien la mandó).
  - `POST /api/follow-requests/{requestId}/accept` — acepta, crea el follow real.
  - `POST /api/follow-requests/{requestId}/reject` — rechaza, no crea nada. El
    requester no recibe ninguna notificación de esto (decisión de producto) — no hace
    falta que el frontend le muestre nada especial tampoco.
- **Ver mis propias solicitudes enviadas**: `GET /api/follow-requests/outgoing`
  (`otherUser` = a quién se lo pedí) — útil para una pantalla "solicitudes pendientes"
  o simplemente para reconciliar estado si se perdió el `requestId` original.
- **Eliminar un seguidor** (Fase 9.3, nuevo): `DELETE /api/follows/followers/{userId}`
  — saca a `userId` de MIS seguidores (dirección inversa a "dejar de seguir"). No
  afecta si yo también lo sigo a él. Corta su acceso a mi perfil/posts
  `FOLLOWERS_ONLY` de inmediato.
- **`bio`/perfil completo para un follower aceptado de un perfil `PRIVATE`**: a
  diferencia de Fase 9.1/9.2 (donde NADA de un perfil privado era visible para
  terceros), un follower ya **aceptado** ahora ve la `bio` completa y los posts
  `PUBLIC`/`FOLLOWERS_ONLY` de ese perfil con total normalidad — solo los posts
  `PRIVATE` siguen siendo exclusivos del autor. Mientras la solicitud esté `PENDING`
  (o haya sido rechazada/cancelada), sigue sin ver nada, igual que un desconocido.
- **Qué pasa al entrar al perfil de otro usuario que es `PRIVATE` y no soy follower
  aceptado**: la API responde `200` (nunca `404` solo por ser privado — la cuenta
  existe y eso es visible), pero `bio` viaja en `null`. UX esperada: mostrar nombre,
  avatar, un aviso tipo "Este perfil es privado" en el lugar donde iría la bio/los
  posts, y el botón de follow que corresponda según `followState`.
  `followersCount`/`followingCount` **sí** se siguen mostrando con normalidad —
  ocultarlos es una decisión de producto separada, todavía no implementada.
- **Qué pasa con los posts de un perfil privado**: `GET /api/users/{userId}/posts`
  devuelve una página vacía (`200`, `content: []`) si no sos follower aceptado — el
  frontend debe renderizar el estado "sin publicaciones para mostrar" (el mismo que
  usaría para un perfil público sin posts), no necesita un mensaje especial distinto
  para "privado". Si SÍ sos follower aceptado, el listado se comporta como el de
  cualquier perfil público que seguís.
- **Feed propio**: siempre incluye el 100% de los posts propios, cualquiera sea su
  `visibility`. Los posts de terceros que seguís efectivamente (aceptado, sea perfil
  público o privado) aparecen con normalidad (`PUBLIC`+`FOLLOWERS_ONLY`) —
  ya **no** importa si el perfil de ese tercero está actualmente en `PRIVATE`
  (reemplaza la limitación de Fase 9.1/9.2). Comentar/reaccionar sobre un post que dejó
  de ser visible (ej. te sacaron de sus followers) empieza a fallar con `404` —
  tratarlo igual que "post no encontrado", sin un mensaje especial.
- **Fuera de alcance de esta fase** (no implementar todavía en el frontend): listas o
  círculos de audiencia personalizados, ocultar contadores de seguidores, controles de
  privacidad de mensajería, expiración automática de solicitudes. (Bloqueo y silenciado
  de usuarios dejaron de estar acá — ver las dos secciones siguientes.)

## Bloqueo de usuarios (Fase 9.4)

Nuevo endpoint por par de usuarios: `POST/DELETE /api/users/{userId}/block` +
`GET /api/users/me/blocked`. Ver `API_CONTRACT.md` §12 para el detalle completo de
request/response/errores — acá solo la guía de UX.

- **Botón de bloqueo/desbloqueo en el perfil de otro usuario**: usar
  `blockedByCurrentUser` (nuevo campo en `PublicUserProfileResponse`) para decidir cuál
  mostrar — `true` → botón "Desbloquear" (`DELETE .../block`), `false`/ausente → botón
  "Bloquear" (`POST .../block`). **No existe** ningún campo que diga "este usuario me
  bloqueó a mí" — si eso pasó, el perfil directamente responde `404` como si la cuenta no
  existiera (ver abajo), así que no hace falta ni es posible distinguir ese caso en la UI.
- **Pantalla "Usuarios bloqueados"** (nueva, típicamente colgada de una sección de
  privacidad/ajustes): `GET /api/users/me/blocked`, paginado, con
  `{ userId, username, displayName, avatarUrl, blockedAt }` — sin email. Cada fila
  debería ofrecer "Desbloquear" (`DELETE /api/users/{userId}/block`).
- **Qué pasa al bloquear a alguien** (todo esto ocurre automáticamente en el backend, el
  frontend solo necesita reflejarlo la próxima vez que pida esos datos, no hace falta
  lógica especial del lado del cliente):
  - Deja de aparecer en tu feed, en discover y en los listados de disponibilidad
    ("modo compañía"), y vos dejás de aparecer en los suyos.
  - Si se seguían mutuamente o en un solo sentido, esa relación de follow se corta (en
    ambos sentidos si aplicaba). Cualquier solicitud de follow pendiente entre ambos
    queda cancelada.
  - Ya no es posible seguirse, ni enviar/aceptar una solicitud de follow, entre ambos,
    mientras el bloqueo siga activo.
  - Su perfil pasa a responder `404` para vos si fue **él** quien te bloqueó a **vos**
    (ver el punto siguiente); si fuiste **vos** quien lo bloqueó a **él**, seguís viendo
    su tarjeta de perfil (limitada, como un perfil privado del que no sos follower), con
    `blockedByCurrentUser: true`.
  - Ya no podés ver sus posts (ni comentar ni apoyar los suyos), ni él los tuyos.
- **⚠️ Perfil bloqueado → `404`, distinto del caso "perfil privado" ya documentado
  arriba**: la sección de Privacidad (Fase 9.1/9.2/9.3) dice que `GET
  /api/users/{userId}` **nunca** devuelve `404` solo por privacidad — eso sigue siendo
  cierto para perfiles `PRIVATE` sin bloqueo de por medio. Pero si el otro usuario **te
  bloqueó a vos**, ese mismo endpoint sí devuelve `404` — indistinguible de una cuenta
  eliminada/inexistente. El frontend no puede (ni debe intentar) diferenciar "no existe"
  de "me bloqueó" en este caso — tratarlo como cualquier otro 404 de perfil (ej. volver
  al listado anterior, mostrar "usuario no encontrado").
- **Chat: el historial NUNCA se borra al bloquear** — esto es importante para no
  implementar algo que el backend no hace. Una conversación con alguien que bloqueaste
  (o que te bloqueó) sigue apareciendo en `GET /api/conversations` y su historial
  completo sigue siendo legible vía `GET /api/conversations/{id}/messages`, sin ninguna
  restricción. Lo único que cambia es que `POST /api/conversations/{userId}` (abrir/crear
  conversación) y `POST .../messages` (enviar un mensaje nuevo) empiezan a devolver
  `403` — el frontend debe deshabilitar el campo de "escribir un mensaje nuevo" en esa
  conversación específica cuando el envío falle con `403` (no hace falta chequear el
  estado de bloqueo por adelantado; el propio intento de envío ya lo revela), pero
  **no** debe ocultar ni la conversación ni los mensajes ya existentes.
- **Fuera de alcance de esta fase** (no implementar todavía en el frontend): ocultar un
  post puntual sin bloquear a su autor, reporte automático al bloquear, motivo de
  bloqueo visible, bloqueo temporizado, "amigos cercanos"/audiencias personalizadas,
  bloqueo por dispositivo. (Mute dejó de estar acá — ver la sección siguiente.)

## Silenciar usuarios (Fase 9.5)

Nuevo endpoint por par de usuarios: `POST/DELETE /api/users/{userId}/mute` +
`GET /api/users/me/muted`. Ver `API_CONTRACT.md` §13 para el detalle completo de
request/response/errores — acá solo la guía de UX.

**Diferencia clave con Bloqueo, para no confundir los dos botones**: silenciar es
**unilateral e invisible** — el otro usuario nunca se entera, sigue viendo tu contenido
con normalidad, y puede seguir interactuando con vos sin ninguna restricción (seguirte,
comentar, apoyar, escribirte). Silenciar a alguien **solo** cambia lo que **vos** ves en
tu feed, discover, estados y disponibilidad — nunca corta accesos ni relaciones. Si el
producto necesita "ya no quiero saber nada de esta persona, en ningún sentido", ese es
Bloqueo (sección anterior), no esto.

- **Botón de silenciar/dejar de silenciar en el perfil de otro usuario**: usar
  `mutedByCurrentUser` (nuevo campo en `PublicUserProfileResponse`) para decidir cuál
  mostrar — `true` → botón "Dejar de silenciar" (`DELETE .../mute`), `false`/ausente →
  botón "Silenciar" (`POST .../mute`). Este botón puede convivir sin conflicto con el de
  Bloqueo/Seguir en la misma pantalla — silenciar, bloquear y seguir son ejes
  independientes entre sí (podés silenciar a alguien que segís, por ejemplo). **No
  existe** ningún campo que diga "este usuario me silenció a mí" — a diferencia de
  bloqueo, acá ni siquiera hay un `404` que lo insinúe: el perfil de alguien que te
  silenció se ve exactamente igual que el de cualquier otra persona.
- **Pantalla "Usuarios silenciados"** (nueva, típicamente junto a "Usuarios bloqueados"
  en la misma sección de privacidad/ajustes): `GET /api/users/me/muted`, paginado, con
  `{ userId, username, displayName, avatarUrl, mutedAt }` — sin email. Cada fila debería
  ofrecer "Dejar de silenciar" (`DELETE /api/users/{userId}/mute`).
- **Qué pasa al silenciar a alguien** (todo esto ocurre automáticamente en el backend, el
  frontend solo necesita reflejarlo la próxima vez que pida esos datos):
  - Deja de aparecer en tu feed, en discover, en tu feed de estados/presencia y en los
    listados de disponibilidad ("modo compañía") — **solo para vos**. Vos seguís
    apareciendo con total normalidad en todo lo suyo.
  - **Nada más cambia.** Seguís siguiéndolo si ya lo seguías (y podés empezar a
    seguirlo/dejar de seguirlo después, sin que el mute interfiera). Su perfil, sus
    posts, comentarios, apoyo y chat funcionan exactamente igual que antes — entrar
    directamente a su perfil (`GET /api/users/{userId}`) o a un post suyo (`GET
    /api/posts/{postId}`) sigue mostrando todo con normalidad, aunque ese mismo post no
    aparezca en tu feed.
  - No recibís ningún efecto sobre notificaciones: seguís recibiendo notificaciones
    suyas (follow, comentario, apoyo, reacción) exactamente igual que antes de
    silenciarlo.
- **Diferencia práctica para el usuario, en una frase**: bloquear es "cortar el
  contacto"; silenciar es "dejar de verlo en mi feed sin que él lo note ni que cambie
  nada más entre nosotros".
- **Fuera de alcance de esta fase** (no implementar todavía en el frontend): ocultar un
  post puntual sin silenciar a su autor ("hide post"), silenciar una conversación
  puntual, silenciar notificaciones, mute temporizado, mute de temas/topics, "amigos
  cercanos"/audiencias personalizadas/círculos, preferencias de recomendación.

## Respuestas a un post (Backend Debt B1)

Reemplaza el "apoyo" binario anterior (el corazón/like de siempre) por una respuesta
tipada — el backend ahora persiste las 6 respuestas que el frontend ya ofrece, no solo
"Estoy con vos". Ver `API_CONTRACT.md` §3 para el detalle completo de
request/response/errores — acá solo la guía de UX e integración.

**Enum exacto** (`PostResponseType`, mandar/leer el nombre tal cual, nunca traducido):

| Categoría | Valor backend | Texto UI (ya usado en frontend) |
|---|---|---|
| Presencia | `WITH_YOU` | "Estoy con vos" |
| Presencia | `NOT_ALONE` | "No estás solo/a" |
| Presencia | `HUG` | "Te abrazo" |
| Escucha | `READING` | "Te leo" |
| Escucha | `TELL_ME_MORE` | "Contame más" |
| Escucha | `LISTENING` | "Estoy escuchando" |

La categoría (Presencia/Escucha) es solo para agrupar visualmente si el diseño lo pide —
el backend nunca la manda como campo separado, se deriva 1:1 del `type` (ver tabla de
arriba, es fija y completa).

**Contrato de interacción**:
- **Una sola pill activa por post** — `currentUserResponseType` (nuevo campo en
  `PostResponse`, el DTO de post) indica cuál, o `null` si el usuario no respondió
  todavía. Nunca puede haber dos pills seleccionadas a la vez para el mismo usuario/post.
- **Click en una pill nueva → `PUT /api/posts/{postId}/response` con `{ "type": "..." }`**
  — cambia (o crea) la respuesta persistida, siempre `200 OK`. Si ya tenías esa fila con
  otro tipo, el backend la actualiza (no crea una segunda). Si repetís el mismo tipo que
  ya tenías, es un no-op — no hay penalidad ni error por doble click.
- **Click para deseleccionar/quitar tu respuesta → `DELETE /api/posts/{postId}/response`**
  — siempre `200 OK`, incluso si nunca habías respondido (idempotente). No hay
  confirmación ni modal necesarios del lado del backend.
- **`presenceCount` y `listeningCount`** (nuevos campos en `PostResponse`) son los
  conteos **reales** agregados server-side — nunca sumar/inferir client-side, y nunca
  mostrar un conteo optimista que no vino del backend como valor final (ver optimismo
  más abajo).
- **No usar `localStorage` ni ningún estado local como fuente de verdad** para qué
  respondió el usuario — eso era válido solo mientras las 5 reacciones no-"Estoy con vos"
  eran puramente locales (antes de esta fase). Ahora las 6 persisten igual, y el backend
  es la única fuente de verdad: `currentUserResponseType` siempre debe reflejar lo que
  devuelve la última respuesta del servidor, no un valor recordado del dispositivo.
- **Optimismo frontend, solo con rollback correcto**: está bien pintar la pill
  seleccionada inmediatamente al click (antes de que vuelva la respuesta HTTP) para que
  se sienta instantáneo, pero el estado final (pill + conteos) debe terminar reflejando
  la respuesta real del `PUT`/`DELETE` — si la request falla, revertir al estado anterior
  conocido, nunca dejar la UI en un estado que el backend no confirmó.
- **El autor no puede responder a su propio post** — `PUT`/`POST` devuelve
  `400 Bad Request` (`"You cannot respond to your own post"`) si el usuario autenticado
  es el autor. El frontend debería directamente no mostrar las pills de respuesta en los
  posts propios (evita el roundtrip fallido), aunque el backend igual lo rechaza si
  llegara a intentarse.

**Notificaciones**: tu **primera** respuesta a un post dispara una notificación al autor
con `type: "NEW_POST_RESPONSE"` (antes `"NEW_SUPPORT"` — ver ⚠️ más abajo), `postId`
del post respondido, mismo canal WebSocket existente (`/user/queue/notifications`, sin
canal nuevo). **Cambiar de tipo o repetir el mismo NO generan una notificación
adicional** — si el frontend muestra un toast/badge por cada notificación recibida, no
debería sorprender que cambiar de pill varias veces solo notifique una vez al autor.
Borrar tu respuesta nunca notifica; volver a responder después de borrarla sí genera una
notificación nueva (es una respuesta nueva a todos los efectos). El payload de
notificación **no** incluye el `type` de la respuesta todavía (evaluado y diferido, ver
`BACKEND_ARCHITECTURE.md`) — si se quiere mostrar algo más contextual que "tenés una
respuesta nueva", hay que pedir el detalle del post con el `postId` que sí viaja.

**⚠️ Cambio de contrato — `NEW_SUPPORT` renombrado a `NEW_POST_RESPONSE`**: cualquier
lugar del frontend que compare `notification.type === "NEW_SUPPORT"` (texto del toast,
ícono, filtro) debe actualizarse a `"NEW_POST_RESPONSE"`. Es un rename, no una adición —
el string viejo deja de aparecer.

**Compatibilidad legacy (`/support`)**: `POST/DELETE /api/posts/{postId}/support` siguen
funcionando (equivalen a "Estoy con vos" / "quitar mi respuesta"), pero están
**deprecados** — preferir `PUT`/`DELETE /response` en cualquier integración nueva o
refactor. Diferencia de comportamiento a tener presente si el frontend ya los usa: el
legacy `POST /support` sigue devolviendo `409 Conflict` si ya había cualquier respuesta
propia (nunca la pisa silenciosamente, a diferencia de `PUT /response`), y el legacy
`DELETE /support` sigue devolviendo `404` si no había ninguna (a diferencia del `DELETE
/response` nuevo, que es idempotente). **Y, cambio de comportamiento nuevo en ambos**:
ahora también rechazan con `400` que el autor se responda a sí mismo — antes de esta
fase eso estaba permitido.

**Fuera de alcance de esta fase** (no implementar todavía en el frontend): historial de
respuestas (solo se guarda la activa), múltiples respuestas simultáneas por usuario,
reaction emojis/respuesta personalizada, ranking, gamificación, silenciar notificaciones
de respuestas específicamente.

## Status directo + edición de perfil (Backend Debt B2)

### Edición de perfil — `PATCH /api/users/me`
Este endpoint **ya existía y ya persistía de verdad** `displayName`/`bio`/`avatarUrl`/
`profileVisibility` antes de esta fase — lo nuevo acá es la validación de texto. Si el
frontend ya lo integraba, revisar los dos puntos siguientes por cambios de contrato:

- **`displayName`**: se recorta server-side (`trim`) — si el frontend manda
  `"  Facundo  "`, el perfil guarda y devuelve `"Facundo"`. Si se manda un valor
  **no-null** que queda vacío tras el trim (`""` o solo espacios), el backend responde
  `400 Bad Request` con `message: "displayName cannot be blank"` — **no** lo interpreta
  como "vaciar el campo". Para no tocar `displayName`, omitir el campo del body (no
  mandar `""`). Límite `100` caracteres, sin cambios.
- **`bio`**: también se recorta server-side. A diferencia de `displayName`, mandar `bio`
  con solo espacios (o `""`) **sí** es una operación válida — el backend la normaliza y
  persiste como `null` (limpia la bio). El GET subsiguiente refleja `bio: null` (el campo
  directamente no aparece en el JSON, mismo criterio que "sin bio" en cualquier otro
  punto de la API), no una cadena vacía. Límite `500` caracteres, sin cambios.
- **Response**: `200` + `UserResponse` con los valores **ya recortados** — no asumir que
  el string devuelto es idéntico byte-a-byte al enviado si tenía espacios al borde.
- **No hay sanitización de HTML/markup** — `displayName`/`bio` son texto plano de punta a
  punta. La UI debe escapar al renderizar (React/similares ya lo hacen por default al
  interpolar texto; el riesgo real está solo si en algún punto se usa
  `dangerouslySetInnerHTML` u equivalente con estos campos, lo cual no debería hacerse).
- **Campos que NO se pueden modificar vía este endpoint**: `username`, `email`, `role`,
  `emailVerified`/`emailVerifiedAt`, `createdAt`, `id`. `avatarUrl` técnicamente acepta
  cualquier string acá, pero la vía real para subir un archivo sigue siendo
  `POST /api/users/me/avatar` (`multipart/form-data`) — sin cambios.
- **No usar `localStorage`** como fuente de verdad para el perfil editado — el mismo
  criterio que ya aplica al resto de la API: `GET /api/users/me` después del `PATCH` es
  la fuente real.

### Status directo por usuario — `GET /api/users/{userId}/status`
Nuevo endpoint: el status/mood **actual** de un usuario puntual, sin pasar por
`GET /api/statuses/feed`. Pensado para mostrarlo, por ejemplo, en la propia tarjeta de
perfil de esa persona.

- **"Sin status"**: `200 OK` con **body vacío** (no `404`, no un objeto con campos en
  `null`) — mismo patrón ya usado en `GET /api/availability/mine`. El frontend debe
  chequear que la respuesta tenga contenido antes de leer campos (`response.data` vacío/
  `null` según el cliente HTTP), no asumir que siempre viene un objeto `StatusResponse`.
- **DTO**: reusa `StatusResponse` tal cual (misma forma que en `GET /api/statuses/feed`)
  — `id`, `user` (`UserSummary`), `mood`, `createdAt`, `expiresAt`, `reactionCount`,
  `reactedByCurrentUser`. Ningún campo nuevo, ningún DTO paralelo.
- **`404 Not Found`**: `userId` no existe, o no es accesible para el usuario autenticado
  — mismas reglas que ver la `bio` completa en `GET /api/users/{userId}` (perfil
  `PRIVATE` sin ser follower aceptado, o bloqueo en cualquier dirección). El frontend no
  puede (ni debe intentar) distinguir "no existe" de "no tengo acceso" en este `404` —
  mismo criterio que el resto de la API.
- **Mute no lo afecta**: si silenciaste a `userId`, igual podés consultar su status
  directo desde su perfil con normalidad — silenciar solo saca su contenido del feed
  agregado, nunca del acceso puntual a su perfil.
- **No confundir con el feed**: `GET /api/statuses/feed` sigue siendo la superficie para
  el timeline (propios + de quienes seguís, filtrado por mute); este endpoint nuevo es
  para "quiero el status de esta persona en particular" — no reemplaza al feed ni debería
  usarse para armarlo (una llamada por usuario sería N+1 en un timeline).
- **No se agregó `currentStatus` embebido en `PublicUserProfileResponse`** — decisión
  explícita de esta fase, no un olvido (ver `BACKEND_ARCHITECTURE.md`). Si se quiere
  mostrar el status junto con el perfil, es una llamada aparte a este endpoint.

### Follow state en Profile — recordatorio
`followState` en `GET /api/users/{userId}` (y `GET /me`, siempre `"NONE"` ahí) es la
fuente de verdad de la relación — `"NONE"` (sin relación ni solicitud), `"REQUESTED"`
(solicitud pendiente, solo aplica a perfiles `PRIVATE`), `"FOLLOWING"` (relación activa,
sea perfil `PUBLIC` o `PRIVATE`). Nunca tratar `"REQUESTED"` como `"NONE"` en la UI — son
estados distintos con acciones distintas (cancelar solicitud vs. seguir). Ya estaba
implementado y probado desde Fase 9.3; esta fase solo confirmó (auditoría + tests) que
`GET /me`, `GET /{userId}` y `GET /discover` son consistentes entre sí.

## Notificaciones (Backend Debt B3)

### Listado — `GET /api/notifications`
Sin cambios de contrato en el shape general — sigue siendo `Page<NotificationResponse>`
(`page`/`size`, default `0`/`20`) con el envelope completo de Spring Data (`content`,
`totalElements`, `totalPages`, `number`, `size`, `last`). **Orden ahora estable**:
`createdAt DESC, id DESC` — si dos notificaciones se crean en el mismo instante, el
orden entre ellas es determinista y no cambia entre requests.

`NotificationResponse` tiene 3 campos nuevos (adición pura, nada se quita):
```json
{
  "id": "uuid", "actor": { /* UserSummary */ }, "type": "NEW_STATUS_REACTION",
  "postId": null, "statusId": "uuid", "followRequestId": null,
  "read": false, "createdAt": "..."
}
```
Cada `type` puebla **como máximo uno** de `postId`/`statusId`/`followRequestId` — nunca
asumir que `postId` sirve para navegar un `NEW_STATUS_REACTION` (ver tabla completa en
`API_CONTRACT.md` § 9). **Los tres pueden apuntar a un recurso ya resuelto/vencido**
(post borrado, status expirado, follow request ya `ACCEPTED`/`REJECTED`/`CANCELLED`) —
el backend nunca rompe por esto, pero el frontend debe manejar con gracia que el destino
de la navegación ya no exista o ya no acepte la acción esperada (ver "Follow request"
abajo).

### Marcar una notificación como leída — `PATCH /api/notifications/{id}/read`
Nuevo. Llamarlo cuando el usuario abre/interactúa con una notificación puntual (ej. tap
en la lista de Novedades) — no reemplaza a `PATCH /api/notifications/read-all` (marcar
todas), que sigue existiendo para el caso "marcar todo como leído".
- **Response 200**: `NotificationResponse` con `read: true`.
- **Idempotente**: llamarlo sobre una ya leída no falla, simplemente confirma `read: true`.
- **`404`**: la notificación no existe o no es tuya — mismo tratamiento para ambos casos,
  no intentar distinguirlos.
- **`unread-count` después de esto**: `GET /api/notifications/unread-count` refleja el
  nuevo valor real en la siguiente consulta (no hay que esperar ni forzar un refresh
  especial) — es una query `COUNT` real, no un contador cacheado.

### Reacción a un status — usar `statusId`, no `postId`
Al navegar desde una notificación `NEW_STATUS_REACTION`, usar el campo `statusId` para ir
al status correspondiente — **nunca** `postId` (viaja en `null` para este tipo, a
propósito: los estados son un dominio separado de los posts). Si no existe un endpoint de
detalle de status individual en el frontend todavía, `statusId` sigue siendo el
identificador correcto para cuando se implemente esa navegación.

### Follow request desde Novedades — `followRequestId`
La notificación `FOLLOW_REQUEST_RECEIVED` incluye `followRequestId` — usarlo para
ejecutar las acciones reales:
- Aceptar: `POST /api/follow-requests/{followRequestId}/accept`
- Rechazar: `POST /api/follow-requests/{followRequestId}/reject`

**No crear lógica local falsa ni un endpoint alternativo** — estos son los mismos
endpoints que ya existen para la pantalla de solicitudes entrantes (Fase 9.3), la
notificación solo aporta el id para poder actuar directo sin navegar primero a esa
pantalla. `FOLLOW_REQUEST_ACCEPTED` también incluye `followRequestId`, pero ahí es solo
contexto — no hay ninguna acción pendiente sobre un trámite ya aceptado.

**Trámite obsoleto (`followRequestId` "stale")**: si el usuario tarda en actuar sobre una
notificación vieja, el trámite pudo haberse resuelto por otra vía mientras tanto (el otro
lado lo canceló, o ya fue aceptado/rechazado desde la pantalla de solicitudes). Llamar a
`accept`/`reject` con ese `followRequestId` en ese caso devuelve `409 Conflict` — tratarlo
como "esta solicitud ya no está disponible" (refrescar el estado, no como error genérico),
nunca mantener un estado local propio de si la solicitud sigue pendiente.

### WebSocket — mismo canal, mismo DTO
Sin cambios de canal (`/user/queue/notifications`) ni de mecanismo. El payload que llega
por WebSocket es el **mismo** `NotificationResponse` que devuelve `GET /api/notifications`
(incluye `statusId`/`followRequestId` también) — no hay dos formas distintas de la misma
notificación según el canal.

**Notificaciones nuevas durante paginación** (con paginación por offset, no cursor):
- Al recibir una notificación nueva por WS, hacer **prepend en memoria** a la lista ya
  cargada — no volver a pedir `page=0` automáticamente (evita perder la posición de
  scroll del usuario).
- **Deduplicar siempre por `id`** al combinar el prepend con lo que ya está en memoria, y
  también al cargar páginas siguientes (`page=1`, `page=2`, ...) — los offsets pueden
  desplazarse levemente si llegaron notificaciones nuevas entre una carga y la siguiente.
- Un refresh/reapertura de la pantalla puede simplemente volver a pedir `page=0` desde
  cero — **no tratar las páginas ya cargadas como un snapshot inmutable**.
- El backend no implementa cursor pagination ni tokens de snapshot en esta fase — es una
  decisión explícita (`Page`/`Pageable` estándar, ya usado en toda la API), no una
  limitación a rodear con lógica compleja del lado del cliente.

### Unread count + WebSocket
No hace falta un canal separado para el contador de no leídas. Ante una notificación
nueva por WS, el frontend puede **incrementar localmente** el badge, o simplemente
volver a pedir `GET /api/notifications/unread-count` — cualquiera de las dos es válida,
el backend no empuja el count por WS.

### Mute y Block — recordatorio
- **Mute no silencia notificaciones** (decisión explícita, reconfirmada en esta fase) —
  seguís recibiendo notificaciones de alguien que silenciaste, exactamente igual que si
  no lo hubieras silenciado. No confundir "silenciar a un usuario" con "silenciar sus
  notificaciones" (esto último no existe todavía, ver fuera de alcance).
- **Block ya suprime notificaciones nuevas entre las dos partes** (Fase 9.4, sin
  cambios) — notificaciones históricas previas al bloqueo no se borran.

### Fuera de alcance de esta fase (no implementar todavía en el frontend)
Preferencias de notificación, mute de notificaciones específicamente, push notifications
mobile, preferencias de notificación por email, borrar/archivar notificaciones
individuales, agrupamiento de notificaciones.

## Endpoints disponibles

Ver `API_CONTRACT.md` para el detalle completo (request/response/reglas/errores).
Resumen de superficie por dominio:

| Dominio | Base path | Notas rápidas |
|---|---|---|
| Auth | `/api/auth` | público (register/login/verify-email/resend-verification/forgot-password/reset-password/refresh/logout), salvo `change-password` que requiere JWT |
| Users | `/api/users` | perfil propio/ajeno, discover, avatar, privacidad de perfil, status directo (`/{userId}/status`) |
| Posts | `/api/posts` | CRUD + feed + respuestas tipadas (`/response`, `/support` legacy) + privacidad de post |
| Comments | `/api/posts/{postId}/comments` | anidado bajo post |
| Follows | `/api/follows` | seguir/dejar de seguir (inmediato o solicitud según privacidad), listas, eliminar seguidor |
| Follow Requests | `/api/follow-requests` | aceptar/rechazar/cancelar solicitudes, incoming/outgoing |
| Blocking | `/api/users/{userId}/block`, `/api/users/me/blocked` | bloquear/desbloquear, lista de bloqueados propios |
| Muting | `/api/users/{userId}/mute`, `/api/users/me/muted` | silenciar/dejar de silenciar (unilateral), lista de silenciados propios |
| Statuses | `/api/statuses` | "estado de ánimo" efímero (24h) + reacciones |
| Availability | `/api/availability` | "modo compañía" efímero (6h) — **legacy/deprecated** (Backend Debt B4B.3: adapter delgado sobre Companion Offering, mapping lossy). NO desarrollar pantallas nuevas contra este endpoint |
| Companion Need | `/api/companion/need` | "necesito compañía ahora" efímero (2h) — Backend Debt B4B.1. Nunca público, solo `/mine` |
| Companion Offering | `/api/companion/offering` | "cómo puedo acompañar ahora" efímero (6h) — Backend Debt B4B.2/B4B.3. CRUD + búsqueda por tipo + `/compatible`. **Fuente de verdad real** — `/api/availability` es solo un espejo legacy de esto |
| Public Availability | `/api/users/{userId}/availability` | Backend Debt B4B.4 — disponibilidad puntual de un usuario para tarjeta/perfil. `ProfileVisibility`/`FollowState` NO gatean; solo bloqueo bilateral corta el acceso (404) |
| Chat | `/api/conversations` | conversaciones 1:1, mensajes paginados |
| Notifications | `/api/notifications` | in-app, generadas internamente, mark-one (`/{id}/read`) + read-all + unread-count |
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
  tengo permiso" en esos casos, el backend los unifica a propósito. Mismo criterio se
  extiende (Fase 9.1) a un post cuyo autor tiene el perfil en `PRIVATE`.
  **Excepción deliberada**: el perfil de un usuario (`GET /api/users/{userId}`) y sus
  posts (`GET /api/users/{userId}/posts`) **nunca** devuelven 404 solo por privacidad —
  responden `200` con datos limitados o una lista vacía, respectivamente (ver §
  Privacidad de perfil arriba). El 404 en esos dos endpoints significa "el usuario no
  existe" **o** "ese usuario te bloqueó a vos" (Fase 9.4, ver § Bloqueo de usuarios) —
  ambos casos indistinguibles a propósito.
- **PATCH parciales**: en `PATCH /api/users/me`, `PATCH /api/posts/{id}` los campos
  omitidos (`null`) se interpretan como "no tocar", no como "vaciar". Ningún campo se
  vacía enviando `null` explícito. **`bio` es la excepción vía blank, no vía `null`**
  (Backend Debt B2): mandar `bio: "   "` (no-null, solo espacios) sí la limpia — el
  backend la normaliza a `null` internamente. `displayName` **no** tiene una vía de
  vaciado — un valor no-null que quede en blanco tras `trim()` es `400 Bad Request`, no
  una operación de "vaciar". Ver § "Status directo + edición de perfil" arriba.

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
- `409` en `POST /api/posts/{id}/support` **legacy** (ya habías respondido — no aplica a
  `PUT /api/posts/{id}/response`, que es idempotente y nunca da `409`), `POST
  /api/follows/{id}` (ya seguías), `POST /api/reports/{id}/resolve` (ya resuelto) →
  tratar como estado ya alcanzado, no como error duro (refrescar el estado local en vez
  de mostrar un toast de error genérico).
- `400` en `PUT/POST /api/posts/{id}/response` y `.../support` si sos el autor del post
  (Backend Debt B1) → no debería llegar a pasar si el frontend ya oculta las pills de
  respuesta en posts propios, pero si se intenta, tratar como error de validación normal.
- `GET /api/availability/mine` puede devolver body `null` con status `200` — **no
  asumir que siempre hay objeto**, chequear explícitamente antes de leer sus campos.
  **`GET /api/users/{userId}/status` (Backend Debt B2), `GET /api/companion/need/mine`
  (Backend Debt B4B.1) y `GET /api/companion/offering/mine` (Backend Debt B4B.2) tienen
  el mismo comportamiento** cuando no hay dato activo. `GET
  /api/companion/offering/compatible` sin Need activo devuelve `200` con **lista vacía**
  (`[]`), no `null` — es una lista, no un recurso singular.
- `400` en `PATCH /api/users/me` con `displayName` en blanco (solo espacios o `""`)
  (Backend Debt B2) → `message: "displayName cannot be blank"` — mostrar como error de
  formulario, no como error genérico; a diferencia de `bio`, este campo no se puede
  vaciar por esta vía (ver § "Status directo + edición de perfil" arriba).

## Limitaciones actuales conocidas (del backend, verificadas en código)

- El access JWT ya emitido puede seguir funcionando hasta 15 minutos después de un
  `logout`, un cambio/reset de contraseña, o un reuse de refresh token detectado — no
  hay blacklist de access tokens (ver `BACKEND_ARCHITECTURE.md` § Sesiones). El
  `refreshToken` sí queda inutilizado de inmediato en los cuatro casos.
- El `refreshToken` viaja como JSON en el body de `POST /api/auth/refresh` y
  `POST /api/auth/logout` (no como cookie). El almacenamiento actual del lado frontend
  es temporal en `localStorage`; una migración a cookie `HttpOnly`/`Secure`/`SameSite`
  queda pendiente para una fase posterior, todavía no confirmada.
- No hay pantalla de gestión de sesiones/dispositivos (listar o cerrar sesiones activas
  individualmente desde la cuenta) — el único cierre de sesión disponible hoy es
  `logout` (la propia sesión) o la revocación total implícita de
  `change-password`/`reset-password` (todas las sesiones a la vez).
- No hay cleanup periódico de `auth_sessions` — las filas rotadas/revocadas/expiradas se
  acumulan en la base sin borrarse (ver `BACKEND_ARCHITECTURE.md` § Sesiones).
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

- **Modo compañía — `/api/availability` es LEGACY desde Backend Debt B4B.3**: sigue
  funcionando exactamente igual a nivel de contrato (declarar disponibilidad, listar por
  intent, desbloquear chat sin follow previo), pero es un adapter sobre Companion
  Offering — sin backing store propio. **No desarrollar pantallas nuevas contra este
  endpoint.** Si algo del frontend actual ya lo usa, puede seguir usándolo sin cambios
  (mismo path, misma auth, mismo shape de response) — pero **nuevas pantallas van contra
  `/api/companion/offering`**.
- **Companion Need + Offering — flujo COMPLETO y coherente end-to-end desde Backend Debt
  B4B.3** (antes, en B4B.2, el paso 4 de abajo todavía no funcionaba de punta a punta —
  eso ya se resolvió):
  1. `PUT /api/companion/need` — "Necesito compañía" → elegís `NeedType`
     (`LISTEN_TO_ME`/`TALK`/`GET_OPINION`/`DISTRACTION`/`JUST_COMPANY`). Nunca público.
  2. `PUT /api/companion/offering` — "Estoy disponible" → elegís `OfferingType`
     (`LISTEN`/`TALK`/`DISTRACT`).
  3. `GET /api/companion/offering/compatible` — usa tu Need activo internamente (nunca lo
     devuelve) y trae hasta 10 candidatos compatibles, en orden aleatorio:
     ```json
     [{ "user": { "id": "...", "username": "...", "displayName": "...", "avatarUrl": "..." },
        "offeringType": "LISTEN", "expiresAt": "..." }]
     ```
     Sin Need activo → `200` con **lista vacía** (nunca `404`).
  4. El usuario elige un candidato de la lista → `POST /api/conversations/{userId}` (el
     mismo endpoint de siempre). **Ahora sí queda coherente**: `ChatService` valida contra
     `CompanionOffering` (la misma tabla que respaldó la búsqueda del paso 3), así que
     cualquier candidato devuelto por `/compatible` o por la búsqueda por tipo **siempre**
     puede recibir el `POST` exitosamente (salvo que su Offering haya expirado justo
     entre la búsqueda y el intento de chat, o que exista un bloqueo — casos de carrera
     normales, no un bug de integración).
  - `GET /api/companion/offering?type=LISTEN` (búsqueda directa por tipo, sin pasar por
    Need) usa el mismo `CompanionCandidateResponse` de arriba.
  - **Perfil `PRIVATE` sin follow SÍ puede aparecer como candidato** si tiene un Offering
    activo — el Offering es consentimiento específico para esa superficie, nunca
    equivale a un accepted follower ni desbloquea bio/posts/status/perfil completo. Esto
    también aplica al `POST /api/conversations/{userId}` del paso 4: un stranger
    `PRIVATE` con Offering activa desbloquea el chat igual.
  - **El `Need` propio nunca autoriza el chat** — solo importa si el *target* tiene una
    Offering activa (de cualquier tipo). Tener un Need compatible no alcanza si el target
    no tiene ninguna Offering.
  - `CompanionNeed` sigue sin frontend propio más allá de declarar el Need (paso 1) — no
    hay pantalla de "ver mi Need" separada de la de declararlo.
- **Public Availability — para tarjeta/perfil** (Backend Debt B4B.4): cuando quieras
  mostrar "¿esta persona está disponible ahora?" en una tarjeta de usuario o en su perfil,
  usá `GET /api/users/{userId}/availability`. Interpretación de la respuesta:
  - `200` + objeto → disponible ahora. Campos: `available` (siempre `true` cuando el
    objeto existe), `offeringType` (`"LISTEN" | "TALK" | "DISTRACT"`, singular — a lo sumo
    una Offering activa por usuario), `expiresAt`.
  - `200` + body `null` → no disponible ahora mismo (ausencia genuina de dato, no error).
  - `404` → recurso inaccesible (no existe, o hay un bloqueo en cualquier dirección) — no
    mostrar nada, mismo tratamiento que cualquier otro `404` de la API.
  - **NO inferir disponibilidad** desde `Status` (`/status` es "estado de ánimo", dominio
    totalmente distinto), desde el perfil (`PublicUserProfileResponse` no tiene ningún
    campo de disponibilidad), desde `companionPreferences` (futuro, son preferencias
    estables, no el momento actual), ni desde `followState`/cantidad de followers.
  - **Perfil `PRIVATE`**: la disponibilidad puede ser visible (`200` + objeto) **incluso
    si el perfil completo sigue limitado** (`bio: null`, sin posts) — son dos gates
    completamente independientes a propósito. No asumas que un `404` en el perfil implica
    `404` en disponibilidad, ni viceversa (aunque en la práctica ambos suelen coincidir
    salvo por esta excepción deliberada).
- **Reacciones a estados de ánimo** (`POST/DELETE /api/statuses/{id}/react`, 4 tipos:
  `WITH_YOU`, `WANT_TO_TALK`, `HERE_READING`, `NOT_ALONE` — enum `StatusReactionType`) —
  es una interacción social **distinta** de las respuestas a un post (`PostResponseType`,
  ver § "Respuestas a un post" arriba), con su propio contador y su propio tipo de
  notificación (`NEW_STATUS_REACTION`). Ambos enums comparten un par de etiquetas
  (`WITH_YOU`/`NOT_ALONE`) por coincidencia de vocabulario — nunca son el mismo valor ni
  se convierten entre sí. Fácil de confundir/fusionar si no se los trata como features
  separadas.
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
