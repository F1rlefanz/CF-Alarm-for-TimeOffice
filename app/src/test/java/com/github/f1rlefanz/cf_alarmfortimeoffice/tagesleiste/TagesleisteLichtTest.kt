package com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueSunriseExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.Ort
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Licht auf der Tagesleiste (Umbau #195), gerechnet aus den HEUTIGEN Hue-Regeln. Alle Minuten sind
 * Minuten ab 18:00 am Vortag (0..2880), die Erwartungen stehen als LITERALE da.
 *
 * Der Mustertag ist ein Normaltag: Fruehdienst 06:00 bis 14:15, Wecker 04:30 (Minute 630). Den
 * Vorlauf rechnet der ECHTE `HueSunriseExecutor` - kein Stand-in, sonst pruefte der Test die eigene
 * Attrappe.
 */
class TagesleisteLichtTest {

    private val executor = HueSunriseExecutor(
        lightUseCase = mock<IHueLightUseCaseAdvanced>(),
        ladeStand = { TageslichtStand.ohneOrt() },
        fruehAusFuer = { _, _ -> null },
        loadRules = { Result.success(emptyList()) }
    )
    private val vorlauf: VorlaufMinuten = executor::getPreAlarmSunriseLeadMinutes

    private val frueh = Dienstzeit(LocalTime.of(6, 0), 495, ganztaegig = false)
    private val spaet = Dienstzeit(LocalTime.of(14, 0), 495, ganztaegig = false)
    private val nacht = Dienstzeit(LocalTime.of(20, 45), 615, ganztaegig = false)

    private fun eingabe(
        kategorie: ShiftKategorie = ShiftKategorie.DIENST,
        name: String = "F",
        weckerAn: Boolean = true,
        weckzeit: LocalTime? = LocalTime.of(4, 30),
        still: Boolean = false,
        dienstzeit: Dienstzeit? = frueh
    ) = TagesleisteEingabe(name, kategorie, weckerAn, weckzeit, still, dienstzeit)

    private fun lampe(dauer: Int? = null, id: String = "1", an: Boolean? = true, szene: String? = null) = HueLightAction(
        targetType = TargetType.GROUP, targetId = id, actionType = ActionType.TURN_ON,
        on = an, duration = dauer, isGroup = true, sceneId = szene
    )

    private fun regel(
        muster: String,
        vararg aktionen: HueLightAction,
        sonnenaufgang: Int? = null,
        vorDemWecker: Boolean = true,
        aktiv: Boolean = true,
        hellAb: HellAb = HellAb.IMMER,
        ausBeiHell: HellAb = HellAb.IMMER
    ) = HueSchedule(
        id = "r-$muster-${aktionen.size}-$sonnenaufgang", name = muster, shiftPattern = muster, enabled = aktiv,
        timeRanges = listOf(HueTimeRange(aktionen.toList())),
        sunrise = sonnenaufgang?.let { SunriseConfig(enabled = true, durationMinutes = it, startBeforeAlarm = vorDemWecker) },
        hellAb = hellAb, ausBeiHell = ausBeiHell
    )

    private fun rechne(
        e: TagesleisteEingabe,
        vararg regeln: HueSchedule,
        bridge: Boolean = true,
        tageslicht: TageslichtAmMustertag? = null
    ) = licht(e, LichtEinstellungen(bridge, regeln.toList(), tageslicht), vorlauf)

    private fun Tagesleiste.lichtEreignisse() = ereignisse.filter { it.spur == Spur.LICHT }.map { it.zeit to it.text }

    // ── Wann es keine Spur gibt ──────────────────────────────────────────────────────────────

    @Test
    fun `stummer Wecker gibt keine Licht-Spur - dieselben Regeln mit Ton schon`() {
        val regeln = arrayOf(regel("F", lampe(dauer = 20), sonnenaufgang = 30), regel("ALL", lampe(dauer = 45)))

        assertEquals(emptyList<LichtBereich>(), rechne(eingabe(still = true), *regeln))
        assertTrue(rechne(eingabe(still = false), *regeln).isNotEmpty())
    }

    @Test
    fun `Wecker aus gibt keine Licht-Spur - auch nicht mit gueltiger Weckzeit`() {
        val r = regel("F", lampe(dauer = 20))

        assertEquals(emptyList<LichtBereich>(), rechne(eingabe(weckerAn = false), r))
        assertTrue(rechne(eingabe(weckerAn = true), r).isNotEmpty())
    }

    @Test
    fun `ohne eingerichtete Bridge gibt es keine Licht-Spur`() {
        assertEquals(emptyList<LichtBereich>(), rechne(eingabe(), regel("F", lampe(dauer = 20)), bridge = false))
    }

    @Test
    fun `ohne Weckpunkt kein Licht - Krank, Ignorieren, ungueltige Weckzeit`() {
        val r = regel("F", lampe(dauer = 20))

        assertEquals(emptyList<LichtBereich>(), rechne(eingabe(kategorie = ShiftKategorie.KRANK), r))
        assertEquals(emptyList<LichtBereich>(), rechne(eingabe(kategorie = ShiftKategorie.IGNORIEREN), r))
        assertEquals(emptyList<LichtBereich>(), rechne(eingabe(weckzeit = null), r))
    }

    @Test
    fun `eine Regel ohne schaltbares Ziel gibt keine Spur`() {
        assertEquals(emptyList<LichtBereich>(), rechne(eingabe(), regel("F", lampe(dauer = 20, id = " "))))
        assertEquals(emptyList<LichtBereich>(), rechne(eingabe(), regel("F", lampe(dauer = 20, an = false))))
        assertEquals(emptyList<LichtBereich>(), rechne(eingabe(), regel("F")))
    }

    // ── Welche Regeln gelten ─────────────────────────────────────────────────────────────────

    @Test
    fun `ALL und die eigene Regel feuern zusammen - fremde und abgeschaltete nicht`() {
        val bereiche = rechne(
            eingabe(),
            regel("F", lampe(dauer = 20), sonnenaufgang = 30),
            regel("ALL", lampe(dauer = 45, id = "2")),
            regel("S", lampe(dauer = 600, id = "3"), sonnenaufgang = 90),
            regel("F", lampe(dauer = 700, id = "4"), sonnenaufgang = 120, aktiv = false)
        )

        // Beginn: der Wecker (630) minus die Rampe der eigenen Regel (30). Ende: der spaeteste Auto-Aus,
        // und das ist ALL mit 45 min - nicht die 20 min der eigenen Regel.
        assertEquals(
            listOf(LichtBereich(600, 630, LichtArt.SONNENAUFGANG), LichtBereich(630, 675, LichtArt.AN)),
            bereiche
        )
    }

    @Test
    fun `ALL allein gilt fuer jeden Typ`() {
        assertEquals(listOf(LichtBereich(630, 675, LichtArt.AN)), rechne(eingabe(), regel("ALL", lampe(dauer = 45))))
    }

    @Test
    fun `der Typname wird ohne Gross- und Kleinschreibung abgeglichen`() {
        assertEquals(listOf(LichtBereich(630, 650, LichtArt.AN)), rechne(eingabe(name = "f"), regel("F", lampe(dauer = 20))))
    }

    // ── Beginn und Ende ──────────────────────────────────────────────────────────────────────

    @Test
    fun `Sonnenaufgang vor dem Wecker - die Rampe endet am Wecker, danach brennt das Licht bis Auto-Aus`() {
        val e = eingabe()
        val bereiche = rechne(e, regel("F", lampe(dauer = 20), sonnenaufgang = 30))

        assertEquals(
            listOf(LichtBereich(600, 630, LichtArt.SONNENAUFGANG), LichtBereich(630, 650, LichtArt.AN)),
            bereiche
        )
        // Durch die echte Leiste: 04:00 Sonnenaufgang, 04:30 an, 04:50 aus.
        assertEquals(
            listOf(
                "04:00" to "Licht: Sonnenaufgang beginnt",
                "04:30" to "Licht an",
                "04:50" to "Licht aus"
            ),
            tagesleiste(e.copy(licht = bereiche)).lichtEreignisse()
        )
    }

    @Test
    fun `der Vorlauf ist die laengste Rampe vor dem Wecker`() {
        val bereiche = rechne(
            eingabe(),
            regel("F", lampe(dauer = 20), sonnenaufgang = 15),
            regel("ALL", lampe(dauer = 20, id = "2"), sonnenaufgang = 40)
        )

        assertEquals(LichtBereich(590, 630, LichtArt.SONNENAUFGANG), bereiche.first())
    }

    @Test
    fun `eine Rampe, die erst zum Wecker startet, hat keinen Vorlauf und verlaengert das Ende um die Rampe`() {
        val bereiche = rechne(eingabe(), regel("F", lampe(dauer = 20), sonnenaufgang = 15, vorDemWecker = false))

        // Keine SONNENAUFGANG-Spur; Licht ab dem Wecker (630), aus nach Rampe 15 + Dauer 20.
        assertEquals(listOf(LichtBereich(630, 665, LichtArt.AN)), bereiche)
    }

    @Test
    fun `eine Szene schaltet Licht - mit der gespeicherten on-Zusage hat sie ein Auto-Aus, ohne sie bleibt das Licht an`() {
        assertEquals(
            listOf(LichtBereich(630, 660, LichtArt.AN)),
            rechne(eingabe(), regel("F", lampe(dauer = 30, an = true, szene = "s1")))
        )
        // Bestand ohne die Zusage (on = null): die Szene schaltet trotzdem an (`executeActionsWithAutoRevert`),
        // aber `autoOffTargetsOf` filtert auf `on == true` und legt kein Auto-Aus an - das Licht bleibt an.
        assertEquals(
            listOf(LichtBereich(630, TAGESLEISTE_MIN, LichtArt.AN)),
            rechne(eingabe(), regel("F", lampe(dauer = 30, an = null, szene = "s1")))
        )
    }

    @Test
    fun `ohne Auto-Aus bleibt das Licht an - der Bereich laeuft bis zum Fensterende, die Leiste sagt kein Licht aus`() {
        val e = eingabe()
        val bereiche = rechne(e, regel("F", lampe(dauer = null)))

        assertEquals(listOf(LichtBereich(630, TAGESLEISTE_MIN, LichtArt.AN)), bereiche)
        val leiste = tagesleiste(e.copy(licht = bereiche))
        assertEquals(listOf("04:30" to "Licht an"), leiste.lichtEreignisse())
        assertEquals(listOf(Balken(Spur.LICHT, 630, TAGESLEISTE_MIN)), leiste.balken.filter { it.spur == Spur.LICHT })
    }

    @Test
    fun `hat auch nur eine gueltige Regel kein Auto-Aus, bleibt das Licht an`() {
        val bereiche = rechne(eingabe(), regel("F", lampe(dauer = 20)), regel("ALL", lampe(dauer = null, id = "2")))

        assertEquals(listOf(LichtBereich(630, TAGESLEISTE_MIN, LichtArt.AN)), bereiche)
    }

    @Test
    fun `der Wecker einer Nachtschicht liegt am Vortag - Beginn und Ende folgen ihm`() {
        // Weckzeit 17:30 vor einem Dienst ab 20:45: Weckpunkt 1410 (wie in TagesleisteTest).
        val bereiche = rechne(
            eingabe(name = "N", dienstzeit = nacht, weckzeit = LocalTime.of(17, 30)),
            regel("N", lampe(dauer = 20), sonnenaufgang = 30)
        )

        assertEquals(
            listOf(LichtBereich(1380, 1410, LichtArt.SONNENAUFGANG), LichtBereich(1410, 1430, LichtArt.AN)),
            bereiche
        )
    }

    // ── Tageslicht ───────────────────────────────────────────────────────────────────────────

    private val zone = ZoneId.of("Europe/Berlin")
    private val tag = LocalDate.of(2026, 12, 21)
    private val berlin = TageslichtStand(Ort(52.52, 13.40, OrtQuelle.PLZ, null), zone)
    private val amMustertag = TageslichtAmMustertag(tag, berlin)

    private fun minuteDes(ms: Long): Int =
        java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

    @Test
    fun `Nur bei Dunkelheit - die Regel entfaellt, wenn die Weckzeit nach Sonnenaufgang liegt`() {
        // Referenz: Berlin, 21.12.2026, Sonnenaufgang etwa 08:15 (Normalzeit).
        val sonnenaufgang = minuteDes(berlin.hellZeitpunkt(tag, HellAb.SONNENAUFGANG)!!)
        assertTrue("04:30 liegt vor dem Sonnenaufgang", 4 * 60 + 30 < sonnenaufgang)
        assertTrue("10:00 liegt nach dem Sonnenaufgang", sonnenaufgang < 10 * 60)

        val dunkel = regel("F", lampe(dauer = 20), hellAb = HellAb.SONNENAUFGANG)

        // Wecker 04:30: es ist dunkel, die Regel laeuft.
        assertEquals(listOf(LichtBereich(630, 650, LichtArt.AN)), rechne(eingabe(), dunkel, tageslicht = amMustertag))
        // Wecker 10:00 (Spaetdienst ab 14:00): schon hell, die Regel entfaellt ganz.
        val spaeterWecker = eingabe(dienstzeit = spaet, weckzeit = LocalTime.of(10, 0))
        assertEquals(emptyList<LichtBereich>(), rechne(spaeterWecker, dunkel, tageslicht = amMustertag))
    }

    @Test
    fun `ohne Standort wird die Tageslicht-Bedingung nicht behauptet - die Regel zaehlt`() {
        val dunkel = regel("F", lampe(dauer = 20), hellAb = HellAb.SONNENAUFGANG)
        val spaeterWecker = eingabe(dienstzeit = spaet, weckzeit = LocalTime.of(10, 0))

        assertEquals(listOf(LichtBereich(960, 980, LichtArt.AN)), rechne(spaeterWecker, dunkel, tageslicht = null))
        // Auch ein Stand OHNE Ort heisst: kein Hell-Zeitpunkt, die Regel laeuft (fail-safe wie zur Laufzeit).
        val ohneOrt = TageslichtAmMustertag(tag, TageslichtStand.ohneOrt(zone))
        assertEquals(listOf(LichtBereich(960, 980, LichtArt.AN)), rechne(spaeterWecker, dunkel, tageslicht = ohneOrt))
    }

    @Test
    fun `die Tageslicht-Bedingung liest den Wecktag - ein Wecker am Vortag, nach dessen Sonnenaufgang, entfaellt`() {
        // Nachtschicht, Wecker 17:30 am VORTAG (20.12.): laengst hell gewesen -> die Regel entfaellt.
        val nachts = eingabe(name = "N", dienstzeit = nacht, weckzeit = LocalTime.of(17, 30))

        assertEquals(
            emptyList<LichtBereich>(),
            rechne(nachts, regel("N", lampe(dauer = 20), hellAb = HellAb.SONNENAUFGANG), tageslicht = amMustertag)
        )
    }

    @Test
    fun `Auto-Aus bei Tageslicht kuerzt die Leiste nicht - sie zeigt die Dauer`() {
        // Sonnenaufgang ~08:15, Wecker 04:30, Auto-Aus nach 240 min waere 08:30: "bei Tageslicht" zoege es
        // auf ~08:15 vor. Die Leiste nennt die Dauer (Obergrenze), weil das Vorziehen WARN ins Log schriebe.
        val r = regel("F", lampe(dauer = 240), ausBeiHell = HellAb.SONNENAUFGANG)

        assertEquals(listOf(LichtBereich(630, 870, LichtArt.AN)), rechne(eingabe(), r, tageslicht = amMustertag))
    }
}
