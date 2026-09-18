package com.byyourside.backend.status;

import java.util.UUID;

public interface StatusReactionCountProjection {
    UUID getStatusId();
    long getReactionCount();
}