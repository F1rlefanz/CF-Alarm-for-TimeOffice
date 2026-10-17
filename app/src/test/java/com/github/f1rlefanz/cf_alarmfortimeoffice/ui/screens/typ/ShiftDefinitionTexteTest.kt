package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.SchichttypEinordnen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * #142: Die Krankmeldungs-Texte im Schicht-Editor und auf der Schicht-Karte duerfen nur zusagen,
 * was ein Krankmeldungs-Typ wirklich tut - den Tag fuer Wecker aus dem Dienstplan freigeben,
 * eigene Wecker unangetastet lassen. Er hat keine Weckzeit und spannt keine Schicht auf; ein Text,
 * der von "Nicht stören" oder einer Weckzeit spricht, verspraeche etwas, das es nicht gibt.
 */
class ShiftDefinitionTexteTest {

    @Test
    fun `Der Krankmeldungs-Hinweis verspricht nur, was gilt`() {
        val text = ShiftDefinitionTexte.KRANKMELDUNG_HINWEIS
        assertTrue(
            "Ohne diesen Satz muss der Nutzer fuerchten, dass auch manuelle Wecker entfallen",
            text.contains("Eigene Wecker bleiben")
        )
        assertTrue(text.contains("kein Wecker aus dem Dienstplan"))
        assertFalse(
            "Ein Krankmeldungs-Typ steuert \"Nicht stören\" nicht selbst",
            text.contains("Nicht stören")
        )
    }

    @Test
    fun `Statuszeilen einer Krankmeldung nennen keine Weckzeit`() {
        listOf(
            ShiftDefinitionTexte.KRANKMELDUNG_STATUS,
            ShiftDefinitionTexte.KRANKMELDUNG_AUSGESCHALTET,
            ShiftDefinitionTexte.KRANKMELDUNG_AKTIVIERT_HINWEIS
        ).forEach { text ->
            assertFalse(text, text.contains("Alarm"))
            assertFalse(text, text.contains("Weckzeit"))
            assertFalse(text, text.contains("Nicht stören"))
        }
        assertTrue(ShiftDefinitionTexte.KRANKMELDUNG_AUSGESCHALTET.contains("keinen Tag frei"))
        assertTrue(ShiftDefinitionTexte.KRANKMELDUNG_AKTIVIERT_HINWEIS.contains("keinen Tag frei"))
    }

    @Test
    fun `Loeschrueckfrage einer Krankmeldung nennt den Namen und die zurueckkehrenden Wecker`() {
        val text = ShiftDefinitionTexte.krankmeldungLoeschenText("Krank")
        assertTrue(text.startsWith("\"Krank\" wird mit allen Mustern gelöscht."))
        assertTrue(text.contains("bekommen ihre Wecker aus dem Dienstplan zurück"))
        assertFalse(
            "Ein Krankmeldungs-Typ hat keine Weckzeit, die mit geloescht wuerde",
            text.contains("Weckzeit")
        )
    }

    @Test
    fun `Die Ein-Buchstaben-Warnung nennt bei einer Krankmeldung den fehlenden Wecker`() {
        val krank = ShiftDefinitionTexte.einBuchstabeWarnung("K", isSickDay = true)!!
        assertTrue(krank.contains("Diensttag frei"))
        assertTrue(krank.contains("kein Wecker aus dem Dienstplan"))
        assertFalse("Bei Krank weckt ein fremder Termin nicht, er schweigt", krank.contains("weckt dich"))

        val schicht = ShiftDefinitionTexte.einBuchstabeWarnung("F", isSickDay = false)!!
        assertTrue(schicht.contains("weckt dich dann an freien Tagen"))
    }

    /**
     * Seit #150 trifft ein Muster aus reinen Zeichen ("+" fuer frei) nur den GANZEN Titel - nie
     * "Kino mit +" oder "x + y". Die Warnung behauptete das Gegenteil, in Fehlerfarbe, und genau
     * unter dem Kuerzel, das die App seit #150 selbst vorschlaegt. Sie gilt nur, wo die Erkennung
     * mit Wortgrenzen arbeitet: ein einzelner Buchstabe oder eine einzelne Ziffer.
     */
    @Test
    fun `Die Ein-Buchstaben-Warnung erscheint nur bei einem Buchstaben oder einer Ziffer`() {
        for (krank in listOf(false, true)) {
            assertNotNull(ShiftDefinitionTexte.einBuchstabeWarnung("F", krank))
            assertNotNull(ShiftDefinitionTexte.einBuchstabeWarnung(" 7 ", krank))
            assertNull("'+' trifft nur den ganzen Titel", ShiftDefinitionTexte.einBuchstabeWarnung("+", krank))
            assertNull(ShiftDefinitionTexte.einBuchstabeWarnung(" - ", krank))
            assertNull(ShiftDefinitionTexte.einBuchstabeWarnung("FR", krank))
            assertNull(ShiftDefinitionTexte.einBuchstabeWarnung("", krank))
        }
    }

    /** Umbau E1: jede Kategorie hat eine Kurzbeschreibung; die der Rufbereitschaft nennt die Uebernahme (#172). */
    @Test
    fun `Jede Kategorie hat eine Kurzbeschreibung`() {
        ShiftKategorie.entries.forEach { kategorie ->
            assertTrue(kategorie.name, ShiftDefinitionTexte.kategorieBeschreibung(kategorie).isNotBlank())
        }
        assertTrue(
            ShiftDefinitionTexte.kategorieBeschreibung(ShiftKategorie.RUFBEREITSCHAFT).contains("Einsatz")
        )
    }

    /**
     * SPEC 3.3 verspricht beim stummen Wecker auch Licht. Heute ueberspringen AlarmReceiver und
     * HueSmartScheduler bei einer stillen Schicht jede Hue-Regel - der Text darf es nicht zusagen.
     */
    @Test
    fun `Der Stummer-Wecker-Hinweis verspricht kein Licht, das heute nicht startet`() {
        val text = ShiftDefinitionTexte.STUMMER_WECKER_HINWEIS
        assertFalse(text, text.contains("Lichtwecker"))
        assertTrue("Dimmer und DND richten sich weiter nach der Weckzeit", text.contains("Dimmer"))
        assertTrue(text.contains("startet dann nicht"))
    }

    @Test
    fun `Die Wecker-Texte benennen Schalter und Wirkung wie der Prototyp`() {
        assertEquals("Wecker an diesem Tag", ShiftDefinitionTexte.WECKER_AN_TITEL)
        assertEquals("Stummer Wecker", ShiftDefinitionTexte.STUMMER_WECKER_TITEL)
        assertFalse(
            "Ob er klingelt, regelt der Stumm-Schalter darunter",
            ShiftDefinitionTexte.WECKER_AN_HINWEIS.contains("klingelt")
        )
        // Der Aktiviert-Hinweis verwies auf „Stille Schicht“ - den Schalter gibt es so nicht mehr.
        assertFalse(ShiftDefinitionTexte.AKTIVIERT_HINWEIS.contains("Stille Schicht"))
        assertTrue(ShiftDefinitionTexte.AKTIVIERT_HINWEIS.contains("„Wecker an diesem Tag“"))
    }

    /**
     * Pruefer #174: unter einem einbuchstabigen Muster stand bei Ignorieren und bei „Wecker aus“
     * "weckt dich dann an freien Tagen" - beide stellen nie einen Wecker.
     */
    @Test
    fun `Die Ein-Buchstaben-Warnung verspricht keinen Wecker, wo keiner gestellt wird`() {
        listOf(
            ShiftKategorie.IGNORIEREN to true,
            ShiftKategorie.URLAUB to false,
            ShiftKategorie.DIENST to false
        ).forEach { (kategorie, weckerAn) ->
            val text = ShiftDefinitionTexte.einBuchstabeWarnung("U", kategorie, weckerAn)!!
            assertFalse("$kategorie, Wecker an=$weckerAn: $text", text.contains("weckt dich"))
            assertTrue(text.contains("fremde Termine"))
        }
        assertTrue(
            ShiftDefinitionTexte.einBuchstabeWarnung("F", ShiftKategorie.DIENST, true)!!
                .contains("weckt dich dann an freien Tagen")
        )
        assertTrue(
            ShiftDefinitionTexte.einBuchstabeWarnung("K", ShiftKategorie.KRANK, true)!!.contains("Diensttag frei")
        )
        assertNull("'+' trifft nur den ganzen Titel", ShiftDefinitionTexte.einBuchstabeWarnung("+", ShiftKategorie.FREI, true))
    }

    @Test
    fun `Der Aktiviert-Hinweis bei Ignorieren verweist auf keinen ausgeblendeten Schalter`() {
        val text = ShiftDefinitionTexte.IGNORIEREN_AKTIVIERT_HINWEIS
        assertTrue(text.contains("übergangen"))
        assertFalse(text, text.contains("Wecker an diesem Tag"))
    }

    @Test
    fun `Der Ignorieren-Hinweis beginnt wie im Prototyp und nennt den Vorrang des Dienstes`() {
        val text = ShiftDefinitionTexte.IGNORIEREN_STATT_WECKER
        assertTrue(text.startsWith("Dieser Eintrag wird übergangen."))
        assertTrue(text.contains("gilt der Dienst"))
    }

    /**
     * #188: der Hinweis unter „Schichtdefinition aktiviert“ steht jetzt in einer Funktion, die Dialog und
     * Typ-Seite teilen. Vorher entschied ein `when` im Dialog (`isSickDay`, `kategorie == IGNORIEREN`).
     */
    @Test
    fun `Der Aktiviert-Hinweis folgt der Kategorie - Krank und Ignorieren haben eigene Texte`() {
        ShiftKategorie.entries.forEach { kategorie ->
            val erwartet = when (kategorie) {
                ShiftKategorie.KRANK -> ShiftDefinitionTexte.KRANKMELDUNG_AKTIVIERT_HINWEIS
                ShiftKategorie.IGNORIEREN -> ShiftDefinitionTexte.IGNORIEREN_AKTIVIERT_HINWEIS
                else -> ShiftDefinitionTexte.AKTIVIERT_HINWEIS
            }
            assertEquals(kategorie.name, erwartet, ShiftDefinitionTexte.aktiviertHinweis(kategorie))
        }
    }

    /** #188: Dialog und Typ-Seite nennen die Beschriftung des Namensfelds wortgleich - ein Hinweis nennt sie nie anders. */
    @Test
    fun `Der Muster-Hinweis nennt das Namensfeld so, wie es an dieser Stelle heisst`() {
        assertTrue(ShiftDefinitionTexte.musterHinweis("Schichtname").contains("Der Schichtname oben zählt ab zwei Zeichen"))
        assertTrue(ShiftDefinitionTexte.musterHinweis("Name").contains("Der Name oben zählt ab zwei Zeichen"))
        assertTrue(ShiftDefinitionTexte.musterHinweis("Name").contains("\"FD\" trifft \"FD Station 3\", nicht \"FD2\""))
    }

    // --- #189: die Sonderfall-Kaesten der Typ-Seite. Textwahrheit statt Prototyp-Wortlaut. ---

    private fun kastenText(kategorie: ShiftKategorie, code: String? = "AD"): String {
        val kasten = ShiftDefinitionTexte.sonderfall(kategorie, code, erkannt = true)!!
        return (listOfNotNull(kasten.titel) + kasten.absaetze).joinToString("\n")
    }

    @Test
    fun `Einen Sonderfall-Kasten gibt es genau fuer Rufbereitschaft, Krank und Ignorieren`() {
        val mitKasten = ShiftKategorie.entries.filter {
            ShiftDefinitionTexte.sonderfall(it, "X", erkannt = true) != null
        }
        assertEquals(
            setOf(ShiftKategorie.RUFBEREITSCHAFT, ShiftKategorie.KRANK, ShiftKategorie.IGNORIEREN),
            mitKasten.toSet()
        )
        assertEquals("Sonderfall Rufbereitschaft", ShiftDefinitionTexte.sonderfall(ShiftKategorie.RUFBEREITSCHAFT, null, true)!!.titel)
        assertEquals("Sonderfall Krank", ShiftDefinitionTexte.sonderfall(ShiftKategorie.KRANK, null, true)!!.titel)
        assertNull("Ignorieren hat im Prototyp keinen Titel", ShiftDefinitionTexte.sonderfall(ShiftKategorie.IGNORIEREN, "TB", true)!!.titel)
    }

    /** Heute ueberspringen AlarmReceiver und HueSmartScheduler bei stillen Schichten die Hue-Regeln; und Licht am Typ gibt es erst in Etappe 4. */
    @Test
    fun `Kein Sonderfall-Text verspricht Licht`() {
        listOf(ShiftKategorie.RUFBEREITSCHAFT, ShiftKategorie.KRANK, ShiftKategorie.IGNORIEREN).forEach { kategorie ->
            val text = kastenText(kategorie)
            assertFalse("$kategorie: $text", text.contains("Licht"))
        }
    }

    /**
     * #172: ein nachgetragener Dienst nimmt dem Ruf-Typ nur den WECKER ab - Spanne, stuendliche Abfrage und
     * das Ende von „Nicht stoeren“ bleiben. Der Prototyp sagt „dessen Wecker und Einstellungen“.
     */
    @Test
    fun `Der Rufbereitschafts-Kasten begruendet die stuendliche Pruefung und uebertreibt die Uebernahme nicht`() {
        val text = kastenText(ShiftKategorie.RUFBEREITSCHAFT)
        assertTrue(text.contains("stündlich"))
        assertTrue("Ohne die Begruendung wirkt die Abfrage wie Akkuverschwendung", text.contains("nicht von selbst melden"))
        assertTrue(text.contains("Wecker-Einstellung dieses Dienstes"))
        assertTrue("Was bleibt, steht da", text.contains("Die stündliche Prüfung und das Ende von „Nicht stören“ bleiben"))
        assertTrue(text.contains("Steht nur die Rufbereitschaft im Kalender"))
        assertFalse("Dimmer und DND des Ruf-Typs wirken weiter", text.contains("Einstellungen"))
        assertFalse("„Endet spätestens um“ gehört in den Abschnitt „Nicht stören“ (Etappe 3)", text.contains("spätestens"))
    }

    @Test
    fun `Der Krank-Kasten sagt, dass der Dienst-Wecker entfaellt, und verspricht kein Eingestelltes`() {
        val text = kastenText(ShiftKategorie.KRANK)
        assertTrue(text.contains("der Dienst-Wecker entfällt"))
        assertTrue("Eigene Wecker bleiben - der Satz aus #142", text.contains("Eigene Wecker bleiben"))
        assertFalse("Krank hat bis Etappe 6 keinen Wecker-Block: es gilt nichts Eingestelltes", text.contains("Eingestellte"))
    }

    @Test
    fun `Der Ignorieren-Kasten ist ein Absatz mit dem Code - ohne Code faellt nur dieser Satz weg`() {
        val mit = ShiftDefinitionTexte.sonderfall(ShiftKategorie.IGNORIEREN, "TB", true)!!
        assertEquals("Ein Absatz wie im Prototyp", 1, mit.absaetze.size)
        val text = mit.absaetze.single()
        assertTrue(text.startsWith("Dieser Eintrag wird übergangen."))
        assertTrue("#167: ein Dienst im selben Termin gewinnt", text.contains("gilt der Dienst"))
        assertTrue("#173: ein ignorierter Termin zählt an einem sonst leeren Tag nicht als Eintrag", text.contains("gilt „Ohne Kalendereintrag“"))
        assertTrue(text.endsWith("Die App schlägt TB nicht mehr vor."))

        listOf(null, "", "  ").forEach { code ->
            val ohne = ShiftDefinitionTexte.sonderfall(ShiftKategorie.IGNORIEREN, code, true)!!.absaetze.single()
            assertFalse("Code=$code: $ohne", ohne.contains("schlägt"))
            assertTrue(ohne.contains("gilt „Ohne Kalendereintrag“"))
        }
    }

    /** Aus heisst „wird nicht erkannt“: dann gilt keiner dieser Saetze (der Schalter darueber sagt es). */
    @Test
    fun `Ein ausgeschalteter Typ hat keinen Sonderfall-Kasten`() {
        ShiftKategorie.entries.forEach { kategorie ->
            assertNull(kategorie.name, ShiftDefinitionTexte.sonderfall(kategorie, "X", erkannt = false))
        }
    }

    @Test
    fun `Der Umbenennungs-Hinweis nennt den alten Namen und sagt, dass der Nachzug scheitern kann`() {
        val text = ShiftDefinitionTexte.umbenennungHinweis("Frühschicht")
        assertTrue(text.contains("\"Frühschicht\""))
        assertTrue("Keine unbedingte Zusage", text.contains("nach Möglichkeit"))
        assertTrue(text.contains("Klappt das nicht"))
    }
}

/**
 * Umbau Etappe 1, #174: was der Schicht-Editor beim Speichern aus dem Formular baut. Eigene Klasse
 * in derselben Datei wie die Editor-Texte (seit #190 `TypTexte.kt` und `TypFormular.kt`).
 *
 * Gemeinsame Absicht: ein Test faengt, dass der Editor wieder ein NEUES `ShiftDefinition(...)`
 * baut (dann fielen Kategorie und Wecker-Schalter still auf ihren Default) oder die Flags nicht
 * mit der Kategorie setzt (dann sieht die Ruf-Abfrage die Rufbereitschaft nicht).
 */
class ShiftEditFormularTest {

    private fun dienst(
        name: String = "Frueh",
        keywords: List<String> = listOf("F", "IMCF"),
        zeit: LocalTime = LocalTime.of(5, 30)
    ) = ShiftDefinition(id = "id-1", name = name, keywords = keywords, alarmTime = zeit)

    private fun speichere(
        ausgang: ShiftDefinition?,
        kategorie: ShiftKategorie,
        name: String = ausgang?.name ?: "Neu",
        muster: List<String> = ausgang?.keywords ?: listOf("X"),
        zeit: LocalTime = ausgang?.alarmTime ?: LocalTime.of(6, 30),
        isEnabled: Boolean = ausgang?.isEnabled ?: true,
        weckerAn: Boolean = ausgang?.weckerAn ?: true,
        isSilent: Boolean = ausgang?.isSilent ?: false
    ) = ShiftEditFormular.speichern(
        ausgang = ausgang,
        name = name,
        muster = muster,
        alarmTime = zeit,
        isEnabled = isEnabled,
        weckerAn = weckerAn,
        isSilent = isSilent,
        kategorie = kategorie,
        neueId = { "neue-id" }
    )

    @Test
    fun `Speichern mit Kategorie Rufbereitschaft setzt isOnCall`() {
        val neu = speichere(ausgang = null, kategorie = ShiftKategorie.RUFBEREITSCHAFT)
        assertTrue("Die stuendliche Abfrage und das DND-Ende lesen das Flag", neu.isOnCall)
        assertFalse(neu.isSickDay)
        assertEquals(ShiftKategorie.RUFBEREITSCHAFT, neu.kategorie)
        assertEquals("neue-id", neu.id)
    }

    @Test
    fun `Speichern mit Kategorie Krank setzt isSickDay und nimmt isOnCall zurueck`() {
        val ruf = dienst().mitKategorie(ShiftKategorie.RUFBEREITSCHAFT)
        val krank = speichere(ausgang = ruf, kategorie = ShiftKategorie.KRANK)
        assertTrue(krank.isSickDay)
        assertFalse("Nie beide Flags", krank.isOnCall)
        assertEquals(ShiftKategorie.KRANK, krank.kategorie)
    }

    @Test
    fun `Urlaub mit Wecker aus ergibt einen Typ ohne Wecker`() {
        val urlaub = speichere(ausgang = null, kategorie = ShiftKategorie.URLAUB, weckerAn = false)
        assertFalse(urlaub.weckerAn)
        assertEquals(ShiftKategorie.URLAUB, urlaub.kategorie)
        assertFalse(urlaub.isSickDay)
        assertFalse(urlaub.isOnCall)
        assertTrue("Wecker aus heisst nicht „nicht erkannt“", urlaub.isEnabled)
    }

    @Test
    fun `Ein Kategoriewechsel behaelt Weckzeit, Muster und Einstellungen`() {
        val ausgang = dienst().copy(isSilent = true)
        // Hin und her ueber den Sonderfall Krank: nichts davon geht verloren (E2).
        val weg = speichere(ausgang, ShiftKategorie.KRANK)
        val zurueck = speichere(weg, ShiftKategorie.DIENST)
        listOf(weg, zurueck).forEach { typ ->
            assertEquals(LocalTime.of(5, 30), typ.alarmTime)
            assertEquals(listOf("F", "IMCF"), typ.keywords)
            assertTrue("Stummer Wecker bleibt stehen", typ.isSilent)
            assertTrue(typ.weckerAn)
            assertEquals("id-1", typ.id)
        }
        assertEquals(ShiftKategorie.DIENST, zurueck.kategorie)
    }

    /** Der Hinweis aus #166: ein neu gebautes ShiftDefinition(...) machte hieraus „Dienst, Wecker an“. */
    @Test
    fun `Nur den Namen aendern laesst Kategorie Urlaub und Wecker aus stehen`() {
        val urlaub = dienst(name = "Urlaub", keywords = listOf("U"))
            .copy(weckerAn = false)
            .mitKategorie(ShiftKategorie.URLAUB)
        val umbenannt = speichere(urlaub, ShiftKategorie.URLAUB, name = "  Erholungsurlaub ")
        assertEquals("Erholungsurlaub", umbenannt.name)
        assertEquals(ShiftKategorie.URLAUB, umbenannt.kategorie)
        assertFalse(umbenannt.weckerAn)
    }

    @Test
    fun `Ein vorbelegter, noch nicht gespeicherter Typ behaelt id und Voreinstellung`() {
        val vorlage = SchichttypEinordnen.neuerTypAusCode("FBE", ShiftKategorie.FORTBILDUNG) { "vorlage-id" }!!
        val gespeichert = speichere(vorlage, ShiftKategorie.FORTBILDUNG, name = "Fortbildung extern")
        assertEquals("vorlage-id", gespeichert.id)
        assertEquals(listOf("FBE"), gespeichert.keywords)
        assertEquals(vorlage.weckerAn, gespeichert.weckerAn)
        assertEquals(ShiftKategorie.FORTBILDUNG, gespeichert.kategorie)

        val urlaub = SchichttypEinordnen.neuerTypAusCode("U", ShiftKategorie.URLAUB) { "u-id" }!!
        assertFalse("Voreinstellung Urlaub: Wecker aus", urlaub.weckerAn)
        assertFalse(speichere(urlaub, ShiftKategorie.URLAUB, name = "Urlaub").weckerAn)
    }

    @Test
    fun `Die Weckzeit sperrt das Speichern nur, wenn sie etwas bewirkt`() {
        val ausgang = dienst(zeit = LocalTime.of(5, 30))
        // Wecker an, Dienst: eine kaputte Eingabe ist nicht speicherbar.
        assertNull(ShiftEditFormular.zeitZumSpeichern(ShiftKategorie.DIENST, true, null, ausgang))
        assertEquals(
            LocalTime.of(7, 0),
            ShiftEditFormular.zeitZumSpeichern(ShiftKategorie.DIENST, true, LocalTime.of(7, 0), ausgang)
        )
        // Wecker aus, Krank, Ignorieren: das Feld ist verborgen oder ausgegraut und sperrt nichts.
        listOf(
            Triple(ShiftKategorie.DIENST, false, "Wecker aus"),
            Triple(ShiftKategorie.KRANK, true, "Krank"),
            Triple(ShiftKategorie.IGNORIEREN, true, "Ignorieren")
        ).forEach { (kategorie, weckerAn, fall) ->
            assertEquals(fall, LocalTime.of(5, 30), ShiftEditFormular.zeitZumSpeichern(kategorie, weckerAn, null, ausgang))
        }
        // Ohne Vorgaenger gilt die Vorgabe der App (06:00), nicht 00:00 (spaeter eingeschaltet klaenge es um Mitternacht).
        assertEquals(
            LocalTime.of(6, 0),
            ShiftEditFormular.zeitZumSpeichern(ShiftKategorie.KRANK, true, null, null)
        )
    }

    @Test
    fun `Nur Krank und Ignorieren zeigen keinen Wecker-Block`() {
        val ohneBlock = ShiftKategorie.entries.filterNot { ShiftEditFormular.zeigtWeckerBlock(it) }
        assertEquals(listOf(ShiftKategorie.KRANK, ShiftKategorie.IGNORIEREN), ohneBlock)
    }

    @Test
    fun `Muster werden getrimmt, ohne Leere und ohne Doppelte gespeichert`() {
        assertEquals(
            listOf("F", "IMCF"),
            ShiftEditFormular.bereinigteMuster(listOf(" F ", "", "IMCF", "F", "  "))
        )
    }
}
