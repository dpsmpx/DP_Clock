package com.dpsmpx.dpclock.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneVendorTest {
    @Test
    fun vendorByManufacturerOrBrand() {
        assertEquals(PhoneVendor.XIAOMI, PhoneVendor.of("Xiaomi", "Redmi"))
        assertEquals(PhoneVendor.XIAOMI, PhoneVendor.of("unknown", "POCO"))
        assertEquals(PhoneVendor.SAMSUNG, PhoneVendor.of("samsung", "samsung"))
        assertEquals(PhoneVendor.HUAWEI, PhoneVendor.of("HONOR", "HONOR"))
        assertEquals(PhoneVendor.OPPO, PhoneVendor.of("OnePlus", "OnePlus"))
        assertEquals(PhoneVendor.OPPO, PhoneVendor.of("OPPO", "realme"))
        assertEquals(PhoneVendor.VIVO, PhoneVendor.of("vivo", "iQOO"))
        assertEquals(PhoneVendor.OTHER, PhoneVendor.of("Google", "google"))
        assertEquals(PhoneVendor.OTHER, PhoneVendor.of("", ""))
    }

    @Test
    fun lowBatteryIsRiskyOnlyOffTheCharger() {
        assertTrue(BatteryCheck.isRisky(12, charging = false))
        assertFalse(BatteryCheck.isRisky(12, charging = true))
        assertFalse(BatteryCheck.isRisky(20, charging = false))
        assertFalse(BatteryCheck.isRisky(null, charging = false))
    }
}
