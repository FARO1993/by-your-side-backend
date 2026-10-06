package com.byyourside.backend.user;

import java.util.Set;

/**
 * Avatares ilustrados de ByYourSide. En una red de salud mental no se suben
 * fotos: identifican a la persona, pueden ser inapropiadas y hay que
 * moderarlas. Cada id corresponde a un dibujo del frontend
 * (src/lib/avatars.ts); agregar uno nuevo es sumarlo en los dos lados.
 * Son motivos de naturaleza, sin caras: nadie tiene que elegir un aspecto
 * que lo represente (o que no).
 */
public final class AvatarCatalog {

    public static final Set<String> IDS = Set.of(
            "hoja", "luna", "sol", "ola", "montana", "flor",
            "nube", "estrella", "arbol", "gota", "piedras", "pluma",
            "caracola", "hongo", "cactus", "arcoiris", "brote", "faro"
    );

    private AvatarCatalog() {
    }

    public static boolean isValid(String id) {
        return id != null && IDS.contains(id);
    }
}
