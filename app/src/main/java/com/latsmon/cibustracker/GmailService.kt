package com.latsmon.cibustracker

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import com.google.api.services.gmail.model.Message

object GmailService {

    private val SENDERS = listOf(
        "noreply@notifications.pluxee.co.il",
        "liad.atsmon@gmail.com"
    )

    private const val AMOUNT_PATTERN = "החיוב בסיבוס שלך"

    // Returns list of Triple(amount, emailId, emailTimestamp)
    fun fetchNewSpends(
        context: Context,
        accountName: String,
        lastCheckedId: String?
    ): List<Triple<Double, String, SpendMeta>> {
        return try {
            val credential = getCredential(context, accountName)
            val service = Gmail.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("CibusTracker").build()

            val query = SENDERS.joinToString(" OR ") { "from:$it" }
            val listResponse = service.users().messages().list("me")
                .setQ(query)
                .setMaxResults(20)
                .execute()

            val messages = listResponse.messages ?: return emptyList()
            val results = mutableListOf<Triple<Double, String, SpendMeta>>()

            for (msgRef in messages) {
                if (lastCheckedId != null && msgRef.id == lastCheckedId) break

                val message = service.users().messages().get("me", msgRef.id)
                    .setFormat("full")
                    .execute()

                val amount = parseAmount(message)
                if (amount != null) {
                    val timestamp = message.internalDate ?: System.currentTimeMillis()
                    val businessName = parseBusinessName(message)
                    results.add(Triple(amount, msgRef.id, SpendMeta(timestamp, businessName)))
                }
            }

            results
        } catch (e: Exception) {
            Log.e("GmailService", "Error fetching emails", e)
            emptyList()
        }
    }

    private fun getCredential(context: Context, accountName: String): GoogleAccountCredential {
        return GoogleAccountCredential.usingOAuth2(
            context,
            listOf(GmailScopes.GMAIL_READONLY)
        ).apply {
            selectedAccountName = accountName
        }
    }

    private fun parseAmount(message: Message): Double? {
        return try {
            val body = getEmailBody(message)
                .replace("\r\n", "\n")
                .replace("\r", "\n")

            // Primary: find amount after "החיוב בסיבוס שלך" — amount may be on next line
            val primary = Regex("החיוב[^\n]*סיבוס[^\n]*שלך[^₪\u20aa\n]*\n?[^₪\u20aa\n]*[₪\u20aa]([0-9]+(?:\\.[0-9]{1,2})?)")
            val match1 = primary.find(body)
            if (match1 != null) return match1.groupValues[1].toDoubleOrNull()

            // Fallback: find amount after "סכום העסקה" — amount may be on next line
            val fallback = Regex("סכום העסקה[^₪\u20aa\n]*\n?[^₪\u20aa\n]*[₪\u20aa]([0-9]+(?:\\.[0-9]{1,2})?)")
            val match2 = fallback.find(body)
            match2?.groupValues?.get(1)?.toDoubleOrNull()

        } catch (e: Exception) {
            Log.e("GmailService", "Error parsing amount", e)
            null
        }
    }
    private fun extractTextFromPart(
        part: com.google.api.services.gmail.model.MessagePart?,
        preferPlain: Boolean = true
    ): String? {
        if (part == null) return null
        val mimeType = part.mimeType ?: ""

        // Leaf node
        if (mimeType == "text/plain" || mimeType == "text/html") {
            val data = part.body?.data ?: ""
            if (data.isEmpty()) return null
            val raw = String(android.util.Base64.decode(data, android.util.Base64.URL_SAFE))
            return if (mimeType == "text/html")
                android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            else
                raw
        }

        val subParts = part.parts ?: return null

        // First pass: plain text only
        if (preferPlain) {
            for (subPart in subParts) {
                val result = extractTextFromPart(subPart, preferPlain = true)
                if (!result.isNullOrEmpty() && subPart.mimeType == "text/plain") return result
            }
            // Recurse into multipart children for plain text
            for (subPart in subParts) {
                if ((subPart.mimeType ?: "").startsWith("multipart")) {
                    val result = extractTextFromPart(subPart, preferPlain = true)
                    if (!result.isNullOrEmpty()) return result
                }
            }
        }

        // Second pass: accept any text
        for (subPart in subParts) {
            val result = extractTextFromPart(subPart, preferPlain = false)
            if (!result.isNullOrEmpty()) return result
        }

        return null
    }

    private fun parseBusinessName(message: Message): String? {
        return try {
            val body = getEmailBody(message)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\u00a0", " ") // replace non-breaking spaces

            // Pattern 1: "קיבלנו את הזמנת השובר שלך מ<NAME>"
            val pattern1 = Regex("קיבלנו את הזמנת השובר שלך מ(.+?)(?:\\s*[-–]\\s*.+)?$", RegexOption.MULTILINE)
            val match1 = pattern1.find(body)
            if (match1 != null) return match1.groupValues[1].trim()

            // Pattern 2: "החיוב שלך ב<NAME>" — optional space after ב, \s before התקבל
            val pattern2 = Regex("החיוב שלך ב\\s*(.+?)(?:\\s*[-–]\\s*.+?)?\\s+התקבל ואושר")
            val match2 = pattern2.find(body)
            if (match2 != null) return match2.groupValues[1].trim()

            null
        } catch (e: Exception) {
            Log.e("GmailService", "Error parsing business name", e)
            null
        }
    }

    private fun stripHtml(input: String): String {
        return android.text.Html.fromHtml(input, android.text.Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .trim()
    }
    private fun getEmailBody(message: Message): String {
        return extractTextFromPart(message.payload, preferPlain = true) ?: ""
    }

}

data class SpendMeta(
    val timestamp: Long,
    val businessName: String?
)