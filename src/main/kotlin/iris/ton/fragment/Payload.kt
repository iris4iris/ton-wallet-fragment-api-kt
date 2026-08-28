package iris.ton.fragment

import java.util.Base64

/**
 * Fragment returns a base64 payload. The decoded UTF-8 from the first digit
 * onward is the TON transfer comment.
 */
object Payload {
    fun decode(encoded: String): String {
        if (encoded.isBlank()) return encoded
        val padded = padBase64(encoded)
        return try {
            val decoded = String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
            val firstDigit = decoded.indexOfFirst { it.isDigit() }
            if (firstDigit >= 0) decoded.substring(firstDigit) else decoded
        } catch (_: IllegalArgumentException) {
            encoded
        }
    }

    private fun padBase64(value: String): String {
        val missing = value.length % 4
        return if (missing == 0) value else value + "=".repeat(4 - missing)
    }
}
