---
layout: default
title: Developer Guide  
---

{% include navigation.md %}

# 💻 Developer Guide

## Architecture Overview

CF Alarm for Time Office follows **Clean Architecture** principles with **MVVM pattern**.

### Layer Structure

```
Presentation (UI) Layer
├── Activities & Fragments
├── ViewModels
└── Compose UI Components

Domain Layer
├── Use Cases
├── Repository Interfaces
└── Domain Models

Data Layer
├── Repository Implementations
├── Data Sources (Local/Remote)
└── Database/Network/Storage
```

## Project Structure

### Core Modules

```
app/src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/
├── ui/                     # Presentation Layer
│   ├── screens/           # Compose Screens
│   ├── components/        # Reusable UI Components
│   └── theme/             # App Theming
├── viewmodel/             # ViewModels
├── usecase/               # Business Logic Use Cases
├── repository/            # Data Repository Implementations
├── data/                  # Data Sources
├── model/                 # Data Models
├── auth/                  # Authentication Management
├── calendar/              # Calendar Integration
├── hue/                   # Philips Hue Integration
├── service/               # Background Services
├── util/                  # Utility Classes
└── di/                    # Dependency Injection
```

## Key Technologies

### Core Android
- **Kotlin 2.1.0** - Primary language
- **Jetpack Compose** - Modern UI toolkit
- **Android SDK 36** - Target platform
- **Material 3** - Design system

### Architecture Components
- **Hilt** - Dependency injection
- **ViewModel** - UI state management
- **LiveData/StateFlow** - Reactive data
- **Room** - Local database (planned)

### Authentication & APIs
- **Google OAuth 2.0** - Calendar authentication
- **Credential Manager** - Modern auth flow
- **Google Calendar API** - Calendar data access
- **Retrofit** - HTTP client for Hue API

### Security
- **EncryptedSharedPreferences** - Secure local storage
- **Android Keystore** - Key management
- **AES-256-GCM** - Data encryption

## Development Setup

### Environment Requirements

```bash
# Required versions
Android Studio: 2025.1.1+
JDK: 17 (LTS)
Android SDK: API 26-36
Kotlin: 2.1.0+
Gradle: 9.0.0+
```

### Initial Setup

1. **Clone Repository**
```bash
git clone https://github.com/f1rlefanz/cf-alarmfortimeoffice.git
cd cf-alarmfortimeoffice
```

2. **Create Keystore Configuration**
```bash
# Create keystore.properties (NEVER commit this!)
touch keystore.properties
```

Add to `keystore.properties`:
```properties
storeFile=./debug.keystore
storePassword=android
keyAlias=androiddebugkey
keyPassword=android
googleWebClientId=YOUR_CLIENT_ID_FROM_GOOGLE_CLOUD_CONSOLE
```

3. **Google Cloud Console Setup**
- Create project in [Google Cloud Console](https://console.cloud.google.com/)
- Enable **Google Calendar API**
- Create **OAuth 2.0 credentials** (Android app)
- Add your debug/release SHA-1 fingerprints

4. **Build Project**
```bash
./gradlew assembleDebug
```

## Code Guidelines

### Architecture Patterns

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

### Security Best Practices

#### Token Management
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
        // Use Android Keystore for encryption
        // AES-256-GCM implementation
    }
}
```

#### Network Security
```kotlin
// Network Security Config (res/xml/network_security_config.xml)
<network-security-config>
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">googleapis.com</domain>
    </domain-config>
    
    <!-- Allow local HTTP for Hue Bridge -->
    <domain-config cleartextTrafficPermitted="true">
        <domain>192.168.0.0/16</domain>
        <domain>10.0.0.0/8</domain>
    </domain-config>
</network-security-config>
```

## Testing Strategy

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
    fun `getUpcomingEvents returns events when authenticated`() = runTest {
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

### Integration Tests
```kotlin
@HiltAndroidTest
class CalendarIntegrationTest {
    
    @get:Rule
    var hiltRule = HiltAndroidRule(this)
    
    @Test
    fun calendarSyncWorkflow() {
        // Test complete calendar sync workflow
        // From authentication to event display
    }
}
```

## Debugging

### Logging Strategy
```kotlin
object Logger {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
        // Also log to file for production debug
        logToFile(tag, message)
    }
}
```

### Debug Features
- **Developer Options** in app settings
- **Log Export** functionality
- **Network Traffic Logging**
- **Database Inspection** (Room Inspector)

## Performance Optimization

### Memory Management
```kotlin
class MemoryOptimizer {
    fun optimizeForLowMemory() {
        // Clear image caches
        // Reduce background processing
        // Optimize object pools
    }
}
```

### Battery Optimization
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

## Release Process

### Build Configuration
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

### Signing Configuration
```kotlin
// Secure signing with keystore.properties
signingConfigs {
    create("release") {
        storeFile = file(keystoreProperties["storeFile"] as String)
        storePassword = keystoreProperties["storePassword"] as String
        keyAlias = keystoreProperties["keyAlias"] as String
        keyPassword = keystoreProperties["keyPassword"] as String
    }
}
```

## API Documentation

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

## Contributing

### Code Review Checklist
- [ ] Follows architecture patterns
- [ ] Includes appropriate tests
- [ ] Security best practices applied
- [ ] Performance considerations addressed
- [ ] Documentation updated
- [ ] Accessibility requirements met

### Pull Request Template
```markdown
## Description
Brief description of changes

## Type of Change
- [ ] Bug fix
- [ ] New feature
- [ ] Breaking change
- [ ] Documentation update

## Testing
- [ ] Unit tests added/updated
- [ ] Integration tests pass
- [ ] Manual testing completed

## Screenshots (if applicable)
Add screenshots for UI changes
```

---

**Happy coding!** 🚀 For questions, check [GitHub Discussions](https://github.com/f1rlefanz/cf-alarmfortimeoffice/discussions)
