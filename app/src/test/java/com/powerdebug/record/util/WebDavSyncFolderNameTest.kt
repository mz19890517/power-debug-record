package com.powerdebug.record.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 云端项目文件夹命名（v2.29）：文件夹键 = 项目名（去非法字符）/ 重名带id短缀 / 快照解析回项目id。
 * 纯函数回归，Robolectric 提供真实 org.json（parseProjectIdFromSnapshot 用）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavSyncFolderNameTest {

    @Test
    fun sanitize_replaces_illegal_and_keeps_chinese() {
        // 路径/通配非法字符 → '_'，中文保留
        assertEquals("1号_馈线柜_测试", WebDavSync.sanitizeFolderName("1号/馈线柜:测试"))
        assertEquals("A_B_C", WebDavSync.sanitizeFolderName("A\\B<C>|"))
    }

    @Test
    fun sanitize_compresses_whitespace_and_trims() {
        assertEquals("A B C", WebDavSync.sanitizeFolderName("  A   B  C  "))
    }

    @Test
    fun sanitize_falls_back_blank_and_dot_names() {
        assertEquals("未命名", WebDavSync.sanitizeFolderName(""))
        assertEquals("未命名", WebDavSync.sanitizeFolderName("  "))
        assertEquals("未命名", WebDavSync.sanitizeFolderName("."))
        assertEquals("未命名", WebDavSync.sanitizeFolderName(".."))
        assertEquals("未命名", WebDavSync.sanitizeFolderName("/?*"))
    }

    @Test
    fun sanitize_caps_long_names() {
        assertEquals(64, WebDavSync.sanitizeFolderName("A".repeat(100)).length)
    }

    @Test
    fun buildProjectKeys_keeps_unique_names_and_disambiguates_duplicates() {
        val keys = WebDavSync.buildProjectKeys(
            mapOf("p1" to "一号变电站", "p2" to "一号变电站", "p3" to "二号变电站")
        )
        // 非重名项目直接用项目名
        assertEquals("二号变电站", keys["p3"])
        // 重名项目必须各不相同的键，且带各自 id 短缀可辨识
        assertEquals("一号变电站-p1", keys["p1"])
        assertEquals("一号变电站-p2", keys["p2"])
        assertNotEquals(keys["p1"], keys["p2"])
        // 全部键两两不同（云端文件夹不互覆）
        assertEquals(3, keys.values.toSet().size)
    }

    @Test
    fun buildProjectKeys_allows_same_name_after_sanitize_but_short_id_keeps_unique() {
        // "一号站/甲" 与 "一号站:甲" 清洗后都变 "一号站_甲" → 也必须去重
        val keys = WebDavSync.buildProjectKeys(
            mapOf("p1" to "一号站/甲", "p2" to "一号站:甲")
        )
        assertNotEquals(keys["p1"], keys["p2"])
    }

    @Test
    fun parseProjectIdFromSnapshot_reads_first_project_id() {
        assertEquals(
            "abc-123",
            WebDavSync.parseProjectIdFromSnapshot("""{"projects":[{"id":"abc-123","name":"一号站"}]}""")
        )
        assertNull(WebDavSync.parseProjectIdFromSnapshot("not json at all"))
        assertNull(WebDavSync.parseProjectIdFromSnapshot("{}"))
        assertNull(WebDavSync.parseProjectIdFromSnapshot("""{"projects":[]}"""))
    }
}