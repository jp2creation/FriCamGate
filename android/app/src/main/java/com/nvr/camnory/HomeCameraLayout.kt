package com.nvr.camnory

internal enum class HomeCameraLayout(val preference: String, val columns: Int, val titleRes: Int, val iconRes: Int) {
    Column("column", 1, R.string.camera_layout_column, R.drawable.ic_layout_rows),
    TwoColumns("two_columns", 2, R.string.camera_layout_two_columns, R.drawable.ic_layout_columns),
    Compact("compact", 3, R.string.camera_layout_compact, R.drawable.ic_layout_compact);

    companion object {
        fun fromPreference(value: String?): HomeCameraLayout =
            values().firstOrNull { it.preference == value } ?: Column
    }
}
