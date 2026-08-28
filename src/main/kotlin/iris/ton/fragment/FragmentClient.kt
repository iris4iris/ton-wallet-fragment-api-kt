package iris.ton.fragment

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.math.RoundingMode

/**
 * Fragment.com AJAX client.
 *
 * You need a logged-in browser session:
 * 1. Open https://fragment.com and connect Telegram.
 * 2. DevTools → Network → any `fragment.com/api?hash=...` call.
 * 3. Copy the `cookie` request header and the `hash` query param.
 */
class FragmentClient(
    private val cookies: String,
    private val hash: String,
    private val http: HttpClient = defaultHttpClient(),
    private val ownClient: Boolean = false,
) : AutoCloseable {

    suspend fun getUserAddress(username: String): FragmentUser {
        return post(
            "query" to username,
            "quantity" to "",
            "method" to "searchStarsRecipient",
        )
    }

    suspend fun initBuyStars(recipient: String, quantity: Int): InitBuyStars {
        require(quantity > 0) { "quantity must be > 0" }
        return post(
            "recipient" to recipient,
            "quantity" to quantity.toString(),
            "payment_method" to "ton",
            "method" to "initBuyStarsRequest",
        )
    }

    suspend fun getBuyStarsLink(reqId: String, showSender: Boolean = false): BuyStarsLink {
        return post(
            "transaction" to "1",
            "id" to reqId,
            "show_sender" to if (showSender) "1" else "0",
            "method" to "getBuyStarsLink",
        )
    }

    /**
     * Catalog TON price for [quantity] Stars. No order, no wallet send.
     * Fragment method `updateStarsPrices`.
     */
    suspend fun getStarsPrice(quantity: Int): StarsPrice {
        require(quantity > 0) { "quantity must be > 0" }
        val raw = post<StarsPrices>(
            "stars" to "0",
            "quantity" to quantity.toString(),
            "method" to "updateStarsPrices",
        )
        if (raw.error != null && raw.curPrice.isNullOrBlank()) {
            throw FragmentException(raw.error)
        }
        return parseStarsPrice(quantity, raw.curPrice.orEmpty())
    }

    private suspend inline fun <reified T> post(vararg fields: Pair<String, String>): T {
        if (cookies.isBlank() || hash.isBlank()) {
            throw FragmentException("Fragment cookies and hash are required")
        }
        val response = http.submitForm(
            url = FRAGMENT_API,
            formParameters = Parameters.build {
                fields.forEach { (k, v) -> append(k, v) }
            },
        ) {
            parameter("hash", hash)
            header("accept", "application/json, text/javascript, */*; q=0.01")
            header("accept-language", "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7,ru-RU;q=0.6,ru;q=0.5")
            header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            header("origin", "https://fragment.com")
            header("referer", "https://fragment.com/stars/buy")
            header("cookie", cookies)
            header("user-agent", USER_AGENT)
            header("x-requested-with", "XMLHttpRequest")
            header("sec-fetch-dest", "empty")
            header("sec-fetch-mode", "cors")
            header("sec-fetch-site", "same-origin")
        }
        val status = response.status.value
        if (status !in 200..299) {
            throw FragmentException("Fragment HTTP $status")
        }
        return try {
            response.body()
        } catch (e: Exception) {
            throw FragmentException("Failed to parse Fragment response", e)
        }
    }

    override fun close() {
        if (ownClient) http.close()
    }

    companion object {
        const val FRAGMENT_API = "https://fragment.com/api"
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(json)
            }
        }

        val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        fun create(cookies: String, hash: String): FragmentClient =
            FragmentClient(cookies, hash, defaultHttpClient(), ownClient = true)

        private val TON_HTML =
            Regex("""icon-ton">([\d,]+)(?:<span class="mini-frac">\.(\d+)</span>)?""")
        private val USDT_HTML =
            Regex("""(?:icon-usd">|(?:&#0?36;|\$)\s*)([\d,]+(?:\.\d+)?)""")

        internal fun parseStarsPrice(quantity: Int, html: String): StarsPrice {
            val tonMatch = TON_HTML.find(html)
                ?: throw FragmentException("No TON amount in updateStarsPrices: $html")
            val ton = decimal(tonMatch.groupValues[1], tonMatch.groupValues.getOrNull(2))
            val nano = ton.movePointRight(9).setScale(0, RoundingMode.HALF_UP).longValueExact()
            val usdt = USDT_HTML.find(html)?.groupValues?.get(1)
                ?.replace(",", "")
            return StarsPrice(quantity = quantity, ton = ton, amountNano = nano, usdt = usdt)
        }

        private fun decimal(intPart: String, frac: String?): java.math.BigDecimal {
            val whole = intPart.replace(",", "")
            return if (frac.isNullOrBlank()) whole.toBigDecimal()
            else "$whole.$frac".toBigDecimal()
        }
    }
}