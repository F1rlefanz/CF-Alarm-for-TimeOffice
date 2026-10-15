package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

/** Ein Postleitzahl-Praefix mit dem Schwerpunkt seiner Orte (#140). */
data class PlzRegion(
    val land: PlzLand,
    val praefix: String,
    val breite: Double,
    val laenge: Double,
    /** Anzeigename: der Ort, dessen PLZ dem Schwerpunkt am naechsten liegt. */
    val name: String
)

/**
 * Eingebaute Zuordnung PLZ-Praefix -> ungefaehrer Ort, fuer die Stufe "Von Hand" (#140).
 *
 * QUELLE UND LIZENZ: GeoNames (www.geonames.org), CC BY 4.0 - aggregiert zu Regions-Schwerpunkten.
 * Lizenztext: `licenses/GeoNames-CC-BY-4.0.txt`; die Daten liegen unter
 * `app/src/main/assets/tageslicht/plz_<land>.tsv`, erzeugt von `tools/tageslicht/baue_plz_tabelle.py`.
 * Die Namensnennung steht ausserdem im Ort-Bildschirm und auf der Website.
 */
class PlzTabelle(eintraege: List<PlzRegion>) {

    private val nachSchluessel: Map<Pair<PlzLand, String>, PlzRegion> =
        eintraege.associateBy { it.land to it.praefix }

    /**
     * Laengster bekannter Praefix der Eingabe, mindestens [MIN_ZIFFERN] Ziffern; sonst `null`.
     * Die Eingabe muss (nach Trimmen) nur aus Ziffern bestehen - "D-80331" wird abgelehnt statt
     * still zu "80331" bereinigt, damit nie eine Region angezeigt wird, die der Nutzer so nicht
     * eingegeben hat.
     */
    fun suche(land: PlzLand, plz: String): PlzRegion? {
        val ziffern = plz.trim()
        if (ziffern.length < MIN_ZIFFERN || !ziffern.all { it in '0'..'9' }) return null
        for (laenge in ziffern.length downTo MIN_ZIFFERN) {
            nachSchluessel[land to ziffern.substring(0, laenge)]?.let { return it }
        }
        return null
    }

    companion object {
        const val MIN_ZIFFERN = 2

        /**
         * Liest das Asset-Format: Zeilen `praefix<TAB>breite<TAB>laenge<TAB>name`; Zeilen mit `#`
         * sind Kopf/Quelle. Leere oder unlesbare Zeilen werden uebersprungen statt zu werfen - eine
         * kaputte Zeile kostet eine Region, nicht die ganze Tabelle.
         *
         * Zeilenenden: `lines()` + `trim()`, weil die Windows-Arbeitskopie das Asset mit CRLF
         * auscheckt, Git und CI mit LF (Skill cfalarm-bauen-und-testen).
         */
        fun parse(land: PlzLand, text: String): PlzTabelle = PlzTabelle(
            text.lines().mapNotNull { roh ->
                val zeile = roh.trim()
                if (zeile.isEmpty() || zeile.startsWith("#")) return@mapNotNull null
                val teile = zeile.split('\t')
                if (teile.size < 4) return@mapNotNull null
                val praefix = teile[0].trim()
                val breite = teile[1].trim().toDoubleOrNull() ?: return@mapNotNull null
                val laenge = teile[2].trim().toDoubleOrNull() ?: return@mapNotNull null
                if (praefix.isEmpty() || !praefix.all { it in '0'..'9' }) return@mapNotNull null
                PlzRegion(land, praefix, breite, laenge, teile[3].trim())
            }
        )

        fun leer() = PlzTabelle(emptyList())
    }
}
