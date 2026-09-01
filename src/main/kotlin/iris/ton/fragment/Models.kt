package iris.ton.fragment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class FragmentUser(
    val found: Found? = null,
    val error: String? = null,
) {
    @Serializable
    data class Found(
        val name: String? = null,
        val recipient: String? = null,
        val photo: String? = null,
    )
}

@Serializable
data class InitBuyStars(
    @SerialName("req_id") val reqId: String? = null,
    val error: String? = null,
    @SerialName("need_ton") val needTon: Boolean? = null,
    val ok: Boolean? = null,
)

@Serializable
data class BuyStarsLink(
    val transaction: Transaction? = null,
    val error: String? = null,
) {
    @Serializable
    data class Transaction(
        val messages: List<Message> = emptyList(),
        val validUntil: Long? = null,
    )

    @Serializable
    data class Message(
        val address: String? = null,
        val amount: String? = null,
        val payload: String? = null,
    )
}

data class Recipient(
    val nickname: String,
    val address: String,
)

data class StarsPayment(
    val destination: String,
    val amountNano: Long,
    val payload: String,
    val reqId: String,
)

data class StarsPrice(
    val quantity: Int,
    val ton: java.math.BigDecimal,
    val amountNano: Long,
    val usdt: String? = null,
)

@Serializable
data class StarsPrices(
    val ok: Boolean? = null,
    val error: String? = null,
    @SerialName("cur_price") val curPrice: String? = null,
)

sealed class StarsResult {
    data class Ok(val txHash: String) : StarsResult()
    data class Err(val message: String, val cause: Throwable? = null) : StarsResult()
}

class FragmentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Loose JSON bag for undocumented Fragment fields. */
@Serializable
data class FragmentJson(val raw: Map<String, JsonElement> = emptyMap())