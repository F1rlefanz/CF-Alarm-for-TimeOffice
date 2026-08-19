package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import com.github.f1rlefanz.cf_alarmfortimeoffice.util.text.UIText
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Haelt fest, dass die Meldung zu `NoCredentialException` dem Nutzer sagt, was er TUN kann.
 *
 * Hergang: Am 19.08.2026 stand dort "Anmeldung gerade nicht moeglich. Bitte noch einmal
 * versuchen." Am frischen Emulator ohne eingerichtetes Google-Konto war das gemessen die
 * Meldung - und "noch einmal versuchen" haette dort NIE geholfen. Der Satz war nicht falsch,
 * nur nutzlos: Er nannte keine der beiden moeglichen Ursachen, und die App kann sie aus der
 * Exception nicht unterscheiden (dieselbe Exception trat am 14.07.2026 MIT vorhandenem Konto
 * auf). Deshalb muss der Text beide Wege nennen.
 */
class AnmeldeFehlertextTest {

    private val text = CredentialAuthManager.FEHLER_KEIN_CREDENTIAL

    @Test
    fun `nennt das fehlende Konto als moegliche Ursache`() {
        assertTrue(
            "Der Text muss das nicht eingerichtete Google-Konto als Ursache nennen - sonst " +
                "sucht der Nutzer den Fehler in der App statt in den Android-Einstellungen.",
            text.contains("Google-Konto", ignoreCase = true) &&
                text.contains("eingerichtet", ignoreCase = true)
        )
    }

    @Test
    fun `nennt den zweiten Versuch als anderen Weg`() {
        assertTrue(
            "Die zweite Ursache (Google-Dienste brauchen nach einer Neuinstallation kurz) muss " +
                "erhalten bleiben - sie war der Grund, warum der Text 2026 ueberhaupt geaendert wurde.",
            text.contains("zweiter Tipp", ignoreCase = true) ||
                text.contains("noch einmal", ignoreCase = true)
        )
    }

    @Test
    fun `behauptet keine einzelne Ursache`() {
        assertTrue(
            "Der Text darf sich nicht auf EINE Ursache festlegen - die App kann sie nicht " +
                "unterscheiden. 'Entweder ... oder' ist die Zusicherung.",
            text.contains("Entweder", ignoreCase = true) && text.contains("Oder", ignoreCase = true)
        )
    }

    @Test
    fun `LoginScreen bietet den Sprung in die Kontoeinstellungen an - und nur bei dieser Meldung`() {
        val quelle = File(
            "src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/ui/screens/LoginScreen.kt"
        ).readText()
        assertTrue(
            "Der Knopf muss gegen die Konstante vergleichen, nicht gegen einen Textausschnitt.",
            quelle.contains("error == CredentialAuthManager.FEHLER_KEIN_CREDENTIAL")
        )
        assertTrue(
            "Der Knopf darf nur erscheinen, wenn das Geraet die Einstellungsseite wirklich hat - " +
                "kein Knopf darf einen Ablauf behaupten, den es nicht gibt.",
            quelle.contains("resolveActivity(context.packageManager)")
        )
        assertTrue(
            "Der Knopf traegt den zentralen Text.",
            quelle.contains("UIText.ADD_GOOGLE_ACCOUNT") &&
                UIText.ADD_GOOGLE_ACCOUNT.contains("Einstellungen")
        )
    }
}
