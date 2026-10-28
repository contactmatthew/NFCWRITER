package com.nfcwriter.app.data.local

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CredentialLookupTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun currentOwnerIsPreferredButEarlierAppIdentityRemainsRecoverable() = runBlocking {
        val dao = database.dao()
        dao.upsertCredential(credential(id = "old", userId = "offline-user", updatedAt = 20))
        dao.upsertCredential(credential(id = "current", userId = "supabase-user", updatedAt = 10))

        val candidates = dao.credentialCandidatesByUid("supabase-user", TAG_UID)

        assertEquals(listOf("current", "old"), candidates.map { it.id })
        assertEquals("old", dao.credentialCandidatesByUid("new-session-user", TAG_UID).first().id)
    }

    private fun credential(id: String, userId: String, updatedAt: Long) = NfcCredentialEntity(
        id = id,
        userId = userId,
        tagId = "tag-$id",
        tagUid = TAG_UID,
        credentialMode = "auto_generated",
        ciphertext = "ciphertext-$id",
        nonceOrIv = "iv-$id",
        chipVariant = "NTAG215",
        updatedAt = updatedAt,
    )

    private companion object {
        const val TAG_UID = "04:01:02:03:04:05:06"
    }
}
