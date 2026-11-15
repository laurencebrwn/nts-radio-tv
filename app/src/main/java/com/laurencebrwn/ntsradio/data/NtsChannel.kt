package com.laurencebrwn.ntsradio.data

/**
 * The two live NTS channels.
 *
 * The stream URLs are NTS' geo load-balanced relays which redirect to the closest
 * audio edge server. The API identifies the channels by "1" and "2".
 */
enum class NtsChannel(
    val id: String,
    val apiName: String,
    val displayName: String,
    val streamUrl: String,
) {
    ONE(
        id = "nts-1",
        apiName = "1",
        displayName = "NTS 1",
        streamUrl = "https://stream-relay-geo.ntslive.net/stream",
    ),
    TWO(
        id = "nts-2",
        apiName = "2",
        displayName = "NTS 2",
        streamUrl = "https://stream-relay-geo.ntslive.net/stream2",
    );

    companion object {
        fun fromId(id: String?): NtsChannel? = entries.firstOrNull { it.id == id }

        fun fromApiName(name: String?): NtsChannel? =
            entries.firstOrNull { it.apiName == name }
    }
}
