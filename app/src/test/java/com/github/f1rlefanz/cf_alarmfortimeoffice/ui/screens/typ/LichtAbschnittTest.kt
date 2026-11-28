package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.EchteSchichtKonfiguration
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeConnectionInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.TypLichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueBridgeUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedReason
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedRuleTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.LichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Quelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.TagesleisteQuellen
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.ITageslichtRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever
import java.time.LocalTime

/** Das echte Regel-UseCase ueber der echten Schicht-Konfiguration; Hue-Store und Bridge gibt es nicht. */
private fun regelUseCase(repo: IShiftConfigRepository) = HueRuleUseCase(
    configRepository = mock<IHueConfigRepository>(),
    lightUseCase = mock<IHueLightUseCaseAdvanced>(),
    typLicht = TypLichtQuelle.Leser { repo.getCurrentShiftConfig() },
    schichtKonfiguration = repo
)

/** Das echte ViewModel darueber - der Weg, den Schalter, Entfernen und Editor der Typ-Seite nehmen. */
internal fun hueViewModelAn(repo: IShiftConfigRepository): HueViewModel {
    val bridge = mock<IHueBridgeUseCase>()
    whenever(bridge.getDiscoveryStatus()).thenReturn(emptyFlow())
    bridge.stub { on { getBridgeConnectionInfo() } doReturn Result.success(BridgeConnectionInfo(isConnected = false)) }
    val verbindung = mock<HueBridgeConnectionManager> {
        on { connectionStatus } doReturn MutableStateFlow<HueBridgeConnectionManager.ConnectionState>(
            HueBridgeConnectionManager.ConnectionState.DISCONNECTED
        )
    }
    val wecker = mock<IAlarmUseCase> { on { activeAlarms } doReturn MutableStateFlow(emptyList<AlarmInfo>()) }
    return HueViewModel(
        bridge, mock<IHueLightUseCase>(), regelUseCase(repo), verbindung, mock<HueSmartScheduler>(), wecker,
        mock<ITageslichtRepository>()
    )
}

/** Fuehrt eine Regel-Aktion aus und wartet, bis sie durch ist. */
internal suspend fun HueViewModel.fuehreAus(aktion: HueViewModel.() -> Unit) {
    aktion()
    withTimeout(10_000) { uiState.first { !it.isLoading } }
}

/**
 * Die Karte „Licht (Hue)“ der Typ-Seite (#254): was sie zeigt, was bedienbar ist und was Schalter und
 * Entfernen wirklich schreiben - die Schreibwege gegen den echten Use-Case und eine echte Konfigurationsdatei.
 */
class LichtAbschnittTest {

    @get:Rule
    val ordner = TemporaryFolder()

    private fun aktion(id: String, name: String?, gruppe: Boolean = false, an: Boolean = true) = HueLightAction(
        targetType = if (gruppe) TargetType.GROUP else TargetType.LIGHT,
        targetId = id,
        targetName = name,
        actionType = if (an) ActionType.TURN_ON else ActionType.TURN_OFF,
        on = an,
        isGroup = gruppe
    )

    private fun regel(
        id: String,
        muster: String = "Frühdienst",
        aktionen: List<HueLightAction> = listOf(aktion("1", "Bad", gruppe = true))
    ) = HueSchedule(id = id, name = "Licht $id", shiftPattern = muster, timeRanges = listOf(HueTimeRange(aktionen)))

    private fun typ(id: String, name: String, vararg regeln: HueSchedule) =
        ShiftDefinition(id = id, name = name, keywords = listOf(name), alarmTime = LocalTime.of(5, 30)).let { t ->
            t.copy(licht = t.licht.mitRegeln(regeln.toList()))
        }

    private fun karte(
        config: ShiftConfig,
        typId: String,
        weckerAn: Boolean = true,
        bridge: BridgeStand = BridgeStand.EINGERICHTET,
        neu: Boolean = false,
        unaufgeloest: List<UnresolvedRuleTarget> = emptyList(),
        // Die Kategorie kommt auf der Seite aus dem Entwurf; ungeaendert ist es die gespeicherte.
        kategorie: ShiftKategorie = config.definitions.first { it.id == typId }.kategorie
    ) = LichtAbschnittLogik.karte(
        config, config.definitions.first { it.id == typId }, neu, kategorie, weckerAn, bridge, unaufgeloest
    )

    @Test
    fun `Ohne Wecker - die Regeln bleiben sichtbar und ausgegraut, kein Plus, der Hinweis der Vorlage steht`() {
        val config = ShiftConfig(definitions = listOf(typ("f", "Frühdienst", regel("r1"), regel("r2"))))

        val aus = karte(config, "f", weckerAn = false)

        assertEquals(listOf("r1", "r2"), aus.zeilen.map { it.regel.id })
        assertTrue(aus.ausgegraut)
        assertTrue("Schalter, Antippen und × gesperrt", aus.zeilen.none { it.bedienbar || it.entfernbar })
        assertEquals(LichtFuss.OHNE_WECKER, aus.fuss)
        assertEquals(
            "Licht startet mit dem Wecker. Ohne Weckzeit gibt es an diesem Tag kein Licht – schalte oben den " +
                "Wecker ein, gern stumm.",
            LichtTexte.OHNE_WECKER
        )
        // Auch ohne Bridge steht dann dieser Hinweis, kein zweiter.
        assertEquals(
            LichtFuss.OHNE_WECKER,
            karte(config, "f", weckerAn = false, bridge = BridgeStand.NICHT_EINGERICHTET).fuss
        )

        val an = karte(config, "f")
        assertEquals(false, an.ausgegraut)
        assertTrue(an.zeilen.all { it.bedienbar && it.entfernbar })
        assertEquals(LichtFuss.HINZU, an.fuss)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain
    @Test
    fun `Schalter an und aus schreibt enabled genau dieser Regel`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val konfiguration = EchteSchichtKonfiguration(ordner.newFolder())
        try {
            // Die erste Regel ist die Kopie einer frueher allgemeinen: gespeichertes Muster "ALL" (#247).
            konfiguration.repo.saveShiftConfig(
                ShiftConfig(
                    definitions = listOf(
                        typ("f", "Frühdienst", regel("3:alt@f", muster = "ALL"), regel("nachbar")),
                        typ("s", "Spätdienst", regel("andere", muster = "Spätdienst"))
                    )
                )
            ).getOrThrow()
            val viewModel = hueViewModelAn(konfiguration.repo)
            suspend fun gespeichert() = konfiguration.repo.getCurrentShiftConfig().getOrThrow()
            val vorher = gespeichert()
            val kopie = karte(vorher, "f").zeilen.first().regel

            viewModel.fuehreAus { updateRule(LichtAbschnittLogik.geschaltet(kopie, false, "Frühdienst")) }

            assertNull(viewModel.uiState.value.error)
            val aus = gespeichert()
            assertEquals(
                listOf(kopie.copy(enabled = false, shiftPattern = "Frühdienst"), vorher.definitions[0].licht.regeln[1]),
                aus.definitions[0].licht.regeln
            )
            assertEquals("der andere Typ bleibt", vorher.definitions[1], aus.definitions[1])

            viewModel.fuehreAus {
                updateRule(LichtAbschnittLogik.geschaltet(aus.definitions[0].licht.regeln[0], true, "Frühdienst"))
            }

            assertNull(viewModel.uiState.value.error)
            assertEquals(listOf(true, true), gespeichert().definitions[0].licht.regeln.map { it.enabled })
            assertEquals(vorher.definitions[1], gespeichert().definitions[1])
        } finally {
            konfiguration.schliessen()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain
    @Test
    fun `Hoechstzahl - die Karte sperrt genau dort, wo das Speichern ablehnt, mit demselben Satz`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val konfiguration = EchteSchichtKonfiguration(ordner.newFolder())
        try {
            konfiguration.repo.saveShiftConfig(ShiftConfig(definitions = listOf(typ("f", "Frühdienst")))).getOrThrow()
            val viewModel = hueViewModelAn(konfiguration.repo)
            suspend fun gespeichert() = konfiguration.repo.getCurrentShiftConfig().getOrThrow()

            repeat(LichtAbschnittLogik.HOECHSTZAHL) { i ->
                assertEquals(LichtFuss.HINZU, karte(gespeichert(), "f").fuss)
                viewModel.fuehreAus { updateRule(regel("r$i")) }
                assertNull("Regel $i muss gehen", viewModel.uiState.value.error)
            }

            assertEquals(LichtFuss.HOECHSTZAHL, karte(gespeichert(), "f").fuss)
            viewModel.fuehreAus { updateRule(regel("eine-zu-viel")) }
            assertEquals(
                LichtTexte.hoechstzahl(LichtAbschnittLogik.HOECHSTZAHL),
                viewModel.uiState.value.error
            )
        } finally {
            konfiguration.schliessen()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `Bridge - ohne Bridge kein Plus, aber der Weg und die Regeln bleiben, unlesbar ist nicht keine Bridge`() {
        val config = ShiftConfig(definitions = listOf(typ("f", "Frühdienst", regel("r1"))))
        fun quellen(licht: Quelle<LichtQuelle>) =
            TagesleisteQuellen(typen = Quelle.Bekannt(config), licht = licht, spannen = Quelle.Unbekannt)
        fun bekannt(eingerichtet: Boolean) =
            quellen(Quelle.Bekannt(LichtQuelle(eingerichtet, emptyList()) { _, _, _, _ -> 0 }))

        assertEquals(BridgeStand.WIRD_GELESEN, LichtAbschnittLogik.bridgeStand(null))
        assertEquals(BridgeStand.NICHT_LESBAR, LichtAbschnittLogik.bridgeStand(quellen(Quelle.Unbekannt)))
        assertEquals(BridgeStand.NICHT_EINGERICHTET, LichtAbschnittLogik.bridgeStand(bekannt(false)))
        assertEquals(BridgeStand.EINGERICHTET, LichtAbschnittLogik.bridgeStand(bekannt(true)))

        val erwartet = mapOf(
            BridgeStand.WIRD_GELESEN to LichtFuss.WIRD_GELESEN,
            BridgeStand.NICHT_LESBAR to LichtFuss.BRIDGE_NICHT_LESBAR,
            BridgeStand.NICHT_EINGERICHTET to LichtFuss.OHNE_BRIDGE,
            BridgeStand.EINGERICHTET to LichtFuss.HINZU
        )
        erwartet.forEach { (stand, fuss) ->
            val karte = karte(config, "f", bridge = stand)
            assertEquals(stand.name, fuss, karte.fuss)
            assertTrue("$stand: vorhandene Regeln bleiben bedienbar", karte.zeilen.single().bedienbar)
        }
    }

    @Test
    fun `Verdeckter Typ gleichen Namens - Schalter und Plus gesperrt, Entfernen trifft die eigene Regel`() {
        val config = ShiftConfig(
            definitions = listOf(
                typ("erster", "Frühdienst", regel("a")),
                typ("zweiter", "frühdienst", regel("b"))
            )
        )

        val verdeckt = karte(config, "zweiter")
        assertEquals(LichtFuss.VERDECKT, verdeckt.fuss)
        assertEquals(false, verdeckt.zeilen.single().bedienbar)
        assertEquals(true, verdeckt.zeilen.single().entfernbar)

        val wirksam = karte(config, "erster")
        assertEquals(LichtFuss.HINZU, wirksam.fuss)
        assertEquals(true, wirksam.zeilen.single().bedienbar)
    }

    private val ohneWeckerKategorien = listOf(ShiftKategorie.KRANK, ShiftKategorie.IGNORIEREN)

    @Test
    fun `Krank und Ignorieren mit gespeicherter Regel - Karte da, nicht schaltbar, entfernbar, ohne Plus`() {
        val unbekannt = UnresolvedRuleTarget("r1", "Licht r1", "1", "Leselampe", false, UnresolvedReason.NOT_FOUND)
        ohneWeckerKategorien.forEach { kategorie ->
            val gespeichert = typ("k", "Krankmeldung", regel("r1")).mitKategorie(kategorie)
            val config = ShiftConfig(definitions = listOf(gespeichert))

            assertTrue(kategorie.name, LichtAbschnittLogik.zeigtAbschnitt(kategorie, gespeichert, neu = false))
            // „Wecker an diesem Tag“ ist dort versteckt: sein Stand darf nichts entscheiden.
            listOf(true, false).forEach { weckerAn ->
                BridgeStand.entries.forEach { bridge ->
                    val karte = karte(config, "k", weckerAn = weckerAn, bridge = bridge, unaufgeloest = listOf(unbekannt))
                    val fall = "$kategorie, weckerAn=$weckerAn, $bridge"

                    assertEquals(fall, listOf("r1"), karte.zeilen.map { it.regel.id })
                    assertTrue(fall, karte.ausgegraut)
                    assertEquals("$fall: Schalter und Antippen", false, karte.zeilen.single().bedienbar)
                    assertEquals("$fall: ×", true, karte.zeilen.single().entfernbar)
                    assertEquals(fall, LichtFuss.KATEGORIE_WECKT_NICHT, karte.fuss)
                    // Der Weg ueber die Warnkarte: das Ziel steht an der Zeile, ohne zum Antippen zu raten.
                    assertEquals(
                        fall,
                        "Auf dieser Bridge unbekannt: Leselampe. Die Regel schaltet dafür nichts.",
                        karte.zeilen.single().zielHinweis
                    )
                }
            }
        }
        assertEquals(
            "Typen dieser Kategorie wecken nicht, deshalb laufen ihre Lichtregeln nicht. Hier lassen sie sich " +
                "nur entfernen.",
            LichtTexte.KATEGORIE_WECKT_NICHT
        )
    }

    @Test
    fun `Krank und Ignorieren ohne Regel - keine Karte, auch nicht bei der Neuanlage`() {
        ohneWeckerKategorien.forEach { kategorie ->
            val leer = typ("k", "Krankmeldung").mitKategorie(kategorie)
            val vorlageMitRegel = typ("neu", "Krank neu", regel("r1")).mitKategorie(kategorie)

            assertEquals(kategorie.name, false, LichtAbschnittLogik.zeigtAbschnitt(kategorie, leer, neu = false))
            assertEquals(
                "$kategorie: eine Vorlage ist nicht gespeichert",
                false,
                LichtAbschnittLogik.zeigtAbschnitt(kategorie, vorlageMitRegel, neu = true)
            )
        }
    }

    @Test
    fun `Die Kategorie des Entwurfs entscheidet, die Regeln bleiben die gespeicherten`() {
        val dienst = typ("f", "Frühdienst", regel("r1"))
        val config = ShiftConfig(definitions = listOf(dienst))

        val aufKrank = karte(config, "f", kategorie = ShiftKategorie.KRANK)

        assertTrue(LichtAbschnittLogik.zeigtAbschnitt(ShiftKategorie.KRANK, dienst, neu = false))
        assertEquals(LichtFuss.KATEGORIE_WECKT_NICHT, aufKrank.fuss)
        assertEquals(false, aufKrank.zeilen.single().bedienbar)

        val krank = ShiftConfig(definitions = listOf(dienst.mitKategorie(ShiftKategorie.KRANK)))
        val vonKrankZurueck = karte(krank, "f", kategorie = ShiftKategorie.DIENST)

        assertEquals(LichtFuss.HINZU, vonKrankZurueck.fuss)
        assertEquals(true, vonKrankZurueck.zeilen.single().bedienbar)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain
    @Test
    fun `Entfernen am Krank- und Ignorieren-Typ loescht die Regel, danach ist die Karte weg`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val konfiguration = EchteSchichtKonfiguration(ordner.newFolder())
        try {
            konfiguration.repo.saveShiftConfig(
                ShiftConfig(
                    definitions = listOf(
                        typ("k", "Krankmeldung", regel("am-kranken", "Krankmeldung"))
                            .mitKategorie(ShiftKategorie.KRANK),
                        typ("i", "Info", regel("am-ignorierten", "Info")).mitKategorie(ShiftKategorie.IGNORIEREN)
                    )
                )
            ).getOrThrow()
            val viewModel = hueViewModelAn(konfiguration.repo)
            suspend fun gespeichert() = konfiguration.repo.getCurrentShiftConfig().getOrThrow()

            listOf("k" to "am-kranken", "i" to "am-ignorierten").forEach { (typId, regelId) ->
                assertEquals(true, karte(gespeichert(), typId).zeilen.single().entfernbar)

                viewModel.fuehreAus { deleteRule(regelId) }

                assertNull(viewModel.uiState.value.error)
                val danach = gespeichert().definitions.first { it.id == typId }
                assertTrue(danach.licht.regeln.isEmpty())
                assertEquals(false, LichtAbschnittLogik.zeigtAbschnitt(danach.kategorie, danach, neu = false))
            }
        } finally {
            konfiguration.schliessen()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `Neuanlage sagt wann es geht, ohne Regeln haben nur Krank und Ignorieren keine Karte`() {
        val config = ShiftConfig(definitions = listOf(typ("f", "Frühdienst")))
        val vorlage = typ("neu", "Frühdienst")

        val neu = LichtAbschnittLogik.karte(
            config, vorlage, true, ShiftKategorie.DIENST, true, BridgeStand.EINGERICHTET, emptyList()
        )

        assertEquals(LichtFuss.NEUANLAGE, neu.fuss)
        assertTrue(neu.zeilen.isEmpty())
        assertEquals(
            ohneWeckerKategorien,
            ShiftKategorie.entries.filterNot { LichtAbschnittLogik.zeigtAbschnitt(it, vorlage, neu = false) }
        )
    }

    @Test
    fun `Verwerfen sagt bei einem Typ mit Lichtregeln, dass Licht schon gespeichert ist`() {
        val mit = TypSeiteTexte.mitLichtHinweis(TypSeiteTexte.VERWERFEN_TEXT, hatLichtregeln = true)

        assertEquals(
            "${TypSeiteTexte.VERWERFEN_TEXT} Änderungen an Lichtregeln sind schon gespeichert und bleiben.", mit
        )
        assertEquals(
            TypSeiteTexte.VERWERFEN_TEXT,
            TypSeiteTexte.mitLichtHinweis(TypSeiteTexte.VERWERFEN_TEXT, hatLichtregeln = false)
        )
        // TalkBack: der Name des Schalters behauptet keinen Zustand.
        assertEquals("Lichtregel „Bad“", LichtTexte.schalterBeschreibung("Bad"))
    }

    @Test
    fun `Unterzeile nennt Ziele und Modus, der Ziel-Hinweis steht nur an der betroffenen Regel`() {
        val sonnenaufgang = regel(
            "s", aktionen = listOf(aktion("1", "Bad", gruppe = true), aktion("2", "Flur"), aktion("3", "Flur"))
        ).copy(sunrise = SunriseConfig(enabled = true, durationMinutes = 30))
        val szenen = regel(
            "z",
            aktionen = listOf(
                aktion("1", "Wohnzimmer", gruppe = true).copy(sceneId = "x", sceneName = "Nachtlicht"),
                aktion("2", "Schlafzimmer", gruppe = true).copy(sceneId = "y", sceneName = "Lesen")
            )
        )
        val ohneNamen = regel("m", aktionen = listOf(aktion("7", null, an = false), aktion("8", " ", gruppe = true, an = false)))

        assertEquals("Bad und Flur · Sonnenaufgang 30 min", LichtAbschnittLogik.unterzeile(sonnenaufgang))
        assertEquals(
            "Wohnzimmer und Schlafzimmer · Szenen «Nachtlicht» und «Lesen»",
            LichtAbschnittLogik.unterzeile(szenen)
        )
        assertEquals("Licht 7 und Gruppe 8 · Ausschalten", LichtAbschnittLogik.unterzeile(ohneNamen))

        val config = ShiftConfig(definitions = listOf(typ("f", "Frühdienst", sonnenaufgang, ohneNamen)))
        val unbekannt = UnresolvedRuleTarget("m", "Licht m", "7", "Leselampe", false, UnresolvedReason.NOT_FOUND)

        val zeilen = karte(config, "f", unaufgeloest = listOf(unbekannt)).zeilen

        assertNull(zeilen[0].zielHinweis)
        assertEquals(
            "Auf dieser Bridge unbekannt: Leselampe. Die Regel schaltet dafür nichts – tippe auf die Regel " +
                "und wähle neu.",
            zeilen[1].zielHinweis
        )
    }
}
