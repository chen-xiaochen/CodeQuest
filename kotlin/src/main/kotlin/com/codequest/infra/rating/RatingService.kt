package com.codequest.infra.rating

import com.codequest.core.model.OJException
import com.codequest.core.model.ProblemRating
import com.codequest.infra.db.ExposedRatingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * 题目难度评分服务(1.7.0 F1):zerotrac 口径评分的内存缓存持有者。
 *
 * 加载优先级(spec §1.1):DB `problem_rating` 非空用 DB,否则加载插件内置资源
 * `rating/leetcode-ratings.json`(离线兜底);两者皆空为 empty 态。快照进程内缓存,
 * [refresh] 在线拉 zerotrac 全量覆盖 DB 后重载。
 *
 * 仓库经 lambda 按调用解析(数据库切换即时生效;已加载快照不自动失效——评分
 * 为低频只读数据,切换库后手动刷新即可,ponytail: 不做库切换联动)。
 */
class RatingService(
    private val ratings: () -> ExposedRatingRepository,
    /** zerotrac 原始数据拉取(测试注入假源;缺省真实 HTTP)。 */
    private val fetchRemote: suspend () -> String = { fetchZerotrac(ZEROTRAC_DATA_URL) },
    private val now: () -> Instant = Instant::now,
    /** 内置资源 classpath 路径(测试注入不存在路径以构造双空态)。 */
    private val bundledResource: String = BUNDLED_RESOURCE,
) {
    /** 内存快照:source ∈ db / bundled / empty;[updatedAt] 仅 refresh 后有意义。 */
    data class Snapshot(
        val map: Map<String, Double>,
        val source: String,
        val updatedAt: Instant?,
    )

    @Volatile
    private var snapshot: Snapshot = Snapshot(emptyMap(), SOURCE_EMPTY, null)

    @Volatile
    private var loaded = false

    /** 懒加载快照(重复调用读缓存;并发首载竞争幂等,不加锁)。 */
    suspend fun load(): Snapshot {
        if (loaded) return snapshot
        val db = ratings().loadAll()
        snapshot =
            if (db.isNotEmpty()) {
                Snapshot(db, SOURCE_DB, null)
            } else {
                val bundled = loadBundled()
                if (bundled.isEmpty()) Snapshot(emptyMap(), SOURCE_EMPTY, null) else Snapshot(bundled, SOURCE_BUNDLED, null)
            }
        loaded = true
        return snapshot
    }

    /**
     * 在线刷新:拉 zerotrac → 解析 → upsert DB → 重载内存。
     * 拉取/解析失败抛 [OJException](带原因)——upsert 在解析成功后才执行,
     * DB 与既有内存快照均不受影响。
     */
    suspend fun refresh(): Snapshot {
        val parsed =
            try {
                parseZerotrac(fetchRemote())
            } catch (error: Exception) {
                throw OJException("zerotrac 评分数据获取/解析失败: ${error.message}")
            }
        if (parsed.isEmpty()) throw OJException("zerotrac 评分数据为空")
        val stamp = now()
        ratings().upsertAll(parsed.map { (frontendId, rating) -> ProblemRating(frontendId, rating, stamp) })
        snapshot = Snapshot(parsed, SOURCE_DB, stamp)
        loaded = true
        return snapshot
    }

    /** 按题号查分(null = 无该题评分/平台无题号概念;首次调用触发懒加载)。 */
    suspend fun get(frontendId: String?): Double? {
        if (frontendId.isNullOrBlank()) return null
        load()
        return snapshot.map[frontendId]
    }

    /** 当前快照(懒加载后);`/api/rating/status` 数据源。 */
    suspend fun status(): Snapshot = load()

    private fun loadBundled(): Map<String, Double> =
        runCatching {
            javaClass.classLoader.getResourceAsStream(bundledResource)?.use { stream ->
                parseBundled(stream.readBytes().decodeToString())
            }
        }.getOrNull().orEmpty()

    companion object {
        const val SOURCE_DB = "db"
        const val SOURCE_BUNDLED = "bundled"
        const val SOURCE_EMPTY = "empty"

        /** zerotrac 全量数据地址(刷新数据源;内置资源来源见同目录 SOURCE.txt)。 */
        const val ZEROTRAC_DATA_URL = "https://zerotrac.github.io/leetcode_problem_rating/data.json"

        const val BUNDLED_RESOURCE = "rating/leetcode-ratings.json"

        private val JSON = Json { ignoreUnknownKeys = true }

        /** zerotrac 原始格式:`[{"ID": 3743, "Rating": 3124.5, ...}, ...]` → `{"3743": 3124.5}`。 */
        internal fun parseZerotrac(raw: String): Map<String, Double> =
            JSON
                .parseToJsonElement(raw)
                .jsonArray
                .mapNotNull { element ->
                    val item = element.jsonObject
                    val id = item["ID"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val rating = item["Rating"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    id.toString() to rating
                }.toMap()

        /** 内置资源格式:扁平 `{"<frontendId>": rating}` 映射。 */
        internal fun parseBundled(raw: String): Map<String, Double> =
            JSON
                .parseToJsonElement(raw)
                .jsonObject
                .mapNotNull { (id, value) ->
                    value.jsonPrimitive.doubleOrNull?.let { id to it }
                }.toMap()

        /** 真实拉取(java.net.http,连接与请求超时各 ~10s;阻塞 send 挂到 IO 池)。 */
        internal suspend fun fetchZerotrac(url: String): String =
            withContext(Dispatchers.IO) {
                HttpClient
                    .newBuilder()
                    .connectTimeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS))
                    .build()
                    .use { client ->
                        val response =
                            client.send(
                                HttpRequest
                                    .newBuilder(URI.create(url))
                                    .timeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS))
                                    .GET()
                                    .build(),
                                HttpResponse.BodyHandlers.ofString(),
                            )
                        if (response.statusCode() !in HTTP_OK_MIN..HTTP_OK_MAX) {
                            throw OJException("zerotrac HTTP ${response.statusCode()}")
                        }
                        response.body()
                    }
            }

        private const val FETCH_TIMEOUT_SECONDS = 10L

        private const val HTTP_OK_MIN = 200

        private const val HTTP_OK_MAX = 299
    }
}
