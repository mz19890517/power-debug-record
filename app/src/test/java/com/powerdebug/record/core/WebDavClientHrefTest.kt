package com.powerdebug.record.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PROPFIND href → 子项名解析（v2.29 关键修复）：
 * WebDAV 集合（子目录）href 必带尾斜杠，旧代码 substringAfterLast('/') 把目录名整个吞掉，
 * 导致枚举 projects/ 恒为空 → 其他设备只能同步全局区（柜子类型）、拉不到项目数据。
 * 纯函数回归，JVM 直跑。
 */
class WebDavClientHrefTest {

    @Test
    fun parseHrefChild_keeps_trailing_slash_for_subdirectories() {
        // 项目子目录：必须完整保留 project_<id>/ 供同步枚举识别
        assertEquals(
            "project_abc-123/",
            parseHrefChild("https://dav.jianguoyun.com/dav/xx/projects/project_abc-123/")
        )
        assertEquals("demo/", parseHrefChild("https://dav.x/projects/demo/\n"))
    }

    @Test
    fun parseHrefChild_returns_plain_name_for_files() {
        assertEquals(
            "backup_a.json",
            parseHrefChild("https://dav.jianguoyun.com/dav/xx/global/backup_a.json")
        )
        assertEquals(
            "backup_b.json",
            parseHrefChild("https://dav.x/global/backup_b.json ")
        )
    }

    @Test
    fun parseHrefChild_url_decodes_chinese_segments() {
        assertEquals("一号站/", parseHrefChild("https://dav.x/projects/%E4%B8%80%E5%8F%B7%E7%AB%99/"))
    }

    @Test
    fun parseHrefChild_rejects_empty_or_slash_only_paths() {
        assertNull(parseHrefChild(""))
        assertNull(parseHrefChild("   "))
        assertNull(parseHrefChild("/"))
        assertNull(parseHrefChild("https://x/projects//"))
    }
}