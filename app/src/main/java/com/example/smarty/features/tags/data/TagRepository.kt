package com.example.smarty.features.tags.data

import com.example.smarty.core.domain.model.Tag
import com.example.smarty.core.domain.model.TagCreateRequest
import com.example.smarty.core.domain.model.TagCreateResponse
import com.example.smarty.core.domain.model.TagNotesResponse
import com.example.smarty.core.domain.model.TagResponse
import com.example.smarty.core.domain.model.TagsResponse
import com.example.smarty.data.remote.RemoteDataSource

class TagRepository(
    private val remoteDataSource: RemoteDataSource,
) {
    suspend fun getTags(): TagsResponse? = remoteDataSource.getTags()

    suspend fun createTag(
        name: String,
        color: String,
        tagType: String = "MANUAL",
    ): TagCreateResponse? = remoteDataSource.createTag(TagCreateRequest(name = name, color = color, tagType = tagType))

    suspend fun updateTag(tag: Tag): TagResponse? = remoteDataSource.updateTag(tag)

    suspend fun deleteTag(tagId: String): TagResponse? = remoteDataSource.deleteTag(tagId)

    suspend fun getNotesForTag(tagId: String): TagNotesResponse? = remoteDataSource.getNotesForTag(tagId)

    suspend fun assignTagToNote(
        tagId: String,
        noteId: String,
    ): TagResponse? = remoteDataSource.assignTagToNote(tagId, noteId)

    suspend fun removeTagFromNote(
        tagId: String,
        noteId: String,
    ): TagResponse? = remoteDataSource.removeTagFromNote(tagId, noteId)
}
