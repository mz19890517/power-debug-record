package com.fieldlog.powerdebug.core

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 零依赖的最小 .xlsx 生成器（OOXML SpreadsheetML）。
 *
 * 特性：
 * - 内联字符串单元格，无需共享字符串表；
 * - 表头加粗、冻结首行、列宽按内容自适应；
 * - A4 纸打印适配：paperSize=9(A4)、fitToWidth=1（缩放为一页宽）、
 *   fitToHeight=0（行数多时纵向自动分页）、每页重复表头(Print_Titles)。
 */
object XlsxWriter {

    class SheetDef(
        val name: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        /** 需要自动换行的列索引集合 */
        val wrapCols: Set<Int> = emptySet(),
        /** true = A4 横向，默认纵向 */
        val landscape: Boolean = false
    )

    fun write(output: OutputStream, sheets: List<SheetDef>) {
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            val n = sheets.size
            entry("[Content_Types].xml", contentTypes(n))
            entry("_rels/.rels", ROOT_RELS)
            entry("xl/workbook.xml", workbookXml(sheets))
            entry("xl/_rels/workbook.xml.rels", workbookRels(n))
            entry("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, s ->
                entry("xl/worksheets/sheet${i + 1}.xml", sheetXml(s, i == 0))
            }
        }
    }

    // ---------- XML 片段 ----------

    private const val ROOT_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private fun contentTypes(sheetCount: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        sb.append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        sb.append("""<Default Extension="xml" ContentType="application/xml"/>""")
        sb.append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        sb.append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..sheetCount) {
            sb.append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        sb.append("</Types>")
        return sb.toString()
    }

    private fun workbookXml(sheets: List<SheetDef>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheets.forEachIndexed { i, s ->
            sb.append("""<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        sb.append("</sheets>")
        if (sheets.isNotEmpty()) {
            sb.append("<definedNames>")
            sheets.forEachIndexed { i, s ->
                // 每页重复表头行
                sb.append("""<definedName name="_xlnm.Print_Titles" localSheetId="$i">'${esc(s.name)}'!$1:$1</definedName>""")
            }
            sb.append("</definedNames>")
        }
        sb.append("</workbook>")
        return sb.toString()
    }

    private fun workbookRels(sheetCount: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..sheetCount) {
            sb.append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        sb.append("""<Relationship Id="rId${sheetCount + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        sb.append("</Relationships>")
        return sb.toString()
    }

    private val STYLES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="3">
<font><sz val="10"/><name val="Calibri"/></font>
<font><b/><sz val="10"/><name val="Calibri"/></font>
</fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="2">
<border><left/><right/><top/><bottom/><diagonal/></border>
<border><left style="thin"><color auto="1"/></left><right style="thin"><color auto="1"/></right><top style="thin"><color auto="1"/></top><bottom style="thin"><color auto="1"/></bottom><diagonal/></border>
</borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="4">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/>
</cellXfs>
</styleSheet>"""

    private fun sheetXml(s: SheetDef, first: Boolean): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("""<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>""")

        if (first) {
            sb.append("""<sheetViews><sheetView tabSelected="1" workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        } else {
            sb.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        }

        // 列宽：按可见字符宽度自适应（CJK 记 2），限制在 6~55
        val colCount = maxOf(s.headers.size, s.rows.maxOfOrNull { it.size } ?: 0)
        val widths = IntArray(colCount) { c ->
            var w = visualWidth(s.headers.getOrNull(c).orEmpty())
            for (r in s.rows) {
                val v = r.getOrNull(c).orEmpty().split('\n').maxOfOrNull { visualWidth(it) } ?: 0
                if (v > w) w = v
            }
            (w + 2).coerceIn(6, 55)
        }
        sb.append("<cols>")
        widths.forEachIndexed { i, w ->
            sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
        }
        sb.append("</cols>")

        sb.append("<sheetData>")
        // 表头
        sb.append("""<row r="1">""")
        s.headers.forEachIndexed { c, h ->
            cell(sb, c, 1, h, style = "1")
        }
        sb.append("</row>")
        // 数据行
        s.rows.forEachIndexed { ri, row ->
            sb.append("""<row r="${ri + 2}">""")
            row.forEachIndexed { c, v ->
                if (v.isNotEmpty()) {
                    val style = when {
                        c in s.wrapCols -> "2"
                        else -> "3"
                    }
                    cell(sb, c, ri + 2, v, style)
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")

        sb.append("""<pageMargins left="0.39" right="0.39" top="0.55" bottom="0.55" header="0.3" footer="0.3"/>""")
        sb.append(
            """<pageSetup paperSize="9" orientation="${if (s.landscape) "landscape" else "portrait"}" fitToWidth="1" fitToHeight="0"/>"""
        )
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun cell(sb: StringBuilder, col: Int, row: Int, value: String, style: String) {
        if (value.isEmpty()) return
        sb.append("""<c r="${colName(col)}$row" t="inlineStr" s="$style"><is><t xml:space="preserve">${esc(value)}</t></is></c>""")
    }

    private fun colName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        }
        return sb.toString()
    }

    /** XML 转义 */
    internal fun esc(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }

    /** 可视宽度：中日韩全角字符按 2 计 */
    private fun visualWidth(s: String): Int {
        var w = 0
        for (ch in s) {
            val block = Character.UnicodeBlock.of(ch)
            val wide = block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
                block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
            w += if (wide) 2 else 1
        }
        return w
    }
}
