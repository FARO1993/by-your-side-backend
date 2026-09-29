package com.byyourside.backend.availability;

// Backend Debt B4B.3: LEGACY API CONTRACT ENUM -- ya no es un enum de
// dominio (companion_offerings/OfferingType es el dominio real desde
// B4B.2). Usado UNICAMENTE por el contrato historico de
// /api/availability/**: SetAvailabilityRequest, AvailabilityResponse, y
// AvailabilityController (que lo traduce a/desde OfferingType via
// LegacyAvailabilityMapper). Nunca debe usarse dentro de CompanionOffering,
// CompanionOfferingService, ChatService, ni ningun repositorio nuevo.
public enum CompanionIntent {
    TALK,
    DISTRACTION,
    WATCH_TOGETHER,
    MUSIC,
    LAUGH,
    JUST_COMPANY
}
