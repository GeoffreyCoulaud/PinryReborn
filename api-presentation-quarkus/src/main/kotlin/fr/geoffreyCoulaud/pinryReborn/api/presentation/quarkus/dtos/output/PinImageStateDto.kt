package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

data class PinImageStateDto(
    val status: String,
    val url: String? = null,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val byteSize: Long? = null,
    val reasonCode: String? = null,
    val message: String? = null,
    val replacement: ReplacementDto? = null,
) {
    data class ReplacementDto(val status: String, val reasonCode: String? = null, val message: String? = null)
}
