package com.jarvis.assistant.util

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Executes a small, explicit allow-list of phone actions from the user's
 * transcribed voice command. It deliberately does not execute arbitrary code.
 */
object MobileCommandExecutor {
    private const val TAG = "MobileCommandExecutor"

    data class Result(val handled: Boolean, val spokenReply: String? = null)

    fun tryExecute(context: Context, rawCommand: String): Result {
        val command = rawCommand.trim()
        if (command.isBlank()) return Result(false)
        val lower = command.lowercase(Locale.ROOT)

        // Common app-opening commands.
        val openTarget = extractAfter(lower, listOf("open ", "launch ", "start ", "kholo ", "khol do ", "chalao ", "chala do "))
        if (openTarget != null && openTarget.isNotBlank()) {
            val result = openAppByLabel(context, openTarget)
            if (result) return Result(true, "Opening $openTarget.")
        }

        // Direct app names without the word "open".
        val directApps = listOf("whatsapp", "youtube", "chrome", "instagram", "facebook", "telegram", "spotify", "maps", "google maps", "camera", "settings", "gallery")
        directApps.firstOrNull { lower == it || lower == "$it kholo" || lower == "$it khol do" }?.let {
            if (openAppByLabel(context, it)) return Result(true, "Opening $it.")
        }

        // Phone call: "call Mom", "mom ko call karo", "call 987..."
        if (containsAny(lower, "call ", "call karo", "phone karo", "phone kar", "ko call", "ko phone")) {
            val target = extractCallTarget(lower)
            if (target != null) {
                val number = normalizeNumber(target) ?: lookupContactNumber(context, target)
                if (number != null) {
                    return if (hasPermission(context, Manifest.permission.CALL_PHONE)) {
                        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        Result(true, "Calling $target.")
                    } else {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        Result(true, "I opened the dialer for $target.")
                    }
                }
            }
        }

        // SMS composer (does not silently send SMS).
        if (containsAny(lower, "sms", "text message", "message karo", "sms karo")) {
            val target = extractMessageTarget(lower)
            val body = extractMessageBody(command)
            val number = target?.let { normalizeNumber(it) ?: lookupContactNumber(context, it) }
            if (number != null && !body.isNullOrBlank()) {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
                    .putExtra("sms_body", body)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return Result(true, "I opened the SMS with your message ready.")
            }
        }

        // WhatsApp: explicit voice command can open the target chat and, when requested,
        // use the enabled Accessibility service to press Send automatically.
        if (lower.contains("whatsapp")) {
            val target = extractWhatsAppTarget(lower)
            val body = extractMessageBody(command)
            if (target != null) {
                val number = normalizeNumber(target) ?: lookupContactNumber(context, target)
                if (number != null) {
                    return openWhatsApp(context, number, body)
                }
            }
            if (body.isNullOrBlank()) {
                if (openPackage(context, "com.whatsapp")) return Result(true, "Opening WhatsApp.")
            }
        }

        // YouTube: "play <song> on YouTube" opens the YouTube search page and, if
        // Accessibility automation is enabled, selects the first video result.
        if (lower.contains("youtube") && containsAny(lower, "play ", "chalao ", "bajao ", "चलाओ", "बजाओ")) {
            val query = extractYouTubeQuery(command)
            if (!query.isNullOrBlank()) {
                return openYouTubeSearch(context, query)
            }
        }

        // Useful system shortcuts.
        if (containsAny(lower, "settings kholo", "open settings", "settings khol do")) {
            val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return Result(true, "Opening Settings.")
        }
        if (containsAny(lower, "home screen", "go home", "ghar screen")) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return Result(true, "Going home.")
        }

        return Result(false)
    }

    private fun openWhatsApp(context: Context, number: String, body: String?): Result {
        val digits = number.filter { it.isDigit() || it == '+' }.removePrefix("+")
        val encoded = if (!body.isNullOrBlank()) URLEncoder.encode(body, StandardCharsets.UTF_8.toString()) else ""
        val uri = Uri.parse("https://wa.me/$digits" + if (encoded.isNotEmpty()) "?text=$encoded" else "")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isPackageInstalled(context, "com.whatsapp")) intent.setPackage("com.whatsapp")
        return try {
            context.startActivity(intent)
            if (!body.isNullOrBlank()) {
                val automation = JarvisAccessibilityService.instance
                if (automation != null) {
                    automation.automateWhatsAppSend(body)
                    Result(true, "Sending the WhatsApp message to the contact.")
                } else {
                    Result(true, "WhatsApp is open. Enable JARVIS Accessibility for automatic Send.")
                }
            } else {
                Result(true, "Opening the WhatsApp chat.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open WhatsApp", e)
            Result(false)
        }
    }

    private fun openYouTubeSearch(context: Context, query: String): Result {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.toString())
        val uri = Uri.parse("https://www.youtube.com/results?search_query=$encoded")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isPackageInstalled(context, "com.google.android.youtube")) intent.setPackage("com.google.android.youtube")
        return try {
            context.startActivity(intent)
            JarvisAccessibilityService.instance?.automateYouTubeFirstResult(query)
            Result(true, "Playing the first YouTube result for $query.")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open YouTube search", e)
            Result(false)
        }
    }

    private fun openAppByLabel(context: Context, spoken: String): Boolean {
        val wanted = spoken.trim().lowercase(Locale.ROOT)
        val aliases = mapOf(
            "google maps" to listOf("maps", "google maps"),
            "camera" to listOf("camera"),
            "gallery" to listOf("gallery", "photos", "google photos"),
            "chrome" to listOf("chrome", "google chrome"),
            "settings" to listOf("settings"),
            "whatsapp" to listOf("whatsapp"),
            "youtube" to listOf("youtube"),
            "instagram" to listOf("instagram"),
            "facebook" to listOf("facebook"),
            "telegram" to listOf("telegram"),
            "spotify" to listOf("spotify")
        )
        val candidates = aliases[wanted] ?: listOf(wanted)
        val pm = context.packageManager
        val activities = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val match = activities.firstOrNull { info ->
            val label = info.loadLabel(pm).toString().lowercase(Locale.ROOT)
            candidates.any { label == it || label.contains(it) }
        }
        return match?.activityInfo?.packageName?.let { openPackage(context, it) } ?: false
    }

    private fun openPackage(context: Context, packageName: String): Boolean {
        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to launch $packageName", e)
            false
        }
    }

    private fun lookupContactNumber(context: Context, spokenName: String): String? {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return null
        val name = spokenName.trim()
            .removeSuffix("ko")
            .removeSuffix("ko call karo")
            .removeSuffix("call karo")
            .trim()
        if (name.isBlank()) return null
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$name%")
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, selection, args,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup failed", e)
            null
        }
    }

    private fun extractCallTarget(text: String): String? {
        val cleaned = text
            .replace("please", "")
            .replace("karo", "")
            .replace("kar do", "")
            .replace("lagao", "")
            .replace("laga do", "")
            .trim()
        val patterns = listOf("call ", "ko call", "ko phone", "phone ")
        for (p in patterns) {
            val idx = cleaned.indexOf(p)
            if (idx >= 0) {
                val before = cleaned.substring(0, idx).trim()
                val after = cleaned.substring(idx + p.length).trim()
                if (before.isNotBlank() && p.startsWith("ko")) return before
                if (after.isNotBlank()) return after
            }
        }
        return cleaned.removePrefix("call ").trim().ifBlank { null }
    }

    private fun extractWhatsAppTarget(text: String): String? {
        val without = text.replace("whatsapp", "").replace("message", "").trim()
        val number = Regex("\\+?[0-9][0-9 ()-]{7,}").find(without)?.value
        if (number != null) return number
        val toIdx = without.indexOf("to ")
        if (toIdx >= 0) return without.substring(toIdx + 3).substringBefore(":").trim()
        val koIdx = without.indexOf(" ko ")
        if (koIdx >= 0) return without.substring(0, koIdx).trim()
        return without.substringBefore(":").trim().ifBlank { null }
    }

    private fun extractMessageTarget(text: String): String? {
        val cleaned = text.replace("sms", "").replace("message karo", "").trim()
        Regex("\\+?[0-9][0-9 ()-]{7,}").find(cleaned)?.value?.let { return it }
        val toIdx = cleaned.indexOf("to ")
        if (toIdx >= 0) return cleaned.substring(toIdx + 3).substringBefore(":").trim()
        val koIdx = cleaned.indexOf(" ko ")
        if (koIdx >= 0) return cleaned.substring(0, koIdx).trim()
        return cleaned.substringBefore(":").trim().ifBlank { null }
    }

    private fun extractMessageBody(text: String): String? {
        val colon = text.indexOf(':')
        if (colon >= 0 && colon < text.lastIndex) return text.substring(colon + 1).trim()
        val markers = listOf("message bolo ", "message hai ", "message: ", "msg ", "likho ", "send ")
        for (m in markers) {
            val i = text.lowercase(Locale.ROOT).indexOf(m)
            if (i >= 0 && i + m.length < text.length) return text.substring(i + m.length).trim()
        }
        return null
    }

    private fun extractYouTubeQuery(text: String): String? {
        var q = text.trim()
        val patterns = listOf(
            "play ", "play a song ", "play song ", "youtube pe ",
            "youtube par ", "youtube par gaana ", "youtube pe gaana ",
            "chalao ", "bajao "
        )
        for (p in patterns) {
            val i = q.lowercase(Locale.ROOT).indexOf(p)
            if (i >= 0) { q = q.substring(i + p.length); break }
        }
        q = q.replace(Regex("\\b(on|in|using)\\s+youtube\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\byoutube\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(play|song|please|a)\\b", RegexOption.IGNORE_CASE), " ")
            .trim()
        return q.ifBlank { null }
    }

    private fun normalizeNumber(value: String): String? {
        val number = value.trim()
        if (!number.any(Char::isDigit)) return null
        val normalized = PhoneNumberUtils.normalizeNumber(number)
        return normalized.takeIf { it.count(Char::isDigit) >= 7 }
    }

    private fun extractAfter(text: String, prefixes: List<String>): String? {
        for (p in prefixes) {
            val i = text.indexOf(p)
            if (i >= 0) return text.substring(i + p.length).trim().removeSuffix("please").trim()
        }
        return null
    }

    private fun containsAny(text: String, vararg values: String): Boolean = values.any { text.contains(it) }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Exception) { false }
}
