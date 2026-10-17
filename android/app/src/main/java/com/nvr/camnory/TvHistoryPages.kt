package com.nvr.camnory

internal fun tvHistoryColumns(inbox: Boolean) = if (inbox) 1 else 5
internal fun tvHistoryRows(inbox: Boolean, textScale: Float) = if (inbox) (if (textScale > 1.1f) 4 else 5) else 1

internal fun tvShouldLoadHistory(position: Int, total: Int, hasMore: Boolean, loading: Boolean, error: Boolean) =
    hasMore && !loading && !error && total > 0 && position >= 0 && position >= (total - 5).coerceAtLeast(0)

/** Each page belongs to one day; incomplete pages have no focusable filler. */
internal fun <T> tvHistoryPages(items: List<T>, pageSize: Int, day: (T) -> String): List<List<T>> =
    items.groupBy(day).values.flatMap { it.chunked(pageSize) }
