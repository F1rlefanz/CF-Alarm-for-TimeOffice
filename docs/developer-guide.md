---
layout: default
title: Entwickler-Leitfaden  
---

{% include navigation.md %}

# 💻 Entwickler-Leitfaden

## Architektur-Übersicht

CF Alarm for Time Office folgt **Clean Architecture** Prinzipien mit **MVVM-Pattern**.

### Schicht-Struktur

```
Präsentationsschicht (UI)
├── Activities & Fragments
├── ViewModels
└── Compose UI Components

Domain-Schicht
├── Use Cases
├── Repository Interfaces
└── Domain Models

Datenschicht
├── Repository Implementierungen
├── Datenquellen (Lokal/Remote)
└── Datenbank/Netzwerk/Speicher
```

## Projektstruktur

### Kernmodule

```
app/src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/
├── ui/                     # Präsentationsschicht
│   ├── screens/           # Compose Bildschirme
│   ├── components/        # Wiederverwendbare UI Komponenten
│   └── theme/             # App Theming
├── viewmodel/             # ViewModels
├── usecase/               # Business Logic Use Cases
├── repository/            # Daten Repository Implementierungen
├── data/                  # Datenquellen
├── model/                 # Datenmodelle
├── auth/                  # Authentifizierungsverwaltung
├── calendar/              # Kalender Integration
├── hue/                   # Philips Hue Integration
├── service/               # Hintergrunddienste
├── util/                  # Utility Klassen
└── di/                    # Dependency Injection
```

## Schlüsseltechnologien

### Kern Android
- **Kotlin 2.1.0** - Hauptsprache
- **Jetpack Compose** - Modernes UI-Toolkit
- **Android SDK 36** - Zielplattform
- **Material 3** - Designsystem

### Architektur-Komponenten
- **Hilt** - Dependency Injection
- **ViewModel** - UI-Zustandsverwaltung
- **LiveData/StateFlow** - Reaktive Daten
- **Room** - Lokale Datenbank (geplant)

### Authentifizierung & APIs
- **Google OAuth 2.0** - Kalender-Authentifizierung
- **Credential Manager** - Moderner Auth-Flow
- **Google Calendar API** - Kalenderdatenzugriff
- **Retrofit** - HTTP-Client für Hue API

### Sicherheit
- **EncryptedSharedPreferences** - Sichere lokale Speicherung
- **Android Keystore** - Schlüsselverwaltung
- **AES-256-GCM** - Datenverschlüsselung

## Entwicklungssetup

### Umgebungsanforderungen

```bash
# Erforderliche Versionen
Android Studio: 2025.1.1+
JDK: 17 (LTS)
Android SDK: API 26-36
Kotlin: 2.1.0+
Gradle: 9.0.0+
```

### Ersteinrichtung

1. **Repository klonen**
```bash
git clone https://github.com/F1rlefanz/CF-Alarm-for-TimeOffice.git
cd CF-Alarm-for-TimeOffice
```

2. **Keystore-Konfiguration erstellen**
```bash
# keystore.properties erstellen (NIEMALS committen!)
touch keystore.properties
```

Zu `keystore.properties` hinzufügen:
```properties
storeFile=./debug.keystore
storePassword=android
keyAlias=androiddebugkey
keyPassword=android
googleWebClientId=IHRE_CLIENT_ID_AUS_GOOGLE_CLOUD_CONSOLE
```

3. **Google Cloud Console Setup**
- Projekt in [Google Cloud Console](https://console.cloud.google.com/) erstellen
- **Google Calendar API** aktivieren
- **OAuth 2.0 Anmeldedaten** erstellen (Android-App)
- Debug/Release SHA-1 Fingerabdrücke hinzufügen

4. **Projekt erstellen**
```bash
./gradlew assembleDebug
```

## Code-Richtlinien

### Architektur-Pattern

#### ViewModels
```kotlin
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendarUseCase: CalendarUseCase,
    private val authUseCase: AuthUseCase
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()
    
    fun loadCalendarEvents() {
        viewModelScope.launch {
            try {
                val events = calendarUseCase.getUpcomingEvents()
                _uiState.value = _uiState.value.copy(events = events)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }
}
```

#### Use Cases
```kotlin
class CalendarUseCase @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val authRepository: AuthRepository
) {
    suspend fun getUpcomingEvents(): List<CalendarEvent> {
        return if (authRepository.isAuthenticated()) {
            calendarRepository.getEvents(
                startTime = LocalDateTime.now(),
                endTime = LocalDateTime.now().plusDays(7)
            )
        } else {
            emptyList()
        }
    }
}
```

#### Repositories
```kotlin
class CalendarRepositoryImpl @Inject constructor(
    private val calendarApi: CalendarApi,
    private val authManager: AuthManager
) : CalendarRepository {
    
    override suspend fun getEvents(
        startTime: LocalDateTime,
        endTime: LocalDateTime
    ): List<CalendarEvent> {
        return withContext(Dispatchers.IO) {
            val token = authManager.getValidToken()
            calendarApi.getEvents(token, startTime, endTime)
        }
    }
}
```

### Sicherheits-Best-Practices

#### Token-Verwaltung
```kotlin
class SecureTokenManager @Inject constructor(
    private val encryptedPrefs: EncryptedSharedPreferences
) {
    fun saveToken(token: String) {
        encryptedPrefs.edit()
            .putString(TOKEN_KEY, encrypt(token))
            .apply()
    }
    
    private fun encrypt(data: String): String {
        // Android Keystore für Verschlüsselung verwenden
        // AES-256-GCM Implementierung
    }
}
```

## Test-Strategie

### Unit Tests
```kotlin
@ExperimentalCoroutinesTest
class CalendarUseCaseTest {
    
    @Mock
    private lateinit var calendarRepository: CalendarRepository
    
    @Mock
    private lateinit var authRepository: AuthRepository
    
    private lateinit var calendarUseCase: CalendarUseCase
    
    @Test
    fun `getUpcomingEvents gibt Events zurück wenn authentifiziert`() = runTest {
        // Given
        given(authRepository.isAuthenticated()).willReturn(true)
        given(calendarRepository.getEvents(any(), any()))
            .willReturn(listOf(mockCalendarEvent))
        
        // When
        val result = calendarUseCase.getUpcomingEvents()
        
        // Then
        assertThat(result).hasSize(1)
        assertThat(result.first()).isEqualTo(mockCalendarEvent)
    }
}
```

## Debugging

### Logging-Strategie
```kotlin
object Logger {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
        // Auch in Datei für Production-Debug protokollieren
        logToFile(tag, message)
    }
}
```

### Debug-Features
- **Entwickleroptionen** in App-Einstellungen
- **Log-Export** Funktionalität
- **Netzwerkverkehr-Protokollierung**
- **Datenbankinspektion** (Room Inspector)

## Leistungsoptimierung

### Speicherverwaltung
```kotlin
class MemoryOptimizer {
    fun optimizeForLowMemory() {
        // Bild-Caches leeren
        // Hintergrundverarbeitung reduzieren
        // Objekt-Pools optimieren
    }
}
```

### Akkuoptimierung
```kotlin
class BatteryOptimizer {
    fun requestBatteryOptimizationExemption(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:${context.packageName}")
            context.startActivity(intent)
        }
    }
}
```

## Release-Prozess

### Build-Konfiguration
```kotlin
// build.gradle.kts
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

## API-Dokumentation

### Calendar API Integration
```kotlin
interface CalendarApi {
    @GET("calendar/v3/calendars/{calendarId}/events")
    suspend fun getEvents(
        @Header("Authorization") token: String,
        @Path("calendarId") calendarId: String,
        @Query("timeMin") startTime: String,
        @Query("timeMax") endTime: String
    ): CalendarEventsResponse
}
```

### Hue API Integration
```kotlin
interface HueApi {
    @GET("api/{username}/lights")
    suspend fun getLights(
        @Path("username") username: String
    ): Map<String, HueLight>
    
    @PUT("api/{username}/lights/{lightId}/state")
    suspend fun setLightState(
        @Path("username") username: String,
        @Path("lightId") lightId: String,
        @Body state: HueLightState
    ): List<HueResponse>
}
```

## Mitwirken

### Code-Review-Checkliste
- [ ] Folgt Architektur-Pattern
- [ ] Enthält angemessene Tests
- [ ] Sicherheits-Best-Practices angewendet
- [ ] Leistungsaspekte berücksichtigt
- [ ] Dokumentation aktualisiert
- [ ] Barrierefreiheitsanforderungen erfüllt

### Pull-Request-Vorlage
```markdown
## Beschreibung
Kurze Beschreibung der Änderungen

## Art der Änderung
- [ ] Fehlerbehebung
- [ ] Neue Funktion
- [ ] Breaking Change
- [ ] Dokumentations-Update

## Tests
- [ ] Unit-Tests hinzugefügt/aktualisiert
- [ ] Integrationstests bestanden
- [ ] Manuelle Tests abgeschlossen

## Screenshots (falls zutreffend)
Screenshots für UI-Änderungen hinzufügen
```

---

**Frohes Programmieren!** 🚀 Für Fragen, schauen Sie in die [GitHub Diskussionen](https://github.com/F1rlefanz/CF-Alarm-for-TimeOffice/discussions)