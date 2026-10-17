package com.nvr.camnory

/** Each page belongs to one day; incomplete pages have no focusable filler. */
internal fun <T> tvHistoryPages(items: List<T>, pageSize: Int, day: (T) -> String): List<List<T>> =
    items.groupBy(day).values.flatMap { it.chunked(pageSize) }
