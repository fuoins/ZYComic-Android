package com.zycomic.app.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class PlatformPresentationResourcesTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "i18n-kmk/src/commonMain/moko-resources/base/strings.xml").isFile }

    private fun resource(name: String): String {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "i18n-kmk/src/commonMain/moko-resources/base/strings.xml"))
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length).map { strings.item(it) }
            .first { it.attributes.getNamedItem("name").nodeValue == name }.textContent
    }

    @Test
    fun `ordinary platform names are Chinese and JM is short`() {
        assertEquals("漫蛙", resource("content_platform_manwa"))
        assertEquals("哔咔", resource("content_platform_picacg"))
        assertEquals("禁漫", resource("content_platform_jm"))
        assertEquals("禁漫", resource("library_history_platform_jm_short"))
    }

    @Test
    fun `startup and settings labels use full JM name`() {
        assertEquals("开启漫蛙", resource("startup_enable_manwa"))
        assertEquals("开启哔咔", resource("startup_enable_picacg"))
        assertEquals("开启禁漫天堂", resource("startup_enable_jm"))
        assertEquals("禁漫天堂", resource("content_platform_jm_full"))
        assertEquals("禁漫天堂对应颜色", resource("zy_platform_jm_accent"))
    }

    @Test
    fun `notice preserves requested text and contains no terms`() {
        assertEquals(
            "本软件对所有线路和图源进行网络层优化,\\n,三个平台晚上会卡顿,我已经把网络配置改成每日早晚各更新一次了.",
            resource("zy_network_maintenance_notice"),
        )
        val notice = File(root, "app/src/main/java/com/zycomic/app/ui/components/NetworkMaintenanceNotice.kt").readText()
        listOf("使用协议", "学习交流", "24小时", "商业用途", "自行承担").forEach {
            assertFalse(notice.contains(it))
        }
    }
}
