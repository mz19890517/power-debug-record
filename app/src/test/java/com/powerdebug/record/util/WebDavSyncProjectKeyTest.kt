package com.powerdebug.record.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * projects/ 子项 → 项目文件夹键解析（v2.29 双保险之二）：
 * 枚举项目目录时不再依赖 "目录名是否以 / 结尾"——坚果云等服务器对集合 href
 * 不总是补尾斜杠，若还要求 endsWith("/") 会把全部项目目录丢弃 → 其他设备
 * 只能同步柜子类型、拉不到项目。此处带/不带尾斜杠都必须得出同一 UUID。
 */
class WebDavSyncProjectKeyTest {

    @Test
    fun projectKeyOf_accepts_both_trailing_slash_forms() {
        // 标准 RFC 集合 href（带尾斜杠）
        assertEquals(
            "39101cf3-bbc4-424e-af7f-0ae895627721",
            projectKeyOf("project_39101cf3-bbc4-424e-af7f-0ae895627721/")
        )
        // 服务器不带尾斜杠时
        assertEquals(
            "39101cf3-bbc4-424e-af7f-0ae895627721",
            projectKeyOf("project_39101cf3-bbc4-424e-af7f-0ae895627721")
        )
    }

    @Test
    fun projectKeyOf_filters_non_project_entries() {
        assertNull(projectKeyOf("global/"))
        assertNull(projectKeyOf("backup_a.json"))
        assertNull(projectKeyOf("report.txt"))
        assertNull(projectKeyOf("project_"))
        assertNull(projectKeyOf("project_a/b/")) // 深层路径不是本项目文件夹键
        assertNull(projectKeyOf(""))
    }
}