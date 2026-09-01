package iris.ton.wallet

import org.ton.ton4j.address.Address
import org.ton.ton4j.cell.Cell
import org.ton.ton4j.mnemonic.Mnemonic
import org.ton.ton4j.smartcontract.SendMode
import org.ton.ton4j.smartcontract.types.WalletV5Config
import org.ton.ton4j.smartcontract.types.WalletV5InnerRequest
import org.ton.ton4j.smartcontract.utils.MsgUtils
import org.ton.ton4j.smartcontract.wallet.v5.WalletV5
import org.ton.ton4j.tlb.ActionSendMsg
import org.ton.ton4j.tlb.CurrencyCollection
import org.ton.ton4j.tlb.InternalMessageInfoRelaxed
import org.ton.ton4j.tlb.MessageRelaxed
import org.ton.ton4j.tlb.MsgAddressIntStd
import org.ton.ton4j.tlb.OutAction
import org.ton.ton4j.tlb.OutList
import org.ton.ton4j.toncenter.Network
import org.ton.ton4j.toncenter.TonCenter
import org.ton.ton4j.toncenter.model.SendBocResponse
import org.ton.ton4j.utils.Utils
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Wallet V5R1 over TonCenter HTTP.
 *
 * Do not use `WalletV5.send()` / `recipients=`: ton4j attaches this wallet's
 * StateInit to every internal message. Hash(init) ≠ destination →
 * `cskip_bad_state` and the TON bounces. We build the OutAction without init.
 */
class TonWallet(
    private val apiKey: String,
    mnemonic: List<String>,
    private val testnet: Boolean = false,
    private val walletId: Long = MAINNET_WALLET_ID,
) : AutoCloseable {

    private val words: List<String> = mnemonic.map { it.trim() }.filter { it.isNotEmpty() }
    private val tonCenter: TonCenter
    private val wallet: WalletV5

    init {
        require(words.size == 12 || words.size == 24) {
            "Mnemonic must be 12 or 24 words, got ${words.size}"
        }
        require(apiKey.isNotBlank()) { "TonCenter API key is required" }

        val seedPair = Mnemonic.toKeyPair(words)
        val keyPair = Utils.generateSignatureKeyPairFromSeed(seedPair.secretKey)

        tonCenter = TonCenter.builder()
            .apiKey(apiKey)
            .network(if (testnet) Network.TESTNET else Network.MAINNET)
            .build()

        wallet = WalletV5.builder()
            .tonProvider(tonCenter)
            .keyPair(keyPair)
            .walletId(walletId)
            .isSigAuthAllowed(true)
            .build()
    }

    fun address(): String = wallet.address.toBounceable()

    /**
     * EQ/UQ/raw pass through. `name.ton` / `name.t.me` are resolved to the
     * DNS wallet record (not the domain NFT).
     */
    fun resolveDestination(address: String): Address {
        val trimmed = address.trim()
        require(trimmed.isNotEmpty()) { "address is blank" }
        return if (isTonDomain(trimmed)) resolveDns(trimmed.lowercase()) else Address.of(trimmed)
    }

    /**
     * @param amountNano nanotons
     * @param payload text comment for a plain transfer. Ignored when [payloadBoc] is set.
     * @param payloadBoc base64 BOC body (Fragment invoice). Sent as-is.
     *   One of [payload] or [payloadBoc] must be non-null.
     * @param bounce `null` = auto: true if dest is deployed, false for uninit wallets
     *   (`t.me/wallet` first receive). Fragment contracts are deployed → bounce true.
     */
    fun sendTransfer(
        address: String,
        amountNano: Long,
        payload: String? = null,
        payloadBoc: String? = null,
        bounce: Boolean? = null,
    ): String {
        require(amountNano > 0) { "amount must be > 0 nanotons" }
        require(payload != null || payloadBoc != null) {
            "payload or payloadBoc must be specified"
        }
        try {
            val amount = BigInteger.valueOf(amountNano)
            val gasReserve = Utils.toNano(0.01)
            val needed = amount.add(gasReserve)
            val balance = wallet.balance ?: BigInteger.ZERO
            if (balance < needed) {
                throw WalletException(
                    "Insufficient balance: ${formatTon(balance)} TON, need ${formatTon(needed)} TON (amount + gas)",
                )
            }

            val destAddr = resolveDestination(address)
            val bounceFlag = bounce ?: try {
                tonCenter.isDeployed(destAddr)
            } catch (_: Exception) {
                false
            }

            val seqno = wallet.seqno
            val inner = WalletV5InnerRequest.builder()
                .outActions(
                    OutList.builder()
                        .actions(
                            listOf(
                                outgoingPayment(destAddr, amount, payload, payloadBoc, bounceFlag),
                            ),
                        )
                        .build(),
                )
                .hasOtherActions(false)
                .build()
            val config = WalletV5Config.builder()
                .walletId(walletId)
                .seqno(seqno)
                .body(inner.toCell())
                .build()

            val boc = wallet.prepareExternalMsg(config).toCell().toBase64()
            val tonResp = tonCenter.sendBocReturnHash(boc)
                ?: throw WalletException("Empty sendBocReturnHash response")
            if (!tonResp.isSuccess) {
                throw WalletException(
                    tonResp.error ?: "sendBocReturnHash failed, code=${tonResp.code}",
                )
            }
            val hash = hashFromResult(tonResp.result)
                ?: throw WalletException(
                    "sendBocReturnHash ok but no hash in result=${tonResp.result}",
                )
            waitSeqno(seqno, hash)
            return hash
        } catch (e: WalletException) {
            throw e
        } catch (e: Exception) {
            throw WalletException("Wallet transfer failed: ${e.message}", e)
        }
    }

    fun getBalance(): BigDecimal {
        return try {
            val nano = wallet.balance ?: BigInteger.ZERO
            BigDecimal(nano).divide(NANOTON, 9, RoundingMode.DOWN)
        } catch (e: Exception) {
            throw WalletException("Failed to read balance: ${e.message}", e)
        }
    }

    override fun close() {
        tonCenter.close()
    }

    private fun waitSeqno(seqnoBefore: Long, hash: String) {
        repeat(8) {
            Thread.sleep(2_000)
            try {
                if (wallet.seqno > seqnoBefore) return
            } catch (_: Exception) {
                // keep polling
            }
        }
        throw WalletException(
            "BOC accepted (hash=$hash) but wallet seqno did not increase — transfer dropped or not executed",
        )
    }

    /** Fragment BOC as-is when present; otherwise a text comment. */
    private fun transferBody(comment: String?, payloadBoc: String?): Cell {
        val raw = payloadBoc?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            try {
                return Cell.fromBocBase64(padBase64(raw))
            } catch (_: Exception) {
                try {
                    return Cell.fromBoc(padBase64(raw))
                } catch (_: Exception) {
                    // fall through to text
                }
            }
        }
        return MsgUtils.createTextMessageBody(comment.orEmpty())
    }

    private fun outgoingPayment(
        dest: Address,
        amount: BigInteger,
        comment: String?,
        payloadBoc: String?,
        bounce: Boolean,
    ): OutAction {
        val info = InternalMessageInfoRelaxed.builder()
            .iHRDisabled(true)
            .bounce(bounce)
            .bounced(false)
            .dstAddr(
                MsgAddressIntStd.builder()
                    .workchainId(dest.wc)
                    .address(dest.toBigInteger())
                    .build(),
            )
            .value(CurrencyCollection.builder().coins(amount).build())
            .build()
        val msg = MessageRelaxed.builder()
            .info(info)
            .body(transferBody(comment, payloadBoc))
            .build()
        return ActionSendMsg.builder()
            .mode(SendMode.PAY_GAS_SEPARATELY_AND_IGNORE_ERRORS.value)
            .outMsg(msg)
            .build()
    }

    private fun padBase64(value: String): String {
        val missing = value.length % 4
        return if (missing == 0) value else value + "=".repeat(4 - missing)
    }

    private fun resolveDns(domain: String): Address {
        return try {
            resolveDnsHttp(domain)
        } catch (e: WalletException) {
            throw e
        } catch (e: Exception) {
            throw WalletException("Failed to resolve $domain: ${e.message}", e)
        }
    }

    private fun resolveDnsHttp(domain: String): Address {
        val host = if (testnet) "https://testnet.toncenter.com" else "https://toncenter.com"
        val encoded = URLEncoder.encode(domain, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create("$host/api/v3/dns/records?domain=$encoded"))
            .header("X-API-Key", apiKey)
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = dnsHttp.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw WalletException("DNS lookup HTTP ${response.statusCode()} for $domain")
        }
        val parsed = DNS_JSON.decodeFromString<DnsRecordsResponse>(response.body())
        val wallet = parsed.records.firstNotNullOfOrNull { rec ->
            rec.dnsWallet?.takeIf { it.isNotBlank() }
                ?: rec.nftItemOwner?.takeIf { it.isNotBlank() }
        } ?: throw WalletException("No wallet DNS record for $domain")
        return Address.of(wallet)
    }

    private fun isTonDomain(value: String): Boolean {
        val lower = value.lowercase()
        return lower.endsWith(".ton") || lower.endsWith(".t.me")
    }

    companion object {
        /** Default Wallet V5R1 walletId used by Tonkeeper on mainnet. */
        const val MAINNET_WALLET_ID = 2147483409L
        private val NANOTON = BigDecimal.TEN.pow(9)
        private val dnsHttp: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        private val DNS_JSON = Json { ignoreUnknownKeys = true }

        fun nanoToTon(nano: Long): BigDecimal =
            BigDecimal.valueOf(nano).divide(NANOTON, 9, RoundingMode.DOWN)

        private fun formatTon(nano: BigInteger): String =
            BigDecimal(nano).divide(NANOTON, 9, RoundingMode.DOWN).stripTrailingZeros().toPlainString()

        internal fun hashFromResult(result: Any?): String? = when (result) {
            null -> null
            is SendBocResponse -> result.hash?.takeIf { it.isNotBlank() }
            is Map<*, *> -> {
                val hash = result["hash"] ?: result["@hash"]
                hash?.toString()?.takeIf { it.isNotBlank() && it != "ok" }
            }
            else -> null
        }
    }
}