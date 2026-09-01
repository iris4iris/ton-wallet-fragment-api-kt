package iris.ton.wallet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class WalletException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

sealed class TransferResult {
    data class Ok(val txHash: String) : TransferResult()
    data class Err(val message: String, val cause: Throwable? = null) : TransferResult()
}

sealed class BalanceResult {
    data class Ok(val balance: java.math.BigDecimal) : BalanceResult()
    data class Err(val message: String, val cause: Throwable? = null) : BalanceResult()
}

enum class TxStatus {
    /** Indexer has no record yet (or hash unknown). */
    PENDING,
    SUCCESS,
    /** Wallet executed, outgoing action skipped (e.g. not enough TON + IGNORE_ERRORS). */
    SKIPPED,
    /** Value returned to sender. */
    BOUNCED,
    FAILED,
}

data class TxMessage(
    val source: String? = null,
    val destination: String? = null,
    val valueNano: Long? = null,
    val bounce: Boolean? = null,
    val bounced: Boolean? = null,
    val opcode: String? = null,
    val comment: String? = null,
)

data class TxInfo(
    val hash: String,
    val status: TxStatus,
    val account: String? = null,
    val utime: Long? = null,
    val lt: String? = null,
    val totalFeesNano: Long? = null,
    val aborted: Boolean = false,
    val computeSuccess: Boolean? = null,
    val exitCode: Int? = null,
    val skippedActions: Int = 0,
    val messagesCreated: Int = 0,
    val inMsg: TxMessage? = null,
    val outMsgs: List<TxMessage> = emptyList(),
    val children: List<TxInfo> = emptyList(),
    val raw: JsonObject? = null,
) {
    fun isSuccessful(): Boolean = status == TxStatus.SUCCESS
}

@Serializable
internal data class DnsRecordsResponse(
    val records: List<DnsRecord> = emptyList(),
)

@Serializable
internal data class DnsRecord(
    @SerialName("dns_wallet") val dnsWallet: String? = null,
    @SerialName("nft_item_owner") val nftItemOwner: String? = null,
    val domain: String? = null,
)