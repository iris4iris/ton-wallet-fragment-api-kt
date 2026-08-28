package iris.ton.fragment.example

import iris.ton.fragment.FragmentClient
import iris.ton.fragment.FragmentStars
import iris.ton.fragment.StarsResult
import iris.ton.wallet.TonWallet
import kotlinx.coroutines.runBlocking

/**
 * Example entrypoint.
 *
 * Fill in credentials, then:
 *
 *   ./gradlew run
 */
fun main() = runBlocking {
    val walletApiKey = System.getenv("TONCENTER_API_KEY")
        ?.ifBlank { null }
        ?: System.getenv("TONCONSOLE_API_KEY").orEmpty()
    val walletMnemonic = System.getenv("WALLET_MNEMONIC").orEmpty()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    val cookies = System.getenv("FRAGMENT_COOKIES").orEmpty()
    val hash = System.getenv("FRAGMENT_HASH").orEmpty()

    val username = argsOrDefault("STARS_USERNAME", "demo")
    val quantity = System.getenv("STARS_QUANTITY")?.toIntOrNull() ?: 50
    val showSender = System.getenv("FRAGMENT_SHOW_SENDER")
        ?.let { it == "1" || it.equals("true", ignoreCase = true) }
        ?: false

    FragmentClient.create(cookies, hash).use { fragment ->
        TonWallet(walletApiKey, walletMnemonic).use { wallet ->
            val stars = FragmentStars(fragment, wallet)

            val user = stars.getUser(username)
            println("user = $user")
            println("price = ${stars.getStarsPrice(quantity)}")
            println("balance = ${wallet.getBalance()} TON")

            when (val result = stars.payStarsOrder(username, quantity, showSender)) {
                is StarsResult.Ok -> println("tx = ${result.txHash}")
                is StarsResult.Err -> System.err.println("pay failed: ${result.message}")
            }
        }
    }
}

private fun argsOrDefault(envName: String, fallback: String): String {
    val fromEnv = System.getenv(envName)
    return if (!fromEnv.isNullOrBlank()) fromEnv else fallback
}
