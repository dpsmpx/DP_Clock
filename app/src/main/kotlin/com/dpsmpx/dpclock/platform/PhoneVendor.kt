package com.dpsmpx.dpclock.platform

/**
 * Производитель телефона — для подсказок об автозапуске и батарее (D-036, п. 4):
 * у этих оболочек фоновые ограничения мешают будильникам чаще всего. [brands] —
 * как их называют на коробке.
 */
enum class PhoneVendor(val brands: String) {
    XIAOMI("Xiaomi, Redmi, POCO"),
    SAMSUNG("Samsung"),
    HUAWEI("Huawei, Honor"),
    OPPO("OPPO, realme, OnePlus"),
    VIVO("vivo, iQOO"),
    OTHER("");

    companion object {
        /** По `Build.MANUFACTURER` и `Build.BRAND`; незнакомый — [OTHER]. */
        fun of(manufacturer: String, brand: String): PhoneVendor {
            val names = setOf(manufacturer.trim().lowercase(), brand.trim().lowercase())
            fun any(vararg known: String) = known.any { it in names }
            return when {
                any("xiaomi", "redmi", "poco") -> XIAOMI
                any("samsung") -> SAMSUNG
                any("huawei", "honor") -> HUAWEI
                any("oppo", "realme", "oneplus") -> OPPO
                any("vivo", "iqoo") -> VIVO
                else -> OTHER
            }
        }
    }
}

/** Что думать о заряде перед сном (D-036, п. 4). */
object BatteryCheck {
    /** Ниже этого без зарядки телефон может не дожить до утра. */
    const val LOW_PERCENT = 20

    fun isRisky(percent: Int?, charging: Boolean): Boolean = percent != null && !charging && percent < LOW_PERCENT
}
