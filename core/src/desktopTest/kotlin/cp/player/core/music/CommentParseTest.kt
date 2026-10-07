package cp.player.core.music

import cp.player.core.BackendResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 评论解析（顶层 / 楼层 / 发送结果）与长度工具的守卫测试。
 *
 * 钉住的都是**容易写错、且写错后不易察觉**的规则：
 * 楼层数取较大值、直回楼主不显前缀、无效条目丢弃、楼层 parentId 回填、码点截断不切代理对。
 * 对照基准是网易云原 API 的字段形状。
 */
class CommentParseTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun page(text: String): CommentPage =
        (MusicSourceFromApi.parseComments(json(text)) as BackendResult.Success).data

    @Test
    fun `楼层数取 replyCount 与 showFloorComment 的较大值`() {
        val p = page(
            """
            {"code":200,"data":{"comments":[
              {"commentId":1,"content":"a","user":{"nickname":"u"},
               "replyCount":0,"showFloorComment":{"replyCount":36}}
            ]}}
            """.trimIndent()
        )
        assertEquals(36, p.comments.single().replyCount)
    }

    @Test
    fun `楼层内直回楼主不显示回复前缀`() {
        val p = page(
            """
            {"code":200,"data":{"comments":[
              {"commentId":10,"content":"x","user":{"nickname":"u"},"replyCount":1},
              {"commentId":11,"content":"y","parentCommentId":10,"user":{"nickname":"v"},
               "beReplied":[{"beRepliedCommentId":10,"user":{"nickname":"u"},"content":"x"}]}
            ]}}
            """.trimIndent()
        )
        // 回复已被归回父评论的楼层里（见「平铺的楼层回复被归回父评论」）。
        val parent = p.comments.first { it.id == 10L }
        assertNull(parent.topReplies.single().replyToNickname)
    }

    @Test
    fun `回复他人保留目标昵称与所属楼层`() {
        val p = page(
            """
            {"code":200,"data":{"comments":[
              {"commentId":11,"content":"y","parentCommentId":10,"user":{"nickname":"v"},
               "beReplied":[{"beRepliedCommentId":9,"user":{"nickname":"Bob"},"content":"hi"}]}
            ]}}
            """.trimIndent()
        )
        val c = p.comments.single()
        assertEquals("Bob", c.replyToNickname)
        assertEquals(10L, c.parentCommentId)
        assertTrue(c.isFloorReply)
    }

    @Test
    fun `comments 为空时回退 hotComments 并标记 isHot`() {
        val p = page(
            """
            {"code":200,"data":{"comments":[],"hotComments":[
              {"commentId":2,"content":"hot","user":{"nickname":"u"}}
            ]}}
            """.trimIndent()
        )
        val c = p.comments.single()
        assertTrue(c.isHot)
        assertEquals(2L, c.id)
    }

    @Test
    fun `主键缺失或非正的条目被丢弃`() {
        val p = page(
            """
            {"code":200,"data":{"comments":[
              {"commentId":0,"content":"bad"},
              {"content":"no id"},
              {"commentId":5,"content":"ok","user":{"nickname":"u"}}
            ]}}
            """.trimIndent()
        )
        assertEquals(1, p.comments.size)
        assertEquals(5L, p.comments.single().id)
    }

    @Test
    fun `顶层分页字段与用户字段被解析`() {
        val p = page(
            """
            {"code":200,"data":{"comments":[
              {"commentId":3,"content":"c","timeStr":"12分钟前","time":1699999999999,
               "likedCount":7,"liked":true,"ipLocation":{"location":"江苏"},
               "user":{"userId":42,"nickname":"n","avatarUrl":"http://a/b.png"}}
            ],"totalCount":120,"hasMore":true,"cursor":"abc","sortType":2}}
            """.trimIndent()
        )
        val c = p.comments.single()
        assertEquals(42L, c.userId)
        assertEquals("江苏", c.ipLocation)
        assertEquals(1699999999999L, c.timeMs)
        assertEquals("12分钟前", c.time)
        assertTrue(c.liked)
        assertEquals(120L, p.totalCount)
        assertTrue(p.hasMore)
        assertEquals("abc", p.cursor)
        assertEquals(2, p.sortType)
    }

    @Test
    fun `楼层解析回填 parentCommentId 并取下一页游标`() {
        val result = MusicSourceFromApi.parseFloorComments(
            json(
                """
                {"code":200,"data":{"comments":[
                  {"commentId":21,"content":"r","user":{"nickname":"u"}}
                ],"totalCount":36,"hasMore":true,"time":1699999999999}}
                """.trimIndent()
            ),
            parentCommentId = 10L,
        )
        val floor = (result as BackendResult.Success).data
        assertEquals(10L, floor.replies.single().parentCommentId)
        assertEquals(36, floor.totalCount)
        assertTrue(floor.hasMore)
        assertEquals(1699999999999L, floor.nextTime)
    }

    @Test
    fun `发送结果可从 comment 或 data comment 解析`() {
        val a = MusicSourceFromApi.parseCreatedComment(
            json("""{"code":200,"comment":{"commentId":7,"content":"new","user":{"nickname":"u"}}}""")
        )
        assertEquals(7L, (a as BackendResult.Success).data?.id)

        val b = MusicSourceFromApi.parseCreatedComment(
            json("""{"code":200,"data":{"comment":{"commentId":8,"content":"new2","user":{"nickname":"u"}}}}"""),
            parentCommentId = 3L,
        )
        val created = (b as BackendResult.Success).data
        assertEquals(8L, created?.id)
        assertEquals(3L, created?.parentCommentId)
    }

    @Test
    fun `发送结果缺体时返回 null 而不是报错`() {
        val r = MusicSourceFromApi.parseCreatedComment(json("""{"code":200}"""))
        assertNull((r as BackendResult.Success).data)
    }

    @Test
    fun `内联 topReplies 被解析且计入楼层数`() {
        val p = page(
            """
            {"code":200,"data":{"comments":[
              {"commentId":30,"content":"x","user":{"nickname":"u"},
               "showFloorComment":{"topReplies":[
                  {"commentId":31,"content":"r1","user":{"nickname":"a"}},
                  {"commentId":32,"content":"r2","user":{"nickname":"b"}}
               ]}}
            ]}}
            """.trimIndent()
        )
        val c = p.comments.single()
        // 上游没给任何 replyCount，只有内联回复 —— 楼层数按内联条数兜底。
        assertEquals(2, c.replyCount)
        assertEquals(2, c.topReplies.size)
        assertEquals(30L, c.topReplies.first().parentCommentId)
        assertTrue(c.topReplies.all { it.isFloorReply })
    }

    @Test
    fun `内联回复不再递归取自己的 topReplies`() {
        val p = page(
            """
            {"code":200,"data":{"comments":[
              {"commentId":40,"content":"x","user":{"nickname":"u"},
               "showFloorComment":{"topReplies":[
                  {"commentId":41,"content":"r","user":{"nickname":"a"},
                   "showFloorComment":{"topReplies":[{"commentId":42,"content":"deep","user":{"nickname":"c"}}]}}
               ]}}
            ]}}
            """.trimIndent()
        )
        val c = p.comments.single()
        assertEquals(1, c.topReplies.size)
        assertTrue(c.topReplies.single().topReplies.isEmpty())
    }

    @Test
    fun `平铺的楼层回复被归回父评论`() {
        val p = page(
            """
            {"code":200,"data":{"comments":[
              {"commentId":50,"content":"parent","user":{"nickname":"u"}},
              {"commentId":51,"content":"r1","parentCommentId":50,"user":{"nickname":"a"},
               "beReplied":[{"beRepliedCommentId":50,"user":{"nickname":"u"},"content":"parent"}]},
              {"commentId":52,"content":"r2","parentCommentId":50,"user":{"nickname":"b"}},
              {"commentId":60,"content":"other","user":{"nickname":"c"}}
            ]}}
            """.trimIndent()
        )
        // 顶层只剩两条（父评论 + 无关评论），回复不再单独成条。
        assertEquals(2, p.comments.size)
        val parent = p.comments.first { it.id == 50L }
        assertEquals(2, parent.replyCount)
        assertEquals(listOf(51L, 52L), parent.topReplies.map { it.id })
        assertTrue(p.comments.none { it.id == 51L || it.id == 52L })
        // 直回楼主（目标就是父评论）不显示「回复 @x」前缀。
        assertNull(parent.topReplies.first().replyToNickname)
    }

    @Test
    fun `父评论不在本页的平铺回复原样留在顶层`() {
        val p = page(
            """
            {"code":200,"data":{"comments":[
              {"commentId":70,"content":"parent","user":{"nickname":"u"}},
              {"commentId":71,"content":"r","parentCommentId":999,"user":{"nickname":"a"}}
            ]}}
            """.trimIndent()
        )
        // 不能丢：父评论不在本页，这条回复仍要能看到。
        assertEquals(2, p.comments.size)
        assertTrue(p.comments.any { it.id == 71L })
    }

    @Test
    fun `标准响应不受平铺归组影响`() {
        val p = page(
            """
            {"code":200,"data":{"comments":[
              {"commentId":80,"content":"a","user":{"nickname":"u"},"replyCount":3},
              {"commentId":81,"content":"b","user":{"nickname":"v"}}
            ]}}
            """.trimIndent()
        )
        assertEquals(2, p.comments.size)
        assertEquals(3, p.comments.first { it.id == 80L }.replyCount)
        assertTrue(p.comments.all { it.topReplies.isEmpty() })
    }

    @Test
    fun `码点长度把代理对算作一个`() {
        assertEquals(1, commentCodePointLength("😀"))
        assertEquals(2, commentCodePointLength("😀😀"))
        assertEquals(3, commentCodePointLength("a😀b"))
        assertEquals(0, commentCodePointLength(""))
    }

    @Test
    fun `截断不切断代理对且不改动未超长的文本`() {
        val text = "😀".repeat(5)
        val clipped = clipComment(text, 2)
        assertEquals(2, commentCodePointLength(clipped))
        assertEquals("😀😀", clipped)
        // 未超长：原样返回
        assertEquals(text, clipComment(text, 10))
        // 中文按码点逐字截
        assertEquals("一二", clipComment("一二三四", 2))
    }
}
