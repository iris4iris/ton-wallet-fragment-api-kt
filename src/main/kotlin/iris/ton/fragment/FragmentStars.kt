package iris.ton.fragment

import iris.ton.wallet.TonWallet
import iris.ton.wallet.TransferResult
import kotlinx.coroutines.CancellationException
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
            requireRecipient(clean)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun quoteStarsOrder(
        username: String,
        quantity: Int,
        showSender: Boolean = false,
    ): StarsPayment = quoteStarsOrder(requireRecipient(username), quantity, showSender)

    suspend fun quoteStarsOrder(
        recipient: Recipient,
        quantity: Int,
        showSender: Boolean = false,
    ): StarsPayment {
        val init = fragment.initBuyStars(recipient.address, quantity)
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
            payStarsOrder(requireRecipient(username), quantity, showSender)
        } catch (e: IOException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StarsResult.Err(e.message ?: e::class.simpleName ?: "unknown", e)
        }
    }

    suspend fun payStarsOrder(
        recipient: Recipient,
        quantity: Int,
        showSender: Boolean = false,
    ): StarsResult {
        return try {
            val payment = quoteStarsOrder(recipient, quantity, showSender)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StarsResult.Err(e.message ?: e::class.simpleName ?: "unknown", e)
        }
    }

    private suspend fun requireRecipient(username: String): Recipient {
        val clean = username.trim().removePrefix("@")
        if (clean.isEmpty()) throw FragmentException("Username is blank")
        val user = fragment.getUserAddress(clean)
        val nickname = user.found?.name
        val address = user.found?.recipient
        if (nickname.isNullOrBlank() || address.isNullOrBlank()) {
            throw FragmentException("User @$clean not found on Fragment")
        }
        return Recipient(nickname = nickname, address = address)
    }
}