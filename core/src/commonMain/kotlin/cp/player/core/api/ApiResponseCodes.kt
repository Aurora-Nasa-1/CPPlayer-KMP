package cp.player.core.api

/**
 * 上游「成功码」判定的**唯一**码表。
 *
 * 这张表原本在三处各写了一份 —— `MusicApiServiceImpl.callApi` 内联、
 * `MusicSourceFromApi.isSuccess`、`CachedMusicApiService` 的 `classifyFresh` /
 * `tryFallback`。三处必须同时改才一致，漏一处就出现「健康监控认为成功、
 * 业务层认为失败」的错位，且没有任何编译期提示。
 *
 * 现统一到本对象，`ApiResponseCodesTest` 同时钉住**码表内容**与
 * **业务层确实走同一张表**。
 *
 * ### 为什么不把扫码中间态并进 [SUCCESS]
 * `801/802/803` 表示「二维码还没扫 / 已扫码未确认」，是**过程态**而非成功：
 * 把它算成功会让登录流程在用户还没点确认时就当作已登录。
 * 它们只在 `login/qr/check` 这个端点有意义，因此单独成表 [QR_PENDING]。
 */
object ApiResponseCodes {

    /**
     * 通用成功码：
     * - `200` 网易云风格
     * - `0` 部分 Provider 用 0 表示成功
     * - `201` 创建类接口
     * - `301` 重定向类接口
     */
    val SUCCESS: Set<Int> = setOf(0, 200, 201, 301)

    /** 扫码登录的中间态（未扫码 / 已扫码 / 已确认），**不属于** [SUCCESS]。 */
    val QR_PENDING: Set<Int> = setOf(801, 802, 803)

    /**
     * 判定响应码是否为成功。
     *
     * 拿不到 `code`（返回 null）一律不算成功 —— 响应体里没有 code 字段时
     * 无法判断业务是否成功，按失败处理（与既有行为一致）。
     */
    fun isSuccess(code: Int?): Boolean = code != null && code in SUCCESS
}
