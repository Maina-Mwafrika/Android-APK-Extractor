package com.example

data class AppInfo(
    val packageName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val sourceDir: String,
    val sizeBytes: Long,
    val isSystemApp: Boolean,
    val installTime: Long,
    val updateTime: Long,
    val isSelected: Boolean = false
) {
    val sizeFormatted: String
        get() {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb < 0.1) {
                String.format("%.1f KB", sizeBytes / 1024.0)
            } else {
                String.format("%.2f MB", mb)
            }
        }
}
