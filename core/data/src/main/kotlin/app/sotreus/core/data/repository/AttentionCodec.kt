package app.sotreus.core.data.repository

import app.sotreus.core.model.AttentionInputs
import app.sotreus.core.model.AttentionReason
import app.sotreus.core.model.AttentionReasonKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** JSON storage for attention reasons and inputs (local only). */
object AttentionCodec {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ReasonDto(val kind: String, val args: List<String> = emptyList(), val strong: Boolean = true)

    @Serializable
    private data class InputsDto(
        val reEncounter: Float, val yourTag: Float, val persistence: Float,
        val novelty: Float, val knownSignature: Float, val freshness: Float,
    )

    fun encodeReasons(reasons: List<AttentionReason>): String =
        json.encodeToString(ListSerializer(ReasonDto.serializer()), reasons.map { ReasonDto(it.kind.name, it.args, it.strong) })

    fun decodeReasons(text: String): List<AttentionReason> =
        json.decodeFromString(ListSerializer(ReasonDto.serializer()), text).mapNotNull { dto ->
            AttentionReasonKind.entries.firstOrNull { it.name == dto.kind }?.let { AttentionReason(it, dto.args, dto.strong) }
        }

    fun encodeInputs(i: AttentionInputs): String = json.encodeToString(
        InputsDto.serializer(),
        InputsDto(i.reEncounter, i.yourTag, i.persistence, i.novelty, i.knownSignature, i.freshness),
    )

    fun decodeInputs(text: String): AttentionInputs = json.decodeFromString(InputsDto.serializer(), text).let {
        AttentionInputs(it.reEncounter, it.yourTag, it.persistence, it.novelty, it.knownSignature, it.freshness)
    }
}
