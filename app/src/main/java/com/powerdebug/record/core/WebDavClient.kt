package com.powerdebug.record.core

import android.util.Base64
import android.util.Xml
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 极简WebDAV客户端（OkHttp实现）。
 * 关键点：PROPFIND等WebDAV自定义动词在Android原生HttpURLConnection上会直接
 * 抛 ProtocolException（动词白名单硬编码），必须使用支持任意方法的网络库。
 * 仅用 PROPFIND(验证/列目录)/PUT(上传)/GET(下载)，Basic认证。
 */
class WebDavClient(
    baseUrlRaw: String,
    private val user: String,
    private val pass: String
) {

    class DavException(message: String, val code: Int = -1) : Exception(message)

    /** 规范化：确保以 / 结尾，作为远端工作目录 */
    private val baseUrl = baseUrlRaw.trim().trimEnd('/') + "/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 逐段URL编码，支持中文路径/文件名 */
    private fun urlFor(fileName: String): String {
        val encoded = fileName.trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8") }
        return baseUrl + encoded
    }

    private fun authHeader(): String =
        "Basic " + Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private val emptyBody = ByteArray(0).toRequestBody(null)
    private val jsonBody = "application/json; charset=utf-8".toMediaType()

    private fun request(method: String, fileName: String, depth: String? = null, body: ByteArray? = null): Request {
        val b = Request.Builder()
            .url(urlFor(fileName))
            .header("Authorization", authHeader())
        if (depth != null) b.header("Depth", depth)
        when {
            // 正常带体请求（PUT上传等）
            body != null -> b.method(method, body.toRequestBody(jsonBody))
            // GET/HEAD 绝不能带请求体（坚果云会拒绝：method GET must not have a request body）
            method.equals("GET", true) || method.equals("HEAD", true) -> b.method(method, null)
            // PROPFIND等自定义动词：OkHttp要求非GET/HEAD必须有体，给零长度体
            else -> b.method(method, emptyBody)
        }
        return b.build()
    }

    /**
     * 登录验证：对工作目录发 PROPFIND Depth:0。2xx 或 207 即视为账号密码正确。
     */
    fun verify() {
        try {
            client.newCall(request("PROPFIND", "", depth = "0")).execute().use { resp ->
                when (resp.code) {
                    401 -> throw DavException("账号或密码错误（401）", 401)
                    403 -> throw DavException("禁止访问（403）：目录不存在或无权限", 403)
                    in 200..299 -> return
                    207 -> return
                    else -> throw DavException("服务器返回 HTTP ${resp.code}", resp.code)
                }
            }
        } catch (e: DavException) {
            throw e
        } catch (e: Exception) {
            throw DavException("无法连接服务器：${e.message ?: "网络异常"}")
        }
    }

    /**
     * 连接诊断：返回完整排查报告（URL/HTTP状态/关键响应头/响应体片段/结论提示），
     * 用于登录弹窗内直接展示，便于远程定位失败原因。
     */
    fun diagnose(): String {
        val sb = StringBuilder()
        sb.appendLine("【连接诊断】")
        sb.appendLine("地址: $baseUrl")
        sb.appendLine("方式: PROPFIND (Depth:0)")
        try {
            client.newCall(request("PROPFIND", "", depth = "0")).execute().use { resp ->
                val code = resp.code
                sb.appendLine("结果: HTTP $code ${resp.message.trim()}")
                for (h in listOf("WWW-Authenticate", "Server", "Allow", "Content-Type")) {
                    resp.header(h)?.let { sb.appendLine("$h: ${it.take(120)}") }
                }
                val bodyText = try { resp.body?.string()?.replace(Regex("\\s+"), " ")?.take(150) } catch (_: Exception) { null }
                if (!bodyText.isNullOrBlank()) sb.appendLine("响应体: $bodyText")

                when {
                    code == 207 || code in 200..299 -> sb.appendLine("✅ 验证通过：账号密码有效，可正常同步")
                    code == 401 -> {
                        sb.appendLine("❌ 认证失败（401）：服务器拒绝了这对账号密码")
                        sb.appendLine("· 坚果云：必须用「应用密码」，网页端→账户信息→安全选项→添加应用密码；不是网页登录密码")
                        sb.appendLine("· 群晖/威联通NAS：确认该用户已开启 WebDAV Server 应用权限")
                        sb.appendLine("· 检查密码大小写、末尾空格")
                    }

                    code == 403 -> sb.appendLine("❌ 禁止访问（403）：目录不存在或该账号无此目录权限")
                    code == 404 -> sb.appendLine("❌ 路径不存在（404）：检查URL路径，坚果云正确地址为 https://dav.jianguoyun.com/dav/")
                    code == 405 -> sb.appendLine("❌ 该地址不支持PROPFIND（405）：可能不是WebDAV服务端口")
                    else -> sb.appendLine("❌ 异常状态码，请把以上完整内容发给开发者")
                }
            }
        } catch (e: Exception) {
            sb.appendLine("❌ 请求异常：${e.message}")
            sb.appendLine("提示：超时多为IP/端口不通或防火墙拦截；https/http不匹配也会失败")
        }
        return sb.toString()
    }

    /** 上传文件（覆盖写）。 */
    fun upload(fileName: String, data: ByteArray) {
        try {
            client.newCall(request("PUT", fileName, body = data)).execute().use { resp ->
                if (resp.code !in 200..299) {
                    throw DavException("上传失败 HTTP ${resp.code}", resp.code)
                }
            }
        } catch (e: DavException) {
            throw e
        } catch (e: Exception) {
            throw DavException("上传失败：${e.message ?: "网络异常"}")
        }
    }

    /** 下载文件内容。 */
    fun download(fileName: String): ByteArray {
        try {
            client.newCall(request("GET", fileName)).execute().use { resp ->
                if (resp.code == 404) throw DavException("云端还没有备份文件", 404)
                if (resp.code !in 200..299) throw DavException("下载失败 HTTP ${resp.code}", resp.code)
                return resp.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: DavException) {
            throw e
        } catch (e: Exception) {
            throw DavException("下载失败：${e.message ?: "网络异常"}")
        }
    }

    /**
     * 列出工作目录下全部 backup_*.json 文件名（已URL解码）。
     * 旧版同步目录专用；新版增量同步用 listChildren 枚举子目录。
     */
    fun listBackups(): List<String> =
        propfindNames("").filter { it.startsWith("backup_") && it.endsWith(".json") }

    /** 通用目录枚举（Depth:1）：返回 dir 下所有直接子项名称（已URL解码，含 / 结尾的子目录名）。 */
    fun listChildren(dir: String): List<String> =
        propfindNames(dir.trim('/'))

    /** PROPFIND Depth:1，仅解析 <href> 节点转成相对名称。 */
    private fun propfindNames(dir: String): List<String> {
        try {
            client.newCall(request("PROPFIND", dir, depth = "1")).execute().use { resp ->
                if (!(resp.code == 207 || resp.code in 200..299)) {
                    throw DavException("列出云端文件失败 HTTP ${resp.code}", resp.code)
                }
                val input = resp.body?.byteStream() ?: return emptyList()
                val out = mutableListOf<String>()
                val p = Xml.newPullParser()
                p.setInput(input.bufferedReader(Charsets.UTF_8))
                var capture = false
                var sb = StringBuilder()
                while (p.eventType != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG ->
                            if (p.name.equals("href", ignoreCase = true)) {
                                capture = true; sb = StringBuilder()
                            }

                        XmlPullParser.TEXT -> if (capture) sb.append(p.text)

                        XmlPullParser.END_TAG ->
                            if (p.name.equals("href", ignoreCase = true)) {
                                capture = false
                                val raw = sb.toString().trim()
                                if (raw.isNotEmpty()) {
                                    val name = try {
                                        URLDecoder.decode(raw.substringAfterLast('/'), "UTF-8")
                                    } catch (_: Exception) {
                                        raw.substringAfterLast('/')
                                    }
                                    if (name.isNotEmpty()) out += name
                                }
                            }
                    }
                    p.next()
                }
                val dirSelf = runCatching { URLDecoder.decode(dir.substringAfterLast('/'), "UTF-8") }
                    .getOrDefault(dir.substringAfterLast('/'))
                return out.distinct().filter { it.isNotEmpty() && it != dirSelf }
            }
        } catch (e: DavException) {
            throw e
        } catch (e: Exception) {
            throw DavException("列出云端文件失败：${e.message ?: "网络异常"}")
        }
    }

    /**
     * 逐级创建远端目录（MKCOL；父级缺失时逐段补齐）。
     * 目录已存在时宽容（405 Method Not Allowed / 301 / 409 视为已存在）。
     */
    fun ensureDir(relativePath: String) {
        val segments = relativePath.trim('/').split('/').filter { it.isNotEmpty() }
        var acc = ""
        for (seg in segments) {
            acc = if (acc.isEmpty()) seg else "$acc/$seg"
            try {
                client.newCall(request("MKCOL", acc)).execute().use { resp ->
                    when (resp.code) {
                        in 200..299 -> Unit
                        in arrayOf(301, 302, 405, 409) -> Unit // 已存在/重定向，可接受
                        else -> throw DavException("创建目录失败 HTTP ${resp.code}（$acc）", resp.code)
                    }
                }
            } catch (e: DavException) {
                throw e
            } catch (e: Exception) {
                throw DavException("创建目录失败：${e.message ?: "网络异常"}（$acc）")
            }
        }
    }
}
