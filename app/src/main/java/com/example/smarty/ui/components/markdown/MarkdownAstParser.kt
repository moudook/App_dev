package com.example.smarty.ui.components.markdown

sealed class MarkdownNode {
    data class Header(val level: Int, val text: String) : MarkdownNode()
    data class Paragraph(val text: String) : MarkdownNode()
    data class CodeBlock(val language: String, val code: String) : MarkdownNode()
    data class Blockquote(val text: String) : MarkdownNode()
    data object HorizontalRule : MarkdownNode()
    data class Table(val rows: List<String>) : MarkdownNode()
    data class TaskList(val tasks: List<Pair<Boolean, String>>) : MarkdownNode()
    data class BulletItem(val text: String) : MarkdownNode()
    data class NumberedItem(val prefix: String, val text: String) : MarkdownNode()
    data class LatexBlock(val math: String) : MarkdownNode()
    data class AccordionGroup(val sections: List<AccordionItemData>) : MarkdownNode()
}

data class AccordionItemData(val title: String, val content: String)

object MarkdownAstParser {
    private val cache = object : android.util.LruCache<String, List<MarkdownNode>>(64) {
        override fun sizeOf(key: String, value: List<MarkdownNode>) = 1
    }

    private val taskUnchecked = Regex("^\\s*[-*]\\s+\\[\\s*\\]\\s+.*")
    private val taskChecked = Regex("^\\s*[-*]\\s+\\[\\s*[xX]\\s*\\]\\s+.*")
    private val taskItem = Regex("^\\s*[-*]\\s+\\[(\\s*[xX]?\\s*)\\]\\s+(.+)$")
    private val horizontalRule = Regex("^(---+|\\*\\*\\*+|___+)$")

    fun parse(content: String): List<MarkdownNode> {
        return cache.get(content) ?: parseInternal(content).also {
            cache.put(content, it)
        }
    }

    private fun parseInternal(content: String): List<MarkdownNode> {
        val lines = content.lines()
        val nodes = mutableListOf<MarkdownNode>()
        var i = 0

        var inCodeBlock = false
        var codeLanguage = ""
        val codeContent = mutableListOf<String>()

        while (i < lines.size) {
            val line = lines[i]
            val trimStart = line.trimStart()

            // 1. Handle Code Blocks
            if (trimStart.startsWith("```")) {
                if (inCodeBlock) {
                    nodes.add(MarkdownNode.CodeBlock(codeLanguage, codeContent.joinToString("\n")))
                    inCodeBlock = false
                    codeContent.clear()
                    codeLanguage = ""
                } else {
                    inCodeBlock = true
                    codeLanguage = trimStart.removePrefix("```").trim()
                }
                i++
                continue
            }

            if (inCodeBlock) {
                codeContent.add(line)
                i++
                continue
            }

            if (trimStart.isEmpty()) {
                i++
                continue
            }

            // 2. Handle Block Math ($$ or \[)
            if (trimStart.startsWith("$$") || trimStart.startsWith("\\[")) {
                val mathLines = mutableListOf<String>()
                val startTag = if (trimStart.startsWith("$$")) "$$" else "\\["
                val endTag = if (trimStart.startsWith("$$")) "$$" else "\\]"
                
                var inlineClosed = false
                val firstLineMath = trimStart.removePrefix(startTag).trim()
                if (firstLineMath.endsWith(endTag)) {
                    mathLines.add(firstLineMath.removeSuffix(endTag).trim())
                    inlineClosed = true
                } else if (firstLineMath.isNotEmpty()) {
                    mathLines.add(firstLineMath)
                }
                
                i++
                if (!inlineClosed) {
                    while (i < lines.size) {
                        val next = lines[i].trim()
                        if (next.endsWith(endTag)) {
                            val lastPart = next.removeSuffix(endTag).trim()
                            if (lastPart.isNotEmpty()) mathLines.add(lastPart)
                            i++
                            break
                        }
                        mathLines.add(next)
                        i++
                    }
                }
                nodes.add(MarkdownNode.LatexBlock(mathLines.joinToString("\n")))
                continue
            }

            // 2.5 Handle Accordion Group
            if (trimStart.trimEnd().startsWith("[[[") && trimStart.trimEnd().endsWith("]]]") && !trimStart.trimEnd().startsWith("[[[/") && trimStart.trimEnd() != "[[[]]]") {
                val sections = mutableListOf<AccordionItemData>()
                
                while (i < lines.size) {
                    val line = lines[i]
                    val lineTrim = line.trim()
                    
                    if (lineTrim.startsWith("[[[") && lineTrim.endsWith("]]]") && !lineTrim.startsWith("[[[/") && lineTrim != "[[[]]]") {
                        val rawTitle = lineTrim.removePrefix("[[[").removeSuffix("]]]").trim()
                        val title = stripQuotes(rawTitle)
                        val accordionContentLines = mutableListOf<String>()
                        i++
                        
                        var depth = 1
                        while (i < lines.size) {
                            val nextLine = lines[i]
                            val nextTrim = nextLine.trim()
                            
                            if (nextTrim.startsWith("[[[") && nextTrim.endsWith("]]]") && !nextTrim.startsWith("[[[/") && nextTrim != "[[[]]]") {
                                depth++
                            } else if (nextTrim.startsWith("[[[/") || nextTrim == "[[[]]]") {
                                depth--
                                if (depth == 0) {
                                    i++ // consume the closing tag
                                    break
                                }
                            }
                            accordionContentLines.add(nextLine)
                            i++
                        }
                        
                        sections.add(AccordionItemData(title, accordionContentLines.joinToString("\n").trim()))
                    } else if (lineTrim.isEmpty()) {
                        i++
                    } else {
                        break
                    }
                }
                nodes.add(MarkdownNode.AccordionGroup(sections))
                continue
            }

            // 3. Handle Headers
            if (trimStart.startsWith("#") && trimStart.contains(" ")) {
                val spaceIdx = trimStart.indexOf(" ")
                val hashes = trimStart.substring(0, spaceIdx)
                if (hashes.all { it == '#' }) {
                    nodes.add(MarkdownNode.Header(hashes.length, trimStart.substring(spaceIdx + 1).trim()))
                    i++
                    continue
                }
            }

            // 4. Handle Horizontal Rule
            if (trimStart.matches(horizontalRule)) {
                nodes.add(MarkdownNode.HorizontalRule)
                i++
                continue
            }

            // 5. Handle Blockquote
            if (trimStart.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoteLines.add(lines[i].trimStart().substring(1).trimStart())
                    i++
                }
                nodes.add(MarkdownNode.Blockquote(quoteLines.joinToString("\n")))
                continue
            }

            // 6. Handle Tables
            if (trimStart.startsWith("|")) {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    tableLines.add(lines[i].trim())
                    i++
                }
                nodes.add(MarkdownNode.Table(tableLines))
                continue
            }

            // 7. Handle Task Lists
            if (trimStart.matches(taskUnchecked) || trimStart.matches(taskChecked)) {
                val tasks = mutableListOf<Pair<Boolean, String>>()
                while (i < lines.size) {
                    val tLine = lines[i].trimStart()
                    if (tLine.matches(taskUnchecked) || tLine.matches(taskChecked)) {
                        val match = taskItem.find(tLine)
                        if (match != null) {
                            tasks.add(Pair(match.groupValues[1].trim().isNotEmpty(), match.groupValues[2]))
                        }
                        i++
                    } else if (tLine.isEmpty() || isBlockBreak(tLine)) {
                        break
                    } else {
                        // Continuation line
                        if (tasks.isNotEmpty()) {
                            val last = tasks.removeLast()
                            tasks.add(Pair(last.first, last.second + "\n" + tLine))
                        }
                        i++
                    }
                }
                nodes.add(MarkdownNode.TaskList(tasks))
                continue
            }

            // 8. Handle Bullet Lists
            if (trimStart.startsWith("- ") || trimStart.startsWith("* ")) {
                var itemText = trimStart.substring(2)
                i++
                while (i < lines.size) {
                    val tLine = lines[i].trimStart()
                    if (tLine.isEmpty() || isBlockBreak(tLine)) break
                    itemText += "\n" + tLine
                    i++
                }
                nodes.add(MarkdownNode.BulletItem(itemText))
                continue
            }

            // 9. Handle Numbered Lists
            if (trimStart.firstOrNull()?.isDigit() == true && trimStart.contains(". ")) {
                val dotIdx = trimStart.indexOf(". ")
                if (dotIdx in 1..3) {
                    val prefix = trimStart.substring(0, dotIdx + 1)
                    var itemText = trimStart.substring(dotIdx + 2)
                    i++
                    while (i < lines.size) {
                        val tLine = lines[i].trimStart()
                        if (tLine.isEmpty() || isBlockBreak(tLine)) break
                        itemText += "\n" + tLine
                        i++
                    }
                    nodes.add(MarkdownNode.NumberedItem(prefix, itemText))
                    continue
                }
            }

            // 10. Handle Paragraphs (Default)
            val paragraphLines = mutableListOf<String>()
            var insideInlineMath = trimStart.contains("$$") && !trimStart.substringAfter("$$").contains("$$")
            
            while (i < lines.size) {
                val tLine = lines[i].trimStart()
                if (tLine.isEmpty()) break
                
                if (!insideInlineMath && isBlockBreak(tLine)) break
                
                paragraphLines.add(tLine)
                
                if (tLine.contains("$$")) {
                    val count = tLine.windowed(2).count { it == "$$" }
                    if (count % 2 != 0) insideInlineMath = !insideInlineMath
                }
                i++
            }
            nodes.add(MarkdownNode.Paragraph(paragraphLines.joinToString("\n")))
        }

        if (inCodeBlock) {
            nodes.add(MarkdownNode.CodeBlock(codeLanguage, codeContent.joinToString("\n")))
        }

        return nodes
    }

    private fun isBlockBreak(trimStart: String): Boolean {
        val trimmed = trimStart.trimEnd()
        if (trimmed.isEmpty()) return true
        if (trimmed.startsWith("```")) return true
        if (trimmed.startsWith("#") && trimmed.contains(" ")) return true
        if (trimmed.startsWith(">")) return true
        if (trimmed.startsWith("|")) return true
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) return true
        if (trimmed.firstOrNull()?.isDigit() == true && trimmed.contains(". ")) {
            val dotIdx = trimmed.indexOf(". ")
            if (dotIdx in 1..3) return true
        }
        if (trimmed.matches(horizontalRule)) return true
        if (trimmed.startsWith("$$") || trimmed.startsWith("\\[")) return true
        if (trimmed.startsWith("[[[") && trimmed.endsWith("]]]")) return true
        return false
    }

    private fun stripQuotes(s: String): String {
        var result = s
        if ((result.startsWith("\"") && result.endsWith("\"")) ||
            (result.startsWith("'") && result.endsWith("'")) ||
            (result.startsWith("`") && result.endsWith("`"))) {
            result = result.substring(1, result.length - 1)
        }
        return result.trim()
    }
}
