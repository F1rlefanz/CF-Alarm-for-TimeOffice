package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import android.service.notification.ZenPolicy

/**
 * Reine Policy->ZenPolicy-Intent-Abbildung, unabhaengig vom [ZenPolicy.Builder] selbst - testbar
 * ohne Robolectric (die referenzierten ZenPolicy.PEOPLE_TYPE_* und CONVERSATION_SENDERS_*-Konstanten
 * sind einfache statische Int-Werte, die auch im reinen JVM-Unit-Test-Stub aufloesbar sind; nur
 * echte Methodenaufrufe auf Android-Klassen brauchen Mocking/Robolectric). [DndScheduleUseCase]
 * wendet das Ergebnis auf den echten Builder an. Siehe CLAUDE.md "DND-Steuerung" fuer den Vorfall
 * (hartcodiertes/invertiertes allowMedia(false) schaltete am 28.07.2026 live einen Podcast stumm),
 * der zu dieser Trennung fuehrte - eine falsch invertierte Kategorie soll hier von einem Unit-Test
 * gefangen werden, nicht erst live.
 */
data class DndZenPolicyIntent(
    val allowCallsPeopleType: Int,
    val allowMessagesPeopleType: Int,
    val allowConversationsSenders: Int,
    val allowReminders: Boolean,
    val allowEvents: Boolean,
    val allowAlarms: Boolean,
    val allowMedia: Boolean,
    val allowSystem: Boolean,
)

// Lint meldet hier InlinedApi (die ZenPolicy-Konstanten PEOPLE_TYPE_... und
// CONVERSATION_SENDERS_... brauchen API 29/30, minSdk ist 26) - Falschbefund, aus zwei Gruenden:
// 1. Es sind Compile-Zeit-Konstanten (statische Ints), die der Compiler als Literale einbackt. Zur
//    Laufzeit findet KEIN Zugriff auf eine fehlende API statt, der Wert ist einfach eine Zahl.
// 2. Der einzige Produktiv-Konsument ist DndScheduleUseCase.buildAutomaticZenRule() mit
//    RequiresApi(30); das gesamte DND-Feature ist ohnehin auf API 30+ gegated
//    (DndPermissionHelper.isFeatureSupported() / DndScheduleUseCase.isSupported()).
@Suppress("InlinedApi")
fun resolveDndZenPolicyIntent(p: DndPrefs.Policy): DndZenPolicyIntent = DndZenPolicyIntent(
    allowCallsPeopleType = if (p.blockCalls) ZenPolicy.PEOPLE_TYPE_NONE else ZenPolicy.PEOPLE_TYPE_ANYONE,
    allowMessagesPeopleType = if (p.blockMessages) ZenPolicy.PEOPLE_TYPE_NONE else ZenPolicy.PEOPLE_TYPE_ANYONE,
    allowConversationsSenders = if (p.blockConversations) ZenPolicy.CONVERSATION_SENDERS_NONE else ZenPolicy.CONVERSATION_SENDERS_ANYONE,
    allowReminders = !p.blockReminders,
    allowEvents = !p.blockEvents,
    allowAlarms = !p.blockAlarms,
    allowMedia = !p.blockMedia,
    allowSystem = !p.blockSystem,
)
