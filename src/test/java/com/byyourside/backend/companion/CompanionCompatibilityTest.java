package com.byyourside.backend.companion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Test unitario puro (sin contexto de Spring) de los 5 mappings aprobados
// en la decision B4A #4/#12 -- llama solo al metodo publico, sin depender
// de como esta implementada la matriz por dentro.
class CompanionCompatibilityTest {

    @Test
    void listenToMe_isCompatibleWith_listen() {
        assertEquals(OfferingType.LISTEN, CompanionCompatibility.compatibleOfferingFor(NeedType.LISTEN_TO_ME));
    }

    @Test
    void talk_isCompatibleWith_talk() {
        assertEquals(OfferingType.TALK, CompanionCompatibility.compatibleOfferingFor(NeedType.TALK));
    }

    @Test
    void getOpinion_isCompatibleWith_talk() {
        assertEquals(OfferingType.TALK, CompanionCompatibility.compatibleOfferingFor(NeedType.GET_OPINION));
    }

    @Test
    void distraction_isCompatibleWith_distract() {
        assertEquals(OfferingType.DISTRACT, CompanionCompatibility.compatibleOfferingFor(NeedType.DISTRACTION));
    }

    @Test
    void justCompany_isCompatibleWith_listen() {
        assertEquals(OfferingType.LISTEN, CompanionCompatibility.compatibleOfferingFor(NeedType.JUST_COMPANY));
    }
}
