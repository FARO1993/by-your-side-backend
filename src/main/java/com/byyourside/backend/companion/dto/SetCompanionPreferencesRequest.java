package com.byyourside.backend.companion.dto;

import com.byyourside.backend.companion.CompanionPreferenceType;
import jakarta.validation.constraints.NotNull;

import java.util.List;

// Backend Debt B4B.5: PATCH siempre reemplaza el set COMPLETO -- nunca
// add/remove incremental. `types` no puede ser null (null no significa
// "no tocar" aca, a diferencia de PATCH /api/users/me) -- pero SI puede
// ser una lista vacia, que significa "borrar todas mis preferences".
// Duplicados del cliente se normalizan a Set en el service, no fallan
// aca. Un valor de enum invalido en el JSON falla antes de llegar a este
// record (400 por deserializacion de Jackson, mismo criterio que
// NeedType/OfferingType).
public record SetCompanionPreferencesRequest(
        @NotNull
        List<CompanionPreferenceType> types
) {
}
