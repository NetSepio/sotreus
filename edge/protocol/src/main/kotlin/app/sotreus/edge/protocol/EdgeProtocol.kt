package app.sotreus.edge.protocol

/** Constants of Sotreus Edge protocol v1 (`sotreus-edge/docs/protocol.md`). */
object EdgeProtocol {
    const val VERSION = 1

    const val SERVICE_UUID = "5e0e0001-5307-4e75-8d9a-ed9e5e0e0001"
    const val INFO_UUID = "5e0e0002-5307-4e75-8d9a-ed9e5e0e0001"
    const val CONTROL_UUID = "5e0e0003-5307-4e75-8d9a-ed9e5e0e0001"
    const val EVENTS_UUID = "5e0e0004-5307-4e75-8d9a-ed9e5e0e0001"
    const val RECORDS_UUID = "5e0e0005-5307-4e75-8d9a-ed9e5e0e0001"

    /** The MTU the app asks for; the Edge prefers 512. */
    const val REQUESTED_MTU = 517

    /** ATT header bytes subtracted from the MTU for a write or notification payload. */
    const val ATT_OVERHEAD = 3

    const val RECORD_SIZE = 256
    const val RECORD_FORMAT = 1

    /** `sync` pages the app asks for over Bluetooth. */
    const val SYNC_PAGE = 500

    /** `live` rows per request (the firmware caps it at 100). */
    const val LIVE_PAGE = 100

    /** `watch` is capped at 600 s by the firmware; the app renews before then. */
    const val WATCH_SECONDS = 600
}
