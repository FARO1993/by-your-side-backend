package com.byyourside.backend.companion;

import com.byyourside.backend.companion.dto.CompanionPreferencesResponse;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompanionPreferenceService {

    private final CompanionPreferenceRepository companionPreferenceRepository;
    private final UserRepository userRepository;

    public CompanionPreferencesResponse getMine(UserPrincipal principal) {
        return toResponse(companionPreferenceRepository.findTypesByUserId(principal.getId()));
    }

    // Backend Debt B4B.5: PATCH reemplaza el set COMPLETO -- nunca add/remove
    // incremental. Duplicados del cliente se normalizan via EnumSet (que
    // ademas nos da el orden determinista de respuesta gratis).
    //
    // Concurrencia -- analisis explicito (no se copio el patron
    // REQUIRES_NEW/retry de CompanionNeedWriter/CompanionOfferingWriter a
    // proposito, porque resuelve un problema DISTINTO): aca no hay
    // UNIQUE(user_id) solo -- un usuario puede tener 0-3 filas, asi que dos
    // PATCH concurrentes con delete+insert corren el riesgo real de
    // INTERCALARSE sin violar ningun constraint (UNIQUE(user_id, type) no
    // lo evita), dejando como resultado commiteado una MEZCLA de ambos
    // sets en vez de reemplazar por completo con uno de los dos (ej.: PATCH
    // A=[LISTEN,TALK] y PATCH B=[DISTRACT] concurrentes podrian terminar en
    // [LISTEN,TALK,DISTRACT], que no es lo que pidio NINGUNA de las dos
    // requests). Last-writer-wins es aceptable (la semantica es "reemplazar
    // el set"), pero un resultado mezclado nunca lo es.
    //
    // Proteccion elegida: lock pesimista sobre la fila de `users` del
    // propio usuario (SELECT ... FOR UPDATE via
    // UserRepository.findByIdForUpdate) antes de tocar companion_preferences.
    // Esto serializa cualquier par de PATCH concurrentes del mismo
    // usuario -- el segundo espera a que el primero COMMITEE antes de
    // empezar su propio delete+insert, así que nunca hay dos operaciones de
    // reemplazo corriendo a la vez sobre las mismas filas. No hace falta
    // REQUIRES_NEW ni reintento: no hay ninguna excepcion de integridad que
    // capturar, el lock evita la carrera en el origen, no la resuelve
    // despues de que ya ocurrio.
    @Transactional
    public CompanionPreferencesResponse replacePreferences(UserPrincipal principal, List<CompanionPreferenceType> requestedTypes) {
        User user = userRepository.findByIdForUpdate(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Set<CompanionPreferenceType> normalized = EnumSet.noneOf(CompanionPreferenceType.class);
        normalized.addAll(requestedTypes);

        // Mismo motivo que CompanionNeedWriter/CompanionOfferingWriter: el
        // orden de flush por defecto de Hibernate ejecuta inserts antes que
        // deletes dentro de un mismo flush -- acá no violaría ningún UNIQUE
        // (los types nuevos podrían no solaparse con los viejos), pero
        // igual flusheamos el delete primero para que el estado sea
        // predecible y nunca dependa del orden interno de Hibernate.
        companionPreferenceRepository.deleteByUserId(user.getId());
        companionPreferenceRepository.flush();

        if (!normalized.isEmpty()) {
            List<CompanionPreference> rows = normalized.stream()
                    .map(type -> CompanionPreference.builder().user(user).type(type).build())
                    .toList();
            companionPreferenceRepository.saveAll(rows);
        }

        return toResponse(normalized);
    }

    // Backend Debt B4B.5: usado por UserService.getPublicProfile -- SOLO
    // cuando el perfil completo ya es visible (nunca se llama para un
    // perfil limitado, para no hacer una query cuyo resultado se
    // descartaria).
    public List<String> findOrderedTypesForFullProfile(UUID userId) {
        return toOrderedList(companionPreferenceRepository.findTypesByUserId(userId));
    }

    private CompanionPreferencesResponse toResponse(Set<CompanionPreferenceType> types) {
        return new CompanionPreferencesResponse(toOrderedList(types));
    }

    // Orden determinista = orden de declaracion del enum (LISTEN, TALK,
    // DISTRACT), nunca el orden fisico/de insercion en la DB -- EnumSet ya
    // itera en ese orden nativamente.
    private List<String> toOrderedList(Set<CompanionPreferenceType> types) {
        EnumSet<CompanionPreferenceType> ordered = EnumSet.noneOf(CompanionPreferenceType.class);
        ordered.addAll(types);
        return ordered.stream().map(Enum::name).toList();
    }
}
