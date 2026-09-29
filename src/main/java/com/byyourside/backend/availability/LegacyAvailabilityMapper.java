package com.byyourside.backend.availability;

import com.byyourside.backend.companion.OfferingType;

// Backend Debt B4B.3: unico punto de traduccion entre el contrato legacy
// (CompanionIntent) y el dominio nuevo (OfferingType). Nunca disperso en
// controller/service -- toda la logica de mapping vive aca.
//
// CompanionIntent -> OfferingType: TALK->TALK, DISTRACTION/WATCH_TOGETHER/
// MUSIC/LAUGH->DISTRACT, JUST_COMPANY->LISTEN.
//
// OfferingType -> CompanionIntent (deliberadamente LOSSY, decision B4A):
// TALK->TALK, LISTEN->JUST_COMPANY, DISTRACT->DISTRACTION. Si antes se
// escribio MUSIC/WATCH_TOGETHER/LAUGH via la ruta legacy, leer de vuelta
// (GET /mine o listado) SIEMPRE devuelve DISTRACTION -- el matiz original
// se pierde porque OfferingType no lo modela (solo tiene 3 valores, no 6).
// Este es el comportamiento ESPERADO del adapter, no un bug: no se guarda
// metadata adicional para "recordar" el intent original porque eso
// recrearia una segunda fuente de verdad (justo lo que B4B.3 elimina).
public final class LegacyAvailabilityMapper {

    private LegacyAvailabilityMapper() {
    }

    public static OfferingType toOfferingType(CompanionIntent intent) {
        return switch (intent) {
            case TALK -> OfferingType.TALK;
            case DISTRACTION, WATCH_TOGETHER, MUSIC, LAUGH -> OfferingType.DISTRACT;
            case JUST_COMPANY -> OfferingType.LISTEN;
        };
    }

    public static CompanionIntent toCompanionIntent(OfferingType type) {
        return switch (type) {
            case TALK -> CompanionIntent.TALK;
            case LISTEN -> CompanionIntent.JUST_COMPANY;
            case DISTRACT -> CompanionIntent.DISTRACTION;
        };
    }
}
