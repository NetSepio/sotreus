package app.sotreus.social.presence

/**
 * The advertised presence frame: manufacturer data under company ID 0xFFFF (Bluetooth SIG's
 * reserved test ID; a registered ID is needed before production), payload "S" + version + token.
 * It carries no account, wallet, handle or stable device ID.
 */
object PresenceFrame {
    const val COMPANY_ID = 0xFFFF
    private const val MAGIC: Byte = 0x53
    private const val VERSION: Byte = 0x01
    const val TOKEN_BYTES = 16

    fun encode(token: ByteArray): ByteArray {
        require(token.size == TOKEN_BYTES)
        return byteArrayOf(MAGIC, VERSION) + token
    }

    /** Returns the token if [companyId]/[data] is a Sotreus presence frame. */
    fun decode(companyId: Int?, data: ByteArray): ByteArray? =
        if (companyId == COMPANY_ID && data.size == TOKEN_BYTES + 2 && data[0] == MAGIC && data[1] == VERSION) data.copyOfRange(2, data.size) else null
}
