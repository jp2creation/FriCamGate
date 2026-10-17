package com.nvr.camnory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private fun catalogText(value: String, maximum: Int, blank: Boolean = false) {
    require(value.length <= maximum && (blank || value.isNotBlank()) && value.none { it.code < 32 || it.code == 127 })
}

internal data class CameraCategory(val id: String, val name: String) {
    init { catalogText(id, 160); catalogText(name, 60); require(name == name.trim()) }
}
internal data class CameraLabel(val alias: String = "", val categories: Set<String> = emptySet())

internal data class CameraCatalog(val categories: List<CameraCategory> = emptyList(),
    val labels: Map<String, CameraLabel> = emptyMap(), val selected: String? = null) {
    init {
        require(categories.size <= 50 && labels.size <= 5000)
        require(categories.map { it.id }.distinct().size == categories.size)
        require(categories.map { it.name.lowercase(Locale.ROOT) }.distinct().size == categories.size)
        val ids = categories.map { it.id }.toSet()
        require(selected == null || selected in ids)
        labels.forEach { (key, label) ->
            catalogText(key, 4096); catalogText(label.alias, 120, true)
            require(label.categories.all { it in ids })
        }
    }
    fun displayName(key: String, original: String) = labels[key]?.alias?.takeIf { it.isNotBlank() } ?: original
    fun includes(key: String) = selected == null || selected in labels[key]?.categories.orEmpty()
    fun select(id: String?) = copy(selected = id)
    fun add(id: String, name: String) = copy(categories = categories + CameraCategory(id, name.trim()))
    fun rename(id: String, name: String): CameraCatalog {
        require(categories.any { it.id == id })
        return copy(categories = categories.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }
    fun delete(id: String) = copy(categories = categories.filterNot { it.id == id },
        labels = labels.mapValues { (_, value) -> value.copy(categories = value.categories - id) },
        selected = selected.takeUnless { it == id })
    fun label(key: String, alias: String, groups: Set<String>) = copy(labels = labels.toMutableMap().apply {
        val label = CameraLabel(alias.trim(), groups.toSet())
        if (label.alias.isEmpty() && groups.isEmpty()) remove(key) else put(key, label)
    })
    fun assign(category: String, available: Set<String>, selected: Set<String>): CameraCatalog {
        require(categories.any { it.id == category } && selected.all { it in available })
        return available.fold(this) { current, key ->
            val previous = current.labels[key] ?: CameraLabel()
            current.label(key, previous.alias, if (key in selected) previous.categories + category else previous.categories - category)
        }
    }
    fun encode(): String = JSONObject().put("version", 1)
        .put("categories", JSONArray().apply { categories.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        .put("labels", JSONObject().apply { labels.forEach { (key, value) ->
            put(key, JSONObject().put("alias", value.alias).put("categories", JSONArray(value.categories.toList())))
        } }).put("selected", selected ?: JSONObject.NULL).toString().also { require(it.length <= 1_048_576) }
    companion object {
        fun decode(text: String): CameraCatalog {
            require(text.length <= 1_048_576)
            val root = JSONObject(text)
            require(root.getInt("version") == 1)
            val array = root.getJSONArray("categories")
            require(array.length() <= 50)
            val categories = (0 until array.length()).map { array.getJSONObject(it).let { CameraCategory(it.getString("id"), it.getString("name")) } }
            val values = root.getJSONObject("labels")
            require(values.length() <= 5000)
            val labels = values.keys().asSequence().associateWith { key ->
                val o = values.getJSONObject(key)
                val groups = o.getJSONArray("categories")
                require(groups.length() <= 50)
                CameraLabel(o.getString("alias"), (0 until groups.length()).map { groups.getString(it) }.toSet())
            }
            return CameraCatalog(categories, labels, if (root.isNull("selected")) null else root.getString("selected"))
        }
    }
}

/** Local presentation metadata only; no provider settings or credentials are written. */
internal class CameraCatalogStore(context: Context, preferenceName: String = "camera-catalog") {
    private val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    fun load() = prefs.getString("catalog", null)?.let { CameraCatalog.decode(it) } ?: CameraCatalog()
    fun update(change: (CameraCatalog) -> CameraCatalog) = synchronized(LOCK) {
        val next = change(load())
        check(prefs.edit().putString("catalog", next.encode()).commit())
        next
    }
    companion object { private val LOCK = Any() }
}
