
package com.smarty.data.di

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.smarty.data.dao.SmartDatabaseDao
import com.smarty.data.database.SmartDatabase
import com.smarty.data.repository.SmartRepository
import com.smarty.data.sync.CRDTManager
import com.smarty.data.sync.OfflineFirstSyncManager
import com.smarty.data.sync.SupabaseEventStreamer
import kotlinx.coroutines.tasks.await

/**
 * Dependency Injection module for the data layer.
 *
 * [provideSyncManager] now wires the real HTTP bridge:
 *  - serverBaseUrl: pass BuildConfig.SERVER_URL from the call site
 *  - getIdToken:    fetches the current Firebase ID token on demand
 *  - dao:           Room DAO so sync manager can write state back locally
 */
object DataModule {

    fun provideDatabase(context: Context): SmartDatabase =
        SmartDatabase.getInstance(context)

    fun provideDao(database: SmartDatabase): SmartDatabaseDao =
        database.smartDao()

    fun provideCRDTManager(dao: SmartDatabaseDao): CRDTManager =
        CRDTManager(dao)

    fun provideEventStreamer(): SupabaseEventStreamer =
        SupabaseEventStreamer()

    fun provideSyncManager(
        crdtManager: CRDTManager,
        eventStreamer: SupabaseEventStreamer,
        dao: SmartDatabaseDao,
        serverBaseUrl: String,
    ): OfflineFirstSyncManager =
        OfflineFirstSyncManager(
            crdtManager = crdtManager,
            eventStreamer = eventStreamer,
            serverBaseUrl = serverBaseUrl,
            getIdToken = {
                FirebaseAuth.getInstance().currentUser
                    ?.getIdToken(false)?.await()?.token
            },
            dao = dao,
        )

    fun provideRepository(
        dao: SmartDatabaseDao,
        crdtManager: CRDTManager,
        syncManager: OfflineFirstSyncManager,
        eventStreamer: SupabaseEventStreamer,
    ): SmartRepository =
        SmartRepository(dao, crdtManager, syncManager, eventStreamer)
}
