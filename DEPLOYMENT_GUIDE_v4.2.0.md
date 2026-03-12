# Deployment Guide - Database Schema v4.2.0

**Version**: 4.2.0  
**Date**: March 12, 2026  
**Status**: 🚀 **READY FOR DEPLOYMENT**

---

## 📋 Pre-Deployment Checklist

### Prerequisites
- [ ] Supabase project created and accessible
- [ ] Firebase project configured
- [ ] Hugging Face account with Spaces access
- [ ] Backend server builds successfully
- [ ] Mobile app builds successfully
- [ ] All tests passing (40+ tests)
- [ ] Database backup completed

### Required Files
- [ ] `DATABASE_SCHEMA_v4.2.0_OPTIMIZED.sql`
- [ ] `MIGRATION_v4.1.0_to_v4.2.0.sql`
- [ ] `IMPLEMENTATION_COMPLETE_REPORT.md`
- [ ] `TESTING_GUIDE_v4.2.0.md`

---

## 🚀 Deployment Steps

### Step 1: Database Deployment (Supabase)

#### Option A: Fresh Installation (New Project)

1. **Open Supabase Dashboard**
   ```
   URL: https://supabase.com/dashboard
   Action: Select your project
   ```

2. **Navigate to SQL Editor**
   ```
   Left Sidebar → SQL Editor → New Query
   ```

3. **Execute Full Schema**
   ```sql
   -- Copy entire content from:
   -- DATABASE_SCHEMA_v4.2.0_OPTIMIZED.sql
   
   -- Paste into SQL Editor
   -- Click "Run" or press Ctrl+Enter (Cmd+Enter)
   ```

4. **Verify Installation**
   ```sql
   -- Check tables created (should be 28+)
   SELECT COUNT(*) as table_count 
   FROM information_schema.tables 
   WHERE table_schema = 'public';
   
   -- Check junction tables exist
   SELECT 'chat_message_notes' as table, COUNT(*) as rows 
   FROM chat_message_notes
   UNION ALL
   SELECT 'calendar_event_notes', COUNT(*) 
   FROM calendar_event_notes;
   
   -- Check foreign keys
   SELECT COUNT(*) as fk_count 
   FROM pg_constraint 
   WHERE contype = 'f';
   ```

5. **Expected Results**:
   - ✅ 28+ tables created
   - ✅ Junction tables exist (empty initially)
   - ✅ 30+ foreign keys created
   - ✅ All indexes created

#### Option B: Migration (Existing Database v4.1.0)

1. **CREATE BACKUP FIRST** ⚠️
   ```sql
   -- Critical: Backup all existing data
   CREATE TABLE backup_chat_sessions AS SELECT * FROM chat_sessions;
   CREATE TABLE backup_chat_messages AS SELECT * FROM chat_messages;
   CREATE TABLE backup_notes AS SELECT * FROM notes;
   CREATE TABLE backup_calendar_events AS SELECT * FROM calendar_events;
   -- ... repeat for all critical tables
   ```

2. **Execute Migration Script**
   ```sql
   -- Copy entire content from:
   -- MIGRATION_v4.1.0_to_v4.2.0.sql
   
   -- Paste into SQL Editor
   -- Click "Run"
   ```

3. **Verify Migration**
   ```sql
   -- Check junction table data migrated
   SELECT 
       'chat_message_notes' as table, 
       COUNT(*) as rows 
   FROM chat_message_notes
   UNION ALL
   SELECT 'calendar_event_notes', COUNT(*) 
   FROM calendar_event_notes;
   
   -- Check foreign keys added
   SELECT conname, conrelid::regclass as table_name
   FROM pg_constraint 
   WHERE contype = 'f'
   AND conrelid::regclass::text IN (
       'chat_sessions', 
       'chat_messages', 
       'calendar_event_notes'
   );
   
   -- Compare row counts with backup
   SELECT 'chat_sessions' as table, 
          (SELECT COUNT(*) FROM chat_sessions) as current_count,
          (SELECT COUNT(*) FROM backup_chat_sessions) as backup_count;
   ```

4. **Expected Results**:
   - ✅ Junction tables created
   - ✅ Data migrated from TEXT arrays
   - ✅ Foreign keys enforced
   - ✅ No data loss

---

### Step 2: Backend Server Deployment (Hugging Face Spaces)

#### 1. Update Environment Variables

**In Hugging Face Space Settings**:
```
Settings → Repository → Variables

Add/Update:
- ENVIRONMENT=production
- DB_URL=postgresql://your-supabase-url
- DB_USER=postgres
- DB_PASSWORD=your-password
- FIREBASE_CREDENTIALS={"type":"service_account",...}
- SERVER_PORT=7860
```

#### 2. Deploy Backend

**Option A: Automatic Deployment (Git-connected Space)**
```bash
# Pull latest code
git pull origin main

# Build server
./gradlew :server:build

# Commit and push (triggers auto-deploy)
git add -A
git commit -m"deploy: Deploy v4.2.0 with junction tables"
git push origin main

# Hugging Face will automatically rebuild and deploy
```

**Option B: Manual Deployment**
```bash
# Build production JAR
cd server
./gradlew :server:shadowJar

# Upload JAR to Hugging Face Space
# Or use Docker deployment
```

#### 3. Verify Backend Deployment

**Test Health Endpoint**:
```bash
curl https://your-space.hf.space/health
```

**Expected Response**:
```json
{
  "status": "healthy",
  "database": "connected",
  "version": "4.2.0"
}
```

**Test API Endpoints**:
```bash
# Test chat message relationship endpoint
curl -X POST "https://your-space.hf.space/chat/messages/test-message-id/notes/test-note-id" \
  -H "Authorization: Bearer YOUR_FIREBASE_TOKEN"
```

#### 4. Monitor Logs

**In Hugging Face Space**:
```
Metrics → Logs

Watch for:
✅ "Database migrations applied successfully"
✅ "Server started on port 7860"
✅ "Firebase authentication initialized"
❌ Any ERROR level logs
```

---

### Step 3: Mobile App Deployment

#### 1. Update Version Code

**In `app/build.gradle.kts`**:
```kotlin
android {
    defaultConfig {
        versionCode 36  // Increment for v4.2.0
        versionName "4.2.0"
    }
}
```

#### 2. Build Release APK

```bash
cd app
./gradlew assembleRelease
```

**Output**: `app/build/outputs/apk/release/app-release.apk`

#### 3. Test Migration Locally

```bash
# Install on test device
adb install app/build/outputs/apk/release/app-release.apk

# Run tests
./gradlew connectedAndroidTest
```

#### 4. Verify Room Migration

**In app code or test**:
```kotlin
// Verify database version
val dbVersion = SupportSQLiteOpenHelper.Configuration
    .builder(context)
    .name("Smarty_database")
    .build()
    
// Should be version 36
assert(dbVersion.callback.version == 36)
```

#### 5. Release to Testing Track

**Google Play Console**:
1. Internal Testing → Create new release
2. Upload APK
3. Release notes: "Database schema v4.2.0 with improved note relationships"
4. Roll out to internal testers

#### 6. Monitor Crash Reports

**Firebase Crashlytics**:
```
Firebase Console → Crashlytics

Watch for:
✅ No migration failures
✅ No Room database errors
✅ No junction table errors
```

---

## 🧪 Post-Deployment Verification

### Database Verification

```sql
-- 1. Verify all tables exist
SELECT table_name 
FROM information_schema.tables 
WHERE table_schema = 'public' 
ORDER BY table_name;
-- Expected: 28+ tables

-- 2. Verify junction tables work
INSERT INTO chat_message_notes (message_id, note_id) 
VALUES (gen_random_uuid(), gen_random_uuid());

SELECT COUNT(*) FROM chat_message_notes;
-- Expected: 1 or more

-- 3. Verify foreign keys enforced
-- Try to insert invalid reference (should fail)
INSERT INTO chat_message_notes (message_id, note_id) 
VALUES ('00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000');
-- Expected: ERROR - foreign key violation

-- 4. Verify indexes exist
SELECT indexname 
FROM pg_indexes 
WHERE schemaname = 'public' 
AND indexname LIKE '%chat_message_notes%';
-- Expected: 3+ indexes
```

### Backend API Verification

**Test All Endpoints**:

```bash
# 1. Health check
curl https://your-space.hf.space/health

# 2. Link note to message
curl -X POST "https://your-space.hf.space/chat/messages/{messageId}/notes/{noteId}" \
  -H "Authorization: Bearer YOUR_TOKEN"

# 3. Get linked notes
curl -X GET "https://your-space.hf.space/chat/messages/{messageId}/notes" \
  -H "Authorization: Bearer YOUR_TOKEN"

# 4. Unlink note
curl -X DELETE "https://your-space.hf.space/chat/messages/{messageId}/notes/{noteId}" \
  -H "Authorization: Bearer YOUR_TOKEN"

# 5. Link note to calendar event
curl -X POST "https://your-space.hf.space/api/v1/calendar/events/{eventId}/notes/{noteId}" \
  -H "Authorization: Bearer YOUR_TOKEN"

# 6. Get linked notes for event
curl -X GET "https://your-space.hf.space/api/v1/calendar/events/{eventId}/notes" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

### Mobile App Verification

**Test Junction Table Operations**:

```kotlin
// In your test or debug screen
val messageId = "test-message-123"
val noteId = "test-note-456"

// 1. Link
chatRepository.linkNoteToMessage(messageId, noteId)

// 2. Verify
val linkedNotes = chatRepository.getLinkedNoteIds(messageId)
assert(linkedNotes.contains(noteId))

// 3. Unlink
chatRepository.unlinkNoteFromMessage(messageId, noteId)

// 4. Verify removed
val linkedNotesAfter = chatRepository.getLinkedNoteIds(messageId)
assert(linkedNotesAfter.isEmpty())
```

---

## 📊 Monitoring & Alerts

### Key Metrics to Monitor

**Database**:
- Query response time (< 100ms)
- Connection pool usage (< 80%)
- Junction table row count (growing)
- Foreign key violations (should be 0)

**Backend**:
- API response time (< 500ms)
- Error rate (< 1%)
- Active users
- Relationship operations count

**Mobile**:
- App crash rate (< 1%)
- Migration success rate (100%)
- Sync success rate (> 95%)

### Set Up Alerts

**Supabase**:
```
Settings → Notifications → Email Alerts

Enable:
- Database errors
- API errors
- Performance warnings
```

**Hugging Face**:
```
Space Settings → Monitoring

Enable:
- Build failures
- Runtime errors
- Resource warnings
```

**Firebase**:
```
Firebase Console → Crashlytics → Alerts

Enable:
- New crash issues
- Regression alerts
- ANR alerts
```

---

## 🔄 Rollback Procedure

### If Database Migration Fails

1. **Stop Backend Server**
   ```bash
   # Pause Hugging Face Space
   Settings → Pause Space
   ```

2. **Restore from Backup**
   ```sql
   -- Drop new tables
   DROP TABLE IF EXISTS chat_message_notes;
   DROP TABLE IF EXISTS calendar_event_notes;
   
   -- Restore from backups (if needed)
   -- Data should be preserved via CASCADE
   ```

3. **Revert Schema**
   ```sql
   -- Run previous schema version
   -- File: DATABASE_SCHEMA_v4.1.0.sql
   ```

4. **Restart Backend**
   ```bash
   Settings → Reboot Space
   ```

### If Backend Deployment Fails

1. **Revert Code**
   ```bash
   git revert HEAD
   git push origin main
   ```

2. **Redeploy Previous Version**
   ```bash
   # Hugging Face will auto-redeploy
   ```

### If Mobile App Has Issues

1. **Stop Rollout**
   ```
   Google Play Console → Release → Halt rollout
   ```

2. **Fix and Re-release**
   ```bash
   # Fix issues
   # Increment version code
   # Build new release
   ```

---

## ✅ Deployment Success Criteria

### Database ✅
- [ ] All 28+ tables created
- [ ] Junction tables exist and functional
- [ ] Foreign keys enforced
- [ ] All indexes created
- [ ] No data loss
- [ ] Query performance < 100ms

### Backend ✅
- [ ] Server starts successfully
- [ ] All API endpoints respond
- [ ] Authentication works
- [ ] User ownership verified
- [ ] Error handling works
- [ ] Logs show no errors

### Mobile App ✅
- [ ] App installs successfully
- [ ] Migration completes (v35→v36)
- [ ] No crashes on startup
- [ ] Junction table operations work
- [ ] Sync with backend works
- [ ] Crash rate < 1%

### Performance ✅
- [ ] API response time < 500ms
- [ ] Database queries < 100ms
- [ ] Mobile app startup < 3s
- [ ] No memory leaks

---

## 📝 Deployment Timeline

| Phase | Duration | Status |
|-------|----------|--------|
| Database Deployment | 30 min | ⏳ Pending |
| Backend Deployment | 1 hour | ⏳ Pending |
| Mobile App Deployment | 2 hours | ⏳ Pending |
| Verification | 1 hour | ⏳ Pending |
| Monitoring | 24 hours | ⏳ Pending |
| **Total** | **~34.5 hours** | ⏳ Pending |

---

## 🎯 Go/No-Go Decision

### Go Criteria ✅
- All tests passing (40+ tests)
- No critical bugs
- Performance benchmarks met
- Backup completed
- Rollback procedure tested

### No-Go Criteria ❌
- Critical test failures
- Data loss detected
- Performance below benchmarks
- Backup not available
- Rollback procedure untested

---

## 📞 Support Contacts

**During Deployment**:
- Database Admin: [Your DBA]
- Backend Lead: [Your Backend Lead]
- Mobile Lead: [Your Mobile Lead]
- DevOps: [Your DevOps Team]

**Emergency Contacts**:
- Primary: [Name + Phone]
- Secondary: [Name + Phone]
- Escalation: [Name + Phone]

---

## 🎉 Post-Deployment Celebration

Once all verification steps pass:

1. ✅ Update status page
2. ✅ Notify stakeholders
3. ✅ Update documentation
4. ✅ Monitor for 24 hours
5. ✅ Celebrate! 🎉

---

**Last Updated**: March 12, 2026  
**Version**: 4.2.0  
**Status**: 🚀 **READY FOR DEPLOYMENT**

**Good luck with the deployment!** 🚀
