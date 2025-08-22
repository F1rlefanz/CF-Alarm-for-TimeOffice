package com.github.f1rlefanz.cf_alarmfortimeoffice.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * DataStore preference keys for alarm skip functionality.
 * Defines the keys used to persist alarm skip state.
 */
object AlarmSkipPreferences {
    val IS_NEXT_ALARM_SKIPPED = booleanPreferencesKey("is_next_alarm_skipped")
    val SKIPPED_ALARM_ID = intPreferencesKey("skipped_alarm_id") 
    val SKIP_ACTIVATED_AT = longPreferencesKey("skip_activated_at")
    val SKIP_REASON = stringPreferencesKey("skip_reason")
}
