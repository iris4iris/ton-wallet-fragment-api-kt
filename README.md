# fragment-api-wallet-kt

Kotlin/JVM library to buy Telegram Stars on [fragment.com](https://fragment.com) and pay the invoice from a **Wallet V5R1** via [TonCenter](https://toncenter.com).

`iris.ton.wallet` is the wallet/on-chain layer. `iris.ton.fragment` is the Fragment AJAX client and Stars flow.

Fragment's AJAX API is unofficial and session-based. Use at your own risk. Do not commit cookies, hash, or the wallet seed.

| Package | Classes |
| --- | --- |
| `iris.ton.fragment` | `FragmentClient`, `FragmentStars` |
| `iris.ton.wallet` | `TonWallet`, `TonViewer` |

## Usage

```kotlin
import iris.ton.fragment.FragmentClient
import iris.ton.fragment.FragmentStars
import iris.ton.fragment.StarsResult
import iris.ton.wallet.TonWallet

val fragment = FragmentClient.create(cookies, hash)
val wallet = TonWallet(apiKey, mnemonic)
val stars = FragmentStars(fragment, wallet)

val user = stars.getUser("durov")
val price = stars.getStarsPrice(50)        // catalog TON, no order
val quote = stars.quoteStarsOrder("durov", 50)  // exact invoice, still no transfer
when (val result = stars.payStarsOrder("durov", 50, showSender = true)) {
    is StarsResult.Ok -> println(result.txHash)
    is StarsResult.Err -> System.err.println(result.message)
}
```

`initBuyStarsRequest` **must** send `payment_method=ton`. Without it Fragment returns `Access denied` even when `searchStarsRecipient` works.

`getBuyStarsLink` takes `show_sender`: `0` anonymous (default), `1` shows the Telegram account tied to the Fragment session.

## Credentials

| Env | What |
| --- | --- |
| `FRAGMENT_COOKIES` | Full `Cookie` header from DevTools. For **buys** it must include `stel_ton_token` (TON wallet connected on fragment.com). Search-only cookies are not enough. |
| `FRAGMENT_HASH` | `hash` query param on `fragment.com/api?hash=…`, preferably from `/stars/buy` |
| `TONCENTER_API_KEY` | Key from [@toncenter](https://t.me/toncenter). Not a wallet secret. `TONCONSOLE_API_KEY` is accepted as an alias. |
| `WALLET_MNEMONIC` | 24-word **Wallet V5R1 / W5** seed (Tonkeeper). This *is* the wallet. Backend only. |

Copy [.env.example](.env.example) → `.env` locally. Never commit `.env`.

## Run

JDK 19+ (21 from IntelliJ / Android Studio is fine). Wrapper included.

```bash
export TONCENTER_API_KEY=...
export WALLET_MNEMONIC="word1 word2 ... word24"
export FRAGMENT_COOKIES="stel_token=...; stel_ssid=...; stel_ton_token=..."
export FRAGMENT_HASH="..."
export STARS_USERNAME="telegram_user"
export STARS_QUANTITY=50
./gradlew run
```

Windows (`cmd`): quote cookies — `;` splits commands.

```bat
set "TONCENTER_API_KEY=..."
set "WALLET_MNEMONIC=word1 word2 ... word24"
set "FRAGMENT_COOKIES=stel_token=...; stel_ssid=...; stel_ton_token=..."
set "FRAGMENT_HASH=..."
set "STARS_USERNAME=telegram_user"
set "STARS_QUANTITY=50"
gradlew.bat run
```

```bat
gradlew.bat build
```

If Gradle asks for a JDK 19 toolchain, `settings.gradle.kts` already has Foojay resolver, or set **Gradle JVM** to 19/21 in IntelliJ.

## Install as a module

```kotlin
dependencies {
    implementation("io.ktor:ktor-client-cio:3.1.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.ton.ton4j:smartcontract:2.1.0")
    implementation("org.ton.ton4j:mnemonic:2.1.0")
    implementation("org.ton.ton4j:utils:2.1.0")
    implementation("org.ton.ton4j:address:2.1.0")
    implementation("org.ton.ton4j:toncenter:2.1.0")
}
```

## Flow

```
searchStarsRecipient  →  initBuyStarsRequest
        │                                            │ payment_method=ton
     nickname                                     req_id
     recipient                                       │
                                              getBuyStarsLink
                                                     │
                                              payload BOC as-is
                                                     │
                                         WalletV5R1.transfer
```

## License

MIT. No warranty.