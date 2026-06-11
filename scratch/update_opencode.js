const fs = require('fs');

const filePath = 'c:\\Users\\gbust\\Smarty\\server\\src\\main\\kotlin\\com\\example\\smarty\\server\\llm\\OpencodeLlmProvider.kt';
let content = fs.readFileSync(filePath, 'utf8');

const model_parse_old = `            val rawModelId = (model ?: defaultModel).substringAfter('/').takeIf { it.isNotBlank() } ?: "deepseek-v4-flash"
            val cleanModelId = rawModelId.removeSuffix("-free").removeSuffix("-Free")
            val modelId = if (cleanModelId == "auto") "deepseek-v4-flash" else cleanModelId`;

const model_parse_new = `            val requestedModelId = (model ?: defaultModel).substringAfter('/').takeIf { it.isNotBlank() } ?: "deepseek-v4-flash-free"
            val freeModels = listOf("north-mini-code-free", "mimo-v2.5-free", "deepseek-v4-flash-free")
            val modelsToTry = if (requestedModelId == "auto" || requestedModelId == "default") {
                freeModels
            } else if (requestedModelId in freeModels) {
                listOf(requestedModelId) + freeModels.filter { it != requestedModelId }
            } else {
                listOf(requestedModelId)
            }`;

content = content.replace(model_parse_old, model_parse_new);

const try_block_old = `            val body =
                buildJsonObject {
                    put("model", JsonPrimitive(modelId))
                    put("stream", JsonPrimitive(true))
                    put("messages", kotlinx.serialization.json.JsonArray(chatMessages))
                    if (toolsJson.isNotEmpty()) {
                        put("tools", toolsJson)
                    }
                }

            logger.info(
                "[OpenCode.DirectZen][inference=$inferenceId] POST $ZEN_BASE_URL/chat/completions model=$modelId tools=${tools.size}",
            )

            try {
                client
                    .preparePost("$ZEN_BASE_URL/chat/completions") {`;

const try_block_new = `            var lastError: Exception? = null

            for (currentModelId in modelsToTry) {
                val body =
                    buildJsonObject {
                        put("model", JsonPrimitive(currentModelId))
                        put("stream", JsonPrimitive(true))
                        put("messages", kotlinx.serialization.json.JsonArray(chatMessages))
                        if (toolsJson.isNotEmpty()) {
                            put("tools", toolsJson)
                        }
                    }

                logger.info(
                    "[OpenCode.DirectZen][inference=$inferenceId] POST $ZEN_BASE_URL/chat/completions model=$currentModelId tools=${tools.size}",
                )

                var success = false
                try {
                    client
                        .preparePost("$ZEN_BASE_URL/chat/completions") {`;

content = content.replace(try_block_old, try_block_new);

const catch_block_old = `                        val totalMs = System.currentTimeMillis() - requestStartMs
                        logger.info(
                            "[OpenCode.DirectZen.StreamDiag][inference=$inferenceId] STREAM_COMPLETE totalMs=$totalMs chunks=$directChunkCount",
                        )
                    }
            } catch (e: Exception) {
                logger.error("[OpenCode.DirectZen][inference=$inferenceId] failed class={} msg={}", e.javaClass.name, e.message)
                emit(LlmChunk(content = null, finishReason = "error", sseEvent = "error"))
            }`;

const catch_block_new = `                        val totalMs = System.currentTimeMillis() - requestStartMs
                        logger.info(
                            "[OpenCode.DirectZen.StreamDiag][inference=$inferenceId] STREAM_COMPLETE totalMs=$totalMs chunks=$directChunkCount",
                        )
                        success = true
                    }
                    if (success) {
                        return@flow
                    }
                } catch (e: Exception) {
                    lastError = e
                    logger.warn("[OpenCode.DirectZen][inference=$inferenceId] failed model=$currentModelId class=${e.javaClass.name} msg=${e.message}, trying next if available")
                    continue
                }
            }
            if (lastError != null) {
                logger.error("[OpenCode.DirectZen][inference=$inferenceId] all models failed", lastError)
                emit(LlmChunk(content = null, finishReason = "error", sseEvent = "error"))
            }`;

content = content.replace(catch_block_old, catch_block_new);

fs.writeFileSync(filePath, content, 'utf8');
console.log("Updated OpencodeLlmProvider.kt with Node.js!");
