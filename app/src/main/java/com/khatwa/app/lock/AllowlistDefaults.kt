package com.khatwa.app.lock

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

/** Resolves package names for the default allowlist and the always-allowed system set. */
object AllowlistDefaults {
    private val YOUTUBE = listOf("com.google.android.youtube", "app.revanced.android.youtube")

    /** Phone, contacts, messages, YouTube: the spec's default allowlist. */
    fun compute(context: Context): Set<String> {
        val out = LinkedHashSet<String>()
        dialer(context)?.let(out::add)
        contacts(context).let(out::addAll)
        sms(context)?.let(out::add)
        YOUTUBE.filter { installed(context, it) }.let(out::addAll)
        return out
    }

    /** Never blockable: system UI, launcher, dialer / in-call UI, emergency, input methods, ourselves. */
    fun alwaysAllowed(context: Context): Set<String> {
        val out = LinkedHashSet<String>()
        out += context.packageName
        out += "com.android.systemui"
        out += "android"
        out += "com.android.emergency"
        out += "com.android.phone"
        out += "com.android.server.telecom"
        out += "com.samsung.android.incallui"
        out += "com.android.incallui"
        out += "com.google.android.dialer"
        out += "com.samsung.android.dialer"
        out += "com.android.dialer"
        dialer(context)?.let(out::add)
        launchers(context).let(out::addAll)
        inputMethods(context).let(out::addAll)
        return out
    }

    fun settingsPackages(): Set<String> = setOf("com.android.settings", "com.samsung.android.settings")

    fun dialer(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
            if (rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
            } else null
        } else (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
    }.getOrNull()

    fun sms(context: Context): String? = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()

    fun contacts(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_VIEW).setData(ContactsContract.Contacts.CONTENT_URI)
        return resolveAll(context, intent)
    }

    fun launchers(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val all = resolveAll(context, intent)
        val def = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return if (def != null) all + def else all
    }

    fun inputMethods(context: Context): Set<String> {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return runCatching { imm.enabledInputMethodList.map { it.packageName }.toSet() }.getOrDefault(emptySet())
    }

    private fun resolveAll(context: Context, intent: Intent): Set<String> =
        runCatching {
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}
