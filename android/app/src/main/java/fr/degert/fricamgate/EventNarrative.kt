package fr.degert.fricamgate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal data class EventFacts(val label: String, val name: String?, val description: String?) {
    val subjectRes: Int get() = when (label.lowercase(Locale.ROOT)) {
        "person" -> R.string.activity_person
        "car", "vehicle", "truck", "bus", "motorcycle", "bicycle" -> R.string.activity_vehicle
        "cat" -> R.string.activity_cat
        "dog" -> R.string.activity_dog
        "bird", "animal" -> R.string.activity_animal
        else -> R.string.activity_motion
    }
    val useNameAsSubject get() = label.lowercase(Locale.ROOT) in listOf("person", "cat", "dog", "bird", "animal")

    companion object {
        fun fromJson(event: JSONObject): EventFacts {
            val sub = event.opt("sub_label")
            val name = when (sub) {
                is JSONArray -> sub.opt(0) as? String
                is String -> sub
                else -> null
            }
            return EventFacts(clean(event.opt("label")) ?: "motion", clean(name),
                clean(event.optJSONObject("data")?.opt("description")) ?: clean(event.opt("description")))
        }
        private fun clean(value: Any?): String? = (value as? String)?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals("null", ignoreCase = true)
        }
    }
}

internal data class ActivityItem(val id: String, val camera: String, val startTime: Double, val facts: EventFacts)
internal data class ActivityGroup(val latest: ActivityItem, val count: Int)

internal fun unreadActivities(items: List<ActivityItem>, acknowledged: Set<String>): List<ActivityItem> =
    items.filter { it.id !in acknowledged }

internal fun groupActivities(items: List<ActivityItem>): List<ActivityGroup> = items
    .filter { it.startTime.isFinite() && it.startTime > 0 }
    .distinctBy { it.id }
    .groupBy { Triple(it.camera, it.facts.label, it.facts.name) }
    .values.map { ActivityGroup(it.maxBy { event -> event.startTime }, it.size) }
    .sortedByDescending { it.latest.startTime }

internal fun eventNarrative(context: Context, facts: EventFacts, camera: String, startTime: Double?): String {
    val category = context.getString(facts.subjectRes)
    val subject = facts.name?.let { name ->
        if (facts.useNameAsSubject) name else context.getString(R.string.activity_named_category, category, name)
    } ?: category
    val date = startTime?.takeIf { it.isFinite() && it > 0 }?.let {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, context.resources.configuration.locales[0])
            .format(Date((it * 1000).toLong()))
    }
    return if (date != null) context.getString(R.string.activity_sentence_time, subject, camera, date)
        else context.getString(R.string.activity_sentence, subject, camera)
}
