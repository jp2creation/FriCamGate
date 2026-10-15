package com.nvr.camnory

/** Null means recordings have caught up with live; do not request future media. */
internal fun nextRecordingFocus(windowEndSec: Long, nowSec: Double): Double? =
    windowEndSec.toDouble().takeIf { it < nowSec - 3.0 }
