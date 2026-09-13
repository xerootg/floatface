package app.floatface.wear.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.floatface.core.SettingsStore
import app.floatface.core.SpeedUnit
import app.floatface.core.TempUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * [SettingsStore] adapter (SPEC §8.5) backed by Jetpack DataStore Preferences.
 * Defaults to MPH / F until the store emits a first value (also its
 * persisted default when a key is absent).
 */
class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : SettingsStore {

    private val _speedUnit = MutableStateFlow(SpeedUnit.MPH)
    override val speedUnit: StateFlow<SpeedUnit> = _speedUnit

    private val _tempUnit = MutableStateFlow(TempUnit.F)
    override val tempUnit: StateFlow<TempUnit> = _tempUnit

    init {
        dataStore.data
            .map { prefs -> prefs[SPEED_UNIT_KEY]?.toSpeedUnitOrNull() ?: SpeedUnit.MPH }
            .onEach { _speedUnit.value = it }
            .launchIn(scope)

        dataStore.data
            .map { prefs -> prefs[TEMP_UNIT_KEY]?.toTempUnitOrNull() ?: TempUnit.F }
            .onEach { _tempUnit.value = it }
            .launchIn(scope)
    }

    override suspend fun setSpeedUnit(unit: SpeedUnit) {
        dataStore.edit { it[SPEED_UNIT_KEY] = unit.name }
        _speedUnit.value = unit
    }

    override suspend fun setTempUnit(unit: TempUnit) {
        dataStore.edit { it[TEMP_UNIT_KEY] = unit.name }
        _tempUnit.value = unit
    }

    private fun String.toSpeedUnitOrNull(): SpeedUnit? = runCatching { SpeedUnit.valueOf(this) }.getOrNull()
    private fun String.toTempUnitOrNull(): TempUnit? = runCatching { TempUnit.valueOf(this) }.getOrNull()

    companion object {
        val SPEED_UNIT_KEY: Preferences.Key<String> = stringPreferencesKey("speed_unit")
        val TEMP_UNIT_KEY: Preferences.Key<String> = stringPreferencesKey("temp_unit")
    }
}
