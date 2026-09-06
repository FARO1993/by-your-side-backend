# by-your-side-backend

**ByYourSide** — *You don't have to face it alone.*

Backend de una red social de apoyo para personas con depresión y otros
problemas de salud mental. Monolito modular en Spring Boot (Java 21),
organizado por dominio.

## Estructura de paquetes

```
com.byyourside.backend
├── user        User, UserRole, UserStatus, UserRepository
├── post        Post, PostVisibility, PostStatus, PostRepository
├── comment     Comment, CommentStatus, CommentRepository
├── follow      Follow, FollowRepository
├── report      Report, ReportReason, ReportStatus, ReportTargetType, ReportRepository
└── config      SecurityConfig
```

Cada paquete es un módulo de dominio autocontenido (entidad + enums +
repositorio). A medida que se agreguen servicios y controladores, van en
el mismo paquete del dominio al que pertenecen.

## Cómo levantar el proyecto

```bash
docker compose up --build
```

Esto levanta:
- `postgres` en el puerto `5432`
- `backend` (Spring Boot) en el puerto `8080`

## Pendientes conocidos (a propósito, fuera del scope del primer commit)

- Autenticación real con JWT (`SecurityConfig` hoy permite todo, es un placeholder)
- Controladores y servicios (`UserService`, `PostService`, etc.)
- Migraciones versionadas con Flyway en vez de `ddl-auto: update` (recomendado antes de tener datos reales)
- Endpoint y flujo de derivación a recursos de ayuda para reportes `SELF_HARM_RISK`
