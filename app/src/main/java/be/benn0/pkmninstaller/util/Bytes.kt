package be.benn0.pkmninstaller.util

import java.util.Locale

/** Formats [bytes] as a human-readable size, e.g. `1.5 GB`. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "unknown"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}
