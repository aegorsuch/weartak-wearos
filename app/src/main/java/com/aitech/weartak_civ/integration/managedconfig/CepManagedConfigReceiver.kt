package com.aitech.weartak_civ.integration.managedconfig

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * App-level receiver for ACTION_APPLICATION_RESTRICTIONS_CHANGED. Per WEAR-508 design,
 * CEP overlay processing is NOT tied to MainActivity — overlays may arrive while no
 * activity is running. This receiver enqueues a unique [ManagedConfigApplyWorker] which
 * does the parse/validate/recompose/swap work off the main thread.
 *
 * Coalescing is applied at the WorkManager layer via [ExistingWorkPolicy.REPLACE]; engine-
 * side last-write-wins semantics are enforced separately by `CepProvider.applyManagedOverlay`
 * (per [Update v1.1 — U10]) so the contract holds even if a worker is re-entered.
 */
class CepManagedConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED) {
            Log.w(TAG, "Ignoring unexpected action: $action")
            return
        }
        Log.i(TAG, "ACTION_APPLICATION_RESTRICTIONS_CHANGED received; enqueuing apply worker")

        (context.applicationContext as WearTakApplication).repo.refreshManagedHailServicePolicy()
        val req = OneTimeWorkRequestBuilder<ManagedConfigApplyWorker>().build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    companion object {
        private const val TAG = "CEP-MgrReceiver"
        const val UNIQUE_WORK_NAME = "cep_managed_config_apply"
    }
}
