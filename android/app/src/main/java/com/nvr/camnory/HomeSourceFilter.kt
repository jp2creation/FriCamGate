package com.nvr.camnory

/** A removed connection must never leave the home screen stuck on an invisible source. */
internal class HomeSourceFilter(saved: String?, available: Set<String>) {
    val selected = saved?.takeIf { it in available }
    fun includes(source: String): Boolean = selected == null || selected == source
}
