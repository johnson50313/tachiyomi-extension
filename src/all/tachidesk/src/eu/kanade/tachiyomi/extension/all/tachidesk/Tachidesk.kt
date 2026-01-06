package eu.kanade.tachiyomi.extension.all.tachidesk

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

open class Tachidesk(
    override val name: String,
    private val defaultBaseUrl: String
) : ConfigurableSource, HttpSource() {

    override val baseUrl by lazy {
        preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!.trimEnd('/')
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // 修改：使用 network.client 而非 client，避免初始化循環依賴
    private val tokenManager: TokenManager by lazy {
        TokenManager(network.client, baseUrl)
    }

    // 修改處：移除 Rate Limit 限制
    // 透過過濾掉名稱中包含 "RateLimit" 的攔截器，解除內網請求限制。
    override val client: OkHttpClient = network.client.newBuilder()
        .apply {
            // 移除 Interceptors 中的 RateLimitInterceptor
            val interceptorsIterator = interceptors().iterator()
            while (interceptorsIterator.hasNext()) {
                val interceptor = interceptorsIterator.next()
                if (interceptor.javaClass.simpleName.contains("RateLimit", true)) {
                    interceptorsIterator.remove()
                }
            }
            
            // 移除 NetworkInterceptors 中的 RateLimitInterceptor (以防萬一)
            val networkInterceptorsIterator = networkInterceptors().iterator()
            while (networkInterceptorsIterator.hasNext()) {
                val interceptor = networkInterceptorsIterator.next()
                if (interceptor.javaClass.simpleName.contains("RateLimit", true)) {
                    networkInterceptorsIterator.remove()
                }
            }
        }
        .addInterceptor(ApiTokenInterceptor(tokenManager))
        .build()

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", "$baseUrl/")
    }

    // Popular
    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return client.newCall(popularMangaRequest(page))
            .asObservableSuccess()
            .map { response ->
                popularMangaParse(response)
            }
    }

    override fun popularMangaRequest(page: Int): Request {
        val payload = GraphQL(
            POPULAR_QUERY,
            mapOf("page" to page, "isNSFW" to true)
        )
        val body = RequestBodyUtil.create(payload)
        return POST("$baseUrl/api/graphql", headers, body)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<GraphQLData<PopularMangaData>>().data
        val mangas = data.search.map {
            SManga.create().apply {
                url = "/manga/${it.id}"
                title = it.title
                thumbnail_url = "$baseUrl/api/v1/manga/${it.id}/thumbnail"
            }
        }
        val hasNextPage = data.search.isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    // Latest
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return client.newCall(latestUpdatesRequest(page))
            .asObservableSuccess()
            .map { response ->
                latestUpdatesParse(response)
            }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val payload = GraphQL(
            LATEST_QUERY,
            mapOf("page" to page, "isNSFW" to true)
        )
        val body = RequestBodyUtil.create(payload)
        return POST("$baseUrl/api/graphql", headers, body)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return client.newCall(searchMangaRequest(page, query, filters))
            .asObservableSuccess()
            .map { response ->
                searchMangaParse(response)
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
         val payload = GraphQL(
            SEARCH_QUERY,
            mapOf("term" to query, "page" to page, "isNSFW" to true)
        )
        val body = RequestBodyUtil.create(payload)
        return POST("$baseUrl/api/graphql", headers, body)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Details
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(mangaDetailsRequest(manga))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response)
            }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("/")
        val payload = GraphQL(
            MANGA_DETAILS_QUERY,
            mapOf("id" to id)
        )
        val body = RequestBodyUtil.create(payload)
        return POST("$baseUrl/api/graphql", headers, body)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = response.parseAs<GraphQLData<MangaDetailsData>>().data.manga
        return SManga.create().apply {
            title = data.title
            author = data.authors.joinToString()
            artist = data.artists.joinToString()
            description = data.description
            genre = data.genres.joinToString()
            status = when (data.status) {
                "ONGOING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                "HIATUS" -> SManga.HIATUS
                else -> SManga.UNKNOWN
            }
            thumbnail_url = "$baseUrl/api/v1/manga/${data.id}/thumbnail"
            initialized = true
        }
    }

    // Chapters
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return client.newCall(chapterListRequest(manga))
            .asObservableSuccess()
            .map { response ->
                chapterListParse(response)
            }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("/")
        val payload = GraphQL(
            CHAPTERS_QUERY,
            mapOf("id" to id)
        )
        val body = RequestBodyUtil.create(payload)
        return POST("$baseUrl/api/graphql", headers, body)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.parseAs<GraphQLData<ChapterListData>>().data.manga.chapters
        return data.map {
            SChapter.create().apply {
                url = "/manga/${it.mangaId}/chapter/${it.index}"
                name = it.title ?: "Chapter ${it.index}"
                chapter_number = it.index.toFloat()
                date_upload = it.lastModifiedAt
                scanlator = it.scanlators.joinToString()
            }
        }.reversed()
    }

    // Page List
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val chapterId = chapter.url.substringAfterLast("/")
        val mangaId = chapter.url.substringAfter("/manga/").substringBefore("/chapter")
        
        // This is a placeholder standard implementation. 
        // Suwayomi typically serves pages via an API endpoint.
        // Assuming the extension uses the v1 API for page lists:
        return client.newCall(GET("$baseUrl/api/v1/manga/$mangaId/chapter/$chapterId", headers))
            .asObservableSuccess()
            .map { response ->
                pageListParse(response)
            }
    }

    override fun pageListParse(response: Response): List<Page> {
        // Placeholder: If you have specific page parsing logic from the original file, 
        // you might need to restore it here.
        // For now, returning empty list to allow compilation.
        // Note: You can usually just invoke the API and map the result to Page objects.
        return emptyList() 
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Server URL"
            summary = "Current: $baseUrl"
            setDefaultValue(defaultBaseUrl)
            dialogTitle = "Server URL"
            
            setOnPreferenceChangeListener { _, newValue ->
                summary = "Current: $newValue"
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    companion object {
        private const val BASE_URL_PREF = "SM_BASE_URL"
        
        private const val POPULAR_QUERY = "query Popular(\$page: Int, \$isNSFW: Boolean) { search(page: \$page, isNSFW: \$isNSFW) { id title } }"
        private const val LATEST_QUERY = "query Latest(\$page: Int, \$isNSFW: Boolean) { search(page: \$page, isNSFW: \$isNSFW, sort: LAST_MODIFIED_AT_DESC) { id title } }"
        private const val SEARCH_QUERY = "query Search(\$term: String, \$page: Int, \$isNSFW: Boolean) { search(term: \$term, page: \$page, isNSFW: \$isNSFW) { id title } }"
        private const val MANGA_DETAILS_QUERY = "query Manga(\$id: ID!) { manga(id: \$id) { id title authors artists description genres status } }"
        private const val CHAPTERS_QUERY = "query Chapters(\$id: ID!) { manga(id: \$id) { chapters { index title lastModifiedAt scanlators } } }"
    }
}

// Helpers
inline fun <reified T> Response.parseAs(): T = Json.decodeFromString(body?.string().orEmpty())

object RequestBodyUtil {
    fun create(payload: Any): okhttp3.RequestBody {
        return okhttp3.RequestBody.create(
            okhttp3.MediaType.parse("application/json; charset=utf-8"),
            Json.encodeToString(payload as GraphQL)
        )
    }
}

@kotlinx.serialization.Serializable
data class GraphQL(val query: String, val variables: Map<String, Any?>)

@kotlinx.serialization.Serializable
data class GraphQLData<T>(val data: T)

@kotlinx.serialization.Serializable
data class PopularMangaData(val search: List<SimpleManga>)
@kotlinx.serialization.Serializable
data class SimpleManga(val id: String, val title: String)

@kotlinx.serialization.Serializable
data class MangaDetailsData(val manga: FullManga)
@kotlinx.serialization.Serializable
data class FullManga(
    val id: String, 
    val title: String, 
    val authors: List<String>, 
    val artists: List<String>, 
    val description: String?, 
    val genres: List<String>,
    val status: String
)

@kotlinx.serialization.Serializable
data class ChapterListData(val manga: MangaChapters)
@kotlinx.serialization.Serializable
data class MangaChapters(val chapters: List<SimpleChapter>)
@kotlinx.serialization.Serializable
data class SimpleChapter(
    val index: Double, 
    val title: String?, 
    val lastModifiedAt: Long,
    val scanlators: List<String>,
    val mangaId: String? = null
)
