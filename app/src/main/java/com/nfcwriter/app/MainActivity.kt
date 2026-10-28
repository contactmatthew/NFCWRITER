package com.nfcwriter.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nfcwriter.app.nfc.NfcEngine
import com.nfcwriter.app.nfc.isCriticalOperation
import com.nfcwriter.app.ui.*

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private lateinit var engine: NfcEngine
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if (savedInstanceState == null) {
            Toast.makeText(this, R.string.developer_credit, Toast.LENGTH_SHORT).show()
        }
        val appearancePreferences = getSharedPreferences("appearance", MODE_PRIVATE)
        engine = NfcEngine(this).also { nfc ->
            nfc.onVerifiedWrite = { snapshot, payload, operation, previous -> viewModel.recordVerifiedWrite(snapshot, payload, operation, previous) }
            nfc.checkManagedUrlWrite = viewModel::checkManagedUrlWrite
            nfc.onOperationRecorded = viewModel::recordOperation
            nfc.credentialForUid = viewModel::credentialForUid
            nfc.managedTagIdForUid = viewModel::managedTagIdForUid
            nfc.onCredentialStored = viewModel::storeNfcCredential
            nfc.onSecurityResult = viewModel::recordSecurityResult
        }
        setContent {
            val nfcState by engine.state.collectAsStateWithLifecycle()
            DisposableEffect(nfcState.isCriticalOperation) {
                if (nfcState.isCriticalOperation) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { if (nfcState.isCriticalOperation) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            var themeMode by remember {
                mutableStateOf(runCatching { ThemeMode.valueOf(appearancePreferences.getString("theme_mode", ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM))
            }
            NfcWriterTheme(themeMode) {
                NfcWriterApp(
                    viewModel,
                    engine,
                    nfcState,
                    themeMode,
                    { mode -> themeMode = mode; appearancePreferences.edit().putString("theme_mode", mode.name).apply() },
                ) { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            }
        }
    }
    override fun onResume() { super.onResume(); engine.enable() }
    override fun onPause() { engine.onAppBackgrounded(); engine.disable(); super.onPause() }
    override fun onDestroy() { engine.close(); super.onDestroy() }
}
