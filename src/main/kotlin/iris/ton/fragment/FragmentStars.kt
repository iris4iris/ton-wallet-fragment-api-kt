package iris.ton.fragment

import iris.ton.wallet.TonWallet
import iris.ton.wallet.TransferResult
import java.io.IOException

/**
 * High-level Stars API: look up a recipient and pay an order.
 */
class FragmentStars(
    private val fragment: FragmentClient,
    private val wallet: TonWallet,
) {

    /**
     * Catalog TON price for [quantity] Stars. No order, no wallet send.
     * Fragment method `updateStarsPrices`.
     */
    suspend fun getStarsPrice(quantity: Int): StarsPrice =
        fragment.getStarsPrice(quantity)

    suspend fun getUser(username: String): Recipient? {
        val clean = username.trim().removePrefix("@")
        if (clean.isEmpty()) return null
        return try {
            val user = fragment.getUserAddress(clean)
            val name = user.found?.name
            val address = user.found?.recipient
            if (name.isNullOrBlank() || address.isNullOrBlank()) null
            else Recipient(nickname = name, address = address)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun quoteStarsOrder(
        username: String,
        quantity: Int,
        showSender: Boolean = false,
    ): StarsPayment {
        val clean = username.trim().removePrefix("@")
        val user = fragment.getUserAddress(clean)
        val nickname = user.found?.name
        val recipient = user.found?.recipient
        if (nickname.isNullOrBlank() || recipient.isNullOrBlank()) {
            throw FragmentException("User @$clean not found on Fragment")
        }

        val init = fragment.initBuyStars(recipient, quantity)
        if (init.needTon == true) {
            throw FragmentException(
                "Fragment need_ton: connect a TON wallet on fragment.com, then recopy cookies (stel_ton_token is required for buys)",
            )
        }
        val reqId = init.reqId
            ?: throw FragmentException(init.error ?: "No req_id from initBuyStars")

        val buy = fragment.getBuyStarsLink(reqId, showSender)
        val message = buy.transaction?.messages?.firstOrNull()
            ?: throw FragmentException(buy.error ?: "No transaction messages from getBuyStarsLink")

        val address = message.address ?: throw FragmentException("Missing destination address")
        val amountRaw = message.amount ?: throw FragmentException("Missing amount")
        val amountNano = amountRaw.toLongOrNull()
            ?: throw FragmentException("Bad amount: $amountRaw")
        val payload = message.payload.orEmpty()
        if (payload.isBlank()) throw FragmentException("Missing payload from getBuyStarsLink")

        return StarsPayment(
            destination = address,
            amountNano = amountNano,
            payload = payload,
            reqId = reqId,
        )
    }

    suspend fun payStarsOrder(
        username: String,
        quantity: Int,
        showSender: Boolean = false,
    ): StarsResult {
        return try {
            val payment = quoteStarsOrder(username, quantity, showSender)
            when (val sent = wallet.sendTransfer(
                address = payment.destination,
                amountNano = payment.amountNano,
                payloadBoc = payment.payload,
                bounce = true,
            )) {
                is TransferResult.Ok -> StarsResult.Ok(sent.txHash)
                is TransferResult.Err -> StarsResult.Err(sent.message, sent.cause)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            StarsResult.Err(e.message ?: e::class.simpleName ?: "unknown", e)
        }
    }
}