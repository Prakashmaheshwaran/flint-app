package com.flint.peakfocus.core.model

/** Missing data after a successful read is different from unreadable storage. */
enum class StorageFailure { IO, CORRUPT, UNSUPPORTED_VERSION }

sealed interface StorageState<out T> {
    data object Loading : StorageState<Nothing>
    data class Ready<T>(val value: T) : StorageState<T>
    data class Unavailable(val reason: StorageFailure) : StorageState<Nothing>
}
