package cp.player.core.listentogether

import cp.player.core.BackendResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 引擎的行为契约。
 *
 * 这些用例钉的是**实测出来的服务端怪癖**（见方案 §9.5），不是我自己设计的规则 ——
 * 每一条都对应一次真实踩坑：
 *
 * - `accept` **已进房时无条件成功** ⇒ 加入流程必须先查在房状态，否则会把「其实没加入」
 *   当成加入成功；
 * - `accept` 失败只有笼统的 `488` ⇒ 错误文案必须是中性的，不能编造具体原因；
 * - 建房会**顶掉**当前房间且 `end` 不可逆 ⇒ 已在房间时必须硬拦；
 * - 轮询失败**不能**清空房间状态 ⇒ 一次网络抖动不该把用户踢出房间。
 */
class ListenTogetherEngineTest {

    /** 可控假后端；记录调用次数，用来验证「某些路径压根没发请求」。 */
    private class FakeBackend(
        var membershipResult: BackendResult<ListenTogetherMembership> = ok(membership(inRoom = false)),
        var createResult: BackendResult<ListenTogetherRoom> = ok(room()),
        var acceptResult: BackendResult<Unit> = ok(Unit),
    ) : ListenTogetherBackend {
        override val providerId: String = "test"
        override fun isSupported(): Boolean = true

        var createCalls = 0
        var acceptCalls = 0
        var endCalls = 0

        override suspend fun membership() = membershipResult
        override suspend fun createRoom(): BackendResult<ListenTogetherRoom> {
            createCalls++
            return createResult
        }

        override suspend fun accept(roomId: String, inviterId: Long): BackendResult<Unit> {
            acceptCalls++
            return acceptResult
        }

        override suspend fun end(roomId: String): BackendResult<Unit> {
            endCalls++
            return ok(Unit)
        }

        override suspend fun snapshot(roomId: String) = ok(RoomSnapshot(null, null))
        override suspend fun heartbeat(
            roomId: String, songId: String, playStatus: String, progressMs: Long,
        ) = ok(Unit)

        override suspend fun reportPlayCommand(
            roomId: String, commandType: String, playStatus: String, progressMs: Long,
            formerSongId: String?, targetSongId: String?, clientSeq: Long,
        ) = ok(Unit)

        override suspend fun reportPlaylist(
            roomId: String, userId: Long, version: Long, songIds: List<String>,
        ) = ok(Unit)
    }

    private fun engine(backend: FakeBackend) = ListenTogetherEngine(
        backend = backend,
        // 用例直接调 suspend 方法，不跑 start() 的轮询循环，所以 scope 不会被用到。
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        myUserId = { 42L },
    )

    // ======================== 建房：已在房间必须硬拦 ========================

    @Test
    fun `已在房间时建房被拦下且不发请求`() = runBlocking {
        val backend = FakeBackend(membershipResult = ok(membership(inRoom = true)))
        val e = engine(backend)

        e.createRoom()

        // 关键：**一次 createRoom 都不能发** —— 发了就会顶掉当前房间，而 end 不可逆。
        assertEquals(0, backend.createCalls, "已在房间却仍然打了建房请求")
        assertEquals(ListenTogetherEngine.ALREADY_IN_ROOM, e.state.value.error)
    }

    @Test
    fun `本地以为不在房间但服务端在房间时也要拦`() = runBlocking {
        // 典型场景：另一台设备上建的房，本地还没同步到。
        val backend = FakeBackend()
        val e = engine(backend)
        // 先把本地状态做成「不在房间」，然后让服务端回答「在房间」。
        backend.membershipResult = ok(membership(inRoom = true))

        e.createRoom()

        assertEquals(0, backend.createCalls)
        assertEquals(ListenTogetherEngine.ALREADY_IN_ROOM, e.state.value.error)
        assertTrue(e.state.value.inRoom, "拦下之后应把真实状态同步回来")
    }

    // ======================== 加入：accept 的不可靠性 ========================

    @Test
    fun `已在房间时加入被拦下且不调 accept`() = runBlocking {
        val backend = FakeBackend(membershipResult = ok(membership(inRoom = true)))
        val e = engine(backend)

        e.join("room_1", 99L)

        // accept 在已进房时会**无条件返回 200**，所以绝不能靠它的返回值判断是否加入成功。
        assertEquals(0, backend.acceptCalls, "已在房间却仍然调了 accept")
        assertEquals(ListenTogetherEngine.ALREADY_IN_ROOM, e.state.value.error)
    }

    @Test
    fun `参数不完整时本地判死不发请求`() = runBlocking {
        val backend = FakeBackend()
        val e = engine(backend)

        e.join("", 99L)

        assertEquals(0, backend.acceptCalls)
        assertEquals("邀请里缺少房间号", e.state.value.error)
    }

    @Test
    fun `accept 返回 488 时给中性文案而不编造原因`() = runBlocking {
        val backend = FakeBackend()
        val e = engine(backend)
        backend.acceptResult = BackendResult.Error(
            message = "API error (code=488): Unknown error",
            code = ListenTogetherEngine.INVITE_REJECTED_CODE,
        )

        e.join("room_1", 99L)

        // 实测 488 对「房间不存在」与「邀请不是给你的」返回**完全相同**的响应，
        // 因此只能给中性文案；这里断言上游那句看不懂的话没有漏到 UI 上。
        assertEquals(ListenTogetherEngine.INVALID_INVITE, e.state.value.error)
    }

    @Test
    fun `accept 成功但房间状态没变时不算加入成功`() = runBlocking {
        // accept 成功只代表服务端收下了请求；必须重新核对房间状态才算数。
        val backend = FakeBackend()
        val e = engine(backend)

        e.join("room_1", 99L)

        assertEquals(1, backend.acceptCalls)
        assertFalse(e.state.value.inRoom, "没有真的进房就不能显示成已加入")
        assertEquals(ListenTogetherEngine.INVALID_INVITE, e.state.value.error)
    }

    @Test
    fun `accept 成功且状态确实进房时才算成功`() = runBlocking {
        val backend = FakeBackend()
        val e = engine(backend)
        // 第一次 membership（join 的前置检查）不在房间，之后（加入后复核）在房间。
        var call = 0
        backend.membershipResult = ok(membership(inRoom = false))
        // 用一个会变的 membership：第 2 次起返回在房间。
        val dynamic = object : ListenTogetherBackend by backend {
            override suspend fun membership(): BackendResult<ListenTogetherMembership> {
                call++
                return ok(membership(inRoom = call > 1))
            }
        }
        val e2 = ListenTogetherEngine(
            backend = dynamic,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            myUserId = { 42L },
        )

        e2.join("room_1", 99L)

        assertTrue(e2.state.value.inRoom)
        assertEquals("已加入房间", e2.state.value.notice)
        assertNull(e2.state.value.error)
        // 顺带确认 e 没被这次操作影响（两个引擎互不串状态）
        assertFalse(e.state.value.inRoom)
    }

    // ======================== 轮询失败不能踢人 ========================

    @Test
    fun `轮询失败不清空已有的房间状态`() = runBlocking {
        val backend = FakeBackend()
        val e = engine(backend)
        e.createRoom()
        assertTrue(e.state.value.inRoom, "前置：建房后应在房间")

        // 网络抖动一次
        backend.membershipResult = BackendResult.Error("网络错误")

        e.refresh()

        assertTrue(e.state.value.inRoom, "一次轮询失败就把用户踢出房间是不可接受的")
        assertNull(e.state.value.error, "已有房间时不该因为轮询失败弹错误")
    }

    // ======================== 退出 ========================

    @Test
    fun `退出后本地状态归零`() = runBlocking {
        val backend = FakeBackend()
        val e = engine(backend)
        e.createRoom()

        e.endRoom()

        assertEquals(1, backend.endCalls)
        assertFalse(e.state.value.inRoom)
        assertNull(e.state.value.room)
    }

    // ======================== 邀请链接 ========================

    @Test
    fun `在房间且已知 uid 时给出邀请链接`() = runBlocking {
        val backend = FakeBackend()
        val e = engine(backend)
        e.createRoom()

        val url = e.state.value.shareUrl()

        assertTrue(url != null && url.contains("roomId=room_1"), "链接里必须带房间号：$url")
        assertTrue(url!!.contains("inviterId=42"), "链接里必须带邀请人 uid")
    }

    // ======================== 夹具 ========================

    private companion object {
        fun <T> ok(value: T): BackendResult<T> = BackendResult.Success(value)

        fun membership(inRoom: Boolean) = ListenTogetherMembership(
            inRoom = inRoom,
            room = if (inRoom) room() else null,
            connectionStatus = if (inRoom) "NOT_CONNECTED" else null,
        )

        fun room() = ListenTogetherRoom(
            roomId = "room_1",
            creatorId = 42L,
            roomType = "FRIEND",
            createdAtMs = 0L,
            effectiveDurationMs = 1_800_000L,
            waitMs = 120_000L,
            members = emptyList(),
        )
    }
}
