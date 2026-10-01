package com.byyourside.backend.companion;

import java.util.Map;

// Backend Debt B4B.2: matriz ESTATICA de compatibilidad Need -> Offering
// (decision B4A #4/#12, aprobada tal cual) -- un simple lookup tipo-a-tipo
// en codigo, NUNCA en DB. Explicitamente NO es matching inteligente: sin
// scoring, sin pesos, sin heuristica, sin IA, sin persistencia. JUST_COMPANY
// usa LISTEN como compatibilidad inicial a falta de un OfferingType
// equivalente a "solo hacerme compañia" (decision B4A #2) -- se revisara
// con evidencia de producto mas adelante, no antes.
public final class CompanionCompatibility {

    private static final Map<NeedType, OfferingType> MATRIX = Map.of(
            NeedType.LISTEN_TO_ME, OfferingType.LISTEN,
            NeedType.TALK, OfferingType.TALK,
            NeedType.GET_OPINION, OfferingType.TALK,
            NeedType.DISTRACTION, OfferingType.DISTRACT,
            NeedType.JUST_COMPANY, OfferingType.LISTEN
    );

    private CompanionCompatibility() {
    }

    public static OfferingType compatibleOfferingFor(NeedType needType) {
        return MATRIX.get(needType);
    }
}
