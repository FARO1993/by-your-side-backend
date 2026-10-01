package com.byyourside.backend.postresponse;

// Dominio independiente de StatusReactionType a proposito -- posts y
// statuses son conceptos distintos aunque algunas etiquetas se parezcan
// (WITH_YOU/NOT_ALONE existen en ambos enums, con el mismo significado
// conceptual, pero como valores separados: nunca se comparan ni convierten
// entre si, y cada uno puede evolucionar sin arrastrar al otro).
//
// Categoria (PRESENCE/LISTENING) se deriva de este enum en vez de
// persistirse aparte -- ver isPresence()/isListening() y
// PostResponseRepository, que agrupa por estos mismos tres+tres valores en
// la query de conteo. Evita redundancia en DB (Backend Debt B1 § 3).
public enum PostResponseType {
    WITH_YOU,
    NOT_ALONE,
    HUG,
    READING,
    TELL_ME_MORE,
    LISTENING;

    public boolean isPresence() {
        return this == WITH_YOU || this == NOT_ALONE || this == HUG;
    }

    public boolean isListening() {
        return !isPresence();
    }
}
