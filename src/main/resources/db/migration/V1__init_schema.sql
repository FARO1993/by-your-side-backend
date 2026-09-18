CREATE TABLE users (
                       id UUID PRIMARY KEY,
                       username VARCHAR(50) NOT NULL UNIQUE,
                       email VARCHAR(255) NOT NULL UNIQUE,
                       password_hash VARCHAR(255) NOT NULL,
                       display_name VARCHAR(255),
                       bio TEXT,
                       avatar_url VARCHAR(500),
                       role VARCHAR(20) NOT NULL,
                       status VARCHAR(20) NOT NULL,
                       created_at TIMESTAMP NOT NULL,
                       updated_at TIMESTAMP NOT NULL
);

CREATE TABLE posts (
                       id UUID PRIMARY KEY,
                       author_id UUID NOT NULL REFERENCES users(id),
                       content TEXT NOT NULL,
                       visibility VARCHAR(20) NOT NULL,
                       status VARCHAR(20) NOT NULL,
                       created_at TIMESTAMP NOT NULL,
                       updated_at TIMESTAMP NOT NULL
);

CREATE TABLE comments (
                          id UUID PRIMARY KEY,
                          post_id UUID NOT NULL REFERENCES posts(id),
                          author_id UUID NOT NULL REFERENCES users(id),
                          content TEXT NOT NULL,
                          status VARCHAR(20) NOT NULL,
                          created_at TIMESTAMP NOT NULL
);

CREATE TABLE follows (
                         id UUID PRIMARY KEY,
                         follower_id UUID NOT NULL REFERENCES users(id),
                         following_id UUID NOT NULL REFERENCES users(id),
                         created_at TIMESTAMP NOT NULL,
                         UNIQUE (follower_id, following_id)
);

CREATE TABLE reports (
                         id UUID PRIMARY KEY,
                         reporter_id UUID NOT NULL REFERENCES users(id),
                         target_type VARCHAR(20) NOT NULL,
                         target_id UUID NOT NULL,
                         reason VARCHAR(30) NOT NULL,
                         description TEXT,
                         status VARCHAR(20) NOT NULL,
                         reviewed_by_id UUID REFERENCES users(id),
                         created_at TIMESTAMP NOT NULL,
                         reviewed_at TIMESTAMP
);

CREATE TABLE post_supports (
                               id UUID PRIMARY KEY,
                               post_id UUID NOT NULL REFERENCES posts(id),
                               user_id UUID NOT NULL REFERENCES users(id),
                               created_at TIMESTAMP NOT NULL,
                               UNIQUE (post_id, user_id)
);

-- "type" es VARCHAR sin CHECK constraint a proposito: la validez del valor
-- ya la garantiza el enum de Java (@Enumerated(STRING)). Un CHECK ademas del
-- enum es lo que rompio con NEW_STATUS_REACTION -- Hibernate nunca lo pudo
-- actualizar solo cuando el enum creció.
CREATE TABLE notifications (
                               id UUID PRIMARY KEY,
                               recipient_id UUID NOT NULL REFERENCES users(id),
                               actor_id UUID NOT NULL REFERENCES users(id),
                               type VARCHAR(30) NOT NULL,
                               post_id UUID,
                               read BOOLEAN NOT NULL DEFAULT FALSE,
                               created_at TIMESTAMP NOT NULL
);

CREATE TABLE statuses (
                          id UUID PRIMARY KEY,
                          user_id UUID NOT NULL REFERENCES users(id),
                          mood VARCHAR(20) NOT NULL,
                          created_at TIMESTAMP NOT NULL,
                          expires_at TIMESTAMP NOT NULL
);

CREATE TABLE status_reactions (
                                  id UUID PRIMARY KEY,
                                  status_id UUID NOT NULL REFERENCES statuses(id),
                                  actor_id UUID NOT NULL REFERENCES users(id),
                                  type VARCHAR(20) NOT NULL,
                                  created_at TIMESTAMP NOT NULL,
                                  UNIQUE (status_id, actor_id)
);