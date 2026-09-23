package com.khatwa.app.quotes

import android.content.Context
import com.khatwa.app.data.QuoteDao
import com.khatwa.app.data.QuoteEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class QuoteJson(val text: String, val source: String? = null)

@Serializable
data class QuotesFile(val quotes: List<QuoteJson>)

class QuoteRepository(private val context: Context, private val dao: QuoteDao) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun observeAll() = dao.observeAll()

    suspend fun seedIfEmpty() {
        if (dao.count() > 0) return
        val bundled = loadBundled()
        dao.insertAll(bundled.mapIndexed { i, q -> QuoteEntity(text = q.text, source = q.source, sortOrder = i) })
    }

    fun loadBundled(): List<QuoteJson> {
        val text = context.assets.open("quotes.json").bufferedReader().use { it.readText() }
        return json.decodeFromString<QuotesFile>(text).quotes
    }

    suspend fun add(text: String) {
        val order = (dao.all().maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.upsert(QuoteEntity(text = text.trim(), source = null, sortOrder = order))
    }

    suspend fun update(q: QuoteEntity, text: String) = dao.upsert(q.copy(text = text.trim()))

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun exportJson(): String =
        json.encodeToString(QuotesFile.serializer(), QuotesFile(dao.all().map { QuoteJson(it.text, it.source) }))

    /** Replaces all quotes with the given JSON (same format as assets/quotes.json). */
    suspend fun importJson(text: String): Int {
        val parsed = json.decodeFromString<QuotesFile>(text).quotes.filter { it.text.isNotBlank() }
        require(parsed.isNotEmpty()) { "empty" }
        dao.deleteAll()
        dao.insertAll(parsed.mapIndexed { i, q -> QuoteEntity(text = q.text.trim(), source = q.source, sortOrder = i) })
        return parsed.size
    }

    suspend fun restoreBundled(): Int {
        val bundled = loadBundled()
        dao.deleteAll()
        dao.insertAll(bundled.mapIndexed { i, q -> QuoteEntity(text = q.text, source = q.source, sortOrder = i) })
        return bundled.size
    }
}
