package org.orynnx.outerview.core.internal

object RearWallpaperSelectionPolicy {
    data class Observation(
        val known: Boolean,
        val managedCurrentId: Int?,
    )

    /**
     * Deletion is safe only after this host process observed an authoritative
     * selection. A persisted marker is deliberately not an input: it can be
     * stale if the host stopped between saving its selection and our callback.
     */
    fun canDelete(
        targetId: Int,
        observationKnown: Boolean,
        observedCurrentId: Int?,
        pendingApplyId: Int? = null,
    ): Boolean = observationKnown && targetId != observedCurrentId && targetId != pendingApplyId

    fun observe(
        reflectedIds: Collection<Int>,
        runtimeIds: Collection<Int>,
        managedIds: Set<Int>,
        createdManagedId: Int?,
    ): Observation {
        val globallyUniqueRuntimeIds = runtimeIds.groupingBy { it }.eachCount()
            .filterValues { count -> count == 1 }
            .keys
        val reflectedRuntimeIds = reflectedIds.asSequence()
            .filter(globallyUniqueRuntimeIds::contains)
            .distinct()
            .toList()
        if (createdManagedId != null) {
            if (createdManagedId !in managedIds) return Observation(false, null)
            return if (
                reflectedRuntimeIds.isEmpty() ||
                reflectedRuntimeIds.singleOrNull() == createdManagedId
            ) {
                Observation(true, createdManagedId)
            } else {
                Observation(false, null)
            }
        }
        val observedId = reflectedRuntimeIds.singleOrNull() ?: return Observation(false, null)
        return Observation(true, observedId.takeIf(managedIds::contains))
    }
}
