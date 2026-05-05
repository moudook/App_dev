# BATCH-08: Asset and Dependency Optimization Analysis

**App**: Smarty (Loum) Android Application
**Analysis Date**: 2025-12-31
**Focus Areas**: Image Assets, Unused Resources, Dependencies, ProGuard Rules, Debug Code, Library Alternatives

---

## Executive Summary

This analysis identifies significant optimization opportunities in the Smarty Android app related to assets, dependencies, and build configuration. Key findings include:

- **Vosk speech model** bundled in assets (~40-50MB estimated)
- **Material Icons Extended** library adding ~5MB to APK
- **Dual JSON serialization** libraries (Gson + kotlinx.serialization)
- **Potentially unused Firebase modules** (messaging, analytics, remote config)
- **Unused drawable resources** identified
- **Heavy debug logging** (1152+ Log calls across 95 files)

---

## 1. Large Image Assets Analysis

### Drawable Resources

| Asset | Location | Type | Optimization | Est. Savings |
|-------|----------|------|--------------|--------------|
| `new_app_icon.png` | res/drawable/ | PNG | **Potentially unused** - No references found | 100% if removed |
| `startup_logo.png` | res/drawable/ | PNG | **Potentially unused** - No references found | 100% if removed |
| `app_icon_foreground.png` | res/drawable/ | PNG | Used by ic_launcher_foreground.xml | Keep as-is |

### Mipmap Launcher Icons

| Resource | Densities Present | Recommendation |
|----------|-------------------|----------------|
| ic_launcher.png | mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi | Convert to WebP (15-25% smaller) |
| ic_launcher_round.png | mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi | Convert to WebP (15-25% smaller) |

**Recommendation**: Convert PNG launcher icons to WebP format for ~20% size reduction.

### Large Assets in /assets/ Directory

| Asset | Est. Size | Purpose | Optimization |
|-------|-----------|---------|--------------|
| **vosk-model-small-hi-0.22/** | ~40-50 MB | Offline Hindi speech recognition | See Section 6 for alternatives |
| vosk-model-small-hi-0.22/am/final.mdl | ~30-40 MB | Acoustic model | Primary size contributor |
| vosk-model-small-hi-0.22/graph/*.fst | ~5-10 MB | Language model graphs | Required for model |

**CRITICAL**: The Vosk model is likely the **largest contributor to APK size**. Consider:
1. Download-on-demand from server
2. Android App Bundle with on-demand delivery
3. Smaller model variant (vosk-model-small-en-us-0.15 is ~40MB smaller)

---

## 2. Unused Resources Analysis

### Potentially Unused Drawables

| Resource | Evidence | Recommendation |
|----------|----------|----------------|
| `new_app_icon.png` | No @drawable/new_app_icon or R.drawable.new_app_icon references | Remove or verify usage |
| `startup_logo.png` | No references found in code or XML | Remove or verify usage |

### Unused String Resources

| String Resource | Used In | Status |
|-----------------|---------|--------|
| widget_tap_to_add | Not referenced | Potentially unused |
| widget_capture_hint | Not referenced | Potentially unused |

**Recommendation**: Run Android Lint unused resources check:
```bash
./gradlew lintDebug --check UnusedResources
```

### Potentially Unused Colors (in colors.xml)

| Color | Usage Status |
|-------|--------------|
| purple_200, purple_500, purple_700 | Legacy template colors - verify if used |
| teal_200, teal_700 | Legacy template colors - verify if used |

---

## 3. Dependencies Optimization

### Current Dependencies Analysis

| Dependency | Size Impact | Status | Recommendation |
|------------|-------------|--------|----------------|
| **material-icons-extended** | ~5 MB | Uses 350 icons across 48 files | Replace with individual icons or keep |
| **vosk-android:0.3.75** | ~2 MB (lib) + 40MB (model) | Used for wake word | Consider on-demand download |
| **pdfbox-android:2.0.27.0** | ~3-4 MB | Used in PDFTextExtractor | Consider lighter alternative |
| **youtube-player:12.1.1** | ~500 KB | **NOT USED** - No references found | **REMOVE** |
| **richtext-commonmark:0.17.0** | ~1 MB | Markdown rendering | Keep - actively used |
| **zxing-core:3.5.3** | ~500 KB | QR code generation | Keep - actively used |

### Duplicate/Redundant Dependencies

| Issue | Details | Recommendation | Savings |
|-------|---------|----------------|---------|
| **Dual JSON Libraries** | Both `gson` and `kotlinx-serialization-json` | Migrate fully to kotlinx.serialization | ~300 KB |
| **Dual OkHttp Usage** | Direct `okhttp` + `ktor-client-okhttp` | Remove direct okhttp if only used via Ktor | ~200 KB |
| **google-http-client-gson** | Bundles Gson for Google APIs | Already have Gson - redundant | Shared |

### Firebase Modules Analysis

| Module | Size | Usage Found | Recommendation |
|--------|------|-------------|----------------|
| firebase-auth | ~1.5 MB | Used in AuthViewModel, AuthRepository | Keep |
| firebase-firestore | ~2 MB | Referenced but verify active usage | Audit usage |
| firebase-crashlytics | ~500 KB | Configured in build.gradle | Keep |
| **firebase-messaging** | ~1 MB | **NO usage found** in code | **REMOVE** - Save ~1 MB |
| **firebase-analytics** | ~500 KB | **NO usage found** in code | **REMOVE** - Save ~500 KB |
| **firebase-config** | ~500 KB | **NO usage found** in code | **REMOVE** - Save ~500 KB |

**Total Firebase Savings**: ~2 MB if unused modules removed

### Koog AI Framework Dependencies

| Module | Purpose | Recommendation |
|--------|---------|----------------|
| koog-agents | Core agent framework | Keep |
| koog-executor-google | Google AI provider | Keep if using Gemini |
| koog-executor-anthropic | Anthropic provider | Keep if using Claude |
| koog-executor-openai | OpenAI provider | Keep if using OpenAI |

**Note**: Consider using only the provider(s) actually needed to reduce size.

---

## 4. ProGuard Rules Analysis

### Current Status: GOOD

The ProGuard rules file is comprehensive with rules for:
- Debug log stripping (Log.d, Log.v)
- All major dependencies covered
- Proper keep rules for serialization

### Missing/Recommended Rules

| Library | Current Status | Recommendation |
|---------|----------------|----------------|
| Vosk | Has rules | Add aggressive shrinking for unused model components |
| Media3 | Has rules | Consider removing unused media3 features |
| Compose | Has rules | Good |

### Optimization Opportunity

Add more aggressive R8 optimization:
```proguard
# Remove unused Ktor features
-assumenosideeffects class io.ktor.util.** { *; }

# Strip unused Koog providers if not using all AI providers
# (Uncomment based on which providers you actually use)
# -dontwarn ai.koog.prompt.executor.google.**
# -dontwarn ai.koog.prompt.executor.anthropic.**
# -dontwarn ai.koog.prompt.executor.openai.**
```

---

## 5. Debug Code/Logging Analysis

### Logging Statistics

| Log Level | Count | Files | Release Impact |
|-----------|-------|-------|----------------|
| Log.d() | ~800 | 70+ | **Stripped by ProGuard** |
| Log.v() | ~100 | 20+ | **Stripped by ProGuard** |
| Log.i() | ~150 | 40+ | **NOT stripped** - remains in release |
| Log.w() | ~80 | 35+ | **NOT stripped** - remains in release |
| Log.e() | ~120 | 50+ | **NOT stripped** - remains in release |

**Total**: 1152+ logging statements across 95 files

### Recommendation

Add these ProGuard rules to strip info/warning logs in release:
```proguard
# Strip Log.i() in release (optional, keeps error context)
-assumenosideeffects class android.util.Log {
    public static int i(...);
}

# Strip Log.w() in release (optional, keeps error context)
-assumenosideeffects class android.util.Log {
    public static int w(...);
}
```

### Files with Heaviest Logging

| File | Log Count | Recommendation |
|------|-----------|----------------|
| CogniViewModel.kt | 142 | Audit for sensitive data |
| VoskWakeWordManager.kt | 75 | Many debug logs - already stripped |
| AssistActivity.kt | 83 | Audit for sensitive data |
| AIResponseParser.kt | 32 | Contains AI response logging |
| AIProviderOrchestrator.kt | 29 | Provider selection logging |

---

## 6. Library Alternatives Analysis

### Heavy Libraries with Lighter Alternatives

| Current Library | Size | Alternative | Alternative Size | Savings |
|-----------------|------|-------------|------------------|---------|
| **pdfbox-android** | ~3-4 MB | pdf-android (iText fork) | ~1.5 MB | ~50% |
| **pdfbox-android** | ~3-4 MB | Android PdfRenderer (built-in) | 0 | 100% (limited features) |
| **vosk-android** | ~2 MB + 40MB model | Android SpeechRecognizer | 0 | 100% (needs internet) |
| **vosk-android** | ~2 MB + 40MB model | On-demand model download | ~2 MB APK | ~40 MB APK savings |
| **material-icons-extended** | ~5 MB | Individual SVG/vector icons | ~50 KB | ~99% |
| **youtube-player** | ~500 KB | **REMOVE** (unused) | 0 | 100% |

### Material Icons Extended Analysis

The app uses **350 icon references across 48 files**. Common icons used:
- Icons.Default.* (most common)
- Icons.Filled.*
- Icons.AutoMirrored.*

**Options**:
1. **Keep as-is**: R8 should tree-shake unused icons (verify with APK Analyzer)
2. **Extract used icons**: Create custom icon set with only needed ~100 icons
3. **Use Material Symbols**: Smaller, variable font-based approach

---

## 7. Code Splitting Opportunities

### Dynamic Feature Module Candidates

| Feature | Dependencies | Est. Size | On-Demand? |
|---------|--------------|-----------|------------|
| **Voice/Wake Word** | Vosk + model | ~42 MB | Yes - only for wake word users |
| **PDF Analysis** | pdfbox-android | ~4 MB | Yes - only when viewing PDFs |
| **Google Drive Backup** | Google API client libs | ~2 MB | Yes - only for backup users |
| **AI Providers** | Koog executors | ~3 MB | Yes - only needed providers |

### App Bundle Configuration

Current build.gradle.kts has good foundation:
- `isMinifyEnabled = true`
- `isShrinkResources = true`

**Add for code splitting**:
```kotlin
android {
    bundle {
        language {
            enableSplit = true  // Split by language
        }
        density {
            enableSplit = true  // Split by screen density
        }
        abi {
            enableSplit = true  // Split by CPU architecture
        }
    }
}
```

---

## 8. Summary of Optimization Opportunities

### High Impact (>1 MB savings each)

| Optimization | Est. Savings | Effort | Priority |
|--------------|--------------|--------|----------|
| On-demand Vosk model download | ~40 MB | High | HIGH |
| Remove unused Firebase modules | ~2 MB | Low | HIGH |
| Remove youtube-player dependency | ~500 KB | Low | HIGH |
| Migrate Gson to kotlinx.serialization | ~300 KB | Medium | MEDIUM |

### Medium Impact (100 KB - 1 MB)

| Optimization | Est. Savings | Effort | Priority |
|--------------|--------------|--------|----------|
| Convert PNG icons to WebP | ~200 KB | Low | MEDIUM |
| Remove unused drawables | ~100-500 KB | Low | MEDIUM |
| Custom material icons subset | ~4 MB | High | LOW |

### Low Impact (<100 KB) but Good Practice

| Optimization | Effort | Priority |
|--------------|--------|----------|
| Enable App Bundle splits | Low | HIGH |
| Strip Log.i/Log.w in release | Low | MEDIUM |
| Clean unused string resources | Low | LOW |

---

## 9. Recommended Action Items

### Immediate (This Sprint)

1. **Remove youtube-player dependency** - Not used anywhere in codebase
2. **Remove unused Firebase modules** (messaging, analytics, config) - No usage found
3. **Remove unused drawables** (new_app_icon.png, startup_logo.png) - Verify first
4. **Enable App Bundle splits** in build.gradle.kts

### Short-term (Next Sprint)

5. **Implement on-demand Vosk model download** - Major APK size reduction
6. **Audit Firebase usage** - Confirm firestore is actively used
7. **Convert launcher PNGs to WebP**

### Long-term

8. **Migrate from Gson to kotlinx.serialization** - Reduce duplicate JSON libs
9. **Consider lighter PDF library** if full PDFBox features not needed
10. **Create custom Material Icons subset** if R8 not effectively tree-shaking

---

## 10. Build Analysis Commands

Run these to verify findings:

```bash
# Analyze APK size breakdown
./gradlew :app:assembleRelease
# Then use Android Studio > Build > Analyze APK

# Find unused resources
./gradlew lintDebug --check UnusedResources

# Check dependency tree
./gradlew :app:dependencies --configuration releaseRuntimeClasspath

# R8 mapping for tree-shaking verification
# Located at: app/build/outputs/mapping/release/mapping.txt
```

---

**Report Generated**: Asset Optimization Specialist Analysis
**Total Estimated Savings**: ~45-50 MB (primarily from Vosk model optimization)
