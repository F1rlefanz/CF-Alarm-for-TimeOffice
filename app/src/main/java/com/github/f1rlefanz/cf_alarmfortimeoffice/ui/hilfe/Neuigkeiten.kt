package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe

/** Ein Eintrag: `- **Kurzfassung:** Erklärung`. Ohne fettes Praefix ist [kurz] `null`. */
data class NeuigkeitenPunkt(val kurz: String?, val text: String)

/** Eine Rubrik: `### 🐛 Behoben`. */
data class NeuigkeitenRubrik(val titel: String, val punkte: List<NeuigkeitenPunkt>)

data class NeuigkeitenVersion(
    /** Nur die Nummer ("1.46.2"), `null`, wenn die Ueberschrift keine traegt. */
    val versionName: String?,
    val titel: String,
    val stand: String?,
    val zusammenfassung: String?,
    val rubriken: List<NeuigkeitenRubrik>
)

/**
 * Liest den Ausschnitt aus `CHANGELOG.md`, den der Build als Asset `neuigkeiten.md` beilegt
 * (Gradle-Aufgabe `neuigkeitenAusChangelog`).
 *
 * Die Schreibkonventionen stehen im Kopf von `CHANGELOG.md`; derselbe Text speist auch die
 * Website (`tools/changelog/build_changelog.py`). Was davon abweicht, wird nicht verworfen,
 * sondern als schlichter Text gezeigt - lieber ein ungeschminkter Eintrag als ein fehlender.
 */
object Neuigkeiten {
    const val ASSET = "neuigkeiten.md"

    private val VERSION = Regex("""Version\s+(\d+(?:\.\d+)+)""")
    private val PUNKT_MIT_KURZFASSUNG = Regex("""^\*\*(.+?)\*\*\s*(.*)$""")

    fun parse(markdown: String): List<NeuigkeitenVersion> {
        val versionen = mutableListOf<NeuigkeitenVersion>()
        var aktuell: Bau? = null
        for (roh in markdown.lines()) {
            val zeile = roh.trimEnd()
            when {
                zeile.startsWith("## ") -> {
                    aktuell?.let { versionen += it.fertig() }
                    val kopf = zeile.removePrefix("## ").trim()
                    val nummer = VERSION.find(kopf)?.groupValues?.get(1)
                    aktuell = Bau(nummer, nummer?.let { "Version $it" } ?: ohneMarkup(kopf))
                }
                aktuell == null -> Unit
                zeile.startsWith("### ") ->
                    aktuell.rubriken += NeuigkeitenRubrik(zeile.removePrefix("### ").trim(), mutableListOf())
                zeile.startsWith("**Stand:**") ->
                    aktuell.stand = zeile.removePrefix("**Stand:**").trim()
                zeile.startsWith("- ") -> aktuell.punkt(zeile.removePrefix("- ").trim())
                zeile.isBlank() -> Unit
                // Fortsetzung eines Eintrags ueber mehrere Zeilen
                roh.startsWith(" ") && aktuell.letzterPunktOffen() -> aktuell.fortsetzen(zeile.trim())
                aktuell.rubriken.isEmpty() && aktuell.zusammenfassung == null ->
                    aktuell.zusammenfassung = ohneMarkup(zeile)
                else -> aktuell.punkt(zeile.trim())
            }
        }
        aktuell?.let { versionen += it.fertig() }
        return versionen
    }

    /**
     * Die Version, die gerade laeuft. Ein Debug-Build heisst "1.46.2-DEBUG", deshalb zaehlt nur
     * die Nummer.
     */
    fun zurVersion(versionen: List<NeuigkeitenVersion>, versionName: String): NeuigkeitenVersion? {
        val nummer = VERSION.find("Version $versionName")?.groupValues?.get(1) ?: return null
        return versionen.firstOrNull { it.versionName == nummer }
    }

    /** Fett, kursiv, Code und Links als reinen Text - die Dialoge zeigen keine Auszeichnung. */
    fun ohneMarkup(text: String): String = text
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("""(?<![\p{L}\p{N}])_(.+?)_(?![\p{L}\p{N}])"""), "$1")
        .replace(Regex("""(?<![\p{L}\p{N}*])\*(?!\s)(.+?)(?<!\s)\*"""), "$1")
        .trim()

    private class Bau(val versionName: String?, val titel: String) {
        var stand: String? = null
        var zusammenfassung: String? = null
        val rubriken = mutableListOf<NeuigkeitenRubrik>()

        fun punkt(zeile: String) {
            if (rubriken.isEmpty()) rubriken += NeuigkeitenRubrik("", mutableListOf())
            val treffer = PUNKT_MIT_KURZFASSUNG.find(zeile)
            val neu = if (treffer != null) {
                NeuigkeitenPunkt(
                    ohneMarkup(treffer.groupValues[1]).removeSuffix(":").trim(),
                    ohneMarkup(treffer.groupValues[2].trimStart(':', ' '))
                )
            } else {
                NeuigkeitenPunkt(null, ohneMarkup(zeile))
            }
            (rubriken.last().punkte as MutableList) += neu
        }

        fun letzterPunktOffen(): Boolean = rubriken.lastOrNull()?.punkte?.isNotEmpty() == true

        fun fortsetzen(zeile: String) {
            val punkte = rubriken.last().punkte as MutableList
            val letzter = punkte.removeAt(punkte.lastIndex)
            punkte += letzter.copy(text = (letzter.text + " " + ohneMarkup(zeile)).trim())
        }

        fun fertig() = NeuigkeitenVersion(
            versionName, titel, stand, zusammenfassung,
            rubriken.filter { it.punkte.isNotEmpty() || it.titel.isNotEmpty() }
        )
    }
}
