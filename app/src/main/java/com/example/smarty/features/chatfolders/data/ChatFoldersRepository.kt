package com.example.smarty.features.chatfolders.data

import com.example.smarty.core.domain.model.ChatFolder
import com.example.smarty.core.domain.model.ChatFolderCreateRequest
import com.example.smarty.core.domain.model.ChatFolderCreateResponse
import com.example.smarty.core.domain.model.ChatFolderResponse
import com.example.smarty.core.domain.model.ChatFoldersResponse
import com.example.smarty.data.remote.RemoteDataSource

class ChatFoldersRepository(
    private val remoteDataSource: RemoteDataSource,
) {
    suspend fun getFolders(): ChatFoldersResponse? = remoteDataSource.getChatFolders()

    suspend fun createFolder(
        name: String,
        color: String,
        sortOrder: Int = 0,
    ): ChatFolderCreateResponse? {
        return remoteDataSource.createChatFolder(ChatFolderCreateRequest(name = name, color = color, sortOrder = sortOrder))
    }

    suspend fun updateFolder(folder: ChatFolder): ChatFolderResponse? = remoteDataSource.updateChatFolder(folder)

    suspend fun deleteFolder(folderId: String): ChatFolderResponse? = remoteDataSource.deleteChatFolder(folderId)
}
