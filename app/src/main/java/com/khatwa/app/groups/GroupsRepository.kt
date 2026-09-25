package com.khatwa.app.groups

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.khatwa.app.BuildConfig
import com.khatwa.app.settings.SettingsRepository
import com.khatwa.core.groups.GroupSummary
import com.khatwa.core.groups.GroupTotals
import com.khatwa.core.groups.InviteCode
import com.khatwa.core.groups.MemberStats
import com.khatwa.core.groups.PeriodStats
import com.khatwa.core.groups.Ranking
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A group as everyone sees it (the public card). */
data class Group(
    val id: String,
    val name: String,
    val description: String,
    val ownerUid: String,
    val memberCount: Int,
    val hidden: Boolean,
    val summary: GroupSummary?,
)

data class Member(val uid: String, val nickname: String, val joinedAt: Long)

/** The signed-in account. [anonymous] accounts are from before email accounts existed. */
data class Account(val uid: String, val email: String?, val anonymous: Boolean)

/** A group's totals on one day ([date] yyyy-mm-dd). */
data class GroupDay(val date: String, val steps: Long, val goalMet: Int, val members: Int)

/** A profile as the admin sees it. */
data class UserProfile(val uid: String, val nickname: String, val ownedGroups: Int, val createdAt: Long)

class GroupsException(val kind: Kind, cause: Throwable? = null) : Exception(kind.name, cause) {
    enum class Kind {
        NOT_CONFIGURED, OFFLINE, NOT_SIGNED_IN, INVALID_CODE, UNKNOWN_CODE, ALREADY_MEMBER, GROUP_FULL, TOO_MANY_GROUPS, OWNER_CANNOT_LEAVE, DENIED, UNKNOWN,
        // account
        EMAIL_IN_USE, INVALID_EMAIL, WEAK_PASSWORD, WRONG_CREDENTIALS, SIGN_IN_DISABLED, TOO_MANY_ATTEMPTS,
        // admin
        WRONG_ADMIN_KEY, REMOVE_OWNER_FIRST,
    }
}

/**
 * Firestore access for the groups feature. Every document is a plain map (no reflection-based
 * models), and every write is a batch shaped exactly as the security rules expect, so the rules
 * tests in firebase/tests/ are also tests of this code's write shapes.
 */
class GroupsRepository(private val context: Context, private val settings: SettingsRepository) {

    /** True when a real google-services.json was built in (CI secret / your own download). */
    val configured: Boolean by lazy {
        runCatching { FirebaseApp.getInstance().options.projectId }.getOrNull()
            .let { it != null && it != PLACEHOLDER_PROJECT }
    }

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore by lazy {
        val f = FirebaseFirestore.getInstance()
        emulatorHost?.let { host ->
            f.useEmulator(host, 8080)
            auth.useEmulator(host, 9099)
            f.firestoreSettings = FirebaseFirestoreSettings.Builder().setPersistenceEnabled(false).build()
        }
        installAppCheck()
        f
    }

    private fun installAppCheck() {
        if (emulatorHost != null) return
        runCatching {
            val appCheck = FirebaseAppCheck.getInstance()
            if (BuildConfig.DEBUG) {
                val factory = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                    .getMethod("getInstance").invoke(null) as com.google.firebase.appcheck.AppCheckProviderFactory
                appCheck.installAppCheckProviderFactory(factory)
            } else {
                appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
            }
        }.onFailure { Log.w(TAG, "app check not installed: ${it.message}") }
    }

    val uid: String? get() = auth.currentUser?.uid

    // ---- identity --------------------------------------------------------------------------

    /** The current account, updated on sign-in and sign-out. */
    fun observeAccount(): Flow<Account?> = if (!configured) flowOf(null) else callbackFlow {
        db // points auth at the emulator first when one is set
        val listener = FirebaseAuth.AuthStateListener { a -> trySend(a.currentUser?.let { Account(it.uid, it.email, it.isAnonymous) }) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    val account: Account? get() = if (!configured) null else { db; auth.currentUser?.let { Account(it.uid, it.email, it.isAnonymous) } }

    /** The signed-in uid. Groups need an account; there is no automatic anonymous sign-in any more. */
    suspend fun ensureSignedIn(): String {
        if (!configured) throw GroupsException(GroupsException.Kind.NOT_CONFIGURED)
        db // initialise first: points auth + firestore at the emulator when one is set, installs App Check
        return auth.currentUser?.uid ?: throw GroupsException(GroupsException.Kind.NOT_SIGNED_IN)
    }

    /**
     * Creates an email account. On a phone that still has an old anonymous account, the email is
     * added to that same account, so its groups are kept.
     */
    suspend fun signUp(email: String, password: String, nickname: String) {
        if (!configured) throw GroupsException(GroupsException.Kind.NOT_CONFIGURED)
        db
        val credential = com.google.firebase.auth.EmailAuthProvider.getCredential(email.trim(), password)
        val current = auth.currentUser
        val user = if (current != null && current.isAnonymous) {
            wrap { current.linkWithCredential(credential).await() }.user
        } else {
            if (current != null) auth.signOut()
            wrap { auth.createUserWithEmailAndPassword(email.trim(), password).await() }.user
        } ?: throw GroupsException(GroupsException.Kind.NOT_SIGNED_IN)
        settings.setGroupsUid(user.uid)
        settings.setGroupsNickname(nickname)
        ensureProfile(nickname)
        Log.i(TAG, "account created")
    }

    /** Signs in to an existing account and restores its nickname (after a reinstall, on a new phone). */
    suspend fun signIn(email: String, password: String) {
        if (!configured) throw GroupsException(GroupsException.Kind.NOT_CONFIGURED)
        db
        if (auth.currentUser != null) auth.signOut()
        val user = wrap { auth.signInWithEmailAndPassword(email.trim(), password).await() }.user
            ?: throw GroupsException(GroupsException.Kind.NOT_SIGNED_IN)
        settings.setGroupsUid(user.uid)
        val nickname = wrap { db.document("users/${user.uid}").get().await() }.getString("nickname")
        if (nickname != null) settings.setGroupsNickname(nickname)
        else ensureProfile(settings.current().groupsNickname.ifBlank { user.email?.substringBefore('@')?.take(24) ?: "khatwa" })
        Log.i(TAG, "signed in")
    }

    suspend fun sendPasswordReset(email: String) {
        if (!configured) throw GroupsException(GroupsException.Kind.NOT_CONFIGURED)
        db
        wrap { auth.sendPasswordResetEmail(email.trim()).await() }
    }

    fun signOut() {
        if (!configured) return
        db
        auth.signOut()
    }

    /** Creates the private profile on first use, or updates the nickname everywhere it appears. */
    suspend fun ensureProfile(nickname: String) {
        val me = ensureSignedIn()
        val ref = db.document("users/$me")
        val snap = wrap { ref.get().await() }
        if (!snap.exists()) {
            wrap { ref.set(mapOf("nickname" to nickname, "ownedGroups" to 0, "createdAt" to now())).await() }
            return
        }
        if (snap.getString("nickname") == nickname) return
        wrap { ref.update("nickname", nickname).await() }
        for (gid in myGroupIds()) {
            runCatching { db.document("groups/$gid/members/$me").update("nickname", nickname).await() }
        }
    }

    private suspend fun myGroupIds(): List<String> {
        val me = uid ?: return emptyList()
        return wrap { db.collection("users/$me/memberships").get().await() }.documents.map { it.id }
    }

    // ---- groups ----------------------------------------------------------------------------

    suspend fun createGroup(name: String, description: String): String {
        val me = ensureSignedIn()
        val nickname = settings.current().groupsNickname.ifBlank { "khatwa" }
        ensureProfile(nickname)
        val owned = wrap { db.document("users/$me").get().await() }.getLong("ownedGroups") ?: 0L
        if (owned >= 5) throw GroupsException(GroupsException.Kind.TOO_MANY_GROUPS)
        val gid = db.collection("groups").document().id
        val code = InviteCode.generate()
        val emptyTotals = totalsMap(GroupTotals(0, 1, 0, 0.0))
        val b = db.batch()
        b.set(db.document("groups/$gid"), mapOf(
            "name" to name.trim().take(40), "description" to description.trim().take(120), "ownerUid" to me,
            "memberCount" to 1, "hidden" to false, "createdAt" to now(),
            "summary" to mapOf("date" to LocalDate.now().toString(), "today" to emptyTotals, "week" to emptyTotals, "month" to emptyTotals, "updatedAt" to now()),
        ))
        b.set(db.document("groups/$gid/private/invite"), mapOf("code" to code))
        b.set(db.document("groups/$gid/members/$me"), mapOf("nickname" to nickname, "joinedAt" to now()))
        b.set(db.document("invites/$code"), mapOf("groupId" to gid))
        b.set(db.document("users/$me/memberships/$gid"), mapOf("joinedAt" to now()))
        b.update(db.document("users/$me"), "ownedGroups", FieldValue.increment(1))
        wrap { b.commit().await() }
        Log.i(TAG, "group created $gid")
        return gid
    }

    suspend fun joinByCode(input: String): String {
        val me = ensureSignedIn()
        val code = InviteCode.normalize(input)
        if (!InviteCode.isValid(code)) throw GroupsException(GroupsException.Kind.INVALID_CODE)
        val invite = wrap { db.document("invites/$code").get().await() }
        val gid = invite.getString("groupId") ?: throw GroupsException(GroupsException.Kind.UNKNOWN_CODE)
        val group = wrap { db.document("groups/$gid").get().await() }
        if (!group.exists()) throw GroupsException(GroupsException.Kind.UNKNOWN_CODE)
        if ((group.getLong("memberCount") ?: 0L) >= 200) throw GroupsException(GroupsException.Kind.GROUP_FULL)
        if (wrap { db.document("users/$me/memberships/$gid").get().await() }.exists()) throw GroupsException(GroupsException.Kind.ALREADY_MEMBER)
        val nickname = settings.current().groupsNickname.ifBlank { "khatwa" }
        val b = db.batch()
        b.set(db.document("groups/$gid/members/$me"), mapOf("nickname" to nickname, "joinedAt" to now(), "code" to code))
        b.update(db.document("groups/$gid"), "memberCount", FieldValue.increment(1))
        b.set(db.document("users/$me/memberships/$gid"), mapOf("joinedAt" to now()))
        wrap { b.commit().await() }
        Log.i(TAG, "joined $gid")
        return gid
    }

    suspend fun leave(gid: String) {
        val me = ensureSignedIn()
        val group = wrap { db.document("groups/$gid").get().await() }
        if (group.getString("ownerUid") == me) throw GroupsException(GroupsException.Kind.OWNER_CANNOT_LEAVE)
        val b = db.batch()
        b.delete(db.document("groups/$gid/members/$me"))
        b.delete(db.document("groups/$gid/contrib/$me"))
        b.update(db.document("groups/$gid"), "memberCount", FieldValue.increment(-1))
        b.delete(db.document("users/$me/memberships/$gid"))
        wrap { b.commit().await() }
    }

    suspend fun removeMember(gid: String, member: String) {
        ensureSignedIn()
        val b = db.batch()
        b.delete(db.document("groups/$gid/members/$member"))
        b.delete(db.document("groups/$gid/contrib/$member"))
        b.update(db.document("groups/$gid"), "memberCount", FieldValue.increment(-1))
        wrap { b.commit().await() }
    }

    suspend fun deleteGroup(gid: String) {
        val me = ensureSignedIn()
        val members = wrap { db.collection("groups/$gid/members").get().await() }.documents.map { it.id }
        val code = wrap { db.document("groups/$gid/private/invite").get().await() }.getString("code")
        val b = db.batch()
        members.forEach { m ->
            b.delete(db.document("groups/$gid/members/$m"))
            b.delete(db.document("groups/$gid/contrib/$m"))
        }
        b.delete(db.document("groups/$gid/private/invite"))
        if (code != null) b.delete(db.document("invites/$code"))
        b.delete(db.document("groups/$gid"))
        b.delete(db.document("users/$me/memberships/$gid"))
        b.update(db.document("users/$me"), "ownedGroups", FieldValue.increment(-1))
        wrap { b.commit().await() }
        Log.i(TAG, "group deleted $gid")
    }

    suspend fun regenerateCode(gid: String): String {
        ensureSignedIn()
        val old = wrap { db.document("groups/$gid/private/invite").get().await() }.getString("code")
        val code = InviteCode.generate()
        val b = db.batch()
        b.set(db.document("groups/$gid/private/invite"), mapOf("code" to code))
        if (old != null) b.delete(db.document("invites/$old"))
        b.set(db.document("invites/$code"), mapOf("groupId" to gid))
        wrap { b.commit().await() }
        return code
    }

    suspend fun inviteCode(gid: String): String? {
        ensureSignedIn()
        return wrap { db.document("groups/$gid/private/invite").get().await() }.getString("code")
    }

    suspend fun setHidden(gid: String, hidden: Boolean) {
        ensureSignedIn()
        wrap { db.document("groups/$gid").update("hidden", hidden).await() }
    }

    // ---- admin: admins/{uid} (created once with the admin key) gets full access in the rules ----

    fun observeIsAdmin(): Flow<Boolean> = observeAccount().flatMapLatest { acc ->
        if (acc == null) flowOf(false) else callbackFlow {
            val reg = db.document("admins/${acc.uid}").addSnapshotListener { snap, _ -> trySend(snap?.exists() == true) }
            awaitClose { reg.remove() }
        }
    }

    /**
     * The password is checked by the rules against its SHA-256; a wrong one is simply refused.
     * Tried exactly as typed (the admin's own password), then in the one-time key's form
     * (capitals and digits only, so dashes and case do not matter for that key).
     */
    suspend fun claimAdmin(password: String) {
        val me = ensureSignedIn()
        val attempts = listOf(password.trim(), normalizeAdminKey(password)).filter { it.isNotEmpty() }.distinct()
        var last: Exception? = null
        for (candidate in attempts) {
            try {
                db.document("admins/$me").set(mapOf("key" to candidate, "claimedAt" to now())).await()
                Log.i(TAG, "admin access granted")
                return
            } catch (e: Exception) { last = e }
        }
        val kind = last?.let { kindOf(it) } ?: GroupsException.Kind.UNKNOWN
        throw GroupsException(if (kind == GroupsException.Kind.DENIED) GroupsException.Kind.WRONG_ADMIN_KEY else kind, last)
    }

    /** Sets the admin password. Only its SHA-256 is stored; from now on the one-time key stops working. */
    suspend fun setAdminPassword(password: String) {
        ensureSignedIn()
        wrap { db.document("config/admin").set(mapOf("keySha256" to sha256Hex(password.trim()), "changedAt" to now())).await() }
        Log.i(TAG, "admin password changed")
    }

    // ---- private cloud backup (users/{uid}/backup/latest, owner only) --------------------------

    suspend fun saveBackup(data: String, bytes: Int) {
        val me = ensureSignedIn()
        wrap { db.document("users/$me/backup/latest").set(mapOf("data" to data, "updatedAt" to now(), "bytes" to bytes, "version" to 1)).await() }
    }

    /** The saved backup (data, saved at) or null when this account has none yet. */
    suspend fun loadBackup(): Pair<String, Long>? {
        val me = ensureSignedIn()
        val snap = wrap { db.document("users/$me/backup/latest").get().await() }
        val data = snap.getString("data") ?: return null
        return data to (snap.getLong("updatedAt") ?: 0L)
    }

    /** When the account's backup was saved, or null (no backup yet). Cheap: no data is parsed. */
    suspend fun backupSavedAt(): Long? {
        val me = ensureSignedIn()
        val snap = wrap { db.document("users/$me/backup/latest").get().await() }
        return if (snap.exists()) snap.getLong("updatedAt") ?: 0L else null
    }

    fun observeAllGroups(): Flow<List<Group>> = flow {
        ensureSignedIn()
        emitAll(snapshots(db.collection("groups").limit(1000)).flatMapLatest { qs ->
            flowOf(qs.documents.mapNotNull { parseGroup(it) }.sortedBy { it.name.lowercase() })
        })
    }

    fun observeUsers(): Flow<List<UserProfile>> = flow {
        ensureSignedIn()
        emitAll(snapshots(db.collection("users").limit(2000)).flatMapLatest { qs ->
            flowOf(qs.documents.map {
                UserProfile(it.id, it.getString("nickname") ?: "?", (it.getLong("ownedGroups") ?: 0L).toInt(), it.getLong("createdAt") ?: 0L)
            }.sortedBy { it.nickname.lowercase() })
        })
    }

    suspend fun groupIdsOf(user: String): List<String> =
        wrap { db.collection("users/$user/memberships").get().await() }.documents.map { it.id }

    suspend fun adminUpdateGroup(gid: String, name: String, description: String, hidden: Boolean) {
        ensureSignedIn()
        wrap { db.document("groups/$gid").update(mapOf("name" to name.trim().take(40), "description" to description.trim().take(120), "hidden" to hidden)).await() }
    }

    /** Removes a member everywhere, their own list of groups included. The owner must be changed first. */
    suspend fun adminRemoveMember(gid: String, member: String) {
        ensureSignedIn()
        val group = wrap { db.document("groups/$gid").get().await() }
        if (group.getString("ownerUid") == member) throw GroupsException(GroupsException.Kind.REMOVE_OWNER_FIRST)
        val isMember = wrap { db.document("groups/$gid/members/$member").get().await() }.exists()
        val b = db.batch()
        b.delete(db.document("groups/$gid/members/$member"))
        b.delete(db.document("groups/$gid/contrib/$member"))
        b.delete(db.document("users/$member/memberships/$gid"))
        if (isMember && group.exists()) b.update(db.document("groups/$gid"), "memberCount", FieldValue.increment(-1))
        wrap { b.commit().await() }
    }

    suspend fun adminMakeOwner(gid: String, newOwner: String) {
        ensureSignedIn()
        val old = wrap { db.document("groups/$gid").get().await() }.getString("ownerUid")
        if (old == newOwner) return
        val b = db.batch()
        b.update(db.document("groups/$gid"), "ownerUid", newOwner)
        if (old != null && profileExists(old)) b.update(db.document("users/$old"), "ownedGroups", FieldValue.increment(-1))
        if (profileExists(newOwner)) b.update(db.document("users/$newOwner"), "ownedGroups", FieldValue.increment(1))
        wrap { b.commit().await() }
    }

    /** Deletes a group with everything in it, in batches (a group can have up to 200 members). */
    suspend fun adminDeleteGroup(gid: String) {
        ensureSignedIn()
        val group = wrap { db.document("groups/$gid").get().await() }
        val members = wrap { db.collection("groups/$gid/members").get().await() }.documents.map { it.id }
        val contrib = wrap { db.collection("groups/$gid/contrib").get().await() }.documents.map { it.id }
        val code = wrap { db.document("groups/$gid/private/invite").get().await() }.getString("code")
        val ops = members.flatMap { m -> listOf("groups/$gid/members/$m", "users/$m/memberships/$gid") } +
            contrib.map { "groups/$gid/contrib/$it" }
        ops.chunked(400).forEach { chunk ->
            val b = db.batch()
            chunk.forEach { b.delete(db.document(it)) }
            wrap { b.commit().await() }
        }
        val owner = group.getString("ownerUid")
        val b = db.batch()
        b.delete(db.document("groups/$gid/private/invite"))
        if (code != null) b.delete(db.document("invites/$code"))
        b.delete(db.document("groups/$gid"))
        if (group.exists() && owner != null && profileExists(owner)) b.update(db.document("users/$owner"), "ownedGroups", FieldValue.increment(-1))
        wrap { b.commit().await() }
        Log.i(TAG, "admin deleted group $gid")
    }

    suspend fun adminRenameUser(user: String, nickname: String) {
        ensureSignedIn()
        val name = nickname.trim().take(24)
        if (name.isEmpty()) return
        wrap { db.document("users/$user").update("nickname", name).await() }
        for (gid in groupIdsOf(user)) runCatching { db.document("groups/$gid/members/$user").update("nickname", name).await() }
    }

    /** Takes a user out of every group (deleting the groups they own) and deletes their profile. */
    suspend fun adminDeleteUserData(user: String) {
        ensureSignedIn()
        for (gid in groupIdsOf(user)) {
            val owner = wrap { db.document("groups/$gid").get().await() }.getString("ownerUid")
            if (owner == user) adminDeleteGroup(gid) else adminRemoveMember(gid, user)
        }
        wrap { db.document("users/$user").delete().await() }
        Log.i(TAG, "admin deleted user data")
    }

    private suspend fun profileExists(user: String): Boolean = wrap { db.document("users/$user").get().await() }.exists()

    // ---- observation -----------------------------------------------------------------------

    fun observeMyGroups(): Flow<List<Group>> = flow {
        val me = ensureSignedIn()
        emitAll(snapshots(db.collection("users/$me/memberships")).flatMapLatest { ids ->
            val gids = ids.documents.map { it.id }
            if (gids.isEmpty()) flowOf(emptyList())
            else combine(gids.map { gid -> docSnapshots(gid) }) { arr -> arr.filterNotNull().sortedBy { it.name } }
        })
    }

    private fun docSnapshots(gid: String): Flow<Group?> = callbackFlow {
        val reg: ListenerRegistration = db.document("groups/$gid").addSnapshotListener { snap, err ->
            if (err != null) { Log.w(TAG, "group $gid: ${err.message}"); trySend(null); return@addSnapshotListener }
            trySend(snap?.let { parseGroup(it) })
        }
        awaitClose { reg.remove() }
    }

    fun observePublicGroups(): Flow<List<Group>> = flow {
        ensureSignedIn()
        emitAll(snapshots(db.collection("groups").whereEqualTo("hidden", false).limit(200)).flatMapLatest { qs ->
            flowOf(qs.documents.mapNotNull { parseGroup(it) })
        })
    }

    fun observeMembers(gid: String): Flow<List<Member>> = flow {
        ensureSignedIn()
        emitAll(snapshots(db.collection("groups/$gid/members")).flatMapLatest { qs ->
            flowOf(qs.documents.map { Member(it.id, it.getString("nickname") ?: "?", it.getLong("joinedAt") ?: 0L) })
        })
    }

    fun observeStats(gid: String): Flow<List<MemberStats>> = flow {
        ensureSignedIn()
        emitAll(snapshots(db.collection("groups/$gid/contrib")).flatMapLatest { qs ->
            flowOf(qs.documents.mapNotNull { parseStats(it) })
        })
    }

    /** The group's totals for its most recent days (newest first), for the 7-day chart. */
    fun observeGroupDays(gid: String): Flow<List<GroupDay>> = flow {
        ensureSignedIn()
        val q = db.collection("groups/$gid/days").orderBy(com.google.firebase.firestore.FieldPath.documentId(), Query.Direction.DESCENDING).limit(7)
        emitAll(snapshots(q).flatMapLatest { qs ->
            flowOf(qs.documents.map { GroupDay(it.id, it.getLong("steps") ?: 0L, (it.getLong("goalMet") ?: 0L).toInt(), (it.getLong("members") ?: 0L).toInt()) })
        })
    }

    private fun snapshots(q: Query): Flow<com.google.firebase.firestore.QuerySnapshot> = callbackFlow {
        val reg = q.addSnapshotListener { snap, err ->
            if (err != null) { close(GroupsException(kindOf(err), err)); return@addSnapshotListener }
            if (snap != null) trySend(snap)
        }
        awaitClose { reg.remove() }
    }

    // ---- publishing my numbers ---------------------------------------------------------------

    /** Writes my stats to every group I am in and refreshes each group's public summary. */
    suspend fun publish(stats: MemberStats, today: LocalDate) {
        val me = ensureSignedIn()
        val data = mapOf(
            "streak" to stats.streak,
            "today" to periodMap(stats.today), "week" to periodMap(stats.week), "month" to periodMap(stats.month),
            "updatedAt" to now(),
        )
        for (gid in myGroupIds()) {
            runCatching {
                db.document("groups/$gid/contrib/$me").set(data).await()
                val group = db.document("groups/$gid").get().await()
                val memberCount = (group.getLong("memberCount") ?: 1L).toInt()
                val all = db.collection("groups/$gid/contrib").get().await().documents.mapNotNull { parseStats(it) }
                val summary = Ranking.summary(all, memberCount, today)
                db.document("groups/$gid").update("summary", mapOf(
                    "date" to summary.date, "today" to totalsMap(summary.today), "week" to totalsMap(summary.week),
                    "month" to totalsMap(summary.month), "updatedAt" to now(),
                )).await()
                // Today's totals, kept per day for the group's 7-day chart.
                db.document("groups/$gid/days/${summary.date}").set(totalsMap(summary.today)).await()
            }.onFailure { Log.w(TAG, "publish to $gid failed: ${it.message}") }
        }
    }

    // ---- parsing -----------------------------------------------------------------------------

    private fun parseGroup(d: DocumentSnapshot): Group? {
        if (!d.exists()) return null
        @Suppress("UNCHECKED_CAST")
        val s = d.get("summary") as? Map<String, Any?>
        return Group(
            id = d.id,
            name = d.getString("name") ?: return null,
            description = d.getString("description") ?: "",
            ownerUid = d.getString("ownerUid") ?: "",
            memberCount = (d.getLong("memberCount") ?: 0L).toInt(),
            hidden = d.getBoolean("hidden") ?: false,
            summary = s?.let { parseSummary(it) },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSummary(m: Map<String, Any?>): GroupSummary? {
        fun totals(x: Any?): GroupTotals {
            val t = x as? Map<String, Any?> ?: return GroupTotals.EMPTY
            return GroupTotals(
                steps = (t["steps"] as? Number)?.toLong() ?: 0L,
                members = (t["members"] as? Number)?.toInt() ?: 0,
                goalMet = (t["goalMet"] as? Number)?.toInt() ?: 0,
                goalRatio = (t["goalRatio"] as? Number)?.toDouble() ?: 0.0,
            )
        }
        val date = m["date"] as? String ?: return null
        return GroupSummary(date, totals(m["today"]), totals(m["week"]), totals(m["month"]))
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseStats(d: DocumentSnapshot): MemberStats? {
        fun period(x: Any?): PeriodStats {
            val p = x as? Map<String, Any?> ?: return PeriodStats.EMPTY
            return PeriodStats(
                key = p["key"] as? String ?: "",
                steps = (p["steps"] as? Number)?.toLong() ?: 0L,
                goalDays = (p["goalDays"] as? Number)?.toInt() ?: 0,
                longestMs = (p["longestMs"] as? Number)?.toLong() ?: 0L,
            )
        }
        if (!d.exists()) return null
        return MemberStats(d.id, (d.getLong("streak") ?: 0L).toInt(), period(d.get("today")), period(d.get("week")), period(d.get("month")))
    }

    private fun periodMap(p: PeriodStats) = mapOf("key" to p.key, "steps" to p.steps, "goalDays" to p.goalDays, "longestMs" to p.longestMs)
    private fun totalsMap(t: GroupTotals) = mapOf("steps" to t.steps, "members" to t.members, "goalMet" to t.goalMet, "goalRatio" to t.goalRatio)
    private fun now() = System.currentTimeMillis()

    private suspend fun <T> wrap(block: suspend () -> T): T = try { block() } catch (e: GroupsException) { throw e } catch (e: Exception) { throw GroupsException(kindOf(e), e) }

    private fun kindOf(e: Exception): GroupsException.Kind = when {
        e is com.google.firebase.firestore.FirebaseFirestoreException && e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED -> GroupsException.Kind.DENIED
        e is com.google.firebase.firestore.FirebaseFirestoreException && e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE -> GroupsException.Kind.OFFLINE
        e is com.google.firebase.FirebaseNetworkException -> GroupsException.Kind.OFFLINE
        e is com.google.firebase.FirebaseTooManyRequestsException -> GroupsException.Kind.TOO_MANY_ATTEMPTS
        e is com.google.firebase.auth.FirebaseAuthException -> when (e.errorCode) {
            "ERROR_EMAIL_ALREADY_IN_USE", "ERROR_CREDENTIAL_ALREADY_IN_USE", "ERROR_PROVIDER_ALREADY_LINKED" -> GroupsException.Kind.EMAIL_IN_USE
            "ERROR_INVALID_EMAIL" -> GroupsException.Kind.INVALID_EMAIL
            "ERROR_WEAK_PASSWORD" -> GroupsException.Kind.WEAK_PASSWORD
            "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS", "ERROR_USER_DISABLED" -> GroupsException.Kind.WRONG_CREDENTIALS
            "ERROR_OPERATION_NOT_ALLOWED" -> GroupsException.Kind.SIGN_IN_DISABLED
            "ERROR_TOO_MANY_REQUESTS" -> GroupsException.Kind.TOO_MANY_ATTEMPTS
            else -> GroupsException.Kind.UNKNOWN
        }
        else -> GroupsException.Kind.UNKNOWN
    }

    companion object {
        private const val TAG = "Groups"
        const val PLACEHOLDER_PROJECT = "khatwa-placeholder"

        fun sha256Hex(text: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** Upper-case letters and digits only: dashes, spaces and case do not matter when typing. */
        fun normalizeAdminKey(key: String): String = key.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

        /** Set before first use to talk to the Firebase Emulator Suite (tests / CI). */
        @Volatile var emulatorHost: String? = null
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val e = task.exception
        if (e != null) cont.resumeWithException(e)
        else if (task.isCanceled) cont.cancel()
        else cont.resume(task.result)
    }
}

private suspend fun <T> kotlinx.coroutines.flow.FlowCollector<T>.emitAll(flow: Flow<T>) = flow.collect { emit(it) }
