package com.nvr.camnory

internal enum class TvGridLayout(val preference: String, val columns: Int, val rows: Int, val titleRes: Int) {
    Single("1x1", 1, 1, R.string.tv_grid_1x1),
    Pair("2x1", 2, 1, R.string.tv_grid_2x1),
    Four("2x2", 2, 2, R.string.tv_grid_2x2),
    Nine("3x3", 3, 3, R.string.tv_grid_3x3),
    Sixteen("4x4", 4, 4, R.string.tv_grid_4x4);

    companion object {
        fun fromPreference(value: String?) = values().firstOrNull { it.preference == value } ?: Four
    }
}
