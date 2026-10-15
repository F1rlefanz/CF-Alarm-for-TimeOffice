package com.github.f1rlefanz.cf_alarmfortimeoffice.freietage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate

/**
 * Testdoubles fuer "Tag freigeben".
 *
 * WARUM ZENTRAL UND NICHT JE TESTDATEI: `FreieTageStore` haengt an vier Konsumenten
 * (`AlarmUseCase`, `DimScheduleUseCase`, `DndScheduleUseCase`, `AlarmViewModel`), und ein blosses
 * `mock<FreieTageStore>()` liefert fuer `freieTageNow()` `null` - in Kotlin ein NPE an einer
 * Stelle, die mit dem eigentlichen Testgegenstand nichts zu tun hat. Ein Default an einer
 * Stelle ist besser als dieselbe Stubbing-Zeile in einem Dutzend Dateien, die beim naechsten
 * Feld wieder alle angefasst werden muessten.
 *
 * #142: dasselbe gilt fuer die neuen Funktionen mit Datenklassen-Rueckgabe
 * (`freigabeQuellenNow()`, `setzeKalenderKrankTage()`) und den `freigabeQuellen`-Flow - ungestubbt
 * liefert der Mock `null`, und der Sync bzw. ein ViewModel-`init{}` stirbt an einem NPE, der mit
 * dem Testgegenstand nichts zu tun hat. Die vorgegebenen Tage gelten als HAND-Freigaben.
 */
internal fun keineFreienTage(): FreieTageStore = freieTageStoreMit(emptySet())

/** Wie [keineFreienTage], aber mit vorgegebenen Freigaben. */
internal fun freieTageStoreMit(tage: Set<LocalDate>): FreieTageStore {
    val store = mock<FreieTageStore>()
    runBlocking {
        whenever(store.freieTageNow()).thenReturn(tage)
        whenever(store.freieTage).thenReturn(flowOf(tage))
        whenever(store.freigabeQuellenNow()).thenReturn(FreigabeQuellen(tage, emptySet()))
        whenever(store.freigabeQuellen).thenReturn(flowOf(FreigabeQuellen(tage, emptySet())))
        whenever(store.setzeKalenderKrankTage(any(), any(), anyOrNull(), any(), any())).thenReturn(KrankTageAenderung.KEINE)
        whenever(store.offeneKrankMeldungNow(any())).thenReturn(emptySet())
    }
    return store
}

/**
 * #142: ein ECHTER FreieTageStore ueber einem DataStore im Arbeitsspeicher - fuer Tests, die die
 * Mischsemantik ueber syncAlarms() pruefen (ein Mock wuerde genau das wegstubben).
 *
 * Achtung: der Store filtert beim LESEN gegen `LocalDate.now()` - Tage vor gestern verschwinden.
 */
internal fun freieTageStoreImSpeicher(
    hand: Set<LocalDate> = emptySet(),
    kalender: Set<LocalDate> = emptySet()
): FreieTageStore {
    val start = mutablePreferencesOf().apply {
        this[stringSetPreferencesKey(FreieTageStore.KEY_FREIE_TAGE_NAME)] = hand.map { it.toString() }.toSet()
        this[stringSetPreferencesKey(FreieTageStore.KEY_KRANK_KALENDER_NAME)] = kalender.map { it.toString() }.toSet()
    }
    return FreieTageStore(SpeicherDataStore(start))
}

/** DataStore im Arbeitsspeicher, Muster aus `KalenderVorausschauPrefsTest`. */
private class SpeicherDataStore(initial: Preferences) : DataStore<Preferences> {
    private val zustand = MutableStateFlow(initial)
    override val data: Flow<Preferences> = zustand
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val neu = transform(zustand.value)
        zustand.value = neu
        return neu
    }
}

/**
 * `TagFreigabeUseCase` als Mock, dessen `freigabeQuellen`-Flow etwas liefert - `AlarmViewModel.init{}`
 * sammelt ihn sofort, ein `null` waere dort ein NPE beim Konstruieren des ViewModels. #142:
 * `freigabeQuellenNow()` ebenso (das Wiederherstellen nach einer abgebrochenen Freigabe fragt ihn).
 */
internal fun tagFreigabeUseCaseOhneFreigaben(): TagFreigabeUseCase {
    val useCase = mock<TagFreigabeUseCase>()
    whenever(useCase.freigabeQuellen).thenReturn(flowOf(FreigabeQuellen.LEER))
    runBlocking { whenever(useCase.freigabeQuellenNow()).thenReturn(FreigabeQuellen.LEER) }
    return useCase
}
