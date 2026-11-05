package com.aitech.weartak_civ.data

import androidx.annotation.StringRes
import com.aitech.weartak_civ.R

enum class ProfileUnit(
    val storageValue: String,
    @StringRes val labelRes: Int,
    val batdokValue: String?
) {
    AIR_FORCE("air_force", R.string.unit_air_force, "Air Force"),
    ARMY("army", R.string.unit_army, "Army"),
    CIVILIAN("civilian", R.string.unit_civilian, "Civilian"),
    FOREIGN_NATIONAL("foreign_national", R.string.unit_foreign_national, "Foreign National"),
    MARINE_CORPS("marine_corps", R.string.unit_marine_corps, "Marine Corps"),
    NAVY("navy", R.string.unit_navy, "Navy"),
    NONE("none", R.string.unit_none, null);

    companion object {
        fun fromStored(value: String): ProfileUnit? = when (value) {
            "N/A", "None" -> NONE
            else -> entries.firstOrNull { it.storageValue == value || it.batdokValue == value }
        }

        // Unknown legacy classifications survive until the user explicitly selects a Unit.
        fun normalizeStored(value: String): String = fromStored(value)?.storageValue ?: value
    }
}
