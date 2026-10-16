package io.github.magisk317.smscode.ui.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.magisk317.smscode.runtime.BuildConfig as RuntimeBuildConfig
import com.github.tianma8023.xposed.smscode.BuildConfig
import io.github.magisk317.smscode.common.constant.PrefConst
import io.github.magisk317.smscode.runtime.common.diagnostics.ActivationDiagnosticsStore
import io.github.magisk317.smscode.runtime.common.prefs.AppPreferencesDataStore
import io.github.magisk317.smscode.common.utils.XscPreferenceHooks
import io.github.magisk317.smscode.common.utils.HookPreferenceMirror
import io.github.magisk317.smscode.xposed.utils.ModuleActivationStore
import io.github.magisk317.smscode.xposed.utils.ModuleUtils
import io.github.magisk317.smscode.common.utils.RuntimeDiagnosticsBridge
import io.github.magisk317.smscode.runtime.common.diagnostics.RuntimeLogStore
import io.github.magisk317.smscode.xp.helper.ModuleConflictArbiter
import io.github.magisk317.smscode.xposed.runtime.CoreHookPolicy
import io.github.magisk317.smscode.xposed.runtime.CoreHookPolicyHolder
import io.github.magisk317.smscode.xposed.runtime.CoreLogSink
import io.github.magisk317.smscode.xposed.runtime.CoreLogSinkHolder
import io.github.magisk317.smscode.xposed.runtime.CoreRuntime
import io.github.magisk317.smscode.runtime.common.utils.StorageUtils
import io.github.magisk317.smscode.xposed.runtime.CoreRuntimeAccess
import io.github.magisk317.xposed.logging.XLog
import io.github.magisk317.xposed.logging.DefaultLogSanitizer
import io.github.magisk317.smscode.di.appModule
import io.github.magisk317.smscode.di.billingModule
import io.github.magisk317.smscode.di.uiModule
import io.github.magisk317.smscode.runtime.RuntimeCodeRecordRestoreFacade
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import timber.log.Timber

@SuppressLint("LogNotTimber")
class SmsCodeApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppPreferencesDataStore.install(XscPreferenceHooks)
        android.util.Log.w("smscode", "SmsCodeApplication.onCreate() START")
        ensureIpcToken()
        RuntimeDiagnosticsBridge.ensureInstalled()
        RuntimeLogStore.initialize(this, enableDetailedLogs = false)
        installCoreRuntime()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        startKoin {
            androidLogger()
            androidContext(this@SmsCodeApplication)
            modules(appModule, billingModule, uiModule)
        }
        
        org.koin.core.context.GlobalContext.get().getAll<io.github.magisk317.uikit.shell.AppInitializer>().forEach {
            it.init(this)
        }

        initXposedServiceActivationMonitor()
        importPendingCodeRecords()
        syncPreferences()
    }



    private fun importPendingCodeRecords() {
        applicationScope.launch {
            RuntimeCodeRecordRestoreFacade.importToDatabase(this@SmsCodeApplication)
        }
    }

    private fun syncPreferences() {
        applicationScope.launch {
            HookPreferenceMirror.publish(this@SmsCodeApplication)
            val verboseLog = AppPreferencesDataStore.getBoolean(
                this@SmsCodeApplication,
                PrefConst.KEY_VERBOSE_LOG_MODE,
                false,
            )
            RuntimeDiagnosticsBridge.ensureInstalled()
            RuntimeLogStore.setEnabled(verboseLog)
            val sensitiveDebugMode = AppPreferencesDataStore.getBoolean(
                this@SmsCodeApplication,
                PrefConst.KEY_SENSITIVE_DEBUG_LOG_MODE,
                false,
            )
            io.github.magisk317.xposed.logging.LogSanitizerConfig
                .syncFromVerboseMode(sensitiveDebugMode)
            StorageUtils.ensureExternalAppDataPermissions(this@SmsCodeApplication)
        }
    }

    private fun initXposedServiceActivationMonitor() {
        XposedServiceBridge.initialize(this, applicationScope)
    }

    private fun installCoreRuntime() {
        CoreRuntime.install(object : CoreRuntimeAccess {
            override val logTag: String = BuildConfig.LOG_TAG
            override val logLevel: Int = RuntimeBuildConfig.LOG_LEVEL
            override val logToXposed: Boolean = RuntimeBuildConfig.LOG_TO_XPOSED
            override val debug: Boolean = BuildConfig.DEBUG
            override val applicationId: String = BuildConfig.APPLICATION_ID
            override val actionNamespace: String = "io.github.magisk317.smscode"
        })
        CoreLogSinkHolder.install(object : CoreLogSink {
            override fun append(
                priority: Int,
                tag: String,
                message: String,
                force: Boolean,
                route: String?,
                sensitive: Boolean,
                throwableText: String?,
            ) {
                // sensitive=true means payload may contain secrets; honor shared switch
                // Opening pref_sensitive_debug_log_mode disables sanitization and lets
                // plaintext through for debugging.
                val safeMessage = if (sensitive) {
                    DefaultLogSanitizer.sanitizeIfEnabled(message)
                } else {
                    message
                }
                RuntimeDiagnosticsBridge.ensureInstalled()
                RuntimeLogStore.append(priority, tag, safeMessage, force, route, throwableText)
            }
        })
        CoreHookPolicyHolder.install(object : CoreHookPolicy {
            override fun shouldSuppressSystemHooks(context: Context?, source: String): Boolean {
                return ModuleConflictArbiter.shouldSuppressByRelay(context, source)
            }
        })
        AppShellRuntimeBridge.install(this)
    }

    internal fun handleXposedServiceBound(
        remotePrefsProvider: (() -> SharedPreferences?)?,
        frameworkName: String?,
        frameworkVersion: String?,
    ) {
        XLog.i(
            message = "handleXposedServiceBound() called: framework=$frameworkName version=$frameworkVersion provider=${remotePrefsProvider != null}",
        )
        AppPreferencesDataStore.setRemotePrefsProvider(remotePrefsProvider)
        // Publishes attempted before the service bound are marked pending rather
        // than dropped. The provider is live now, so flush them; otherwise the
        // hook process keeps reading a mirror written before the last change.
        if (AppPreferencesDataStore.hasPendingRemotePrefsPublish()) {
            applicationScope.launch {
                HookPreferenceMirror.publish(this@SmsCodeApplication)
            }
        }
        ModuleUtils.setRuntimeActivated(true)
        ModuleActivationStore.markActivated(this)
        ActivationDiagnosticsStore.recordServiceBind(
            context = this,
            frameworkName = frameworkName ?: "unknown",
            frameworkVersion = frameworkVersion ?: "unknown",
            verboseLogging = readVerboseLogMode(),
        )
        XLog.i(
            message = "Xposed service connected: framework=%s version=%s",
            frameworkName ?: "unknown",
            frameworkVersion ?: "unknown",
        )
    }

    internal fun handleXposedServiceDied() {
        AppPreferencesDataStore.setRemotePrefsProvider(null)
        ModuleUtils.setRuntimeActivated(false)
        ActivationDiagnosticsStore.recordServiceDied(
            context = this,
            verboseLogging = readVerboseLogMode(),
        )
        XLog.w(message = "Xposed service disconnected")
    }

    internal fun logXposedServiceBridgeFailure(throwable: Throwable) {
        XLog.w(message = "Failed to register Xposed service listener: %s", throwable.message ?: "unknown")
    }

    private fun readVerboseLogMode(): Boolean = runBlocking(Dispatchers.IO) {
        AppPreferencesDataStore.getBoolean(this@SmsCodeApplication, PrefConst.KEY_VERBOSE_LOG_MODE, false)
    }

    private fun ensureIpcToken() {
        runBlocking(Dispatchers.IO) {
            AppIpcTokenStore.ensurePublished(
                existingToken = {
                    AppPreferencesDataStore.getString(this@SmsCodeApplication, PrefConst.KEY_IPC_TOKEN, "")
                },
                generateToken = { UUID.randomUUID().toString() },
                persistToken = { generated ->
                    AppPreferencesDataStore.setString(
                        this@SmsCodeApplication,
                        PrefConst.KEY_IPC_TOKEN,
                        generated,
                    )
                    Timber.i("Generated new IPC Security Token via DataStore")
                },
            )
            HookPreferenceMirror.publish(this@SmsCodeApplication)
        }
    }

}
