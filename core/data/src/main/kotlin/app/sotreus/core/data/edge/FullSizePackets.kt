package app.sotreus.core.data.edge

/**
 * Full-size Bluetooth packets (protocol "Link-layer packets"): whether this phone asks the Edge to
 * keep 251-byte link-layer packets, and when Sotreus turns that off by itself.
 */
object FullSizePackets {
    /** A full-size link lost sooner than this after it came up dropped early. */
    const val EARLY_DROP_MS = 2 * 60_000L

    /** Early drops in a row that turn full-size packets off for that Edge. */
    const val EARLY_DROPS_TO_TURN_OFF = 2

    /** The owner's choice, else on when this phone's Bluetooth has Bluetooth 5 features. */
    fun wanted(choice: Boolean?, phoneBluetooth5: Boolean): Boolean = choice ?: phoneBluetooth5

    /**
     * A full-size link the Edge dropped (never one this app closed), judged at the next handshake:
     * [early] when it was up less than [EARLY_DROP_MS], [boot] the Edge's boot it ran on.
     */
    data class Loss(val early: Boolean, val boot: Long?)

    fun loss(upMs: Long, boot: Long?) = Loss(upMs < EARLY_DROP_MS, boot)

    /**
     * Early drops in a row once the Edge answers again on [bootNow]. A full-size link that held
     * starts the count again; a drop across an Edge restart (a new boot) is the restart, not this
     * phone's link, so it neither counts nor resets. No loss (a limited link) changes nothing.
     */
    fun afterReconnect(earlyDrops: Int, loss: Loss?, bootNow: Long): Int = when {
        loss == null -> earlyDrops
        !loss.early -> 0
        loss.boot != bootNow -> earlyDrops
        else -> earlyDrops + 1
    }

    fun turnsOff(earlyDrops: Int): Boolean = earlyDrops >= EARLY_DROPS_TO_TURN_OFF
}
