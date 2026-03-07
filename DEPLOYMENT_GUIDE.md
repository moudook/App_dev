# Deployment Guide - March 7, 2026

## ✅ Changes Pushed to Repository

### Commit: `3c0a7712`
**Message**: fix: thinking section storage and session-scoped key rotation

### Files Changed:
1. **ServerAgent.kt** - Final thinking emission, thinking in Result event
2. **ChatFeatureManager.kt** - Debug logging for thinking
3. **ChatRepository.kt** - Debug logging for database storage
4. **LlmProviderFactory.kt** - Session-scoped key rotation (major refactor)
5. **KeyRotationManager.kt** - New session-scoped rotation infrastructure (NEW FILE)

---

## 🚀 Hugging Face Spaces Deployment

### Automatic Deployment
Your HF Spaces is configured for **auto-deployment** on push to `main` branch.

**Dockerfile**: Uses optimized 3-stage build
- Stage 1: Download dependencies (cached)
- Stage 2: Build server JAR
- Stage 3: Minimal JRE runtime (~100MB)

**Build Time**: 5-10 minutes (dependencies are cached)

### Monitor Deployment

1. **Go to your HF Space**:
   ```
   https://huggingface.co/spaces/YOUR_USERNAME/smarty-server
   ```

2. **Check Build Logs**:
   - Click "Logs" tab
   - Watch for:
     ```
     Step 1/3: FROM gradle:8.12.1-jdk17-alpine
     Step 2/3: Building server JAR
     Step 3/3: Deploying with JRE
     ```

3. **Verify Deployment**:
   - Check "App" tab shows "Running"
   - Health check endpoint: `https://YOUR_SPACE.hf.space/health`
   - Expected response: `{"status":"ok","module":"smarty-server"}`

### Manual Trigger (if needed)

If auto-deployment doesn't start:
1. Go to HF Space Settings
2. Click "Factory Rebuild"
3. Wait for build to complete (~5-10 min)

---

## 📱 Android App Testing

### 1. Build Debug App
```bash
cd C:\Users\gbust\Smarty
./gradlew :app:assembleDebug
```

### 2. Install on Device
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. Configure Server URL
In the app:
1. Open Settings
2. Server Configuration
3. Enter your HF Space URL: `https://YOUR_USERNAME-smarty-server.hf.space`

---

## 🧪 Testing Checklist

### Thinking Section Fix
- [ ] Start a new chat
- [ ] Ask: "Search for productivity apps and save top 3 as a note"
- [ ] Verify thinking section shows:
  - 🧠 Reasoning content
  - [Action: search (completed)]
  - [Action: save_note (completed)]
- [ ] Check logcat for:
  ```
  ChatFeatureManager: saveMessage: fullThinking length=XXX, hasToolCalls=true
  ChatRepository: saveMessage: thinking hasToolCalls=true
  ```
- [ ] Clear app data: `adb shell pm clear com.example.smarty`
- [ ] Reopen app, check chat history
- [ ] Verify thinking section still visible

### Key Rotation Fix
- [ ] Configure multiple API keys (comma-separated in env vars)
- [ ] Start a chat with multiple tool calls
- [ ] Check server logs for:
  ```
  [sessionId] Stream with key #0 for OpenAI (attempt 1/3)
  [sessionId] Stream with key #0 for OpenAI (attempt 2/3)  ← Same key!
  ```
- [ ] Verify key stays same during successful operations
- [ ] Test key rotation on error (temporarily use invalid key)

---

## 🔍 Server Logs Verification

### Connect to HF Space Logs
```bash
# Via HF UI: Spaces → Your Space → Logs tab
# Or via API if you have access
```

### Look for These Log Messages

**Thinking Section**:
```
ServerAgent: Emit final thinking state with all tool calls included
ServerAgent: currentThinkingContent length=XXX
```

**Key Rotation**:
```
KeyRotatingOpenAiProvider: [sessionId] Stream with key #0 for OpenAI
KeyRotatingOpenAiProvider: [sessionId] Generate with key #0 for OpenAI
```

**Error Cases**:
```
KeyRotatingOpenAiProvider: [sessionId] Key #0 INVALID for OpenAI: 401 Unauthorized
KeyRotatingOpenAiProvider: [sessionId] Rotating API key: #0 → #1
```

---

## 📊 Database Verification

### Query Messages with Thinking
```sql
-- Check thinking storage
SELECT 
    id,
    role,
    LENGTH(thinking) as thinking_length,
    CASE 
        WHEN thinking LIKE '%[Action:%' THEN 'YES'
        ELSE 'NO'
    END as has_tool_calls,
    LEFT(thinking, 100) as thinking_preview
FROM chat_messages 
WHERE role = 'ASSISTANT' 
ORDER BY timestamp DESC 
LIMIT 10;

-- Summary statistics
SELECT 
    COUNT(*) as total_assistant_messages,
    COUNT(thinking) as messages_with_thinking,
    COUNT(CASE WHEN thinking LIKE '%[Action:%' THEN 1 END) as messages_with_tools
FROM chat_messages 
WHERE role = 'ASSISTANT';
```

---

## ⚠️ Troubleshooting

### Deployment Fails
**Symptom**: HF Space shows "Error" or "Failed to build"

**Solutions**:
1. Check build logs for compilation errors
2. Verify all Kotlin files have correct syntax
3. Check for missing imports in KeyRotationManager.kt
4. Try "Factory Rebuild" in HF Space settings

### Thinking Not Showing
**Symptom**: Thinking section visible during chat but not in history

**Debug Steps**:
1. Check logcat for thinking storage logs
2. Query database directly to verify thinking column
3. Verify `AgentEvent.Result` includes thinking field
4. Check ChatMessageEntity.toChatMessage() includes thinking

### Key Still Rotating
**Symptom**: Keys rotating on every tool call

**Debug Steps**:
1. Check server logs for session ID consistency
2. Verify `extractSessionId()` returns same ID across tool calls
3. Check KeyRotationManager.sessionStates for session reuse
4. Verify no new KeyRotationManager instances created per request

---

## 📝 Rollback Plan

If issues arise, rollback to previous commit:

```bash
# Rollback to previous version
git checkout 23c2aa8b  # Previous commit (pink text fix)
git push origin main:main --force

# Trigger HF rebuild
# Go to HF Space → Settings → Factory Rebuild
```

---

## 🎯 Success Criteria

### Thinking Section
- ✅ Thinking visible during live chat
- ✅ Thinking visible after app restart
- ✅ Thinking visible after fresh install
- ✅ Tool calls visible in thinking section
- ✅ Database contains thinking with tool calls

### Key Rotation
- ✅ Same key used for entire user request
- ✅ Key rotates only on 401/403/429 errors
- ✅ No mid-conversation key changes
- ✅ Exponential backoff working
- ✅ Invalid keys marked and skipped

---

## 📞 Support

If you encounter issues:
1. Check this guide's troubleshooting section
2. Review server logs in HF Space
3. Check client logcat for debug messages
4. Query database to verify storage
5. Consider rollback if critical issues

---

**Deployment Time**: ~5-10 minutes
**Expected Completion**: By the time you finish testing the Android app

Good luck! 🚀
