package com.ikverse.signallab.update

/**
 * A release number: `major.minor.patch`, minor and patch below 100. The limit is what keeps this order the same as the
 * order of Android's version code (`major * 10000 + minor * 100 + patch`, see app/build.gradle.kts), which is the number
 * Android uses to refuse a downgrade. A tag that could be numbered differently by the two is not a version at all.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    val code: Long get() = major * 10_000L + minor * 100L + patch

    override fun compareTo(other: AppVersion): Int = code.compareTo(other.code)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""(\d{1,4})\.(\d{1,2})\.(\d{1,2})""")

        /** "v1.2.3", "1.2.3" or "1.2.3-debug" as a version; anything else, null. */
        fun parse(text: String?): AppVersion? {
            val core = text?.trim()?.removePrefix("v")?.removePrefix("V")?.substringBefore('-')?.substringBefore('+') ?: return null
            val m = PATTERN.matchEntire(core) ?: return null
            return AppVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
    }
}
