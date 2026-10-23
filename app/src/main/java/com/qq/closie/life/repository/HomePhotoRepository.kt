package com.qq.closie.life.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.data.backup.gateAwareFlow
import com.qq.closie.life.core.LifeEntityEntity
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.media.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class HomePhotoReference(val id: String, val assetId: String, val date: LocalDate, val uri: String?, val name: String?)

/** The home-photo link is explicit Home sharing consent, not a gallery scan.
 * Its owner is the registered, typed MediaAsset itself. No parallel media store or file copy.
 * createdAt is the real consent date; it is never rewritten to pretend an older photo was imported earlier.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomePhotoRepository(private val database: LifeDatabase) {
    private val media = MediaRepository(database)
    companion object { const val ROLE = "home-photo"; const val ENTITY_TYPE = "media_asset" }
    fun observe(): Flow<List<HomePhotoReference>> = gateAwareFlow {
        database.mediaDao().observeHomePhotoLinks().mapLatest {
            RestoreStartupGate.withBusinessAccessSuspending {
                database.withTransaction {
                    database.mediaDao().getHomePhotoLinks().map { link ->
                        val source = database.mediaDao().getResourcesForAsset(link.mediaAssetId)
                            .firstOrNull { it.role in setOf(MediaResourceRole.ORIGINAL, MediaResourceRole.PRIMARY_IMAGE) && it.contentUri != null }
                        HomePhotoReference(link.id, link.mediaAssetId,
                            Instant.ofEpochMilli(link.createdAt).atZone(ZoneId.systemDefault()).toLocalDate(),
                            source?.contentUri, source?.originalName)
                    }
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    /** Only URIs returned by a user document picker reach here. Cancellation makes no records. */
    suspend fun addReferences(context: Context, uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        require(uris.size <= 50) { "一次最多选择 50 张照片" }
        val selected = uris.distinct().map { uri ->
            require(uri.scheme == "content") { "请选择系统相册或文件中的照片" }
            val mime = context.contentResolver.getType(uri).orEmpty()
            require(mime.startsWith("image/")) { "当前只支持静态图片" }
            context.contentResolver.openFileDescriptor(uri, "r")?.use { } ?: error("原图不可读取，请重新选择")
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            Triple(uri.toString(), mime, name)
        }
        if (selected.isEmpty()) return@withContext emptyList()
        RestoreStartupGate.withBusinessAccessSuspending {
            database.withTransaction {
                val now = System.currentTimeMillis()
                selected.map { (uri, mime, name) -> grantValidated(uri, mime, name, now) }
            }
        }
    }

    internal suspend fun grantValidated(uri: String, mime: String, name: String?, now: Long): String =
        RestoreStartupGate.withBusinessAccessSuspending { database.withTransaction {
            require(Uri.parse(uri).scheme == "content" && mime.startsWith("image/"))
            val existing = database.mediaDao().findResourceByContentUri(uri)
            val asset = existing?.let { database.mediaDao().getAssetById(it.mediaAssetId) }
                ?: media.createMediaAsset(MediaType.IMAGE, timestamp = now)
            if (existing == null) media.addMediaResource(asset.id, MediaResourceRole.ORIGINAL, mime,
                originalName = name, contentUri = uri, timestamp = now)
            val identity = database.lifeEntityDao().getById(asset.id)
            require(identity == null || identity.entityType == ENTITY_TYPE)
            if (identity == null) database.lifeEntityDao().insert(LifeEntityEntity(asset.id, ENTITY_TYPE, now, now))
            val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            val old = database.mediaDao().getLinksForOwner(asset.id).firstOrNull {
                it.role == ROLE && Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate() == today
            }
            (old ?: media.linkMedia(asset.id, asset.id, ROLE, timestamp = now)).id
        } }

    /** Revokes only the Home presentation grant. The shared original and other owners survive. */
    suspend fun remove(id: String) = RestoreStartupGate.withBusinessAccessSuspending {
        database.withTransaction {
            val grant = database.mediaDao().getHomePhotoLinks().singleOrNull { it.id == id }
            require(grant != null) { "首页照片引用已失效" }
            media.unlinkMedia(grant.id)
        }
    }
}
