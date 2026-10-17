package com.nvr.camnory

import java.net.URLEncoder

internal enum class EventKind(val titleRes: Int, val labels: String?) {
    Any(R.string.filter_all, null),
    Person(R.string.person, "person"),
    Vehicle(R.string.vehicle, "car,vehicle,truck,bus,motorcycle,bicycle"),
    Animal(R.string.animal, "dog,cat,bird,horse,sheep,cow,bear,animal")
}

internal data class EventSearch(val camera: String? = null, val name: String? = null,
    val kind: EventKind = EventKind.Any, val hours: Int? = null) {
    val activeFilterCount: Int get() = listOf(camera != null, name != null, kind != EventKind.Any, hours != null).count { it }
    fun query(now: Double, before: Double? = null): String {
        val params = linkedMapOf("limit" to "100", "include_thumbnails" to "0")
        camera?.let { params["cameras"] = it }
        name?.let { params["sub_labels"] = it }
        kind.labels?.let { params["labels"] = it }
        hours?.let { params["after"] = (now - it * 3600.0).toString() }
        params["before"] = (before ?: now).toString()
        return "/api/events?" + params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8") }"
        }
    }
}
