package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PicacgSmartDnsTest {
    @Test
    fun `parse real nested response aggregates lines and filters pollution from failed lines`() {
        val body = """
            {
              "code":200,
              "message":"ok",
              "data":{
                "domain":"picaapi.picacomic.com",
                "lines":{
                  "mobile":{
                    "status":"ok",
                    "ips":["104.16.146.184","104.17.102.145","104.16.146.184","10.0.0.1"],
                    "pollutedIps":["104.17.102.145"]
                  },
                  "unicom":{
                    "status":"ok",
                    "ips":["104.16.68.62","192.0.2.1"],
                    "pollutedIps":[]
                  },
                  "telecom":{
                    "status":"failed",
                    "ips":["9.9.9.9"],
                    "pollutedIps":["104.16.68.62"]
                  },
                  "overseas":{
                    "status":"ok",
                    "ips":["172.66.157.114"],
                    "pollutedIps":[]
                  }
                }
              }
            }
        """.trimIndent()

        assertEquals(listOf("104.16.146.184", "172.66.157.114"), PicacgSmartDns.parse(body))
    }

    @Test
    fun `parse strictly requires expected nested domain and numeric code 200`() {
        val wrongDomain = """
            {"code":200,"data":{"domain":"other.example","lines":{"mobile":{"status":"ok","ips":["1.1.1.1"]}}}}
        """.trimIndent()
        val wrongCode = """
            {"code":403,"data":{"domain":"picaapi.picacomic.com","lines":{"mobile":{"status":"ok","ips":["1.1.1.1"]}}}}
        """.trimIndent()
        val oldCode = """
            {"code":0,"data":{"domain":"picaapi.picacomic.com","lines":{"mobile":{"status":"ok","ips":["1.1.1.1"]}}}}
        """.trimIndent()
        val stringCode = """
            {"code":"200","data":{"domain":"picaapi.picacomic.com","lines":{
              "mobile":{"status":"ok","ips":["1.1.1.1"]}
            }}}
        """.trimIndent()
        val wrongStructure = """
            {"code":200,"domain":"picaapi.picacomic.com","lines":[]}
        """.trimIndent()
        assertTrue(PicacgSmartDns.parse(wrongDomain).isEmpty())
        assertTrue(PicacgSmartDns.parse(wrongCode).isEmpty())
        assertTrue(PicacgSmartDns.parse(oldCode).isEmpty())
        assertTrue(PicacgSmartDns.parse(stringCode).isEmpty())
        assertTrue(PicacgSmartDns.parse(wrongStructure).isEmpty())
        assertEquals(
            listOf("9.9.9.9"),
            PicacgSmartDns.parse(
                """
                    {"code":200,"data":{"domain":"PICAAPI.PICACOMIC.COM","lines":{
                      "mobile":{"status":"ok","ips":["9.9.9.9"]}
                    }}}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `parse rejects malformed lines and oversized body`() {
        assertTrue(
            PicacgSmartDns.parse(
                """
                    {"code":200,"data":{"domain":"picaapi.picacomic.com","lines":{
                      "mobile":{"status":"ok","ips":"1.1.1.1"}
                    }}}
                """.trimIndent(),
            ).isEmpty(),
        )
        assertTrue(PicacgSmartDns.parse(" ".repeat(65 * 1024)).isEmpty())
    }

    @Test
    fun `public ipv4 validation excludes special purpose ranges`() {
        assertTrue(isPublicIpv4("104.20.42.9"))
        assertTrue(isPublicIpv4("172.66.173.71"))
        assertTrue(!isPublicIpv4("127.0.0.1"))
        assertTrue(!isPublicIpv4("169.254.1.1"))
        assertTrue(!isPublicIpv4("192.0.2.1"))
        assertTrue(!isPublicIpv4("224.0.0.1"))
        assertTrue(!isPublicIpv4("01.2.3.4"))
    }
}
