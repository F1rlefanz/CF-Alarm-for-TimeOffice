package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.api

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * #138: Ein Deckel um einen Hue-Aufruf muss den Aufruf auch BEENDEN.
 *
 * Bis 1.49.2 lief `execute()` blockierend in `withContext(IO)`. Ein `withTimeoutOrNull(3.seconds)`
 * darum - der "Kurzversuch" ausser Haus - wartete trotzdem das volle `connectTimeout` von 10 s
 * ab; am Fairphone stand "gedeckelt auf 3s" und direkt danach "failed to connect ... after
 * 10000ms". Die Manager-Tests konnten das nicht sehen: sie mocken den Client, und ein `delay()`
 * im Mock ist abbrechbar.
 *
 * Hier haengt deshalb ein ECHTER OkHttp-Call (ein Interceptor blockiert den Thread wie ein
 * Socket-Connect), und gemessen wird die Wanduhr.
 */
class HueApiClientAbbruchTest {

    @Test
    fun `ein abgelaufener Deckel beendet einen haengenden Call sofort`() {
        val haengend = OkHttpClient.Builder()
            .addInterceptor { chain ->
                // Blockiert wie ein Connect ins Leere; wird vom Abbruch nicht unterbrochen.
                try {
                    Thread.sleep(10_000)
                } catch (_: InterruptedException) {
                }
                chain.proceed(chain.request())
            }
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        val client = HueApiClient(context = null, testClient = haengend)

        val start = System.nanoTime()
        val ergebnis = runBlocking {
            withTimeoutOrNull(1.seconds) {
                client.getBridgeConfig("192.168.178.24", "user")
            }
        }
        val dauerMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        assertNull(ergebnis)
        assertTrue("Deckel 1 s, gewartet wurden $dauerMs ms", dauerMs < 4_000)
    }
}
