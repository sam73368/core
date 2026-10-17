/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.innertube

import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching] but never swallows coroutine cancellation: when the caller is cancelled
 * (track changed, screen left, timeout) the cancellation propagates instead of being turned into
 * a failed [Result] that callers then treat as a real error (and cache, retry or blacklist).
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
