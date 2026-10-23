package jp.mushamusha.kyounokondate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)

    fun seenIds(): Set<String> = seenList().toSet()

    fun markSeen(id: String) {
        val list = seenList().toMutableList()
        list.remove(id)
        list.add(0, id)
        while (list.size > 300) list.removeAt(list.lastIndex)
        prefs.edit().putString("seen_v2", JSONArray(list).toString()).apply()
    }

    fun clearSeen() {
        prefs.edit().remove("seen_v2").remove("seen").apply()
    }

    private fun seenList(): List<String> {
        prefs.getString("seen_v2", null)?.let { raw ->
            return runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }.getOrDefault(emptyList())
        }
        return runCatching { prefs.getStringSet("seen", emptySet()).orEmpty().toList() }.getOrDefault(emptyList())
    }

    fun addHistory(card: RecipeCard) {
        val old = parseCards(prefs.getString("history", "[]"))
            .distinctBy { it.videoId }
            .filterNot { it.videoId == card.videoId }
        writeCards("history", (listOf(card) + old).take(100))
    }

    fun history(): List<RecipeCard> {
        val favoriteIds = favoriteIds()
        return parseCards(prefs.getString("history", "[]"))
            .distinctBy { it.videoId }
            .map { it.copy(favorite = it.videoId in favoriteIds) }
    }

    fun favoriteIds(): Set<String> {
        val ids = parseCards(prefs.getString("favorite_cards", "[]")).mapTo(mutableSetOf()) { it.videoId }
        ids += legacyFavoriteIds()
        return ids
    }

    fun favorites(): List<RecipeCard> {
        val modern = parseCards(prefs.getString("favorite_cards", "[]"))
            .distinctBy { it.videoId }
            .map { it.copy(favorite = true) }
        val modernIds = modern.mapTo(mutableSetOf()) { it.videoId }
        val legacyIds = legacyFavoriteIds() - modernIds
        val legacyCards = parseCards(prefs.getString("history", "[]"))
            .filter { it.videoId in legacyIds }
            .map { it.copy(favorite = true) }
        return (modern + legacyCards).distinctBy { it.videoId }
    }

    private fun legacyFavoriteIds(): Set<String> =
        runCatching { prefs.getStringSet("favorites", emptySet()).orEmpty() }.getOrDefault(emptySet())

    fun toggleFavorite(card: RecipeCard): Boolean {
        val current = favorites().toMutableList()
        val existing = current.indexOfFirst { it.videoId == card.videoId }
        val nowFavorite = existing < 0
        if (nowFavorite) current.add(0, card.copy(favorite = true)) else current.removeAt(existing)
        writeCards("favorite_cards", current.take(200))
        prefs.edit().remove("favorites").apply()
        return nowFavorite
    }

    private fun writeCards(key: String, cards: List<RecipeCard>) {
        val arr = JSONArray()
        cards.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun parseCards(raw: String?): List<RecipeCard> {
        val arr = runCatching { JSONArray(raw ?: "[]") }.getOrElse { return emptyList() }
        return buildList {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.toRecipe()?.let(::add)
        }
    }

    private fun RecipeCard.toJson() = JSONObject().apply {
        put("videoId", videoId)
        put("title", title)
        put("thumbnailUrl", thumbnailUrl)
        put("recipeMinutes", recipeMinutes)
        put("ingredients", JSONArray(ingredients))
        put("steps", JSONArray(steps))
        put("evidence", evidence)
    }

    private fun JSONObject.toRecipe(): RecipeCard? = runCatching {
        fun list(name: String): List<String> {
            val a = optJSONArray(name) ?: JSONArray()
            return buildList { for (i in 0 until a.length()) add(a.optString(i)) }
        }
        RecipeCard(
            videoId = getString("videoId"),
            title = getString("title"),
            thumbnailUrl = optString("thumbnailUrl").ifBlank {
                "https://i.ytimg.com/vi/${getString("videoId")}/hqdefault.jpg"
            },
            recipeMinutes = optInt("recipeMinutes", 0),
            ingredients = list("ingredients"),
            steps = list("steps"),
            evidence = optString("evidence")
        )
    }.getOrNull()
}
