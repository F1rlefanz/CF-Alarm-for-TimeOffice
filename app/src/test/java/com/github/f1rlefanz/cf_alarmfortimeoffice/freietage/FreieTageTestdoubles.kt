package com.github.f1rlefanz.cf_alarmfortimeoffice.freietage

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
 */
internal fun keineFreienTage(): FreieTageStore = freieTageStoreMit(emptySet())

/** Wie [keineFreienTage], aber mit vorgegebenen Freigaben. */
internal fun freieTageStoreMit(tage: Set<LocalDate>): FreieTageStore {
    val store = mock<FreieTageStore>()
    runBlocking {
        whenever(store.freieTageNow()).thenReturn(tage)
        whenever(store.freieTage).thenReturn(flowOf(tage))
    }
    return store
}

/**
 * `TagFreigabeUseCase` als Mock, dessen `freieTage`-Flow etwas liefert - `AlarmViewModel.init{}`
 * sammelt ihn sofort, ein `null` waere dort ein NPE beim Konstruieren des ViewModels.
 */
internal fun tagFreigabeUseCaseOhneFreigaben(): TagFreigabeUseCase {
    val useCase = mock<TagFreigabeUseCase>()
    whenever(useCase.freieTage).thenReturn(flowOf(emptySet()))
    return useCase
}
