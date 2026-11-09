package com.codexserver.mobile

/** Content being uploaded is independent of subsequent edits to the composer. */
internal data class ComposerSubmission(
    val revision: Long,
    val images: List<ImageAttachment>,
    val video: VideoAttachment?,
    val files: List<SharedFileAttachment>,
    val references: List<ContextReference>
)

internal fun <T> unsentItems(current: List<T>, submitted: List<T>): List<T> =
    current.filter { item -> submitted.none { it === item } }

internal fun acceptedDraftText(current: String, revision: Long, submittedRevision: Long): String =
    if (revision == submittedRevision) "" else current
