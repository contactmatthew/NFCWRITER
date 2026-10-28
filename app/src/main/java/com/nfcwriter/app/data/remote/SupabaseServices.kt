package com.nfcwriter.app.data.remote

import android.content.Context
import com.nfcwriter.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.Realtime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

object SupabaseServices {
    val configured: Boolean get() = BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    val client: SupabaseClient? by lazy {
        if (!configured) null else createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) {
            install(Auth) {
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                autoSaveToStorage = true
            }
            install(Postgrest)
            install(Realtime)
        }
    }
}

private val managedUrlsJson = Json { ignoreUnknownKeys = true }

@Serializable
data class AccessSession(
    @SerialName("key_id") val keyId: String,
    val role: String,
    val label: String,
)

@Serializable
private data class AccessAttempt(
    @SerialName("key_id") val keyId: String? = null,
    val role: String? = null,
    val label: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int? = null,
)

@Serializable
data class CreatedAccessKey(
    @SerialName("key_id") val keyId: String,
    @SerialName("access_key") val accessKey: String,
    val label: String,
    val role: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class ManagedAccessKey(
    @SerialName("key_id") val keyId: String,
    val label: String,
    @SerialName("key_prefix") val keyPrefix: String,
    val role: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("is_current") val isCurrent: Boolean,
    @SerialName("can_manage") val canManage: Boolean,
)

@Serializable
data class RotatedAccessKey(
    @SerialName("key_id") val keyId: String,
    @SerialName("access_key") val accessKey: String,
)

@Serializable
data class ProvisionedTapTag(
    @SerialName("nfc_id") val nfcId: String,
    @SerialName("access_code") val accessCode: String,
    @SerialName("permanent_url") val permanentUrl: String,
)

@Serializable
data class NextMessageCard(
    @SerialName("next_url") val nextUrl: String,
    @SerialName("current_owner_code") val currentOwnerCode: String? = null,
)

@Serializable
data class WriterManagedUrl(
    @SerialName("nfc_id") val nfcId: String,
    @SerialName("link_id") val linkId: String,
    val name: String,
    @SerialName("public_code") val publicCode: String,
    @SerialName("permanent_path") val permanentPath: String,
    @SerialName("page_title") val pageTitle: String,
    val status: String,
    @SerialName("physical_uid") val physicalUid: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("source_type") val sourceType: String = "uncategorized",
    @SerialName("recipient_name") val recipientName: String? = null,
    val linkStatus: String = "active",
)

internal fun writerUrlReadyToWrite(url: WriterManagedUrl): Boolean =
    url.linkStatus == "active" && url.status != "retired" && url.status != "disabled" &&
        (url.sourceType != "messages" || url.status == "provisioning")

internal fun writerUrlUnavailableReason(url: WriterManagedUrl): String = when {
    url.status == "retired" -> "This URL was retired after a card rewrite. Reactivate it in the Dashboard to use the same URL again."
    url.linkStatus != "active" || url.status == "disabled" -> "This URL is disabled. Enable it in the Dashboard before writing."
    else -> "This message URL is already on a card. Generate the next URL below."
}

@Serializable
data class AdminManagedPage(val title: String, val status: String, @SerialName("page_type") val pageType: String)

@Serializable
data class AdminManagedNfc(val id: String, val status: String, @SerialName("physical_uid") val physicalUid: String? = null)

@Serializable
data class AdminManagedLink(
    val id: String, val name: String, val code: String, val status: String,
    @SerialName("created_at") val createdAt: String,
    val page: AdminManagedPage? = null,
    @SerialName("managedNfc") val managedNfc: AdminManagedNfc? = null,
)

@Serializable
data class AdminManagedUrls(val links: List<AdminManagedLink> = emptyList())

internal fun mergeWriterManagedUrls(
    writerUrls: List<WriterManagedUrl>, adminLinks: List<AdminManagedLink>,
): List<WriterManagedUrl> {
    val writerByLink = writerUrls.associateBy { it.linkId }
    return adminLinks.map { link ->
        writerByLink[link.id]?.copy(linkStatus = link.status) ?: WriterManagedUrl(
            nfcId = link.managedNfc?.id.orEmpty(), linkId = link.id, name = link.name,
            publicCode = link.code, permanentPath = "/t/${link.code}",
            pageTitle = link.page?.title.orEmpty(), status = link.managedNfc?.status ?: "disabled",
            physicalUid = link.managedNfc?.physicalUid, createdAt = link.createdAt,
            sourceType = when (link.page?.pageType) {
                "invitation" -> "invitations"
                "business" -> "business"
                "menu" -> "menus"
                "product" -> "products"
                "pet" -> "pets"
                "memorial" -> "memorials"
                "personal", "sweet_message" -> "personal"
                else -> "pages"
            },
            linkStatus = link.status,
        )
    }
}

internal fun filterWriterManagedUrls(
    urls: List<WriterManagedUrl>, sourceType: String?, query: String,
): List<WriterManagedUrl> {
    val search = query.trim()
    return urls.filter { item ->
        (sourceType == null || sourceType == "all" || item.sourceType == sourceType) && (search.isEmpty() ||
            listOfNotNull(item.name, item.pageTitle, item.recipientName, item.publicCode)
                .any { it.contains(search, ignoreCase = true) })
    }
}

internal fun resolveWriterUrl(siteOrigin: String, permanentPath: String): String {
    val productionOrigin = "https://taptagdirect.tech"
    val origin = siteOrigin.trim().trimEnd('/').ifBlank { productionOrigin }
    val path = permanentPath.trim()
    if (path.startsWith("http://") || path.startsWith("https://")) {
        val parsed = runCatching { URI(path) }.getOrNull()
        val localHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")
        if (parsed?.host?.lowercase() in localHosts) {
            val suffix = buildString {
                append(parsed?.rawPath.orEmpty().ifBlank { "/" })
                parsed?.rawQuery?.let { append('?').append(it) }
                parsed?.rawFragment?.let { append('#').append(it) }
            }
            return origin + "/" + suffix.trimStart('/')
        }
        return path
    }
    return origin + "/" + path.trimStart('/')
}

internal fun accessErrorMessage(error: Throwable): String {
    val raw = error.message.orEmpty().lowercase()
    return when {
        "invalid or inactive access key" in raw -> "That access key is invalid, expired, or inactive."
        "anonymous_provider_disabled" in raw || "anonymous sign-ins are disabled" in raw ->
            "TapTag access is not enabled on this server yet. Ask the administrator to enable Anonymous Sign-Ins in Supabase Authentication."
        "network" in raw || "unable to resolve host" in raw -> "Cannot reach Supabase. Check your internet connection."
        "super-admin access is required" in raw -> "Super-admin access is required."
        "administrator access is required" in raw -> "Administrator access is required."
        "admins may create" in raw -> "Admins can create normal access keys only."
        "admins may manage" in raw -> "Admins can manage normal access keys only."
        "admins may edit" in raw -> "Admins can edit normal access keys only."
        "admins may rotate" in raw -> "Admins can rotate normal access keys only."
        "cannot manage the access key" in raw -> "You cannot change the key used by your current session."
        "only a super admin may edit" in raw -> "Only a super admin can edit their current key."
        "only a super admin may rotate" in raw -> "Only a super admin can rotate their current key."
        "revoked access key" in raw -> "A revoked key cannot be resumed."
        "one active super-admin" in raw -> "At least one active super-admin key must remain."
        "not a managed taptag url" in raw -> "Use the NFC activator URL from the greeting's Publish step. It must contain /t/."
        "managed taptag url not found" in raw -> "This NFC URL was not found in this Supabase project. Copy it again from the greeting's Publish step."
        "already assigned to another url" in raw -> "This NFC card is already assigned to another TapTag URL."
        "url is already assigned to another nfc card" in raw -> "That TapTag URL is already assigned to another NFC card."
        "managed taptag is unavailable" in raw -> "This managed TapTag URL is disabled or retired."
        else -> "Access could not be verified. Please try again."
    }
}

class AccessService(context: Context, private val client: SupabaseClient?) {
    private val preferences = context.getSharedPreferences("access_session", Context.MODE_PRIVATE)

    fun currentUserId(): String? = client?.auth?.currentUserOrNull()?.id
    fun isAuthenticated(): Boolean = preferences.getBoolean("authenticated", false)
    fun role(): String = preferences.getString("role", "user") ?: "user"
    fun label(): String = preferences.getString("label", "TapTag access") ?: "TapTag access"
    fun isSuperAdmin(): Boolean = isAuthenticated() && role() == "superadmin"
    fun isAccessManager(): Boolean = isAuthenticated() && role() in setOf("admin", "superadmin")

    suspend fun restoreSession(): Boolean {
        if (!isAuthenticated()) return false
        val supabase = client ?: return false
        return try {
            supabase.auth.awaitInitialization()
            if (supabase.auth.currentUserOrNull() == null) {
                preferences.edit().clear().apply()
                false
            } else {
                val session = supabase.postgrest.rpc("current_access_session").decodeSingleOrNull<AccessSession>()
                if (session == null) {
                    preferences.edit().clear().apply()
                    supabase.auth.signOut()
                    false
                } else {
                    preferences.edit()
                        .putString("role", session.role)
                        .putString("label", session.label)
                        .putString("key_id", session.keyId)
                        .apply()
                    true
                }
            }
        } catch (_: Exception) {
            preferences.edit().clear().apply()
            false
        }
    }

    suspend fun authenticate(accessKey: String): AccessSession {
        val supabase = requireNotNull(client) { "Supabase is not configured. Add SUPABASE_URL and SUPABASE_ANON_KEY to local.properties." }
        val attempt = try {
            if (supabase.auth.currentUserOrNull() == null) supabase.auth.signInAnonymously()
            supabase.postgrest.rpc(
                "authenticate_access_key",
                buildJsonObject { put("p_access_key", accessKey.trim()) },
            ).decodeSingle<AccessAttempt>()
        } catch (error: Exception) {
            throw friendlyAccessError(error)
        }
        if (attempt.errorCode == "rate_limited") {
            val minutes = ((attempt.retryAfterSeconds ?: 1800) + 59) / 60
            throw IllegalStateException("Too many attempts. Try again in about $minutes minute${if (minutes == 1) "" else "s"}.")
        }
        if (attempt.errorCode != null || attempt.keyId == null || attempt.role == null || attempt.label == null) {
            throw IllegalStateException("That access key is invalid, expired, or inactive.")
        }
        val result = AccessSession(attempt.keyId, attempt.role, attempt.label)
        preferences.edit()
            .putBoolean("authenticated", true)
            .putString("role", result.role)
            .putString("label", result.label)
            .putString("key_id", result.keyId)
            .apply()
        return result
    }

    suspend fun createAccessKey(label: String, role: String): CreatedAccessKey {
        check(isAccessManager()) { "Administrator access is required." }
        val supabase = requireNotNull(client) { "Supabase is not configured." }
        return try {
            supabase.postgrest.rpc(
                "create_access_key",
                buildJsonObject {
                    put("p_label", label.trim())
                    put("p_role", role)
                },
            ).decodeSingle()
        } catch (error: Exception) {
            throw friendlyAccessError(error)
        }
    }

    suspend fun listAccessKeys(): List<ManagedAccessKey> {
        check(isAccessManager()) { "Administrator access is required." }
        return try {
            requireNotNull(client) { "Supabase is not configured." }
                .postgrest.rpc("list_access_keys").decodeList()
        } catch (error: Exception) {
            throw friendlyAccessError(error)
        }
    }

    suspend fun manageAccessKey(keyId: String, action: String) {
        check(isAccessManager()) { "Administrator access is required." }
        try {
            requireNotNull(client) { "Supabase is not configured." }.postgrest.rpc(
                "manage_access_key",
                buildJsonObject {
                    put("p_key_id", keyId)
                    put("p_action", action)
                },
            )
        } catch (error: Exception) {
            throw friendlyAccessError(error)
        }
    }

    suspend fun editAccessKey(keyId: String, label: String, role: String): AccessSession {
        check(isAccessManager()) { "Administrator access is required." }
        val result = try {
            requireNotNull(client) { "Supabase is not configured." }.postgrest.rpc(
                "edit_access_key",
                buildJsonObject {
                    put("p_key_id", keyId)
                    put("p_label", label.trim())
                    put("p_role", role)
                },
            ).decodeSingle<AccessSession>()
        } catch (error: Exception) {
            throw friendlyAccessError(error)
        }
        if (preferences.getString("key_id", null) == keyId) {
            preferences.edit().putString("role", result.role).putString("label", result.label).apply()
        }
        return result
    }

    suspend fun rotateAccessKey(keyId: String): RotatedAccessKey {
        check(isAccessManager()) { "Administrator access is required." }
        return try {
            requireNotNull(client) { "Supabase is not configured." }.postgrest.rpc(
                "rotate_access_key",
                buildJsonObject { put("p_key_id", keyId) },
            ).decodeSingle()
        } catch (error: Exception) {
            throw friendlyAccessError(error)
        }
    }

    suspend fun provisionAfterVerifiedWrite(permanentUrl: String, physicalUid: String, chipType: String): ProvisionedTapTag {
        check(isSuperAdmin()) { "Super-admin access is required." }
        return try {
            requireNotNull(client) { "Supabase is not configured." }.postgrest.rpc(
                "provision_taptag_after_verified_write",
                buildJsonObject {
                    put("p_permanent_url", permanentUrl.trim())
                    put("p_physical_uid", physicalUid.trim())
                    put("p_chip_type", chipType.trim())
                },
            ).decodeSingle()
        } catch (error: Exception) {
            throw friendlyAccessError(error)
        }
    }

    suspend fun provisionAfterVerifiedRewrite(newUrl: String, physicalUid: String, chipType: String, previousUrl: String): ProvisionedTapTag {
        check(isSuperAdmin()) { "Super-admin access is required." }
        return try {
            requireNotNull(client) { "Supabase is not configured." }.postgrest.rpc(
                "provision_taptag_after_verified_rewrite",
                buildJsonObject {
                    put("p_new_url", newUrl.trim())
                    put("p_physical_uid", physicalUid.trim())
                    put("p_chip_type", chipType.trim())
                    put("p_previous_url", previousUrl.trim())
                },
            ).decodeSingle()
        } catch (error: Exception) {
            throw friendlyAccessError(error)
        }
    }

    suspend fun retireAfterVerifiedRewrite(previousUrl: String, physicalUid: String) {
        check(isSuperAdmin()) { "Super-admin access is required." }
        try {
            requireNotNull(client) { "Supabase is not configured." }.postgrest.rpc(
                "retire_managed_url_after_verified_rewrite",
                buildJsonObject {
                    put("p_previous_url", previousUrl.trim())
                    put("p_physical_uid", physicalUid.trim())
                },
            )
        } catch (error: Exception) {
            throw friendlyAccessError(error)
        }
    }

    suspend fun prepareNextMessageCard(permanentUrl: String, physicalUid: String): NextMessageCard? {
        check(isSuperAdmin()) { "Super-admin access is required." }
        return try {
            requireNotNull(client) { "Supabase is not configured." }.postgrest.rpc(
                "prepare_next_message_card",
                buildJsonObject {
                    put("p_permanent_url", permanentUrl.trim())
                    put("p_physical_uid", physicalUid.trim())
                },
            ).decodeList<NextMessageCard>().firstOrNull()
        } catch (error: Exception) {
            throw friendlyAccessError(error)
        }
    }

    suspend fun checkManagedUrlWrite(permanentUrl: String, physicalUid: String) {
        check(isSuperAdmin()) { "Super-admin access is required." }
        try {
            requireNotNull(client) { "Supabase is not configured." }.postgrest.rpc(
                "check_managed_url_write",
                buildJsonObject {
                    put("p_permanent_url", permanentUrl.trim())
                    put("p_physical_uid", physicalUid.trim())
                },
            )
        } catch (error: Exception) {
            throw friendlyAccessError(error)
        }
    }

    suspend fun checkManagedUrlRewrite(newUrl: String, physicalUid: String, previousUrl: String) {
        check(isSuperAdmin()) { "Super-admin access is required." }
        try {
            requireNotNull(client) { "Supabase is not configured." }.postgrest.rpc(
                "check_managed_url_rewrite",
                buildJsonObject {
                    put("p_new_url", newUrl.trim())
                    put("p_physical_uid", physicalUid.trim())
                    put("p_previous_url", previousUrl.trim())
                },
            )
        } catch (error: Exception) {
            throw friendlyAccessError(error)
        }
    }

    suspend fun listWriterManagedUrls(limit: Int = 500): List<WriterManagedUrl> {
        check(isSuperAdmin()) { "Super-admin access is required." }
        return try {
            val postgrest = requireNotNull(client) { "Supabase is not configured." }.postgrest
            val writerUrls = postgrest.rpc(
                "list_writer_managed_urls",
                buildJsonObject { put("p_limit", limit.coerceIn(1, 500)) },
            ).decodeList<WriterManagedUrl>()
            val adminLinks = managedUrlsJson.decodeFromString<AdminManagedUrls>(
                postgrest.rpc("admin_get_managed_urls").data,
            ).links
            mergeWriterManagedUrls(writerUrls, adminLinks)
        } catch (error: Exception) {
            throw friendlyAccessError(error)
        }
    }

    suspend fun signOut() {
        preferences.edit().clear().apply()
        client?.auth?.signOut()
    }

    private fun friendlyAccessError(error: Exception): IllegalStateException {
        return IllegalStateException(accessErrorMessage(error), error)
    }
}

class SupabaseRemote(private val client: SupabaseClient?) {
    suspend fun readWorkspaceRows(table: String): List<JsonObject> {
        val supabase = requireNotNull(client) { "Supabase is not configured." }
        val rows = mutableListOf<JsonObject>()
        var offset = 0
        do {
            val page = supabase.from(table).select {
                order("id", Order.ASCENDING)
                range(offset.toLong(), offset.toLong() + 499)
            }.decodeList<JsonObject>()
            rows.addAll(page)
            offset += page.size
        } while (page.size == 500)
        return rows
    }

    suspend fun upsert(table: String, payload: JsonObject) {
        requireNotNull(client) { "Supabase is not configured." }.from(table).upsert(payload) { onConflict = "id" }
    }
    suspend fun insertHistory(payload: JsonObject) {
        requireNotNull(client) { "Supabase is not configured." }.from("nfc_write_history").upsert(payload) { onConflict = "user_id,sync_id" }
    }
}
