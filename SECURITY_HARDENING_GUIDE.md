# 🔒 Security Hardening Guide & Deployment Checklist

**Version**: 1.0  
**Date**: March 12, 2026  
**Status**: ✅ Active

---

## 📋 Pre-Deployment Security Checklist

### ✅ CRITICAL - Must Complete Before Deployment

#### 1. Firebase Configuration
- [ ] **Generate Firebase Service Account Key**
  - Go to Firebase Console → Project Settings → Service Accounts
  - Click "Generate New Private Key"
  - Save the JSON file securely
- [ ] **Set FIREBASE_CREDENTIALS Environment Variable**
  ```bash
  export FIREBASE_CREDENTIALS='{"type":"service_account","project_id":"your-project",...}'
  ```
- [ ] **Verify Firebase Initialization**
  ```bash
  # Server logs should show:
  # "Firebase Admin SDK initialized successfully"
  ```

#### 2. Environment Variables Audit
- [ ] **REMOVE Dangerous Variables**
  ```bash
  # MUST NOT be set in production:
  unset ALLOW_UNSECURE_DEV_AUTH  # REMOVED - DO NOT USE
  ```
- [ ] **SET Required Variables**
  ```bash
  # Production requirements:
  export ENVIRONMENT=production
  export FIREBASE_CREDENTIALS='...'
  ```
- [ ] **SET API Keys Securely**
  ```bash
  export GEMINI_API_KEY='...'
  export TAVILY_API_KEY='...'
  export OPENAI_API_KEY='...'  # Optional
  ```

#### 3. Database Security
- [ ] **Use Strong Database Credentials**
  - Minimum 32 character password
  - Mix of uppercase, lowercase, numbers, symbols
  - Stored in secrets manager (not .env files)
- [ ] **Enable SSL for Database Connections**
  ```bash
  DB_URL="postgresql://user:pass@host:5432/smarty?sslmode=require"
  ```
- [ ] **Restrict Database Access**
  - Only allow connections from server IP
  - Use VPC peering or private networking
  - Enable firewall rules

#### 4. Hugging Face Spaces Specific
- [ ] **Configure Space Variables** (Settings → Variables)
  ```
  FIREBASE_CREDENTIALS = {"type":"service_account",...}
  ENVIRONMENT = production
  GEMINI_API_KEY = ...
  TAVILY_API_KEY = ...
  DB_URL = ...
  DB_USER = ...
  DB_PASSWORD = ...
  ```
- [ ] **DO NOT SET** (Dangerous):
  ```
  ❌ ALLOW_UNSECURE_DEV_AUTH  # NEVER SET THIS
  ```
- [ ] **Enable Space Secrets**
  - Mark sensitive variables as "secret" (hidden in logs)
  - Enable "Encrypted at rest"

---

## 🛡️ Security Hardening Steps

### Level 1: Essential (Do Immediately)

#### 1.1 Authentication Hardening
```kotlin
// ✅ Already implemented in Security.kt
- Mandatory Firebase in production
- No authentication bypass possible
- Server crashes if misconfigured
```

#### 1.2 Rate Limiting
Add to `Application.kt`:
```kotlin
install(RateLimit) {
    register("global") {
        rateLimiter(LimitPerSession(100, 1.minute))
    }
    register("chat") {
        rateLimiter(LimitPerSession(10, 1.minute))
    }
    register("auth") {
        rateLimiter(LimitPerSession(5, 1.minute))
    }
}
```

#### 1.3 CORS Configuration
Update `Security.kt`:
```kotlin
install(CORS) {
    allowHost("your-domain.com", listOf(Https))
    allowHost("localhost", listOf(Https))
    allowHeader(HttpHeaders.Authorization)
    allowHeader(HttpHeaders.ContentType)
    allowMethod(HttpMethod.Post)
    allowMethod(HttpMethod.Get)
    allowMethod(HttpMethod.Delete)
}
```

#### 1.4 Security Headers
Add to routes:
```kotlin
call.response.headers.append("X-Content-Type-Options", "nosniff")
call.response.headers.append("X-Frame-Options", "DENY")
call.response.headers.append("X-XSS-Protection", "1; mode=block")
call.response.headers.append("Strict-Transport-Security", "max-age=31536000")
```

### Level 2: Enhanced (Do Within 1 Week)

#### 2.1 Input Validation
Create `utils/InputValidation.kt`:
```kotlin
object InputValidation {
    fun validateQuery(query: String) {
        require(query.length <= 10000) { "Query too long" }
        require(query.isNotBlank()) { "Query cannot be empty" }
        // Check for injection patterns
        require(!query.contains(Regex("<script|javascript:|onerror="))) {
            "Invalid characters in query"
        }
    }
    
    fun validateUserId(userId: String) {
        require(userId.matches(Regex("^[a-zA-Z0-9_-]{1,64}$"))) {
            "Invalid user ID format"
        }
    }
}
```

#### 2.2 SQL Injection Prevention
All queries already use `prepareStatement` with parameters. Continue this pattern:
```kotlin
// ✅ GOOD - Parameterized query
val sql = "SELECT * FROM notes WHERE user_id = ? AND id = ?"
stmt.setString(1, userId)
stmt.setString(2, noteId)

// ❌ BAD - String concatenation (never do this)
val sql = "SELECT * FROM notes WHERE user_id = '$userId'"
```

#### 2.3 Logging Security
Update `Application.kt`:
```kotlin
// Sanitize sensitive data in logs
install(CallLogging) {
    filter { call ->
        // Don't log auth headers
        call.request.headers["Authorization"] == null
    }
    format { call ->
        val userId = call.firebaseUser()?.userId ?: "anonymous"
        "REQ: ${call.request.method} ${call.request.path} - User: $userId"
    }
}
```

### Level 3: Advanced (Do Within 1 Month)

#### 3.1 Secrets Management
Use a secrets manager instead of environment variables:

**AWS Secrets Manager**:
```kotlin
// Add dependency
implementation("software.amazon.awssdk:secretsmanager:2.20.+")

// Retrieve secrets
val secretsManager = SecretsManagerClient.create()
val request = GetSecretValueRequest.builder()
    .secretId("prod/smarty/firebase-credentials")
    .build()
val response = secretsManager.getSecretValue(request)
val firebaseCredentials = response.secretString()
```

#### 3.2 API Key Rotation
Automate key rotation:
```kotlin
// Add to Application.kt
object ApiKeyRotation {
    private val keyUpdateTime = AtomicLong(0)
    private const val ROTATION_INTERVAL_MS = 30 * 24 * 60 * 60 * 1000L // 30 days
    
    suspend fun rotateKeysIfNeeded() {
        if (System.currentTimeMillis() - keyUpdateTime.get() > ROTATION_INTERVAL_MS) {
            // Fetch new keys from secrets manager
            // Update AppConfig
            keyUpdateTime.set(System.currentTimeMillis())
        }
    }
}
```

#### 3.3 Security Monitoring
Add monitoring for suspicious activity:
```kotlin
// Add to routes
fun logSuspiciousActivity(call: ApplicationCall, reason: String) {
    logger.warn("SECURITY: $reason - IP: ${call.request.local.remoteAddress}, User: ${call.firebaseUser()?.userId}")
    // Send alert to security team
    // Rate limit this user
    // Consider temporary ban if repeated
}
```

---

## 🔍 Security Testing

### Automated Tests

#### 1. Authentication Tests
```bash
# Test without token (should fail)
curl -X POST https://your-server.com/api/chat/query \
  -H "Content-Type: application/json" \
  -d '{"query":"test"}'
# Expected: 401 Unauthorized

# Test with invalid token (should fail)
curl -X POST https://your-server.com/api/chat/query \
  -H "Authorization: Bearer invalid-token" \
  -d '{"query":"test"}'
# Expected: 401 Unauthorized

# Test with valid token (should succeed)
curl -X POST https://your-server.com/api/chat/query \
  -H "Authorization: Bearer VALID_TOKEN" \
  -d '{"query":"test"}'
# Expected: 200 OK
```

#### 2. Rate Limiting Tests
```bash
# Send many requests quickly
for i in {1..100}; do
  curl -X POST https://your-server.com/api/chat/query \
    -H "Authorization: Bearer VALID_TOKEN" \
    -d '{"query":"test"}' &
done

# Should see 429 Too Many Requests after limit
```

#### 3. Input Validation Tests
```bash
# Test SQL injection (should be rejected)
curl -X POST https://your-server.com/api/chat/query \
  -H "Authorization: Bearer VALID_TOKEN" \
  -d '{"query":"test'\''; DROP TABLE users; --"}'
# Expected: 400 Bad Request or safe handling

# Test XSS (should be rejected)
curl -X POST https://your-server.com/api/chat/query \
  -H "Authorization: Bearer VALID_TOKEN" \
  -d '{"query":"<script>alert(1)</script>"}'
# Expected: 400 Bad Request or sanitized
```

### Manual Security Review

#### Monthly Checklist
- [ ] Review server logs for suspicious patterns
- [ ] Check for new dependencies with vulnerabilities
- [ ] Review access patterns (unusual data exports?)
- [ ] Test authentication bypass attempts
- [ ] Verify rate limiting is working
- [ ] Check error messages don't leak sensitive info

---

## 🚨 Incident Response

### If Security Breach Suspected

#### Immediate Actions (Within 1 Hour)
1. **Preserve Evidence**
   ```bash
   # Save server logs
   cp /var/log/smarty/*.log /secure/location/
   
   # Save access logs
   cp /var/log/nginx/access.log /secure/location/
   ```

2. **Rotate All Credentials**
   - Firebase service account key
   - Database password
   - All API keys (Gemini, Tavily, OpenAI)
   - Hugging Face tokens

3. **Enable Enhanced Logging**
   ```bash
   export LOG_LEVEL=DEBUG
   export SECURITY_LOGGING=true
   ```

4. **Review Access Logs**
   ```bash
   # Look for unusual patterns
   grep "dev-user" /var/log/smarty/*.log
   grep "401" /var/log/smarty/*.log | wc -l
   grep "POST.*export\|all\|dump" /var/log/smarty/*.log
   ```

#### Short-Term Actions (Within 24 Hours)
1. **Identify Scope**
   - What data was accessed?
   - Which users affected?
   - How long was breach active?

2. **Contain Breach**
   - Block suspicious IPs
   - Temporarily disable affected features
   - Enable additional authentication checks

3. **Notify Stakeholders**
   - Security team
   - Legal/compliance
   - Affected users (if required by law)

#### Long-Term Actions (Within 1 Week)
1. **Root Cause Analysis**
   - How did breach occur?
   - What controls failed?
   - How to prevent recurrence?

2. **Implement Additional Controls**
   - Enhanced monitoring
   - Additional validation
   - Stricter rate limits

3. **Update Documentation**
   - Incident report
   - Lessons learned
   - Updated procedures

---

## 📊 Security Metrics

### Track These Metrics

| Metric | Target | Alert Threshold |
|--------|--------|-----------------|
| Failed Auth Attempts | < 100/day | > 500/day |
| 401 Responses | < 5% of requests | > 20% |
| Rate Limit Hits | < 1% of requests | > 10% |
| Suspicious Activity | 0 | > 5/week |
| Security Patches Applied | Within 48h | > 7 days |

### Dashboard Setup

```kotlin
// Add monitoring endpoint
get("/api/admin/security-metrics") {
    val metrics = mapOf(
        "failed_auth_24h" to ErrorTracker.getFailedAuthAttempts(),
        "rate_limit_hits_24h" to RateLimit.getHits(),
        "suspicious_activity_7d" to SecurityLog.getSuspiciousCount()
    )
    call.respond(metrics)
}
```

---

## 🔐 Encryption

### Data at Rest
- [x] Database encryption (PostgreSQL TDE or disk-level)
- [ ] File uploads encrypted before storage
- [ ] Backups encrypted

### Data in Transit
- [x] HTTPS enforced (Hugging Face provides this)
- [ ] Database connections use SSL
- [ ] Internal service communication encrypted

### Key Management
- [ ] Keys stored in secrets manager
- [ ] Key rotation automated
- [ ] Key access logged

---

## 📚 Additional Resources

### Security Standards
- [OWASP Top 10](https://owasp.org/www-project-top-ten/)
- [CWE/SANS Top 25](https://cwe.mitre.org/top25/)
- [NIST Cybersecurity Framework](https://www.nist.gov/cyberframework)

### Tools
- **Dependency Scanning**: `./gradlew dependencyCheckAnalyze`
- **Static Analysis**: Detekt (already configured)
- **Penetration Testing**: OWASP ZAP, Burp Suite

### Training
- [Secure Coding Practices](https://owasp.org/www-project-secure-coding-practices-quick-reference-guide/)
- [Security Awareness](https://owasp.org/www-project-security-awareness/)

---

## ✅ Deployment Sign-Off

### Before Going Live

**Security Team**:
- [ ] Reviewed security configuration
- [ ] Verified Firebase setup
- [ ] Tested authentication
- [ ] Checked rate limiting
- [ ] Reviewed logging configuration

**Development Team**:
- [ ] All tests passing
- [ ] No critical vulnerabilities in dependencies
- [ ] Security headers configured
- [ ] Input validation implemented
- [ ] Error handling doesn't leak info

**Operations Team**:
- [ ] Monitoring configured
- [ ] Alerting set up
- [ ] Backup strategy tested
- [ ] Disaster recovery plan documented
- [ ] Incident response procedure ready

---

**Approved By**: _________________  
**Date**: _________________  
**Next Review**: _________________ (3 months from approval)

---

**Document Version**: 1.0  
**Last Updated**: March 12, 2026  
**Classification**: CONFIDENTIAL
