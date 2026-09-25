package com.khatwa.app.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.khatwa.app.AppContainer
import com.khatwa.app.groups.Account
import com.khatwa.app.groups.GroupsException
import com.khatwa.app.groups.GroupsSync
import com.khatwa.app.i18n.Strings
import com.khatwa.app.i18n.strings
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.ui.containerViewModel
import com.khatwa.app.ui.groups.AdminScreen
import com.khatwa.app.ui.groups.errorText
import com.khatwa.app.ui.settings.SubScreen
import com.khatwa.app.util.Fmt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A positive one-off notice about the account. */
enum class AccountNotice { RESET_SENT, ADMIN_GRANTED, SAVED, SAVE_FAILED }

/** Sign-up, sign-in, sign-out, the cloud copy and admin access: one place for the whole app. */
class AccountViewModel(private val c: AppContainer) : ViewModel() {
    val configured: Boolean get() = c.groups.configured
    val account: StateFlow<Account?> = c.groups.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, c.groups.account)
    val isAdmin: StateFlow<Boolean> = c.groups.observeIsAdmin().catch { emit(false) }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _message = MutableStateFlow<GroupsException.Kind?>(null)
    val message: StateFlow<GroupsException.Kind?> = _message
    private val _notice = MutableStateFlow<AccountNotice?>(null)
    val notice: StateFlow<AccountNotice?> = _notice
    private val _savedAt = MutableStateFlow<Long?>(null)
    /** When the account's copy was saved (null: none yet / unknown). */
    val savedAt: StateFlow<Long?> = _savedAt

    /**
     * New account (an old anonymous account gets the email added, keeping its uid and groups).
     * [fresh]: first-time setup, where an existing copy in the account is restored at once.
     */
    fun signUp(email: String, password: String, nickname: String, fresh: Boolean = false, then: suspend () -> Unit = {}) = run {
        val name = nickname.trim().take(24)
        if (name.isBlank()) return@run
        c.groups.signUp(email, password, name)
        c.cloud.afterSignIn(fresh)
        then()
        refreshSavedAt()
    }

    fun signIn(email: String, password: String, fresh: Boolean = false, then: suspend () -> Unit = {}) = run {
        c.groups.signIn(email, password)
        c.cloud.afterSignIn(fresh)
        then()
        refreshSavedAt()
    }

    fun resetPassword(email: String) = run {
        c.groups.sendPasswordReset(email)
        _notice.value = AccountNotice.RESET_SENT
    }

    fun signOut() = run {
        c.settings.setGroupsEnabled(false)
        GroupsSync.schedule(c.app, false)
        c.cloud.onSignedOut()
        c.groups.signOut()
        _savedAt.value = null
    }

    fun saveNow() = run {
        _notice.value = if (c.cloud.upload()) AccountNotice.SAVED else AccountNotice.SAVE_FAILED
        refreshSavedAt()
    }

    /** Asks the global restore dialog to offer the account's copy. */
    fun offerRestore() = run { c.cloud.afterSignIn(fresh = false) }

    fun claimAdmin(password: String) = run {
        c.groups.claimAdmin(password)
        _notice.value = AccountNotice.ADMIN_GRANTED
    }

    fun refreshSavedAt() {
        viewModelScope.launch { _savedAt.value = runCatching { if (c.groups.account != null) c.cloud.savedAt() else null }.getOrNull() }
    }

    fun clearMessage() { _message.value = null; _notice.value = null }

    private fun run(block: suspend () -> Unit): Job = viewModelScope.launch {
        _busy.value = true
        try { block() } catch (e: Exception) {
            _message.value = (e as? GroupsException)?.kind ?: GroupsException.Kind.UNKNOWN
        } finally { _busy.value = false }
    }
}

fun Strings.noticeText(n: AccountNotice): String = when (n) {
    AccountNotice.RESET_SENT -> resetSent
    AccountNotice.ADMIN_GRANTED -> adminGranted
    AccountNotice.SAVED -> savedToAccount
    AccountNotice.SAVE_FAILED -> saveFailed
}

/** Progress, error and notice cards of the account view model. */
@Composable
fun AccountMessages(vm: AccountViewModel, s: Strings) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); VSpace(8.dp) }
    message?.let { kind ->
        KCard(tone = CardTone.Warning, modifier = Modifier.testTag("account_message")) {
            Text(s.errorText(kind))
            TextButton(onClick = { vm.clearMessage() }) { Text(s.done) }
        }
        VSpace()
    }
    notice?.let { n ->
        KCard(tone = CardTone.Accent, modifier = Modifier.testTag("account_notice")) {
            Text(s.noticeText(n))
            TextButton(onClick = { vm.clearMessage() }) { Text(s.done) }
        }
        VSpace()
    }
}

/**
 * Create an account (nickname, email, password) or sign in; "forgot password" sends a reset
 * email. [fresh]: first-time setup (a copy in the account is restored at once). [onSignedIn] runs
 * after a successful sign-in (e.g. the Groups tab turns groups on).
 */
@Composable
fun AccountCard(vm: AccountViewModel, s: Strings, fresh: Boolean = false, onSignedIn: suspend () -> Unit = {}) {
    var signIn by rememberSaveable { mutableStateOf(false) }
    var nickname by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    KCard {
        SectionTitle(if (signIn) s.signIn else s.createAccount)
        Text(s.accountIntro)
        VSpace(6.dp)
        Muted(s.groupsPrivacy)
        VSpace()
        if (!signIn) {
            OutlinedTextField(
                value = nickname, onValueChange = { nickname = it.take(24) }, label = { Text(s.nickname) },
                supportingText = { Text(s.nicknameHint) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("groups_nickname"),
            )
            VSpace(4.dp)
        }
        EmailPasswordFields(s, email, { email = it }, password, { password = it })
        VSpace(8.dp)
        val ready = email.isNotBlank() && password.length >= 6 && (signIn || nickname.isNotBlank())
        PrimaryButton(if (signIn) s.signIn else s.createAccount, Modifier.fillMaxWidth().testTag("groups_account_submit"), enabled = ready) {
            if (signIn) vm.signIn(email, password, fresh, onSignedIn) else vm.signUp(email, password, nickname, fresh, onSignedIn)
        }
        TextButton(onClick = { signIn = !signIn }, modifier = Modifier.testTag("groups_account_switch")) {
            Text(if (signIn) s.noAccount else s.haveAccount)
        }
        if (signIn) {
            TextButton(onClick = { vm.resetPassword(email) }, enabled = email.isNotBlank(), modifier = Modifier.testTag("groups_forgot")) {
                Text(s.forgotPassword)
            }
        }
    }
}

@Composable
fun EmailPasswordFields(s: Strings, email: String, onEmail: (String) -> Unit, password: String, onPassword: (String) -> Unit) {
    OutlinedTextField(
        value = email, onValueChange = { onEmail(it.trim().take(120)) }, label = { Text(s.email) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth().testTag("groups_email"),
    )
    VSpace(4.dp)
    OutlinedTextField(
        value = password, onValueChange = { onPassword(it.take(64)) }, label = { Text(s.password) },
        supportingText = { Text(s.passwordHint) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag("groups_password"),
    )
}

/** Dialog to type the admin password (the first time: the temporary key). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AdminPasswordDialog(s: Strings, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        onDismissRequest = onDismiss,
        title = { Text(s.adminKey) },
        text = {
            OutlinedTextField(
                value = key, onValueChange = { key = it.take(64) }, supportingText = { Text(s.adminKeyHint) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("admin_key"),
            )
        },
        confirmButton = { TextButton(enabled = key.isNotBlank(), onClick = { onConfirm(key) }, modifier = Modifier.testTag("admin_key_confirm")) { Text(s.confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/** Settings → Account: sign in / out, the copy in the account, admin access. */
@Composable
fun AccountScreen(container: AppContainer, onBack: () -> Unit) {
    val vm = containerViewModel { AccountViewModel(it) }
    val s = strings
    val account by vm.account.collectAsStateWithLifecycle()
    val isAdmin by vm.isAdmin.collectAsStateWithLifecycle()
    val savedAt by vm.savedAt.collectAsStateWithLifecycle()
    var adminOpen by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) } // "admin" | "restore"
    LaunchedEffect(account?.uid) { vm.refreshSavedAt() }

    if (adminOpen && isAdmin) {
        androidx.activity.compose.BackHandler { adminOpen = false }
        AdminScreen(container, onBack = { adminOpen = false })
        return
    }
    SubScreen(title = s.account, onBack = onBack, tag = "settings_account_scroll") {
        AccountMessages(vm, s)
        val acc = account
        when {
            !vm.configured -> KCard(tone = CardTone.Soft) { Text(s.groupsNotConfigured) }
            acc == null || acc.anonymous -> AccountCard(vm, s)
            else -> {
                KCard(modifier = Modifier.testTag("account_signed_in")) {
                    Text(s.signedInAs(acc.email ?: "—"), style = MaterialTheme.typography.titleMedium)
                    VSpace(8.dp)
                    SecondaryButton(s.signOut, Modifier.fillMaxWidth().testTag("account_sign_out")) { vm.signOut() }
                    Muted(s.signOutHint)
                }
                VSpace()
                KCard {
                    SectionTitle(s.cloudBackupTitle)
                    Muted(s.cloudBackupAuto)
                    VSpace(6.dp)
                    Text(savedAt?.let { s.lastSaved(Fmt.dateTime(it)) } ?: s.notSavedYet, modifier = Modifier.testTag("account_saved_at"))
                    VSpace(8.dp)
                    PrimaryButton(s.saveNow, Modifier.fillMaxWidth().testTag("account_save_now")) { vm.saveNow() }
                    VSpace(8.dp)
                    SecondaryButton(s.restoreFromAccount, Modifier.fillMaxWidth().testTag("account_restore"), enabled = savedAt != null) { vm.offerRestore() }
                }
                VSpace()
                if (isAdmin) {
                    PrimaryButton(s.adminPanel, Modifier.fillMaxWidth().testTag("admin_open")) { adminOpen = true }
                } else {
                    TextButton(onClick = { dialog = "admin" }, modifier = Modifier.testTag("admin_key_open")) {
                        Text(s.adminKey, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    if (dialog == "admin") AdminPasswordDialog(s, onDismiss = { dialog = null }) { key -> dialog = null; vm.claimAdmin(key) }
}

/**
 * App-wide dialogs of the cloud copy: "restore the copy from your account?" after signing in on
 * a phone that already has data, and "your data is back" after a restore.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CloudBackupDialogs(container: AppContainer) {
    val s = strings
    val scope = rememberCoroutineScope()
    val offer by container.cloud.restoreOffer.collectAsStateWithLifecycle()
    val restored by container.cloud.restored.collectAsStateWithLifecycle()
    var working by remember { mutableStateOf(false) }
    offer?.let { at ->
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = {},
            title = { Text(s.restoreFromAccount) },
            text = { Column { Text(s.restoreQuestion(Fmt.dateTime(at))); if (working) LinearProgressIndicator(Modifier.fillMaxWidth()) } },
            confirmButton = {
                TextButton(enabled = !working, onClick = { working = true; scope.launch { runCatching { container.cloud.restore() }; working = false } }, modifier = Modifier.testTag("restore_confirm")) { Text(s.restore) }
            },
            dismissButton = {
                TextButton(enabled = !working, onClick = { scope.launch { container.cloud.keepPhoneData() } }, modifier = Modifier.testTag("restore_keep")) { Text(s.keepPhoneData) }
            },
        )
    }
    restored?.let { summary ->
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { container.cloud.clearRestored() },
            title = { Text(s.dataRestoredTitle) },
            text = { Text(summary) },
            confirmButton = { TextButton(onClick = { container.cloud.clearRestored() }, modifier = Modifier.testTag("restored_ok")) { Text(s.done) } },
        )
    }
}
