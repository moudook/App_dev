package com.example.smarty.agent.tools.categories

import ai.koog.agents.core.tools.Tool
import com.example.smarty.agent.tools.base.CategoryInfo
import com.example.smarty.agent.tools.base.CategoryResult
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.Note
import com.example.smarty.util.PrivacyGuard
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
class ListCategoriesArgs

/**
 * Tool for listing all note categories with AI-safe counts.
 * Private notes are excluded from counts.
 */
class ListCategoriesTool(
    private val getCategories: () -> List<Category>,
    private val getActiveNotes: () -> List<Note>
) : Tool<ListCategoriesArgs, CategoryResult>() {

    override val argsSerializer: KSerializer<ListCategoriesArgs> = ListCategoriesArgs.serializer()
    override val resultSerializer: KSerializer<CategoryResult> = CategoryResult.serializer()

    override val name = "list_categories"

    override val description = """
        Lists all note categories with note counts.
        Use to help users organize or find notes by category.
        Private notes are not counted.
    """.trimIndent()

    override suspend fun execute(args: ListCategoriesArgs): CategoryResult {
        return try {
            val categories = getCategories()
            val allNotes = getActiveNotes()
            val safeCounts = PrivacyGuard.getAiSafeCategoryCounts(categories, allNotes)

            val categoryInfos = categories.map { category ->
                CategoryInfo(
                    name = category.name,
                    noteCount = safeCounts[category.name] ?: 0
                )
            }.filter { it.noteCount > 0 }

            CategoryResult(
                success = true,
                categories = categoryInfos,
                message = "Found ${categoryInfos.size} categories with notes"
            )
        } catch (e: Exception) {
            CategoryResult(
                success = false,
                message = "Failed to list categories: ${e.message}"
            )
        }
    }
}
