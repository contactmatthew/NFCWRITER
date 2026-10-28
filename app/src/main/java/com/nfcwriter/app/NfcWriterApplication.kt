package com.nfcwriter.app

import android.app.Application
import androidx.room.Room
import com.nfcwriter.app.data.AppRepository
import com.nfcwriter.app.data.SecureSecretStore
import com.nfcwriter.app.data.local.AppDatabase
import com.nfcwriter.app.data.remote.AccessService
import com.nfcwriter.app.data.remote.SupabaseRemote
import com.nfcwriter.app.data.remote.SupabaseServices
import com.nfcwriter.app.sync.SyncWorker
import kotlinx.serialization.json.Json

class NfcWriterApplication : Application() {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "kind" }
    val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "nfc-writer.db").addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5).build() }
    val repository by lazy { AppRepository(database.dao(), json) }
    val auth by lazy { AccessService(this, SupabaseServices.client) }
    val remote by lazy { SupabaseRemote(SupabaseServices.client) }
    val secrets by lazy { SecureSecretStore(this) }
    override fun onCreate() { super.onCreate(); SyncWorker.schedule(this) }
}
