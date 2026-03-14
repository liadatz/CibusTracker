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
            if (!body.contains(AMOUNT_PATTERN)) return null
            val regex = Regex("₪([0-9]+(?:\\.[0-9]{1,2})?)")
            val match = regex.find(body) ?: return null
            match.groupValues[1].toDoubleOrNull()
        } catch (e: Exception) {
            Log.e("GmailService", "Error parsing amount", e)
            null
        }
    }

    private fun parseBusinessName(message: Message): String? {
        return try {
            val body = getEmailBody(message)

            // Pattern 1: "קיבלנו את הזמנת השובר שלך מ<NAME>"
            val pattern1 = Regex("קיבלנו את הזמנת השובר שלך מ(.+?)(?:\\s*[-–]\\s*.+)?$", RegexOption.MULTILINE)
            val match1 = pattern1.find(body)
            if (match1 != null) {
                return match1.groupValues[1].trim()
            }

            // Pattern 2: "החיוב שלך ב<NAME> - <CITY> התקבל ואושר"
            val pattern2 = Regex("החיוב שלך ב(.+?)(?:\\s*[-–]\\s*.+?)? התקבל ואושר")
            val match2 = pattern2.find(body)
            if (match2 != null) {
                return match2.groupValues[1].trim()
            }

            null
        } catch (e: Exception) {
            Log.e("GmailService", "Error parsing business name", e)
            null
        }
    }

    private fun getEmailBody(message: Message): String {
        val parts = message.payload?.parts
        return if (parts != null) {
            val textPart = parts.firstOrNull { it.mimeType == "text/plain" }
                ?: parts.firstOrNull { it.mimeType == "text/html" }
            val data = textPart?.body?.data ?: return ""
            String(Base64.decode(data, Base64.URL_SAFE))
        } else {
            val data = message.payload?.body?.data ?: return ""
            String(Base64.decode(data, Base64.URL_SAFE))
        }
    }
}

data class SpendMeta(
    val timestamp: Long,
    val businessName: String?
)