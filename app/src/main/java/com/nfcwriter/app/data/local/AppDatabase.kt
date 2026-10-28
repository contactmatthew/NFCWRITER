package com.nfcwriter.app.data.local

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProjectEntity::class, NfcTagEntity::class, NfcCredentialEntity::class, HistoryEntity::class, BatchEntity::class, BatchItemEntity::class, PendingMutationEntity::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE write_history ADD COLUMN oldContentType TEXT")
                db.execSQL("ALTER TABLE write_history ADD COLUMN newContentType TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nfc_tags ADD COLUMN credentialId TEXT")
                db.execSQL("ALTER TABLE nfc_tags ADD COLUMN lastSecurityVerifiedAt INTEGER")
                db.execSQL("""CREATE TABLE IF NOT EXISTS nfc_credentials (`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `tagId` TEXT NOT NULL, `tagUid` TEXT NOT NULL, `credentialType` TEXT NOT NULL, `ciphertext` TEXT NOT NULL, `nonceOrIv` TEXT NOT NULL, `keyVersion` INTEGER NOT NULL, `credentialVersion` INTEGER NOT NULL, `chipVariant` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_nfc_credentials_userId ON nfc_credentials(userId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_nfc_credentials_tagId ON nfc_credentials(tagId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_nfc_credentials_userId_tagUid ON nfc_credentials(userId, tagUid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_nfc_credentials_syncState ON nfc_credentials(syncState)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nfc_tags ADD COLUMN credentialMode TEXT")
                db.execSQL("ALTER TABLE nfc_credentials ADD COLUMN credentialMode TEXT NOT NULL DEFAULT 'auto_generated'")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nfc_tags ADD COLUMN isMock INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE nfc_credentials ADD COLUMN isMock INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE write_history ADD COLUMN isMock INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE batches ADD COLUMN isMock INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE batch_items ADD COLUMN isMock INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
