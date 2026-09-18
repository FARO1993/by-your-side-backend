CREATE TABLE conversations (
                               id UUID PRIMARY KEY,
                               user_a_id UUID NOT NULL REFERENCES users(id),
                               user_b_id UUID NOT NULL REFERENCES users(id),
                               last_message_content TEXT,
                               last_message_at TIMESTAMP,
                               created_at TIMESTAMP NOT NULL,
                               UNIQUE (user_a_id, user_b_id)
);

CREATE TABLE messages (
                          id UUID PRIMARY KEY,
                          conversation_id UUID NOT NULL REFERENCES conversations(id),
                          sender_id UUID NOT NULL REFERENCES users(id),
                          content TEXT NOT NULL,
                          read BOOLEAN NOT NULL DEFAULT FALSE,
                          created_at TIMESTAMP NOT NULL
);