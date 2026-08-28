package iris.ton.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

/**
 * Read-only lookup of any TON transaction by hash (tx hash or inbound msg hash).
 * Uses TonCenter API v3 traces — no on-chain fee.
 */
class TonViewer(
    private val apiKey: String,
    testnet: Boolean = false,
) {
    private val host = if (testnet) "https://testnet.toncenter.com" else "https://toncenter.com"

    fun getTransaction(hash: String): TxInfo {
        val query = hash.trim()
        require(query.isNotEmpty()) { "hash is blank" }
        val trace = fetchTrace(query) ?: return TxInfo(hash = toHexHash(query), status = TxStatus.PENDING)
        return buildFromTrace(trace, query)
    }

    private fun fetchTrace(hash: String): JsonObject? {
        val encoded = URLEncoder.encode(hash, StandardCharsets.UTF_8)
        for (param in listOf("tx_hash", "msg_hash")) {
            val body = get("$host/api/v3/traces?$param=$encoded")
            val traces = body.arr("traces") ?: continue
            if (traces.isNotEmpty()) return traces.first() as? JsonObject
        }
        return null
    }

    private fun buildFromTrace(trace: JsonObject, requested: String): TxInfo {
        val txs = trace.obj("transactions") ?: JsonObject(emptyMap())
        val tree = trace.obj("trace")
        val rootHash = tree?.str("tx_hash")
            ?: trace.arr("transactions_order")?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
            ?: txs.keys.firstOrNull()
            ?: return TxInfo(hash = toHexHash(requested), status = TxStatus.PENDING)
        return nodeToInfo(rootHash, tree, txs)
    }

    private fun nodeToInfo(txHash: String, node: JsonObject?, txs: JsonObject): TxInfo {
        val raw = txs.obj(txHash) ?: JsonObject(emptyMap())
        val parsed = parseTx(raw, txHash)
        val childInfos = (node?.arr("children") ?: JsonArray(emptyList())).mapNotNull { el ->
            val child = el as? JsonObject ?: return@mapNotNull null
            val childHash = child.str("tx_hash") ?: return@mapNotNull null
            nodeToInfo(childHash, child, txs)
        }
        val status = classify(parsed, childInfos)
        return parsed.copy(status = status, children = childInfos)
    }

    private fun parseTx(raw: JsonObject, fallbackHash: String): TxInfo {
        val desc = raw.obj("description") ?: JsonObject(emptyMap())
        val compute = desc.obj("compute_ph") ?: JsonObject(emptyMap())
        val action = desc.obj("action") ?: JsonObject(emptyMap())
        val inMsg = raw.obj("in_msg")?.let { parseMsg(it) }
        val outMsgs = raw.arr("out_msgs")?.mapNotNull { (it as? JsonObject)?.let(::parseMsg) }.orEmpty()
        val computeSkipped = compute.bool("skipped") == true
        val computeSuccess = when {
            computeSkipped -> false
            compute.bool("success") != null -> compute.bool("success")
            else -> null
        }
        return TxInfo(
            hash = toHexHash(raw.str("hash") ?: fallbackHash),
            status = TxStatus.PENDING,
            account = raw.str("account"),
            utime = raw.long("now"),
            lt = raw.str("lt"),
            totalFeesNano = raw.long("total_fees"),
            aborted = desc.bool("aborted") == true,
            computeSuccess = computeSuccess,
            exitCode = compute.int("exit_code"),
            skippedActions = action.int("skipped_actions") ?: 0,
            messagesCreated = action.int("msgs_created") ?: 0,
            inMsg = inMsg,
            outMsgs = outMsgs,
            raw = raw,
        )
    }

    private fun parseMsg(obj: JsonObject): TxMessage {
        val decoded = obj.obj("message_content")?.obj("decoded")
        val comment = decoded?.str("comment") ?: decoded?.str("text")
        return TxMessage(
            source = obj.str("source"),
            destination = obj.str("destination"),
            valueNano = obj.long("value"),
            bounce = obj.bool("bounce"),
            bounced = obj.bool("bounced"),
            opcode = obj.str("opcode") ?: obj.str("decoded_opcode"),
            comment = comment,
        )
    }

    private fun classify(root: TxInfo, children: List<TxInfo>): TxStatus {
        val childBounced = children.any { child ->
            child.aborted ||
                child.outMsgs.any { it.bounced == true } ||
                child.computeSuccess == false
        }
        if (childBounced || root.outMsgs.any { it.bounced == true }) return TxStatus.BOUNCED
        if (root.aborted || root.computeSuccess == false) return TxStatus.FAILED
        if (root.skippedActions > 0 && root.messagesCreated == 0) return TxStatus.SKIPPED
        if (root.computeSuccess == true) return TxStatus.SUCCESS
        return TxStatus.FAILED
    }

    private fun get(url: String): JsonObject {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("X-API-Key", apiKey)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw WalletException("TonCenter HTTP ${response.statusCode()} for $url")
        }
        val root = JSON.parseToJsonElement(response.body())
        return root as? JsonObject
            ?: throw WalletException("Unexpected TonCenter payload")
    }

    companion object {
        private val http: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        private val JSON = Json { ignoreUnknownKeys = true }

        internal fun toHexHash(value: String): String {
            val trimmed = value.trim()
            val hex = trimmed.removePrefix("0x")
            if (hex.length == 64 && hex.all { it in HEX }) return hex.lowercase()
            val bytes = decodeBase64(trimmed) ?: return trimmed
            if (bytes.size == 32) return bytes.joinToString("") { "%02x".format(it) }
            return trimmed
        }

        private fun decodeBase64(value: String): ByteArray? {
            return try {
                Base64.getDecoder().decode(value)
            } catch (_: Exception) {
                try {
                    Base64.getUrlDecoder().decode(value)
                } catch (_: Exception) {
                    null
                }
            }
        }

        private const val HEX = "0123456789abcdefABCDEF"
    }
}

private fun JsonObject.str(key: String): String? {
    val el = this[key] ?: return null
    return when (el) {
        is JsonNull -> null
        is JsonPrimitive -> el.contentOrNull
        else -> null
    }
}

private fun JsonObject.long(key: String): Long? {
    val el = this[key] as? JsonPrimitive ?: return str(key)?.toLongOrNull()
    return el.longOrNull ?: el.contentOrNull?.toLongOrNull()
}

private fun JsonObject.int(key: String): Int? {
    val el = this[key] as? JsonPrimitive ?: return str(key)?.toIntOrNull()
    return el.intOrNull ?: el.contentOrNull?.toIntOrNull()
}

private fun JsonObject.bool(key: String): Boolean? {
    val el = this[key] as? JsonPrimitive ?: return null
    return el.booleanOrNull
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
