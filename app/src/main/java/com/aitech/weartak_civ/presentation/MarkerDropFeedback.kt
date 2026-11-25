package com.aitech.weartak_civ.presentation

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DataRepository.MarkerDropStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal fun currentMarkerDropStatus(
    uid: String,
    statuses: Flow<MarkerDropStatus>,
    currentDrop: StateFlow<String?>,
): Flow<MarkerDropStatus> = combine(statuses, currentDrop) { status, current ->
    if (current == uid) status else MarkerDropStatus.SUPERSEDED
}

internal suspend fun observeMarkerDropFeedback(
    statuses: Flow<MarkerDropStatus>,
    report: suspend (MarkerDropStatus) -> Unit,
) {
    var pendingShown = false
    statuses.first { status ->
        when (status) {
            MarkerDropStatus.PENDING -> {
                if (!pendingShown) report(status)
                pendingShown = true
                false
            }
            MarkerDropStatus.SUBMITTED, MarkerDropStatus.UNCERTAIN -> {
                report(status)
                true
            }
            MarkerDropStatus.SUPERSEDED -> {
                report(status)
                true
            }
        }
    }
}

internal suspend fun showMarkerDropJournalResult(
    context: Context,
    repository: DataRepository,
    uid: String,
    currentDrop: StateFlow<String?>,
    onSuccess: () -> Unit,
    operation: suspend () -> Boolean,
) {
    var toast: Toast? = null
    suspend fun show(resource: Int) = withContext(Dispatchers.Main) {
        if (currentDrop.value == uid) {
            toast?.cancel()
            toast = Toast.makeText(context.applicationContext, resource, Toast.LENGTH_LONG).also { it.show() }
        }
    }
    val saved = try {
        operation()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e("MarkerDropFeedback", "Marker journal admission failed", error)
        show(R.string.marker_save_unconfirmed)
        return
    }
    if (!saved) {
        show(R.string.marker_request_invalid)
        return
    }
    try {
        observeMarkerDropFeedback(currentMarkerDropStatus(uid, repository.markerDropStatus(uid), currentDrop)) { status ->
            withContext(Dispatchers.Main) {
                if (status == MarkerDropStatus.SUPERSEDED) {
                    toast?.cancel()
                    return@withContext
                }
                if (currentDrop.value != uid || repository.markerDropStatus(uid).first() != status) return@withContext
                toast?.cancel()
                when (status) {
                    MarkerDropStatus.PENDING -> toast = Toast.makeText(
                        context.applicationContext, R.string.marker_saved_locally, Toast.LENGTH_LONG,
                    ).also { it.show() }
                    MarkerDropStatus.SUBMITTED -> onSuccess()
                    MarkerDropStatus.UNCERTAIN -> toast = Toast.makeText(
                        context.applicationContext, R.string.marker_submission_unknown, Toast.LENGTH_LONG,
                    ).also { it.show() }
                    MarkerDropStatus.SUPERSEDED -> Unit
                }
            }
        }
    } catch (error: CancellationException) {
        withContext(NonCancellable + Dispatchers.Main) { toast?.cancel() }
        throw error
    } catch (error: Exception) {
        Log.e("MarkerDropFeedback", "Marker submission status unavailable", error)
        show(R.string.marker_submission_unknown)
    }
}
