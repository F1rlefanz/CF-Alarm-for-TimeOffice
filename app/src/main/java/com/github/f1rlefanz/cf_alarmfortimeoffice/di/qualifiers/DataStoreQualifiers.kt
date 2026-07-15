package com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers

import javax.inject.Qualifier

/**
 * Qualifiers für verschiedene DataStore-Instanzen
 *
 * Ermöglicht die Unterscheidung zwischen verschiedenen
 * DataStore-Instanzen bei der Injection
 *
 * Es gibt bewusst KEINEN @TokenDataStore: Der Token-Store ist verschlüsselt und wird von
 * `DataStoreTokenRepository` selbst gebaut, nicht injiziert (siehe DataModule).
 */

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HueDataStore
