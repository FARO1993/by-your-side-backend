package com.byyourside.backend.postresponse.dto;

import java.util.UUID;

// Respuesta comun a los 4 endpoints que mutan una PostResponse (los 2
// nuevos -- PUT/DELETE .../response -- y los 2 legacy -- POST/DELETE
// .../support), ya que los cuatro operan sobre la misma fila y todos
// necesitan reflejar el estado resultante. `type` viaja en null despues de
// un DELETE (o si nunca hubo respuesta). `supportCount`/
// `supportedByCurrentUser` son el campo LEGACY (Backend Debt B1 § 12,
// opcion A) -- se mantienen por compatibilidad con quien ya consume
// SupportSummaryResponse, derivados de los mismos datos
// (supportCount = presenceCount + listeningCount, siempre 0 o 1 por ser un
// solo post; supportedByCurrentUser = type != null). Nunca dos fuentes de
// verdad: ambos pares de campos salen del mismo query de conteo.
public record PostResponseSummaryResponse(
        UUID postId,
        String type,
        long presenceCount,
        long listeningCount,
        long supportCount,
        boolean supportedByCurrentUser
) {
}
