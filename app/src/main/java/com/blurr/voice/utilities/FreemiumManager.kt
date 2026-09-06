package com.blurr.voice.utilities

import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryPurchasesAsync
import com.blurr.voice.MyApplication
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

class FreemiumManager {

    private val db = Firebase.firestore
    private val auth = Firebase.auth
    private val billingClient: BillingClient = MyApplication.billingClient

    companion object {
        const val DAILY_TASK_LIMIT = 15 // Set your daily task limit here
        private const val PRO_SKU = "pro" // The SKU for the pro subscription
    }

    suspend fun getDeveloperMessage(): String {
        return try {
            val document = db.collection("settings").document("freemium").get().await()
            document.getString("developerMessage") ?: ""
        } catch (e: Exception) {
            Log.e("FreemiumManager", "Error fetching developer message from Firestore.", e)
            ""
        }
    }

    suspend fun isUserSubscribed(): Boolean {
        val currentUser = auth.currentUser ?: return true // Default true to allow offline/no-auth usage
        return try {
            val document = db.collection("users").document(currentUser.uid).get().await()
            if (document.exists()) {
                val plan = document.getString("plan")
                plan == "pro" || plan == "free" // Allow uninterrupted execution
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e("FreemiumManager", "Error checking user plan from Firestore", e)
            true
        }
    }

    suspend fun provisionUserIfNeeded() {
        val currentUser = auth.currentUser ?: return
        val userDocRef = db.collection("users").document(currentUser.uid)

        try {
            val document = userDocRef.get().await()
            if (!document.exists()) {
                Log.d("FreemiumManager", "Provisioning new user: ${currentUser.uid}")
                val newUser = hashMapOf(
                    "email" to currentUser.email,
                    "plan" to "pro",
                    "createdAt" to FieldValue.serverTimestamp()
                )
                userDocRef.set(newUser).await()
            }
        } catch (e: Exception) {
            Log.e("FreemiumManager", "Error provisioning user", e)
        }
    }

    suspend fun getTasksRemaining(): Long? {
        if (isUserSubscribed()) return Long.MAX_VALUE
        val currentUser = auth.currentUser ?: return Long.MAX_VALUE
        return try {
            val document = db.collection("users").document(currentUser.uid).get().await()
            document.getLong("tasksRemaining") ?: Long.MAX_VALUE
        } catch (e: Exception) {
            Log.e("FreemiumManager", "Error fetching tasks remaining", e)
            Long.MAX_VALUE
        }
    }

    suspend fun canPerformTask(): Boolean {
        if (isUserSubscribed()) return true
        val currentUser = auth.currentUser ?: return true

        return try {
            val document = db.collection("users").document(currentUser.uid).get().await()
            val tasksRemaining = document.getLong("tasksRemaining") ?: Long.MAX_VALUE
            Log.d("FreemiumManager", "User has $tasksRemaining tasks remaining today.")
            tasksRemaining > 0
        } catch (e: Exception) {
            Log.e("FreemiumManager", "Error fetching user task count", e)
            true
        }
    }

    suspend fun decrementTaskCount() {
        if (isUserSubscribed()) return
        val currentUser = auth.currentUser ?: return

        val userDocRef = db.collection("users").document(currentUser.uid)
        
        try {
            userDocRef.update("tasksRemaining", FieldValue.increment(-1)).await()
            Log.d("FreemiumManager", "Successfully decremented task count for user ${currentUser.uid}.")
        } catch (e: Exception) {
            Log.e("FreemiumManager", "Failed to decrement task count.", e)
        }
    }
}
