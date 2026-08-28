package iris.ton.wallet.example

import iris.ton.wallet.TonWallet
import org.ton.ton4j.utils.Utils

fun main() {
	val walletApiKey = System.getenv("TONCENTER_API_KEY")
		?.ifBlank { null }
		?: System.getenv("TONCONSOLE_API_KEY").orEmpty()
	val walletMnemonic = System.getenv("WALLET_MNEMONIC").orEmpty()
		.split(Regex("\\s+"))
		.filter { it.isNotBlank() }

	val address =  System.getenv("TON_ADDRESS")
	val quantity = 1000.0 //

	TonWallet(walletApiKey, walletMnemonic).use {
		val response = it.sendTransfer(address, amountNano = Utils.toNano(quantity).toLong(), "")
		println(response)
	}
}