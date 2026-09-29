package com.byyourside.backend.companion;

// Backend Debt B4B.5: enum INDEPENDIENTE de OfferingType/NeedType/
// CompanionIntent -- mismo vocabulario por coincidencia (igual que
// PostResponseType/StatusReactionType), nunca se comparan ni convierten
// entre si. El orden de declaracion (LISTEN, TALK, DISTRACT) es tambien el
// orden determinista de respuesta de la API -- ver CompanionPreferenceService.
public enum CompanionPreferenceType {
    LISTEN,
    TALK,
    DISTRACT
}
