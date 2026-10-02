package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarPreAlarmRefreshScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.AnmeldeWiederherstellung
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.CredentialAuthManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.AppErrorState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.UserAuthState
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.BackgroundServiceManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAuthUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * AuthViewModel - Anmeldung über CredentialAuthManager (androidx.credentials); hält
 * hasSelectedCalendars mit der Kalenderauswahl synchron.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authDataStoreRepository: IAuthDataStoreRepository,
    private val credentialAuthManager: CredentialAuthManager,
    private val errorHandler: ErrorHandler,
    private val authUseCase: IAuthUseCase,
    private val calendarSelectionRepository: ICalendarSelectionRepository,
    private val backgroundServiceManager: BackgroundServiceManager,
    private val tokenRepository: TokenRepository,
    // ---- Abmelde-Aufraeumen (Pruefrunde 8, Befund 3) -------------------------------------------
    // Alles ab hier wird AUSSCHLIESSLICH von [stopScheduledWorkForSignOut] gebraucht. Warum es
    // hier im ViewModel haengt und nicht im AuthUseCase, steht dort im KDoc von `signOut()`.
    private val alarmUseCase: IAlarmUseCase,
    private val shiftSpanStore: ShiftSpanStore,
    private val dimSchedule: DimScheduleUseCase,
    private val dndSchedule: DndScheduleUseCase,
    private val hueSmartScheduler: HueSmartScheduler,
    private val calendarPreAlarmRefreshScheduler: CalendarPreAlarmRefreshScheduler,
    @param:ApplicationContext private val appContext: Context,
    // Zero-Tap-Wiederherstellung der Anmeldung (#55) - siehe [starteAnmeldeWiederherstellung].
    private val anmeldeWiederherstellung: AnmeldeWiederherstellung
) : ViewModel() {

    /**
     * Fuer Aufrufer, die die Zero-Tap-Wiederherstellung nicht betrachten (bestehende Unit-Tests):
     * dieselbe Klasse mit abgeschalteter Wiederherstellung ([AnmeldeWiederherstellung.Aus]).
     *
     * WARUM EIN ZWEITER KONSTRUKTOR STATT EINES DEFAULT-WERTS: Hilt/Dagger sieht nur den
     * `@Inject`-Konstruktor; ob ein Kotlin-Default-Wert dort auf dem KSP-Weg sauber ignoriert
     * wird, ist nicht belegt. So bleibt der Produktionsweg eindeutig.
     */
    constructor(
        authDataStoreRepository: IAuthDataStoreRepository,
        credentialAuthManager: CredentialAuthManager,
        errorHandler: ErrorHandler,
        authUseCase: IAuthUseCase,
        calendarSelectionRepository: ICalendarSelectionRepository,
        backgroundServiceManager: BackgroundServiceManager,
        tokenRepository: TokenRepository,
        alarmUseCase: IAlarmUseCase,
        shiftSpanStore: ShiftSpanStore,
        dimSchedule: DimScheduleUseCase,
        dndSchedule: DndScheduleUseCase,
        hueSmartScheduler: HueSmartScheduler,
        calendarPreAlarmRefreshScheduler: CalendarPreAlarmRefreshScheduler,
        appContext: Context
    ) : this(
        authDataStoreRepository = authDataStoreRepository,
        credentialAuthManager = credentialAuthManager,
        errorHandler = errorHandler,
        authUseCase = authUseCase,
        calendarSelectionRepository = calendarSelectionRepository,
        backgroundServiceManager = backgroundServiceManager,
        tokenRepository = tokenRepository,
        alarmUseCase = alarmUseCase,
        shiftSpanStore = shiftSpanStore,
        dimSchedule = dimSchedule,
        dndSchedule = dndSchedule,
        hueSmartScheduler = hueSmartScheduler,
        calendarPreAlarmRefreshScheduler = calendarPreAlarmRefreshScheduler,
        appContext = appContext,
        anmeldeWiederherstellung = AnmeldeWiederherstellung.Aus
    )

    companion object {
        /**
         * Nutzertext fuer den Fall, dass beim Abmelden nicht alle Wecker weggeraeumt werden
         * konnten. Beschreibt die WIRKUNG und einen Ausweg, den es in der App wirklich gibt
         * (der Schalter in den Einstellungen raeumt ueber dieselbe zentrale Operation) - und
         * nennt bewusst keine Systemeinstellung, deren Name sich zwischen Android-Versionen
         * verschiebt.
         */
        const val FEHLER_ABMELDEN_WECKER_GEBLIEBEN: String =
            "Du bist abgemeldet, aber es liessen sich nicht alle gestellten Wecker entfernen - " +
                "einzelne koennten noch klingeln. Melde dich kurz wieder an und lege in den " +
                "Einstellungen den Schalter \"Hintergrunddienste pausieren\" um; das raeumt sie weg."

        /**
         * Nutzertext fuer die halbe Abmeldung: das Kalender-Token ist verworfen, das Loeschen der
         * Auth-Daten ist danach gescheitert. Der Nutzer sieht sich also noch als angemeldet,
         * kommt aber an keinen Kalender mehr - das Aufraeumen ist trotzdem gelaufen (siehe
         * [signOut], Abschnitt "Punkt ohne Wiederkehr").
         *
         * Der genannte Knopf steht auf dem Kalender-Autorisierungsbildschirm (siehe [signOut],
         * Befund B) und loest `signOut()` erneut aus.
         */
        const val FEHLER_ABMELDEN_UNVOLLSTAENDIG: String =
            "Die Abmeldung ist nur halb gelungen: Der Kalender-Zugriff ist bereits entzogen und " +
                "deine gestellten Wecker wurden entfernt, aber die App zeigt dich noch als " +
                "angemeldet. Tippe unten auf \"Mit anderem Konto anmelden\", um die Abmeldung " +
                "abzuschliessen. Einen von Hand gestellten Wecker musst du danach selbst neu setzen."

        /**
         * Nutzertext fuer den doppelten Fehlschlag: Token verworfen, Auth-Daten nicht geloescht,
         * UND das Aufraeumen misslungen.
         *
         * Der Ausweg ist derselbe Knopf wie in [FEHLER_ABMELDEN_UNVOLLSTAENDIG] und leistet hier
         * beides auf einmal: ein zweiter `signOut()` schliesst die Abmeldung ab UND laesst
         * [stopScheduledWorkForSignOut] noch einmal laufen. Frueher stand hier der Schalter
         * "Hintergrunddienste pausieren" aus den Einstellungen - der ist von diesem Bildschirm
         * aus nicht erreichbar und war damit eine Sackgasse.
         */
        const val FEHLER_ABMELDEN_UNVOLLSTAENDIG_WECKER_GEBLIEBEN: String =
            "Die Abmeldung ist nur halb gelungen: Der Kalender-Zugriff ist entzogen, die App " +
                "zeigt dich aber noch als angemeldet, und es liessen sich nicht alle gestellten " +
                "Wecker entfernen - einzelne koennten noch klingeln. Tippe unten auf \"Mit " +
                "anderem Konto anmelden\": das schliesst die Abmeldung ab und raeumt die Wecker " +
                "erneut weg."

        /**
         * Ladetext, solange beim Start ein Restore-Schluessel gesucht wird (#55).
         *
         * Bewusst "Suche nach", nicht "wird uebernommen": der Text steht auch bei JEDER frischen
         * Installation ohne Vorgaenger-Geraet da - das ist sogar der haeufigste Fall. Er darf
         * nichts behaupten, was es nicht gibt.
         */
        const val TEXT_WIEDERHERSTELLUNG_LAEUFT: String =
            "Suche nach einer Anmeldung vom alten Gerät …"

        /**
         * Deckel fuer das Lesen beim Start (Entscheidung 02.10.2026). Solange gelesen wird, sieht
         * der Nutzer nur den Ladetext - laenger darf die Funktion die normale Anmeldung nicht
         * aufhalten.
         */
        internal const val LESEN_DECKEL_MS: Long = 5_000L

        /**
         * Deckel fuer das Anlegen. Laeuft im Hintergrund, haelt aber eine Activity-Referenz und
         * die Schluessel-Sperre, auf die ein gleichzeitiges Abmelden wartet - deshalb begrenzt.
         * Doppelt so lang wie das Lesen, weil ohne Ende-zu-Ende-Backup ein zweiter Versuch folgt.
         */
        internal const val ANLEGEN_DECKEL_MS: Long = 10_000L

        /** Deckel fuer das Loeschen beim Abmelden - das Abmelden laeuft nicht abbrechbar. */
        internal const val LOESCHEN_DECKEL_MS: Long = 5_000L
    }

    private val _authState = MutableStateFlow(AuthState.EMPTY)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val uiState: StateFlow<AuthState> = authState

    /**
     * Einmal-Signal: "Das Token ist zur Laufzeit weggefallen, hol die Zustimmung neu ein."
     *
     * WARUM EIN EVENT UND KEIN STATE-FLAG: Den Zustimmungsdialog kann nur eine Activity starten
     * (die Play-Dienste liefern einen PendingIntent, kein Ergebnis) - hier unten gibt es keine. Ein
     * State-Flag würde bei jeder Recomposition erneut ausgelöst; das Event wird genau einmal
     * konsumiert.
     *
     * CONFLATED: Stirbt das Token, während die App im Hintergrund ist, wartet das Signal im
     * Channel, bis MainActivity wieder RESUMED ist. Ein Activity-Start aus dem Hintergrund würde
     * von Android verworfen - das Signal wäre verbraucht und der Dialog käme nie.
     */
    private val _reauthRequired = Channel<Unit>(Channel.CONFLATED)
    val reauthRequired: Flow<Unit> = _reauthRequired.receiveAsFlow()

    /**
     * Meldet jede GELUNGENE Kalender-Autorisierung - sofort erteilt, nach dem Zustimmungsdialog
     * oder ohne Activity. MainActivity laedt daran die Termine neu.
     *
     * WARUM ES DAS BRAUCHT (30.09.2026, am Fairphone): Die Warnung "Kalender-Autorisierung
     * verloren" haengt am Ergebnis des letzten Terminabrufs im CalendarViewModel, nicht an
     * [AuthUiState]. Nach "Kalender-Zugriff erneuern" war der Zugriff da und die Wartung lief an -
     * die Karte blieb trotzdem rot, weil niemand die Termine neu abrief. Viermal getippt, viermal
     * "tut nichts". [requestCalendarAuthorization] hat viele Aufrufer (Knoepfe, Gate,
     * Auto-Re-Auth, nach der Anmeldung); ein Signal HIER erreicht sie alle, statt dass jeder
     * seinen eigenen Nachlauf braucht - und ein kuenftiger Aufrufer ihn vergisst.
     *
     * CONFLATED wie [reauthRequired]: zweimal schnell hintereinander erteilt ist einmal neu laden.
     */
    private val _kalenderZugriffErneuert = Channel<Unit>(Channel.CONFLATED)
    val kalenderZugriffErneuert: Flow<Unit> = _kalenderZugriffErneuert.receiveAsFlow()

    /**
     * Beim Abmelden verwirft die App das Kalender-Token absichtlich selbst.
     *
     * WARUM DAS EIN FLAG BRAUCHT: [observeTokenLoss] deutet ein verschwundenes Token als "Google
     * hat den Zugriff entzogen" und stößt den Zustimmungsdialog an. Beim Abmelden wäre das genau
     * verkehrt — der Nutzer wollte gerade hinaus und bekäme stattdessen einen Dialog vorgesetzt,
     * der ihn wieder hineinbittet.
     *
     * WARUM NICHT EINFACH isSignedIn PRÜFEN: Die DataStore-Emission trifft asynchron ein und kann
     * das ViewModel erreichen, bevor der State auf EMPTY steht (observeAuthState ist zusätzlich
     * um 200ms entprellt). Das Flag wird deshalb VOR dem Verwerfen gesetzt und erst bei der
     * nächsten Anmeldung zurückgenommen — ein Zurücksetzen am Ende von signOut() käme zu früh.
     */
    @Volatile
    private var signOutInProgress = false

    /**
     * Die Start-Pruefung der Wiederherstellung laeuft hoechstens EINMAL je ViewModel (also nicht
     * nach jeder Drehung erneut). Auf dem alten Geraet findet ein spaeterer Versuch nach dem
     * Abmelden ohnehin nichts - der Schluessel ist dann geloescht.
     *
     * Steht VOR dem init{}-Block (CLAUDE.md: sonst ist sie beim ersten Zugriff noch nicht belegt).
     */
    private var wiederherstellungAngestossen = false

    /**
     * Sperre zwischen ANLEGEN und LOESCHEN des Restore-Schluessels.
     *
     * WARUM: Ein Abmelden, waehrend das Anlegen noch laeuft, loeschte sonst zuerst, und das
     * Anlegen schriebe danach einen frischen Schluessel - ein verwaister Schluessel, der nach
     * einer Neuinstallation STILL wieder anmeldet ("Abmelden heisst: nichts bleibt zurueck").
     * Mit der Sperre wartet das Loeschen auf das Ende des Anlegens (gedeckelt auf
     * [ANLEGEN_DECKEL_MS]) und kommt damit garantiert danach.
     */
    private val wiederherstellungsSchluesselSperre = Mutex()

    private fun updateAuthState(updateFunc: (AuthState) -> AuthState) {
        val currentState = _authState.value
        val newState = updateFunc(currentState)

        if (currentState != newState) {
            _authState.value = newState
        }
    }

    private fun markiereTokenUngueltig() {
        updateAuthState { currentState ->
            currentState.copy(
                calendarOps = currentState.calendarOps.copy(
                    hasValidToken = false,
                    tokenChecked = true // GATE: Ergebnis steht fest -> das Onboarding-Gate darf entscheiden
                )
            )
        }
    }

    private fun meldeAutorisierungFehlgeschlagen(error: Throwable) {
        updateAuthState { currentState ->
            currentState.copy(
                calendarOps = currentState.calendarOps.copy(
                    calendarsLoading = false,
                    hasValidToken = false,
                    tokenChecked = true // GATE: Autorisierungsversuch entschieden
                ),
                errors = AppErrorState.authenticationError("Calendar-Autorisierung fehlgeschlagen: ${error.message}")
            )
        }
    }

    init {
        observeAuthState()
        checkInitialAuthState()
        observeCalendarSelection() // REACTIVE CALENDAR: Observer für Calendar-Selection-Änderungen
        observeTokenLoss() // AUTO-RE-AUTH: Token-Verlust zur Laufzeit erkennen
    }

    /**
     * Schließt den reaktiven Token-Pfad an: fällt das gespeicherte Token weg, erfährt die UI davon.
     *
     * DAS PROBLEM: OAuth2TokenManager.invalidate() verwirft das Token bei 401/403 still. Ohne
     * diesen Observer blieb calendarOps.hasValidToken auf dem Wert vom App-Start stehen ("stale
     * true") - das Gate in MainActivity feuerte nie und der Nutzer saß in einer Haupt-UI, deren
     * Kalender-Zugriff längst tot war.
     *
     * WARUM NUR DAS NEGATIVE SIGNAL: hasValidToken bedeutet "getValidToken() klappt gerade",
     * inklusive Refresh eines abgelaufenen Tokens (AuthUseCase.hasCalendarAuthorization).
     * "Liegt im Store" ist schwächer - ein totes, aber noch nicht verworfenes Token würde das Gate
     * fälschlich aufmachen. Das FEHLEN eines Tokens ist dagegen eindeutig. Die positiven Übergänge
     * bleiben deshalb bei den bestehenden Schreibern: checkInitialTokenValidity() beim Start und
     * dem Ergebnis von requestCalendarAuthorization().
     *
     * WARUM drop(1): Die erste Emission ist der Ist-Zustand beim Start, kein Verlust. Ohne drop
     * würde jeder Kaltstart ohne Token (frische Installation, abgemeldet) als "Token verloren"
     * gelten und einen Dialog auslösen - beim frischen Sign-in zusätzlich zu dem, den signIn()
     * ohnehin schon anstößt.
     */
    private fun observeTokenLoss() {
        viewModelScope.launch(Dispatchers.IO) {
            tokenRepository.observe()
                .map { it != null }
                .distinctUntilChanged()
                .drop(1)
                .collect { hasToken ->
                    if (hasToken) return@collect

                    // Beim Abmelden verwirft die App das Token selbst - das ist kein
                    // Zugriffsverlust. Siehe [signOutInProgress], warum isSignedIn allein hier
                    // nicht reicht.
                    if (signOutInProgress) {
                        Logger.d(
                            LogTags.AUTH,
                            "🔑 AUTO-RE-AUTH: Token beim Abmelden verworfen - keine Re-Autorisierung"
                        )
                        return@collect
                    }

                    // Zweiter Wächter für alle übrigen Wege, auf denen das Token ohne aktive
                    // Anmeldung verschwinden kann.
                    if (!_authState.value.isSignedIn) {
                        Logger.d(
                            LogTags.AUTH,
                            "🔑 AUTO-RE-AUTH: Token weg, aber nicht angemeldet - keine Re-Autorisierung"
                        )
                        return@collect
                    }

                    Logger.business(
                        LogTags.AUTH,
                        "🔑 AUTO-RE-AUTH: Token zur Laufzeit verworfen - fordere Zustimmung neu an"
                    )

                    markiereTokenUngueltig()

                    // Nur die Activity kann den Dialog starten - MainActivity konsumiert das Event.
                    _reauthRequired.trySend(Unit)
                }
        }
    }

    /**
     * Observes auth data changes from DataStore.
     * Automatically loads calendars after successful authorization.
     */
    @OptIn(FlowPreview::class)
    private fun observeAuthState() {
        viewModelScope.launch(Dispatchers.IO) { // PERFORMANCE: Background thread only
            authDataStoreRepository.authData
                .debounce(200.milliseconds) // PERFORMANCE: Reduced from 300ms for better responsiveness
                .distinctUntilChanged { old, new ->
                    // PERFORMANCE: Only update if meaningful changes occurred
                    old.isLoggedIn == new.isLoggedIn &&
                            old.email == new.email &&
                            old.accessToken == new.accessToken
                }
                .collect { authData ->
                    // UI THREAD OPTIMIZATION: Atomic update without context switching
                    updateAuthState { currentState ->
                        currentState.copy(
                            userAuth = UserAuthState(
                                isSignedIn = authData.isLoggedIn,
                                userEmail = authData.email,
                                displayName = authData.displayName,
                                accessToken = authData.accessToken,
                                hasValidToken = !authData.accessToken.isNullOrEmpty()
                            )
                        )
                    }
                }
        }
    }

    /**
     * Checks initial authentication state on ViewModel initialization.
     *
     * GENAU EINMAL (`first()`, #129). Bis v1.45 stand hier `collect { ...; return@collect }` mit
     * dem Kommentar "nur einmal" - `return@collect` verlaesst aber nur das Lambda, der
     * DataStore-Flow endet nie. Jede spaetere Emission (Anmelden, Abmelden) startete die
     * Start-Pruefung erneut, und deren `checkInitialTokenValidity()` lief NEBEN der gerade
     * laufenden Autorisierung: kam ihr Ergebnis nach dem Erfolg an, setzte es `hasValidToken`
     * wieder auf `false`. Folge-Emissionen braucht hier niemand - [observeAuthState] fuehrt
     * `userAuth` laufend nach, und Anmelden/Abmelden setzen den Token-Zustand selbst
     * ([requestCalendarAuthorization], [signOut], [observeTokenLoss]).
     */
    private fun checkInitialAuthState() {
        viewModelScope.launch {
            try {
                val authData = authDataStoreRepository.authData.first()
                updateAuthState { currentState ->
                    currentState.copy(
                        userAuth = UserAuthState(
                            hasValidToken = authData.isLoggedIn,
                            userEmail = authData.email,
                            displayName = authData.displayName,
                            isSignedIn = authData.isLoggedIn,
                            accessToken = authData.accessToken
                        )
                    )
                }

                // REACTIVE CALENDAR: Check initial calendar selection status
                checkInitialCalendarSelection()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal lifecycle cancellation - rethrow for proper structured concurrency
                throw e
            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "Error checking initial auth state", e)
            }
        }
    }

    /**
     * REACTIVE CALENDAR: Checks initial calendar selection status on startup
     * Ensures hasSelectedCalendars flag is correctly set on app startup
     */
    private fun checkInitialCalendarSelection() {
        viewModelScope.launch {
            try {
                val selectedIds = calendarSelectionRepository.getCurrentSelectedCalendarIds()
                    .getOrElse { emptySet() }
                val hasSelectedCalendars = selectedIds.isNotEmpty()

                updateAuthState { currentState ->
                    currentState.copy(
                        calendarOps = currentState.calendarOps.copy(
                            hasSelectedCalendars = hasSelectedCalendars
                        )
                    )
                }

            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "Error checking initial calendar selection", e)
            } finally {
                // 🔧 STUFE 2 / GATE: Always run the token check so tokenChecked is set even if the
                // selection check fails — otherwise the onboarding gate would hang on the loading screen.
                checkInitialTokenValidity()
            }
        }
    }

    /**
     * 🔧 STUFE 2: Checks initial Calendar API token validity on startup
     * Ensures hasValidToken flag is correctly set based on actual token status
     */
    private fun checkInitialTokenValidity() {
        viewModelScope.launch {
            try {

                // Check if we have a valid Calendar token
                val tokenValidResult = authUseCase.hasCalendarAuthorization()
                val tokenValid = tokenValidResult.getOrElse { false }

                Logger.business(
                    LogTags.AUTH,
                    "🔍 STUFE-2: Initial token validity check result: $tokenValid"
                )

                updateAuthState { currentState ->
                    currentState.copy(
                        calendarOps = currentState.calendarOps.copy(
                            hasValidToken = tokenValid,
                            tokenChecked = true // GATE: initial check complete -> safe to gate on result
                        )
                    )
                }

                if (!tokenValid && _authState.value.calendarOps.hasSelectedCalendars) {
                    Logger.w(
                        LogTags.AUTH,
                        "⚠️ STUFE-2: User has selected calendars but token is invalid - re-authorization required"
                    )
                }
            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "❌ STUFE-2: Error checking initial token validity", e)
                // On error, assume token is invalid to be safe
                markiereTokenUngueltig()
            }
        }
    }

    /**
     * REACTIVE CALENDAR SELECTION: Observes calendar selection changes
     *
     * BUG FIX: Automatically synchronizes hasSelectedCalendars flag with CalendarSelectionRepository
     * Solves the issue where Calendar-Berechtigung card appears after restart even when calendars are selected
     */
    @OptIn(FlowPreview::class)
    private fun observeCalendarSelection() {
        viewModelScope.launch(Dispatchers.IO) { // PERFORMANCE: Background thread only
            calendarSelectionRepository.selectedCalendarIds
                .debounce(150.milliseconds) // PERFORMANCE: Debounce to prevent excessive updates
                .distinctUntilChanged { old, new ->
                    // PERFORMANCE: Only update if selection actually changed
                    old.size == new.size && old == new
                }
                .collect { selectedIds ->
                    val hasSelectedCalendars = selectedIds.isNotEmpty()

                    // UI THREAD OPTIMIZATION: Atomic update without context switching
                    updateAuthState { currentState ->
                        currentState.copy(
                            calendarOps = currentState.calendarOps.copy(
                                hasSelectedCalendars = hasSelectedCalendars
                            )
                        )
                    }
                }
        }
    }

    /**
     * MODERN AUTH: Sign in using CredentialAuthManager
     */
    fun signIn(context: Context) {
        viewModelScope.launch {
            // Hier - und nur hier - wird die Abmelde-Sperre wieder gelöst: ab jetzt ist ein
            // verschwindendes Token wieder ein echter Zugriffsverlust und darf die
            // Re-Autorisierung anstoßen. Siehe [signOutInProgress].
            signOutInProgress = false

            updateAuthState { currentState ->
                currentState.copy(
                    calendarOps = currentState.calendarOps.copy(calendarsLoading = true),
                    errors = AppErrorState.EMPTY
                )
            }

            try {
                Logger.business(LogTags.AUTH, "Starting modern credential sign-in")
                val signInResult = credentialAuthManager.signIn(context)

                if (signInResult.success && signInResult.credentialResponse != null) {
                    // Extract user info from credential
                    val (_, displayName, initialEmail) = credentialAuthManager.extractUserInfo(
                        signInResult.credentialResponse
                    )

                    if (!initialEmail.isNullOrEmpty()) {
                        val authData = AuthData(
                            isLoggedIn = true,
                            email = initialEmail,
                            displayName = displayName,
                            accessToken = null // Real tokens managed by ModernOAuth2TokenManager
                        )

                        // Auth-Zustand liegt ausschliesslich in authDataStoreRepository (DataStore).
                        authDataStoreRepository.updateAuthData(authData)
                            .onSuccess {
                                updateAuthState { currentState ->
                                    currentState.copy(
                                        userAuth = UserAuthState.authenticated(
                                            initialEmail,
                                            displayName ?: "",
                                            null
                                        ),
                                        calendarOps = currentState.calendarOps.copy(calendarsLoading = false)
                                    )
                                }
                                Logger.business(LogTags.AUTH, "✅ Sign-in successful")

                                // Automatically trigger Calendar authorization
                                Logger.business(
                                    LogTags.AUTH,
                                    "🔄 AUTO-FLOW: Triggering Calendar authorization"
                                )
                                val activity = context as? android.app.Activity
                                requestCalendarAuthorization(activity)

                                // Zero-Tap-Wiederherstellung fuer ein kuenftiges neues Geraet
                                // (#55). Danach, nicht davor: ein Fehlschlag hier darf die
                                // Anmeldung nie aufhalten - er wird nur geloggt.
                                legeWiederherstellungsSchluesselAn(context, initialEmail)
                            }
                            .onFailure { error ->
                                updateAuthState { currentState ->
                                    currentState.copy(
                                        calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                                        errors = AppErrorState.authenticationError(
                                            errorHandler.getErrorMessage(
                                                error
                                            )
                                        )
                                    )
                                }
                            }
                    } else {
                        // Hybrid-Flow failed - this should rarely happen with the working implementation
                        Logger.e(
                            LogTags.AUTH,
                            "❌ HYBRID-FLOW: Email extraction failed - unexpected error"
                        )

                        updateAuthState { currentState ->
                            currentState.copy(
                                calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                                errors = AppErrorState.authenticationError("Anmeldung fehlgeschlagen: E-Mail-Adresse konnte nicht ermittelt werden")
                            )
                        }
                    }
                } else {
                    val errorMessage = signInResult.error ?: "Unbekannter Fehler bei der Anmeldung"
                    updateAuthState { currentState ->
                        currentState.copy(
                            calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                            errors = AppErrorState.authenticationError(errorMessage)
                        )
                    }
                    Logger.e(LogTags.AUTH, "Sign-in failed - $errorMessage")
                }

            } catch (e: Exception) {
                updateAuthState { currentState ->
                    currentState.copy(
                        calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                        errors = AppErrorState.authenticationError(errorHandler.getErrorMessage(e))
                    )
                }
                Logger.e(LogTags.AUTH, "Unexpected error during sign-in", e)
            }
        }
    }

    /**
     * Raeumt beim Abmelden alles weg, was den Abmeldevorgang sonst ueberleben wuerde: die
     * gestellten Wecker, die Schichtspannen (aus denen Dimmer und "Nicht stoeren" ihre
     * Dienstzeit-Fenster ziehen), die 6h-Wartungskette, die Hue-Planung und den
     * Pre-Alarm-Refresh.
     *
     * Abmelden heisst: nichts bleibt zurueck - danach gibt es keine Oberflaeche mehr, ueber die
     * sich Wecker abstellen liessen (Befund 3).
     * Hergang: Skill cfalarm-persistenz-und-auth, reference/auth-und-token.md
     *
     * REIHENFOLGE BEIM LOESCHEN: [IAlarmUseCase.deleteAllAlarms] ist der dafuer vorgesehene
     * zentrale Weg und haelt die einzige erlaubte Richtung ein - es bricht ueber
     * `clearInternalAlarms()` erst die System-Alarme (und schwebende Snoozes) ab und loescht
     * danach den Bestand, wobei der Direct-Boot-Spiegel mitgeleert wird. Umgekehrt bliebe ein
     * armierter Wecker zurueck, den weder Repository noch Spiegel kennen - unsichtbar UND
     * unabbrechbar bis zum naechsten Neustart.
     *
     * WARUM DER SHIFTSPANSTORE MIT MUSS: Dimmer und "Nicht stoeren" beziehen ihre Fenster NICHT
     * aus dem Alarm-Bestand, sondern aus [ShiftSpanStore] (ein Alarm ueberlebt die Weckzeit
     * nicht, ein Dienst schon). Wer nur die Alarme loescht, dimmt und schaltet danach weiter
     * fuer die Schichten des abgemeldeten Kontos - und zwar ohne jede Benachrichtigung, die es
     * verraten wuerde.
     *
     * WANN SIE LAEUFT: NACH dem Abmeldeversuch, nie davor - und dann in BEIDEN Zweigen, weil das
     * Kalender-Token zu diesem Zeitpunkt in beiden Faellen schon verworfen ist. Die vollstaendige
     * Begruendung steht im KDoc von [signOut] unter "Punkt ohne Wiederkehr".
     *
     * NICHT ABBRECHBAR: Die Sequenz stellt einen Zustand HER, statt nur ein Flag umzulegen -
     * dieselbe Begruendung wie bei `MasterPauseUseCase.pause()`. Beim Abmelden ist der Abbruch
     * sogar besonders wahrscheinlich, weil der Nutzer die App unmittelbar danach verlaesst
     * (Activity beendet, Task weggewischt -> `viewModelScope` stirbt). Genau der halb geraeumte
     * Zustand ist der, den das hier verhindern soll. Das `withContext(NonCancellable)` hier
     * genuegt dafuer NICHT allein - es beginnt zu spaet, naemlich erst nach dem Netzaufruf in
     * `invalidate()`; deshalb liegt die eigentliche Sperre in [signOut] um den ganzen Block. Die
     * hiesige bleibt trotzdem stehen: sie sichert die Zusicherung dieser Funktion an sich ab,
     * unabhaengig davon, wer sie eines Tages von wo aufruft.
     *
     * JEDER SCHRITT EIGENES try/catch (Vorbild: `MasterPauseUseCase.pause()`): sonst reisst ein
     * einzelner Fehlschlag alle NACHFOLGENDEN Schritte mit ab. Gemerkt wird der ERSTE Fehler und
     * an den Aufrufer zurueckgegeben - "abgemeldet, aber es klingelt weiter" darf nicht still
     * passieren.
     */
    private suspend fun stopScheduledWorkForSignOut(): Result<Unit> = withContext(NonCancellable) {
        var ersterFehler: Throwable? = null

        // Ein Schritt, der scheitern darf, ohne die NACHFOLGENDEN mitzureissen. Die
        // CancellationException geht bewusst durch: sie ist kein Fehlschlag dieses Schritts,
        // sondern die Ansage, dass die umgebende Coroutine endet - sie zu schlucken wuerde die
        // Struktur zerstoeren und einen Abbruch als "alles geraeumt" verkaufen.
        suspend fun schritt(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(LogTags.AUTH, "Abmelden: $name fehlgeschlagen", e)
                if (ersterFehler == null) ersterFehler = e
            }
        }

        schritt("Wecker abbrechen und loeschen") {
            alarmUseCase.deleteAllAlarms()
                .onSuccess {
                    Logger.business(
                        LogTags.AUTH,
                        "Abmelden: gestellte Wecker abgebrochen und geloescht"
                    )
                }
                .onFailure { throw it }
        }
        schritt("Schichtspannen leeren") { shiftSpanStore.replaceAll(emptyList()) }
        schritt("6h-Wartung abbestellen") { AlarmMaintenanceService.cancelNext(appContext) }
        schritt("Dimmer stoppen") { dimSchedule.disable() }
        schritt("Nicht-stoeren stoppen") { dndSchedule.disable() }
        schritt("Hue-Planung stoppen") { hueSmartScheduler.cleanup() }
        schritt("Pre-Alarm-Refresh abbestellen") { calendarPreAlarmRefreshScheduler.cancelAll() }

        ersterFehler?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    /**
     * Meldet den Nutzer ab und verwirft alle Anmeldedaten - inklusive Kalender-Token. Raeumt
     * DANACH alles weg, was den Abmeldevorgang sonst ueberleben wuerde (siehe
     * [stopScheduledWorkForSignOut]).
     *
     * DIES IST DER EINZIGE ABMELDEPFAD DER APP: die Einstellungskarte ("Abmelden") und der
     * Knopf "Mit anderem Konto anmelden" auf dem Kalender-Autorisierungsbildschirm laufen beide
     * hier durch. Wer einen dritten Weg ergaenzt, muss ihn ebenfalls hier durchleiten - sonst
     * bleibt der geraeumte Zustand ein Zufall.
     *
     * ERST ABMELDEN, DANN RAEUMEN - nicht zurueckdrehen: die umgekehrte Reihenfolge erfindet den
     * Zustand "angemeldet, aber saemtliche Wecker geloescht" und brauchte einen Rueckbau, der in
     * drei Reviews scheiterte. Merksatz: wenn ein Fix ringsum nachgeruestet werden muss, ist der
     * Schnitt falsch. Hergang: Skill cfalarm-persistenz-und-auth, reference/auth-und-token.md
     *
     * DER PUNKT OHNE WIEDERKEHR IST DAS VERWERFEN DES TOKENS, NICHT DAS ENDE VON
     * [IAuthUseCase.signOut] - und daran ist alles Weitere ausgerichtet (Pruefrunde 8, Welle 5).
     * `AuthUseCase.signOut()` verwirft ZUERST das Kalender-Token (`invalidate()`, inkl.
     * Leeren des GMS-Token-Caches) und loescht ERST DANACH die Auth-Daten. Ab dem ersten
     * Schritt ist die Anmeldung praktisch verloren: das Token ist aus dem Store und aus dem
     * GMS-Cache raus, und es kommt durch keinen Fehlerzweig zurueck. Zwei Konsequenzen:
     *
     *  1. NICHT ABBRECHBAR AB DA. Der gesamte Block - Abmelden UND Aufraeumen - laeuft in EINEM
     *     `withContext(NonCancellable)`, weil schon `invalidate()` einen Netzaufruf enthaelt.
     *  2. GERAEUMT WIRD IN BEIDEN ZWEIGEN. Kehrt `authUseCase.signOut()` ueberhaupt zurueck
     *     (statt eine Cancellation zu werfen), wurde das Verwerfen des Tokens versucht; sein
     *     Failure-Zweig heisst ausschliesslich "das Loeschen der Auth-Daten ist gescheitert".
     *     Diesen Nutzer als "angemeldet, alles in Ordnung" stehen zu lassen waere eine Luege:
     *     er kaeme an keinen Kalender mehr, die 6h-Wartung fiele in ihre fail-safe-Zweige, fuer
     *     neue Schichten entstuenden keine Wecker mehr - und der Token-Verlust-Waechter meldet
     *     sich je nach Timing auch nicht (`signOutInProgress` steht noch, `distinctUntilChanged`
     *     + `drop(1)` verschlucken dieselbe Kante). Deshalb laeuft das Aufraeumen auch hier, und
     *     der Nutzer bekommt [FEHLER_ABMELDEN_UNVOLLSTAENDIG] mit einem Ausweg, den es gibt.
     *
     *     UND DIE OBERFLAECHE WIRD MITGEZOGEN (Welle 6, Befund B): dieser Zweig setzt
     *     `hasValidToken = false`. Ohne das blieb der Nutzer in der normalen Haupt-Oberflaeche
     *     stehen, waehrend saemtliche Wecker geloescht und alle Ketten gestoppt waren - eine App,
     *     die normal aussieht und nie wieder einen Wecker stellt. Die Begruendung samt der beiden
     *     verworfenen Alternativen (Kette wieder anwerfen / `AuthState.EMPTY` behaupten) steht
     *     unten an der Stelle selbst.
     *
     *  3. DER PROZESSTOD BLEIBT EINE OFFENE LUECKE - BEWUSST (`NonCancellable` schuetzt nicht
     *     gegen ihn). Ausweg fuer den Nutzer: erneut anmelden oder das Abmelden wiederholen.
     *
     *     WARUM DAGEGEN KEIN DAUERHAFTER MERKER ("Abmelden nicht fertig aufgeraeumt", gelesen vom
     *     `BootReceiver`, abgearbeitet von der 6h-Wartung). Genau das stand hier schon einmal
     *     (Pruefrunde 8, Welle 6) und wurde in der Gegenprobe wieder entfernt, weil es
     *     gefaehrlicher war als die Luecke, die es schliesst. Wer ihn wieder einbauen will, muss
     *     erst diese drei Punkte beantworten:
     *      - ER WIRD BEI EINER NEUANMELDUNG NIRGENDS GELOESCHT. Steht er noch offen, ueberspringt
     *        der `BootReceiver` bei JEDEM Neustart den Direct-Boot-Restore und die
     *        Alarm-Wiederherstellung und schaltet Dimmer, DND und die Pre-Alarm-Jobs ab - fuer
     *        den Bestand des inzwischen NEU angemeldeten Kontos. Aus einer Luecke im
     *        Prozesstod-Fenster wird so ein dauerhaft stummer Wecker.
     *      - DIE WARTUNG ARBEITET IHN GANZ VORNE AB und loescht dabei ALLE Wecker, auch den
     *        manuellen, bevor die sechs fail-safe-Ausstiege der Wartung ueberhaupt erreicht sind.
     *        Die Zusicherung "derselbe Lauf legt sie gleich wieder an" haelt damit nicht, sobald
     *        kein Netz da ist oder ein Kalender gerade nicht abrufbar ist.
     *      - IM HAUPTFEHLERFALL, FUER DEN ER GEBAUT WAR, kappt das Abmelden selbst die 6h-Kette,
     *        die ihn abarbeiten soll - der Abarbeiter faellt genau dann aus, wenn der Auftrag
     *        entsteht.
     *
     * DAMIT VERAENDERT SICH DIE OBEN BESCHRIEBENE FEHLERKLASSE, ABER NICHT DIE ENTSCHEIDUNG:
     * "erst abmelden, dann raeumen" bleibt richtig - was entfaellt, ist nur der Satz, ein
     * gescheitertes Abmelden habe NICHTS angefasst. Der Rest von Punkt 2 ist der Preis dafuer,
     * und er ist der kleinere: die Oberflaeche ist in diesem Zweig vorhanden (der Nutzer gilt
     * weiter als angemeldet), also gibt es keinen Zustand "Wecker armiert, keine Bedienung".
     * Der eng begrenzte Rest: scheitert AUSNAHMSWEISE auch `invalidate()` selbst (es loggt nur
     * und meldet nie nach oben), wurden Wecker geloescht, obwohl das Token noch lebt. Die
     * datengetriebenen Wecker stellt der naechste Sync wieder her; verloren ist nur ein von Hand
     * gestellter Wecker, und genau darauf weist der Text hin.
     *
     * DIE VERBLEIBENDE FEHLERKLASSE DES ERFOLGSZWEIGS, bewusst in Kauf genommen: Scheitert das
     * RAEUMEN, ist der Nutzer abgemeldet, und es koennen armierte Wecker zurueckbleiben - die
     * App zeigt dann nur noch den Anmeldebildschirm (`MainActivity`: `!isSignedIn -> login`),
     * also weder Wecker-Tab noch Master-Pause. Stumm ist dieser Fall deshalb nicht:
     * [FEHLER_ABMELDEN_WECKER_GEBLIEBEN] wird als Fehlermeldung gesetzt, der LoginScreen rendert
     * sie, und sie nennt einen Ausweg, den es in dieser App wirklich gibt. Dass dieser Ausweg
     * daran haengt, dass der Nutzer ihn auch geht, ist der bewusst gezahlte Preis - siehe Punkt 3.
     */
    // Bewusst OHNE Context-Parameter: bis zum 22.08.2026 nahm signOut() ein `Context? = null`
    // entgegen, das im Rumpf nie benutzt wurde - MainActivity reichte dafuer `this@MainActivity`
    // hinein, also eine Activity-Referenz in eine ViewModel-Methode. Die zweite Aufrufstelle
    // kam laengst ohne aus (SettingsTabContent, Kommentar "MEMORY LEAK FIX"). Der Parameter
    // war die Falle, die dieser Kommentar schon einmal entschaerft hatte.
    fun signOut() {
        viewModelScope.launch {
            // VOR dem Verwerfen setzen, nicht danach: die DataStore-Emission trifft asynchron
            // ein und darf observeTokenLoss() nicht als Zugriffsverlust erreichen.
            signOutInProgress = true

            updateAuthState { currentState ->
                currentState.copy(
                    calendarOps = currentState.calendarOps.copy(calendarsLoading = true),
                    errors = AppErrorState.EMPTY
                )
            }

            // AB HIER NICHT MEHR ABBRECHBAR - siehe KDoc, Punkt 1. Auch die Zustandsuebernahme
            // in den `_authState` liegt bewusst drin: sonst koennte der Abbruch die App mit
            // "angemeldet" in der Oberflaeche zuruecklassen, waehrend Token und Auth-Daten
            // bereits weg sind.
            withContext(NonCancellable) {
                try {
                    // Local sign-out using CredentialAuthManager
                    credentialAuthManager.signOutLocally()

                    // Über die UseCase statt direkt aufs Repository: nur dort wird auch das
                    // Kalender-Token verworfen. Der frühere Direktzugriff auf
                    // authDataStoreRepository.clearAuthData() ging daran vorbei - das Token
                    // überlebte die Abmeldung.
                    val abmelden = authUseCase.signOut()

                    // BEIDE ZWEIGE (#55): der Restore-Schluessel muss weg, sonst meldet eine
                    // Neuinstallation den Nutzer STILL wieder an. Wirft nie und ist gedeckelt -
                    // es darf das Abmelden weder aufhalten noch scheitern lassen.
                    loescheWiederherstellungsSchluessel()

                    if (abmelden.isSuccess) {
                        updateAuthState { AuthState.EMPTY }
                        Logger.business(LogTags.AUTH, "Sign-out successful")
                    } else {
                        // Halbe Abmeldung: Token weg, Auth-Daten noch da. Der Nutzer gilt weiter
                        // als angemeldet - die Sperre muss deshalb wieder weg, sonst bliebe die
                        // Re-Autorisierung dauerhaft stumm, obwohl sie hier sogar der richtige
                        // Ausweg waere.
                        signOutInProgress = false

                        // UND DIE OBERFLAECHE MUSS DASSELBE SAGEN wie der Weckbestand (Welle 6,
                        // Befund B). Ohne diese zwei Felder blieb `hasValidToken` auf dem alten
                        // `true` stehen: der Nutzer landete in der normalen Haupt-Oberflaeche,
                        // waehrend saemtliche Wecker geloescht und alle Hintergrundketten
                        // gestoppt waren - und neu angestossen werden die nur bei Anmeldung,
                        // Boot oder Master-Pause-resume, NICHT beim naechsten App-Start. Er haette
                        // eine App gesehen, die normal aussieht und nie wieder einen Wecker stellt.
                        //
                        // Das Token IST weg, also ist `hasValidToken = false` schlicht die
                        // Wahrheit; das Gate in `MainActivity` fuehrt damit auf den
                        // Kalender-Autorisierungsbildschirm. Der zeigt die Meldung unten an, bietet
                        // "Zugriff erlauben" (das startet ueber `requestCalendarAuthorization()`
                        // auch die 6h-Wartung neu) und "Mit anderem Konto anmelden" (ein zweiter
                        // `signOut()`, bei dem nur noch das Loeschen der Auth-Daten aussteht).
                        //
                        // WARUM NICHT STATTDESSEN DIE KETTE WIEDER ANWERFEN: sie liefe ohne Token
                        // sofort in ihren fail-safe-Zweig ("Token refresh failed, aborting") und
                        // legte keinen einzigen Wecker an. Das haette den Widerspruch nicht
                        // aufgeloest, sondern nur unsichtbar gemacht.
                        //
                        // WARUM NICHT `AuthState.EMPTY` (also "abgemeldet" behaupten): die
                        // Auth-Daten liegen noch im DataStore, und `observeAuthState()` spielt
                        // sie binnen 200 ms wieder ein - der Anmeldebildschirm waere nach einem
                        // Wimpernschlag wieder weg. Vor allem aber waere der naechste App-Start
                        // in genau diesem Autorisierungs-Zustand, nicht im abgemeldeten; die
                        // laufende Sitzung soll ihn nicht anders darstellen als der Neustart.
                        markiereTokenUngueltig()
                        Logger.e(
                            LogTags.AUTH,
                            "Abmelden: Auth-Daten nicht geloescht - das Kalender-Token ist " +
                                "trotzdem verworfen, es wird deshalb geraeumt",
                            abmelden.exceptionOrNull()
                        )
                    }

                    // BEIDE ZWEIGE - siehe KDoc, Punkt 2.
                    val aufraeumen = stopScheduledWorkForSignOut()
                    aufraeumen.onFailure { error ->
                        Logger.e(
                            LogTags.AUTH,
                            "Abmelden: Aufraeumen der gestellten Wecker fehlgeschlagen - " +
                                "es koennen armierte Wecker zurueckgeblieben sein",
                            error
                        )
                    }

                    // NACH AuthState.EMPTY, sonst wischt das den Hinweis gleich wieder weg.
                    // Der Text erscheint auf dem Anmeldebildschirm (LoginScreen zeigt
                    // authState.errors.error) bzw. - im halben Fall - auf dem
                    // Kalender-Autorisierungsbildschirm (dessen InlineErrorCard zeigt dasselbe
                    // Feld), also jeweils dort, wo der Nutzer danach landet.
                    val meldung = when {
                        abmelden.isSuccess && aufraeumen.isSuccess -> null
                        abmelden.isSuccess -> FEHLER_ABMELDEN_WECKER_GEBLIEBEN
                        aufraeumen.isSuccess -> FEHLER_ABMELDEN_UNVOLLSTAENDIG
                        else -> FEHLER_ABMELDEN_UNVOLLSTAENDIG_WECKER_GEBLIEBEN
                    }
                    updateAuthState { currentState ->
                        currentState.copy(
                            calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                            errors = meldung
                                ?.let { AppErrorState.authenticationError(it) }
                                ?: AppErrorState.EMPTY
                        )
                    }
                } catch (e: CancellationException) {
                    // Innerhalb von NonCancellable kann sie nur aus einem Kind stammen. Sie
                    // bleibt eine Ansage, kein Fehlschlag - als gewoehnlicher Fehler behandelt
                    // wuerde sie zu "abgemeldet" verrechnet, obwohl nichts feststeht.
                    throw e
                } catch (e: Exception) {
                    // Hier landet nur, was VOR dem Punkt ohne Wiederkehr wirft:
                    // `signOutLocally()`. `authUseCase.signOut()` und
                    // [stopScheduledWorkForSignOut] geben ihre Fehler als Result zurueck.
                    // Angemeldet geblieben, nichts angefasst - der normale Fehlerweg genuegt.
                    signOutInProgress = false
                    updateAuthState { currentState ->
                        currentState.copy(
                            calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                            errors = AppErrorState.authenticationError(
                                errorHandler.getErrorMessage(e)
                            )
                        )
                    }
                    Logger.e(LogTags.AUTH, "Error during sign-out", e)
                }
            }
        }
    }

    /**
     * Zero-Tap-Wiederherstellung beim Start (#55, Play-Pflicht ab April 2027). Wird aus
     * `MainActivity.onCreate` angestossen, weil der `CredentialManager` einen Activity-Kontext
     * braucht - nicht aus dem init{}.
     *
     * - NICHT ANGEMELDET: sucht einen Restore-Schluessel vom alten Geraet (hoechstens
     *   [LESEN_DECKEL_MS]); waehrenddessen zeigt die Oberflaeche [TEXT_WIEDERHERSTELLUNG_LAEUFT].
     *   Bei einem Treffer laeuft derselbe Weg wie nach "Mit Google anmelden": Auth-Daten
     *   schreiben, dann [requestCalendarAuthorization]. Braucht die Zustimmung einen Dialog oder
     *   ist das Geraet offline, fuehrt das bestehende Gate auf den Kalender-Autorisierungs-
     *   bildschirm - keine Sackgasse. Jeder Fehlschlag und jeder Zeitablauf heisst: normaler
     *   Anmeldebildschirm.
     * - ANGEMELDET OHNE MERKER: legt den Schluessel einmal an, damit auch Bestandsnutzer ihn
     *   bekommen, ohne sich neu anzumelden.
     *
     * Der Schluessel ist kein Anmeldenachweis, nur der Hinweis "dieses Google-Konto" - den
     * Kalenderzugriff gewaehrt weiter allein Googles `authorize()`.
     */
    fun starteAnmeldeWiederherstellung(activity: Activity) {
        if (wiederherstellungAngestossen) return
        wiederherstellungAngestossen = true

        viewModelScope.launch {
            val authData = authDataStoreRepository.getCurrentAuthData().getOrNull()
            if (authData == null) {
                Logger.w(LogTags.AUTH, "Restore-Schluessel: Anmeldestatus nicht lesbar - uebersprungen")
                return@launch
            }

            if (authData.isLoggedIn) {
                val email = authData.email
                if (email.isNullOrBlank()) return@launch
                // Nur bei einem SICHEREN "fehlt": ein Lesefehler (null) legt nichts an.
                val angelegt = authDataStoreRepository.istWiederherstellungsSchluesselAngelegt().getOrNull()
                if (angelegt == false) {
                    legeWiederherstellungsSchluesselAn(activity, email)
                }
            } else {
                versucheWiederherstellung(activity)
            }
        }
    }

    private suspend fun versucheWiederherstellung(activity: Activity) {
        updateAuthState { it.copy(wiederherstellungLaeuft = true) }
        try {
            val email = try {
                withTimeoutOrNull(LESEN_DECKEL_MS) { anmeldeWiederherstellung.lesen(activity) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Der Vertrag sagt "wirft nie" - dieser Zweig ist das Netz dafuer.
                Logger.w(LogTags.AUTH, "Restore-Schluessel: Lesen gescheitert - normaler Login", e)
                null
            }
            if (email == null) {
                Logger.d(LogTags.AUTH, "Keine Anmeldung vom alten Geraet - normaler Login")
                return
            }

            // Hat sich der Nutzer in der Zwischenzeit selbst angemeldet, gilt seine Wahl.
            if (authDataStoreRepository.getCurrentAuthData().getOrNull()?.isLoggedIn == true) {
                Logger.d(LogTags.AUTH, "Restore-Schluessel verworfen - inzwischen angemeldet")
                return
            }

            authDataStoreRepository.updateAuthData(AuthData(isLoggedIn = true, email = email))
                .onSuccess {
                    Logger.business(LogTags.AUTH, "Anmeldung vom alten Geraet uebernommen")
                    signOutInProgress = false
                    // In EINEM Schritt mit dem Ende des Ladezustands: dazwischen blitzte sonst
                    // der Anmeldebildschirm auf.
                    updateAuthState { currentState ->
                        currentState.copy(
                            userAuth = UserAuthState.authenticated(email, "", null),
                            wiederherstellungLaeuft = false
                        )
                    }
                    requestCalendarAuthorization(activity)
                    // Der mitgereiste Schluessel wird durch einen eigenen ersetzt, und der
                    // (nicht mitgereiste) Merker entsteht dabei.
                    legeWiederherstellungsSchluesselAn(activity, email)
                }
                .onFailure { error ->
                    Logger.w(
                        LogTags.AUTH,
                        "Restore-Schluessel gefunden, Anmeldung aber nicht speicherbar - normaler Login",
                        error
                    )
                }
        } finally {
            updateAuthState { it.copy(wiederherstellungLaeuft = false) }
        }
    }

    /**
     * Legt den Restore-Schluessel an und merkt es sich. Wirft nie: die Anmeldung haengt nicht
     * daran. Ein Abmelden, das schon begonnen hat, gewinnt (siehe
     * [wiederherstellungsSchluesselSperre]).
     */
    private suspend fun legeWiederherstellungsSchluesselAn(activityContext: Context, email: String) {
        try {
            wiederherstellungsSchluesselSperre.withLock {
                if (signOutInProgress) return
                val angelegt = withTimeoutOrNull(ANLEGEN_DECKEL_MS) {
                    anmeldeWiederherstellung.anlegen(activityContext, email)
                } == true
                if (!angelegt) return
                authDataStoreRepository.merkeWiederherstellungsSchluesselAngelegt()
                    .onFailure {
                        // Folge nur: beim naechsten Start wird er noch einmal angelegt.
                        Logger.w(LogTags.AUTH, "Restore-Schluessel: Merker nicht gespeichert", it)
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(LogTags.AUTH, "Restore-Schluessel: Anlegen gescheitert", e)
        }
    }

    /**
     * Loescht den Restore-Schluessel beim Abmelden. Wirft nie. Wartet auf ein laufendes Anlegen
     * (ohne eigenen Deckel - das Anlegen ist selbst gedeckelt), damit nichts NACH dem Loeschen
     * noch entsteht. Der Merker in `auth_prefs` geht mit `clearAuthData()`.
     */
    private suspend fun loescheWiederherstellungsSchluessel() {
        try {
            wiederherstellungsSchluesselSperre.withLock {
                withTimeoutOrNull(LOESCHEN_DECKEL_MS) { anmeldeWiederherstellung.loeschen() }
                    ?: Logger.w(LogTags.AUTH, "Restore-Schluessel: Loeschen nach $LOESCHEN_DECKEL_MS ms abgebrochen")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(LogTags.AUTH, "Restore-Schluessel: Loeschen gescheitert", e)
        }
    }

    /**
     * Clears current error message.
     */
    fun clearError() {
        updateAuthState { currentState ->
            currentState.copy(errors = AppErrorState.EMPTY)
        }
    }

    /**
     * MODERN: Requests Calendar API authorization for current user
     * CRITICAL FIX: Directly calls AuthUseCase to get real OAuth2 tokens with Activity Context
     *
     * @param activity Activity context for launching permission dialog if needed
     */
    fun requestCalendarAuthorization(activity: android.app.Activity? = null) {
        viewModelScope.launch {
            updateAuthState { currentState ->
                currentState.copy(
                    calendarOps = currentState.calendarOps.copy(calendarsLoading = true),
                    errors = AppErrorState.EMPTY
                )
            }

            try {
                // Get current user email for Calendar authorization
                val currentAuthData = authDataStoreRepository.getCurrentAuthData().getOrNull()
                val userEmail = currentAuthData?.email

                if (userEmail.isNullOrEmpty()) {
                    updateAuthState { currentState ->
                        currentState.copy(
                            calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                            errors = AppErrorState.authenticationError("Benutzer-E-Mail für Calendar-Autorisierung nicht verfügbar")
                        )
                    }
                    return@launch
                }

                Logger.business(
                    LogTags.AUTH,
                    "🔐 ACTIVITY-CONTEXT-FIX: Requesting Calendar authorization with Activity=${activity != null}"
                )

                // CRITICAL FIX: Use Activity-based authorization if activity is provided
                if (activity != null && authUseCase is com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AuthUseCase) {
                    authUseCase.requestCalendarAuthorizationWithActivity(
                        userEmail = userEmail,
                        activity = activity,
                        onResult = { success ->
                            updateAuthState { currentState ->
                                currentState.copy(
                                    calendarOps = currentState.calendarOps.copy(
                                        calendarsLoading = false,
                                        hasSelectedCalendars = success,
                                        hasValidToken = success, // 🔧 STUFE 2: Set token validity based on auth result
                                        tokenChecked = true // GATE: auth attempt resolved -> gate decision is well-defined
                                    )
                                )
                            }

                            if (success) {
                                Logger.business(
                                    LogTags.AUTH,
                                    "✅ ACTIVITY-CONTEXT-FIX: Calendar authorization successful, hasValidToken=true"
                                )

                                // Initialize maintenance service after successful authorization
                                viewModelScope.launch {
                                    backgroundServiceManager.initializeMaintenanceService()
                                }
                                _kalenderZugriffErneuert.trySend(Unit)
                                Logger.business(
                                    LogTags.AUTH,
                                    "✅ Maintenance service initialized after authorization"
                                )
                            } else {
                                Logger.w(
                                    LogTags.AUTH,
                                    "⚠️ ACTIVITY-CONTEXT-FIX: Calendar authorization failed or denied, hasValidToken=false"
                                )
                            }
                        }
                    ).onFailure { error ->
                        meldeAutorisierungFehlgeschlagen(error)
                        Logger.e(
                            LogTags.AUTH,
                            "❌ ACTIVITY-CONTEXT-FIX: Calendar authorization failed",
                            error
                        )
                    }
                } else {
                    // Fallback to old method without Activity context
                    Logger.w(
                        LogTags.AUTH,
                        "⚠️ ACTIVITY-CONTEXT-FIX: No Activity context provided, using legacy method"
                    )

                    val authResult = authUseCase.requestCalendarAuthorization(userEmail)
                    authResult.fold(
                        onSuccess = { authorized ->
                            updateAuthState { currentState ->
                                currentState.copy(
                                    calendarOps = currentState.calendarOps.copy(
                                        calendarsLoading = false,
                                        hasSelectedCalendars = authorized,
                                        hasValidToken = authorized, // 🔧 STUFE 2: Set token validity based on auth result
                                        tokenChecked = true // GATE: auth attempt resolved -> gate decision is well-defined
                                    )
                                )
                            }
                            Logger.business(
                                LogTags.AUTH,
                                "✅ MODERN-FLOW: Calendar authorization successful: $authorized, hasValidToken=$authorized"
                            )

                            if (authorized) {
                                _kalenderZugriffErneuert.trySend(Unit)
                                // Initialize maintenance service after successful authorization
                                backgroundServiceManager.initializeMaintenanceService()
                                Logger.business(
                                    LogTags.AUTH,
                                    "✅ Maintenance service initialized after authorization"
                                )
                            }
                        },
                        onFailure = { error ->
                            meldeAutorisierungFehlgeschlagen(error)
                            Logger.e(
                                LogTags.AUTH,
                                "❌ MODERN-FLOW: Calendar authorization failed",
                                error
                            )
                        }
                    )
                }
            } catch (e: Exception) {
                updateAuthState { currentState ->
                    currentState.copy(
                        calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                        errors = AppErrorState.authenticationError(errorHandler.getErrorMessage(e))
                    )
                }
                Logger.e(LogTags.AUTH, "❌ MODERN-FLOW: Exception during calendar authorization", e)
            }
        }
    }
}
