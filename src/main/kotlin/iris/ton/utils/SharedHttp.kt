package iris.ton.utils

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Process-wide Ktor CIO client for Fragment and our TonCenter v3 calls
 * (DNS, traces). Headers stay per-request. Do not close.
 * ton4j [org.ton.ton4j.toncenter.TonCenter] keeps its own HTTP.
 */
internal object SharedHttp {
	val json: Json = Json {
		ignoreUnknownKeys = true
		isLenient = true
		explicitNulls = false
	}

	val client: HttpClient = HttpClient(CIO) {
		expectSuccess = false
		install(HttpTimeout) {
			connectTimeoutMillis = 10_000
			requestTimeoutMillis = 20_000
			socketTimeoutMillis = 20_000
		}
		install(ContentNegotiation) {
			json(json)
		}
	}
}