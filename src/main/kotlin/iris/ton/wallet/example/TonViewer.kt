package iris.ton.wallet.example

import iris.ton.wallet.TonViewer

fun main() {
	val walletApiKey = System.getenv("TONCENTER_API_KEY")
		?.ifBlank { null }
		?: System.getenv("TONCONSOLE_API_KEY").orEmpty()
	val transaction = System.getenv("TRANSACTION_ID")?.trim().orEmpty()
	require(transaction.isNotEmpty()) { "TRANSACTION_ID is required" }

	val viewer = TonViewer(walletApiKey)
	val txInfo = viewer.getTransaction(transaction)
	println(txInfo.status)
	println(txInfo.raw)
}