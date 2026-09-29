package com.byyourside.backend.companion.dto;

import java.util.List;

// Backend Debt B4B.5: respuesta de GET/PATCH .../companion-preferences.
// `types` viaja en orden determinista (orden de declaracion del enum
// CompanionPreferenceType: LISTEN, TALK, DISTRACT), nunca el orden fisico
// de la DB. Lista vacia (nunca null) cuando el usuario no tiene ninguna
// preference -- a diferencia de PublicUserProfileResponse.companionPreferences,
// que SI usa null quando el perfil esta limitado (ver ahi la diferencia
// semantica exacta).
public record CompanionPreferencesResponse(
        List<String> types
) {
}
