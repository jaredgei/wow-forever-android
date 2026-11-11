package app.gamenative.ui.screen.wow

import android.content.Context
import android.os.FileObserver
import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.gamenative.Crypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicLong

object BattleNetSignIn {
    data class Login(val email: String, val password: String)

    fun load(context: Context): Login? {
        val prefs = prefs(context)
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val sealed = prefs.getString(KEY_PASSWORD, null) ?: return null
        return try {
            Login(email, String(Crypto.decrypt(Base64.decode(sealed, Base64.NO_WRAP))))
        } catch (e: Exception) {
            Timber.w(e, "Saved Battle.net login unreadable, clearing it")
            forget(context)
            null
        }
    }

    fun save(context: Context, login: Login) {
        val sealed = Base64.encodeToString(Crypto.encrypt(login.password.toByteArray()), Base64.NO_WRAP)
        prefs(context).edit().putString(KEY_EMAIL, login.email).putString(KEY_PASSWORD, sealed).apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun writeLoginFile(gameRoot: File, login: Login) {
        try {
            val file = File(File(gameRoot, WowClientDownloader.FLAVOR_DIR), LOGIN_FILE)
            file.parentFile?.mkdirs()
            file.writeText("${login.email}\n${login.password}\n")
        } catch (e: Exception) {
            Timber.e(e, "Failed to write login.txt")
        }
    }

    // Also clears a login.txt left in the beta folder, which the full release no longer reads.
    fun removeLoginFile(gameRoot: File) {
        listOf(WowClientDownloader.FLAVOR_DIR, WowClientDownloader.LEGACY_FLAVOR_DIR).forEach { flavorDir ->
            try {
                val file = File(File(gameRoot, flavorDir), LOGIN_FILE)
                if (file.exists()) {
                    file.writeBytes(ByteArray(file.length().coerceAtMost(4096).toInt()))
                    file.delete()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove login.txt")
            }
        }
    }

    /**
     * Removes login.txt, which holds the password in plain text in shared storage, once the login has gone through
     * (Hotfix.log fills up). WoW reads the file several times over a couple of seconds and only signs in afterwards, so
     * it can't go on the first read. If the login fails, it goes a minute after WoW last read it. Storage mounts don't
     * all deliver file events (some SD cards), so the last resort is a deadline long enough for a fresh install's first
     * boot, which streams assets and plays the intro cinematic before reaching the login screen.
     */
    fun startLoginWatcher(gameRoot: File) {
        val flavorDir = File(gameRoot, WowClientDownloader.FLAVOR_DIR)
        val hotfixLog = File(flavorDir, "Logs/Hotfix.log")
        hotfixLog.delete()
        val lastRead = AtomicLong()
        @Suppress("DEPRECATION") // The File constructor needs API 29.
        val observer = object : FileObserver(flavorDir.path, CLOSE_NOWRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == LOGIN_FILE) lastRead.set(System.currentTimeMillis())
            }
        }
        observer.startWatching()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val deadline = System.currentTimeMillis() + 600_000L
                while (isActive && System.currentTimeMillis() < deadline &&
                    !(hotfixLog.exists() && hotfixLog.length() > 50_000L) &&
                    !(lastRead.get() > 0 && System.currentTimeMillis() - lastRead.get() > 60_000L)
                ) {
                    delay(1000L)
                }
            } finally {
                observer.stopWatching()
                removeLoginFile(gameRoot)
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "battle_net_login"
    private const val KEY_EMAIL = "email"
    private const val KEY_PASSWORD = "password"
    private const val LOGIN_FILE = "login.txt"
}

@Composable
fun BattleNetCredentialDialog(
    onDismiss: () -> Unit,
    onConfirm: (BattleNetSignIn.Login) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val emailFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(DIALOG_SETTLE_MS)
        emailFocus.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Battle.net login") },
        text = {
            Column {
                Text("Saved encrypted on this device. WoW will automatically log in on launch.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.focusRequester(emailFocus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = email.isNotBlank() && password.isNotEmpty(),
                onClick = { onConfirm(BattleNetSignIn.Login(email.trim(), password)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val DIALOG_SETTLE_MS = 300L
