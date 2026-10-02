package com.codequest.infra.db

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * settings_kv 通用键值仓库(1.7.0 F3):插件级持久配置的原始文本存取
 * (首个消费者:复习间隔 [com.codequest.core.review.ReviewService.SETTINGS_KEY])。
 *
 * 键名与取值语义由调用方约定,本仓库不做解析;put 为先删后插单事务
 * (照 [ExposedReviewRepository.upsert] 先例)。
 */
class ExposedSettingsKvRepository(
    private val db: CodeQuestDatabase,
) {
    suspend fun get(key: String): String? =
        db.read {
            SettingsKv
                .selectAll()
                .where { SettingsKv.key eq key }
                .firstOrNull()
                ?.get(SettingsKv.value)
        }

    suspend fun put(
        key: String,
        value: String,
    ) {
        db.write {
            SettingsKv.deleteWhere { SettingsKv.key eq key }
            SettingsKv.insert {
                it[SettingsKv.key] = key
                it[SettingsKv.value] = value
            }
            Unit
        }
    }
}
