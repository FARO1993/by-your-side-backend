CREATE TABLE availabilities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    intent VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL
);