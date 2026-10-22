package com.zycomic.app.net

import android.util.Base64
import java.util.Locale

/** Immutable, client-owned Picacg network defaults. Remote DNS results can only augment Picacomic hosts. */
object PicacgBuiltinNetwork {
    val apiUrls: List<String> = listOf(
        "https://picaapi.go2778.com/",
        "https://picaapi.picacomic.com/",
    )

    /**
     * Content (page) storage buckets serving `/static/tobs` and `/static/sub_storage_*`.
     * The speed test selects the fastest root and pins it to a concrete IP; order only seeds
     * the first default and probe order, so the directly-reachable domestic mirror comes first.
     */
    val imageHosts: List<String> = listOf(
        "storage-b.safedataplj.com",
        "storage-b.go2778.com",
        "storage-b.picacomic.com",
    )

    /**
     * Cover/thumbnail imgproxy nodes. These are exactly the hosts that a storage-b bucket 301-redirects
     * `/static/tobeimg/...` to; each `img.{root}` mirrors `storage-b.{root}` and shares the same
     * Cloudflare anycast IPs. They serve thumbnails only — `/static/tobs` returns 502 on img nodes,
     * so they must never be mixed into [imageHosts]. They are not user-selectable: the chosen content
     * root determines the cover mirror automatically.
     */
    val coverHosts: List<String> = listOf(
        "img.safedataplj.com",
        "img.go2778.com",
        "img.picacomic.com",
    )

    /**
     * Fixed 50x67 imgproxy speed tile (base64url of a permanent storage-b origin JPEG). The insecure
     * form needs no signature, token, auth or redirect; the response is ~1.9KB JPEG and is reused for
     * every root/IP probe so a cold/unauthenticated start can still rank sources.
     */
    private const val SPEED_TILE_ORIGIN_BASE64 =
        "aHR0cHM6Ly9zdG9yYWdlLWIucGljYWNvbWljLmNvbS9zdGF0aWMvOWYyNmUxYTMtZmQyMC00MzMzLTg1YjgtZmM2ZjVmZTA4ODZmLmpwZw"

    /** Probe URL for a cover mirror; the returned tile is a stable ~1.9KB JPEG. */
    fun speedTileUrl(coverHost: String): String =
        "https://${coverHost.trim().lowercase(Locale.ROOT)}/insecure/rs:fill:50:67:0/$SPEED_TILE_ORIGIN_BASE64"

    /**
     * Second fixed tile whose ORIGINAL lives in the storage1 bucket. ~59/60 real covers originate
     * from storage1, so this proves the imgproxy can cross-origin fetch storage1 (not just storage-b).
     */
    private const val SPEED_TILE_STORAGE1_BASE64 =
        "aHR0cHM6Ly9zdG9yYWdlMS5waWNhY29taWMuY29tL3N0YXRpYy9mNjI1OGNhOS0yMmM4LTQxN2MtOWI4MS1mNjRmMzhjNTNiZjEuanBn"

    /** Extra storage1-bucket probe; both tiles must pass before an image source is considered cover-capable. */
    fun speedTileStorage1Url(coverHost: String): String =
        "https://${coverHost.trim().lowercase(Locale.ROOT)}/insecure/rs:fill:50:67:0/$SPEED_TILE_STORAGE1_BASE64"

    /**
     * Fixed reader-page tile served directly from the `storage-b.{root}` bucket (NOT imgproxy — img
     * nodes answer 502/404 for /static/sub_storage). All three mirrors return 200 image/jpeg without
     * following a redirect, so a cold/unauthenticated start can rank reader-page origins independently
     * from cover mirrors. The path mirrors the official contract `{fileServer}/static/{path}`.
     */
    private const val SPEED_TILE_CONTENT_PATH = "/static/sub_storage_1/f0/e3/f0e3922a-6229-4b83-aa1a-2d4c630a4d7c.jpg"

    /** Probe URL for a reader-page (storage-b) origin; returns a stable JPEG directly from the bucket. */
    fun speedTileContentUrl(contentHost: String): String =
        "https://${contentHost.trim().lowercase(Locale.ROOT)}$SPEED_TILE_CONTENT_PATH"

    // ── Cover-vs-content classification + imgproxy rewrite helpers ────────────────────────
    /** Cover thumbnail (imgproxy signed) prefix: `/static/tobeimg/...`. */
    const val COVER_PROXY_PREFIX = "/static/tobeimg/"
    /** Reader page prefixes; these stay on storage-b and MUST NOT go through imgproxy (img nodes 502 on tobs). */
    private const val CONTENT_TOBS_PREFIX = "/static/tobs/"
    private const val CONTENT_SUB_PREFIX = "/static/sub_storage"
    private val BUCKET_LABELS = setOf("storage-b", "storage1", "storage")

    /** True for reader-page paths (`/static/tobs`, `/static/sub_storage_*`). */
    fun isContentPath(path: String?): Boolean {
        val p = path ?: return false
        return p.startsWith(CONTENT_TOBS_PREFIX, ignoreCase = true) ||
            p.contains(CONTENT_SUB_PREFIX, ignoreCase = true)
    }

    /** True when host is a Picacg storage bucket (storage1/storage-b/storage) under any known root. */
    fun isImageBucketHost(host: String?): Boolean {
        val h = normalizeHost(host) ?: return false
        val labels = h.split('.')
        if (labels.size < 3 || labels.first() !in BUCKET_LABELS) return false
        return rootRules.containsKey(labels.drop(1).joinToString("."))
    }

    /**
     * Convert a `/static/tobeimg/{token}/{options}/g:sm/{b64}.jpg` cover path to an imgproxy
     * UNSIGNED URL: `https://{coverHost}/insecure/{rs-option}/{b64}`. The embedded base64 already
     * encodes the ORIGINAL full URL (storage1 or storage-b), so the mirror fetches the real bucket
     * regardless of the host the client happened to put on the request.
     */
    fun coverInsecureFromTobeimg(rawPath: String, coverHost: String): String? {
        if (!rawPath.startsWith(COVER_PROXY_PREFIX, ignoreCase = true)) return null
        val rest = rawPath.substring(COVER_PROXY_PREFIX.length).trimStart('/')
        val segs = rest.split('/').filter { it.isNotEmpty() }
        if (segs.size < 3) return null
        val sourceB64 = segs.last().substringBeforeLast('.', segs.last())
        val options = segs.subList(1, segs.size - 1).filterNot { it.startsWith("g:", ignoreCase = true) }
        val sizeOption = options.firstOrNull { it.startsWith("rs:", ignoreCase = true) } ?: "rs:fill:300:400:0"
        return "https://${coverHost.trim().lowercase(Locale.ROOT)}/insecure/$sizeOption/$sourceB64"
    }

    /** Encode a bucket original URL (non-tobeimg cover / avatar) into an imgproxy unsigned URL. */
    fun coverInsecureFromOrigin(originUrl: String, coverHost: String): String {
        val b64 = Base64.encodeToString(
            originUrl.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "https://${coverHost.trim().lowercase(Locale.ROOT)}/insecure/rs:fill:300:400:0/$b64"
    }

    /** Maps a content bucket `storage-b.{root}` to its cover mirror `img.{root}`; null if no mirror exists. */
    fun coverHostFor(contentHost: String?): String? {
        val host = normalizeHost(contentHost) ?: return null
        if (!host.startsWith("storage-b.")) return null
        val candidate = host.replaceFirst("storage-b.", "img.")
        return candidate.takeIf { c -> coverHosts.any { it.equals(c, ignoreCase = true) } }
    }

    /** Maps a cover mirror `img.{root}` back to its content bucket `storage-b.{root}`; null otherwise. */
    fun contentHostFor(coverHost: String?): String? {
        val host = normalizeHost(coverHost) ?: return null
        if (!host.startsWith("img.")) return null
        val candidate = host.replaceFirst("img.", "storage-b.")
        return candidate.takeIf { c -> imageHosts.any { it.equals(c, ignoreCase = true) } }
    }

    /** True when [host] is one of the cover imgproxy mirrors. */
    fun isCoverHost(host: String?): Boolean {
        val normalized = normalizeHost(host) ?: return false
        return coverHosts.any { it.equals(normalized, ignoreCase = true) }
    }

    /** Resolves a host against the built-in root rules; used as a last-resort DNS fallback for mirrors. */
    fun builtInLookup(host: String): List<String> = lookup(rootRules, host)

    private val rootRules = linkedMapOf(
        "go2778.com" to listOf("104.21.42.75", "172.67.159.59"),
        "picacomic.com" to listOf("104.20.42.9", "172.66.173.71"),
        "safedataplj.com" to listOf("104.21.75.42", "172.67.213.2"),
    )

    /** DNS role aliases are exact entries, not selectable image hosts. */
    private val roleLabels = listOf("picaapi", "img", "storage-b", "storage1")

    /**
     * Returns fixed client-owned rules plus vetted SmartDNS candidates for picacomic.com only.
     * Baseline addresses always stay first and are never replaced by the remote response.
     */
    fun rules(smartIps: List<String> = emptyList()): Map<String, List<String>> {
        val rules = linkedMapOf<String, List<String>>()
        rootRules.forEach { (root, ips) ->
            rules[root] = ips
            roleLabels.forEach { role -> rules["$role.$root"] = ips }
        }

        val smartCandidates = smartIps.asSequence()
            .map(String::trim)
            .filter(::isPublicIpv4)
            .distinct()
            .toList()
        if (smartCandidates.isNotEmpty()) {
            rules.keys.filter { isWithinDomain(it, "picacomic.com") }.forEach { host ->
                rules[host] = (rules.getValue(host) + smartCandidates).distinct()
            }
        }
        return rules.mapValues { (_, ips) -> ips.toList() }
    }

    /** Exact host wins; otherwise match the longest root-domain suffix on a label boundary. */
    fun lookup(rules: Map<String, List<String>>, host: String): List<String> {
        val normalizedHost = normalizeHost(host) ?: return emptyList()
        val normalizedRules = rules.entries.mapNotNull { (ruleHost, ips) ->
            normalizeHost(ruleHost)?.let { it to ips }
        }
        normalizedRules.firstOrNull { (ruleHost, _) -> ruleHost == normalizedHost }?.let { return it.second.toList() }
        return normalizedRules
            .asSequence()
            .filter { (ruleHost, _) -> normalizedHost.endsWith(".$ruleHost") }
            .maxByOrNull { (ruleHost, _) -> ruleHost.length }
            ?.second
            ?.toList()
            .orEmpty()
    }

    private fun isWithinDomain(host: String, root: String): Boolean =
        host.equals(root, ignoreCase = true) || host.endsWith(".$root", ignoreCase = true)

    private fun normalizeHost(host: String?): String? {
        val trimmed = host?.trim() ?: return null
        val withoutFinalDot = if (trimmed.endsWith('.')) trimmed.dropLast(1) else trimmed
        val normalized = withoutFinalDot.lowercase(Locale.ROOT)
        if (normalized.isEmpty() || normalized.length > 253) return null
        val labels = normalized.split('.')
        if (labels.any { label ->
                label.isEmpty() || label.length > 63 || label.first() == '-' || label.last() == '-' ||
                    label.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' }
            }
        ) {
            return null
        }
        return normalized
    }
}
