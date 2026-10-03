package com.codequest.spi

/**
 * 收藏写能力(1.9.0 F1):题面页星标按钮两态切换的数据出口(默认收藏夹,选夹
 * 面板 YAGNI 见 spec §0)。
 *
 * 仅站内有收藏夹体系的平台实现(LeetCode 两变体);REST 消费方
 * (`POST /api/favorite/toggle`)按能力探测 404。失败抛领域异常:
 * 未登录 [com.codequest.core.model.AuthExpiredException] 透传(REST 401 提示
 * 重登),站方拒绝(重复加入/夹不存在等协议级失败)[com.codequest.core.model.OJException]
 * (REST 502,前端乐观切换回滚)。
 *
 * 协议事实(LeetCode,2026-10-03 双站实测 + 插件源锁定,详见
 * LeetCodeApi.addToDefaultFavorite KDoc):mutation
 * `addQuestionToFavorite`/`removeQuestionFromFavorite`,变量
 * `{favoriteIdHash: String!, questionId: String!}`(questionId 为 String! 非 ID!,
 * 实测 schema 拒绝 ID!),响应 `{ok, error}`;默认夹 idHash 经 `favoritesLists`
 * 定位(FavoriteNode 无 isDefaultFavorite 标记字段,按 name=="Favorite" 认定,
 * 插件源 leetcode-extension-main src/rpc/actionChain/chainNode/leetcode.ts
 * getUser:`private_favorites.find(name === "Favorite").id_hash`)。
 */
interface FavoriteWriteCapable {
    /**
     * 加入默认收藏夹。[questionId] 为平台数字主档 id(与
     * [ProblemStatement.questionId] 同源)。重复加入按站方幂等语义处理(已收藏
     * 不视为失败);失败抛领域异常。
     */
    suspend fun addToDefaultFavorite(questionId: String)

    /**
     * 从默认收藏夹移除。**幂等语义:题目不在夹中移除视为成功**(站方 ok=true,
     * 2026-10-03 插件源锁定;spec §5 风险对策);失败抛领域异常。
     */
    suspend fun removeFromDefaultFavorite(questionId: String)
}
