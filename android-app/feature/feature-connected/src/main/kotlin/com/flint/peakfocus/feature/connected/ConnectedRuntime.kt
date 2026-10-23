package com.flint.peakfocus.feature.connected

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.flint.peakfocus.core.datastore.ManagedCaches
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ConnectedRuntime(
    val identity: ConnectedIdentitySession,
    val transport: ConnectedHttpTransport,
    val managed: ManagedPolicyRepository,
)

/** Both account and managed ViewModel factories share this application-lived session. */
internal object ConnectedRuntimes {
    private val runtimes = mutableMapOf<String, ConnectedRuntime>()
    @Synchronized fun open(context: Context, config: ConnectedConfig): ConnectedRuntime {
        val namespace = "${config.projectId}|${config.apiBase}|${config.authHost}:${config.authPort}"
        return runtimes.getOrPut(namespace) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val identity = ConnectedIdentitySession(FirebaseConnectedAuthGateway(context, config), ManagedCaches.open(context), namespace, config.projectId, scope)
            val transport = AuthenticatedConnectedTransport(identity, config.apiBase)
            val boot = runCatching { Settings.Global.getInt(context.contentResolver, "boot_count") }.getOrNull()
            // Missing boot-count support is conservative: a new process cannot reuse its old clock anchor.
            val clock = object : ManagedDeviceClock {
                override val bootId = boot?.takeIf { it >= 0 }?.let { "boot:$it" } ?: "process:${UUID.randomUUID()}"
                override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
            }
            ConnectedRuntime(identity, transport, ManagedPolicyRepository(identity, transport, clock, scope))
        }
    }
}

private class FirebaseConnectedAuthGateway(context: Context, config: ConnectedConfig) : ConnectedAuthGateway {
    private val auth: FirebaseAuth
    init {
        val name = "FlintLocalConnected"
        val existing = FirebaseApp.getApps(context).firstOrNull { it.name == name }
        auth = if (existing != null) FirebaseAuth.getInstance(existing) else {
            val options = FirebaseOptions.Builder().setProjectId(config.projectId)
                .setApplicationId("1:1234567890:android:flintlocal").setApiKey("demo-flint-api-key").build()
            val app = FirebaseApp.initializeApp(context, options, name)
            app.setDataCollectionDefaultEnabled(false)
            FirebaseAuth.getInstance(app).also { it.useEmulator(config.authHost, config.authPort) }
        }
    }
    override val uid: String? get() = auth.currentUser?.uid
    override val email: String? get() = auth.currentUser?.email
    override fun observe(listener: () -> Unit): () -> Unit {
        val observer = FirebaseAuth.AuthStateListener { listener() }
        auth.addAuthStateListener(observer)
        return { auth.removeAuthStateListener(observer) }
    }
    override suspend fun authenticate(email: String, password: String, create: Boolean) {
        if (create) auth.createUserWithEmailAndPassword(email, password).awaitResult()
        else auth.signInWithEmailAndPassword(email, password).awaitResult()
    }
    override suspend fun resetPassword(email: String) { auth.sendPasswordResetEmail(email).awaitResult() }
    override suspend fun token(forceRefresh: Boolean): String = auth.currentUser?.getIdToken(forceRefresh)?.awaitResult()?.token
        ?: throw ManagedRequestFailure(ManagedFailureKind.UNAUTHENTICATED, "Your session expired. Sign in again.")
    override fun signOut() = auth.signOut()
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resume(task.result)
        else continuation.resumeWithException(task.exception ?: ConnectedFailure("Account operation failed."))
    }
}
