package jp.mushamusha.kyounokondate

data class FilterState(
    val minutes: Int = 3,
    val category: String = "主菜",
    val method: String = "なんでも",
    val displayMode: String = "動画",
    val includeIngredients: String = "",
    val excludeIngredients: String = ""
) {
    fun searchKey(): String = listOf(
        minutes,
        category,
        method,
        includeIngredients.trim(),
        excludeIngredients.trim()
    ).joinToString("|")
}

data class SearchCandidate(
    val videoId: String,
    val title: String,
    val snippet: String,
    val summary: String,
    val content: String,
    val thumbnailUrl: String
) {
    fun evidenceText(): String = buildString {
        appendLine(title)
        if (snippet.isNotBlank()) appendLine(snippet)
        if (summary.isNotBlank()) appendLine(summary)
        if (content.isNotBlank()) append(content)
    }
}

data class RecipeCard(
    val videoId: String,
    val title: String,
    val thumbnailUrl: String,
    val recipeMinutes: Int,
    val ingredients: List<String>,
    val steps: List<String>,
    val evidence: String,
    var favorite: Boolean = false
)

fun parseIngredientTerms(raw: String): List<String> = raw
    .split(',', '、', '，', '\n')
    .map { it.trim().take(40) }
    .filter { it.isNotBlank() }
    .distinct()
    .take(10)
