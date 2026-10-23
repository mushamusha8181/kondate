package jp.mushamusha.kyounokondate

import java.util.concurrent.ConcurrentHashMap

class MenuRepository(
    private val secureStore: SecureStore,
    private val localStore: LocalStore
) {
    private data class SearchState(
        val queue: ArrayDeque<RecipeCard> = ArrayDeque(),
        var searchRound: Int = 0,
        var pendingCandidates: List<SearchCandidate>? = null,
        var exhausted: Boolean = false,
        val judgedIds: MutableSet<String> = mutableSetOf()
    )

    private val states = ConcurrentHashMap<String, SearchState>()

    fun next(filters: FilterState): RecipeCard? {
        val state = states.computeIfAbsent(filters.searchKey()) { SearchState() }
        val seen = localStore.seenIds()
        val favoriteIds = localStore.favoriteIds()

        while (state.queue.isNotEmpty()) {
            val card = state.queue.removeFirst()
            if (card.videoId in seen) continue
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Cancelled")
            card.favorite = card.videoId in favoriteIds
            localStore.markSeen(card.videoId)
            localStore.addHistory(card)
            return card
        }

        if (state.exhausted) {
            state.exhausted = false
            state.searchRound = 0
            state.judgedIds.clear()
        }
        refill(state, filters, seen)

        while (state.queue.isNotEmpty()) {
            val card = state.queue.removeFirst()
            if (card.videoId in seen) continue
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Cancelled")
            card.favorite = card.videoId in favoriteIds
            localStore.markSeen(card.videoId)
            localStore.addHistory(card)
            return card
        }
        return null
    }

    private fun refill(state: SearchState, filters: FilterState, seen: Set<String>) {
        val apiKey = secureStore.get("deepseek_api_key") ?: throw MissingKey("DeepSeek APIキー")
        val client = DeepSeekClient(apiKey)

        val excludedIds = seen + state.judgedIds
        val candidates = state.pendingCandidates ?: client.searchYouTube(filters, excludedIds, state.searchRound).also {
            state.pendingCandidates = it
        }
        val accepted = if (candidates.isEmpty()) emptyList() else client.chooseStrict(candidates, filters)
        state.judgedIds += candidates.map { it.videoId }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Cancelled")

        state.pendingCandidates = null
        state.searchRound += 1
        state.exhausted = state.searchRound >= 4
        accepted
            .filterNot { it.videoId in seen }
            .distinctBy { it.videoId }
            .forEach { state.queue.add(it) }
    }

    fun clearQueues() = states.clear()
}

class MissingKey(val label: String) : IllegalStateException(label)
