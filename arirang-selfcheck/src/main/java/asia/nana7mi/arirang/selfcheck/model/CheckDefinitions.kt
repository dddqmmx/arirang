package asia.nana7mi.arirang.selfcheck.model

import android.hardware.Sensor
import java.util.UUID

object CheckDefinitions {
    val SENSOR_TYPES = linkedMapOf(
        Sensor.TYPE_ACCELEROMETER to "Accelerometer",
        Sensor.TYPE_GYROSCOPE to "Gyroscope",
        Sensor.TYPE_MAGNETIC_FIELD to "Magnetic Field",
        Sensor.TYPE_GRAVITY to "Gravity",
        Sensor.TYPE_LINEAR_ACCELERATION to "Linear Acceleration"
    )

    data class PropProbe(val label: String, val frameworkValue: String?, val propKey: String)

    val SYSTEM_PROP_PROBES = listOf(
        "Brand" to "ro.product.brand",
        "Manufacturer" to "ro.product.manufacturer",
        "Model" to "ro.product.model",
        "Device" to "ro.product.device",
        "Product" to "ro.product.name",
        "Board" to "ro.product.board",
        "Hardware" to "ro.hardware",
        "Fingerprint" to "ro.build.fingerprint"
    )

    val PARTITION_PROP_KEYS = listOf(
        "ro.product.vendor.model",
        "ro.product.vendor.brand",
        "ro.product.vendor.manufacturer",
        "ro.product.system.model",
        "ro.product.odm.model",
        "ro.product.bootimage.brand",
        "ro.bootimage.build.fingerprint",
        "ro.vendor.build.fingerprint",
        "ro.odm.build.fingerprint",
        "ro.system.build.fingerprint"
    )

    val SERIAL_PROP_KEYS = listOf(
        "ro.serialno",
        "ro.boot.serialno",
        "ro.boot.bootreason",
        "ro.boot.vbmeta.digest"
    )

    val RIL_PROP_KEYS = listOf(
        "gsm.sim.operator.numeric",
        "gsm.sim.operator.alpha",
        "gsm.sim.operator.iso-country",
        "gsm.operator.numeric",
        "gsm.operator.alpha",
        "gsm.operator.iso-country",
        "gsm.sim.state",
        "gsm.network.type",
        "ril.serialnumber",
        "ril.IMEI",
        "ril.IMSI",
        "ril.ICCID",
        "ril.cdma.meid"
    )

    data class DrmScheme(val name: String, val uuid: UUID, val spoofed: Boolean)

    val DRM_SCHEMES = listOf(
        DrmScheme("Widevine", UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L), spoofed = true),
        DrmScheme("PlayReady", UUID(0x9a04f07998404286uL.toLong(), 0xab92e65be0885f95uL.toLong()), spoofed = false),
        DrmScheme("ClearKey", UUID(0xe2719d58a985b3c9uL.toLong(), 0x781ab030af78d30euL.toLong()), spoofed = false)
    )

    val SETTINGS_GLOBAL_KEYS = listOf(
        "device_name",
        "bluetooth_name",
        "bluetooth_address",
        "wifi_country_code",
        "boot_count",
        "adb_enabled",
        "development_settings_enabled",
        "install_non_market_apps"
    )

    val SETTINGS_SECURE_KEYS = listOf(
        "bluetooth_name",
        "bluetooth_address",
        "android_id"
    )
}
