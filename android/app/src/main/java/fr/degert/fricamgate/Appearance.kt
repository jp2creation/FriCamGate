package fr.degert.fricamgate

internal object Appearance {
    fun isDark(theme: String?, systemDark: Boolean): Boolean = when (theme) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    fun textScale(value: String?): Float = when (value) {
        "small" -> 0.9f
        "large" -> 1.1f
        else -> 1f
    }

    fun accent(value: String?): Int = when (value) {
        "blue" -> 0xff1976d2.toInt()
        "green" -> 0xff00856a.toInt()
        "violet" -> 0xff9162d4.toInt()
        else -> 0xffff2d63.toInt()
    }
}
