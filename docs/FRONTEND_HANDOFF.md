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

## Endpoints disponibles

Ver `API_CONTRACT.md` para el detalle completo (request/response/reglas/errores).
Resumen de superficie por dominio:

| Dominio | Base path | Notas rápidas |
|---|---|---|
| Auth | `/api/auth` | público (register/login/verify-email/resend-verification/forgot-password/reset-password/refresh/logout), salvo `change-password` que requiere JWT |
| Users | `/api/users` | perfil propio/ajeno, discover, avatar, privacidad de perfil |
| Posts | `/api/posts` | CRUD + feed + apoyo ("like") + privacidad de post |
| Comments | `/api/posts/{postId}/comments` | anidado bajo post |
| Follows | `/api/follows` | seguir/dejar de seguir (inmediato o solicitud según privacidad), listas, eliminar seguidor |
| Follow Requests | `/api/follow-requests` | aceptar/rechazar/cancelar solicitudes, incoming/outgoing |
| Blocking | `/api/users/{userId}/block`, `/api/users/me/blocked` | bloquear/desbloquear, lista de bloqueados propios |
| Muting | `/api/users/{userId}/mute`, `/api/users/me/muted` | silenciar/dejar de silenciar (unilateral), lista de silenciados propios |
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
  tengo permiso" en esos casos, el backend los unifica a propósito. Mismo criterio se
  extiende (Fase 9.1) a un post cuyo autor tiene el perfil en `PRIVATE`.
  **Excepción deliberada**: el perfil de un usuario (`GET /api/users/{userId}`) y sus
  posts (`GET /api/users/{userId}/posts`) **nunca** devuelven 404 solo por privacidad —
  responden `200` con datos limitados o una lista vacía, respectivamente (ver §
  Privacidad de perfil arriba). El 404 en esos dos endpoints significa "el usuario no
  existe" **o** "ese usuario te bloqueó a vos" (Fase 9.4, ver § Bloqueo de usuarios) —
  ambos casos indistinguibles a propósito.
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
