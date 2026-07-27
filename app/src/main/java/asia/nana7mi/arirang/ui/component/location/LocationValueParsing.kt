package asia.nana7mi.arirang.ui.component.location

/**
 * Shared parsing for the location coordinate/accuracy/speed text fields.
 *
 * LocationConfigScreen (global profile) and LocationAppComponents (per-app
 * profile) edit the same values and had a copy of these each, under two names
 * for the same result type. The copies had already diverged: the screen's
 * parseFloat tested `value < min || value > max`, which is false for NaN — and
 * "NaN" parses fine via toFloatOrNull — so it accepted NaN where the other
 * rejected it. The range check below rejects NaN in every case.
 */
internal data class ParsedValue(
    val doubleValue: Double? = null,
    val floatValue: Float? = null,
    val intValue: Int? = null,
    val error: String? = null
)

internal fun parseDouble(
    text: String,
    label: String,
    min: Double,
    max: Double,
    errorFormat: String
): ParsedValue {
    val value = text.trim().toDoubleOrNull()
    return if (value == null || value !in min..max) {
        ParsedValue(error = errorFormat.format(label, min.toString(), max.toString()))
    } else {
        ParsedValue(doubleValue = value)
    }
}

internal fun parseFloat(
    text: String,
    label: String,
    min: Float,
    max: Float,
    errorFormat: String
): ParsedValue {
    val value = text.trim().toFloatOrNull()
    return if (value == null || value !in min..max) {
        ParsedValue(error = errorFormat.format(label, min.toString(), max.toString()))
    } else {
        ParsedValue(floatValue = value)
    }
}

internal fun parseInt(
    text: String,
    label: String,
    min: Int,
    max: Int,
    errorFormat: String
): ParsedValue {
    val value = text.trim().toIntOrNull()
    return if (value == null || value !in min..max) {
        ParsedValue(error = errorFormat.format(label, min.toString(), max.toString()))
    } else {
        ParsedValue(intValue = value)
    }
}
