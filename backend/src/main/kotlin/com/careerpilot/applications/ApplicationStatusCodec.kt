package com.careerpilot.applications

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.SerializationException

/** Parses API status strings case-insensitively (e.g. `applied`, `APPLIED`, `online-assessment`). */
fun parseApplicationStatus(raw: String): ApplicationStatus? {
    val n =
        raw.trim()
            .uppercase()
            .replace('-', '_')
            .replace(' ', '_')
    return enumValues<ApplicationStatus>().find { it.name == n }
}

object ApplicationStatusJsonSerializer : KSerializer<ApplicationStatus> {
    override val descriptor =
        PrimitiveSerialDescriptor("ApplicationStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ApplicationStatus {
        val raw = decoder.decodeString()
        return parseApplicationStatus(raw)
            ?: throw SerializationException("Unknown application status: $raw")
    }

    override fun serialize(encoder: Encoder, value: ApplicationStatus) {
        encoder.encodeString(value.name)
    }
}
