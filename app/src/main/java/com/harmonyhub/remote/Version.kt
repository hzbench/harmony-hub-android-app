package com.harmonyhub.remote

/** A release number such as 0.5.0 or 1.0.0-rc1. A pre-release sorts before the release it leads up to. */
data class Version(val major: Int, val minor: Int, val patch: Int, val pre: String? = null) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        val numbers = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (numbers != 0) return numbers
        return when {
            pre == other.pre -> 0
            pre == null -> 1
            other.pre == null -> -1
            else -> pre.compareTo(other.pre)
        }
    }

    override fun toString() = "$major.$minor.$patch" + (pre?.let { "-$it" } ?: "")

    companion object {
        private val PATTERN = Regex("""v?(\d{1,6})\.(\d{1,6})\.(\d{1,6})(?:-([0-9A-Za-z.-]{1,32}))?""")

        /** A leading "v" is allowed (tags); anything else that is not a version gives null. */
        fun parse(text: String?): Version? {
            val match = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
            val (major, minor, patch, pre) = match.destructured
            return Version(major.toInt(), minor.toInt(), patch.toInt(), pre.ifEmpty { null })
        }
    }
}
