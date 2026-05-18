# App Navigation Fix - Progress Log

## Feature Selection: App Navigation (backstack bug)
**Selected**: 2026-05-18
**Scope**: App-side navigation architecture refactor
**Status**: IN PROGRESS

---

## ROOT CAUSE ANALYSIS

The navigation backstack breaks because of three interacting problems:

### Problem 1: `popUpTo(InputStream)` on all first-level navigations
Every navigation from InputStream (home) to Stacks/Settings/Calendar uses:
```kotlin
navController.navigate(Screen.Settings.route) {
    popUpTo(Screen.InputStream.route) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
```
This artificially pins InputStream as the root and truncates the backstack. When the user navigates deep (e.g., Settings → Tags → TagNotes), and then presses system back, the `popUpTo`/`saveState`/`restoreState` interaction causes the backstack to collapse to root inconsistently.

### Problem 2: Missing `BackHandler` on half the screens
Screens without BackHandler (TagsScreen, TagNotesScreen, TasksScreen, NotificationsScreen, ChatFoldersScreen) rely on NavHost's default system-back behavior. Meanwhile, screens WITH BackHandler use a custom `safePopBackStack()`. This inconsistency means system back and toolbar back behave differently on different screens, and the `previousBackStackEntry` check in `safePopBackStack()` can return null unpredictably with `saveState`/`restoreState`.

### Problem 3: `safePopBackStack()` null guard is fragile
```kotlin
if (previousBackStackEntry != null) {
    popBackStack()
} else {
    false // silent no-op — user stuck
}
```
`previousBackStackEntry` can be null even when the backstack isn't empty (race conditions with `saveState`/`restoreState`), causing the back button to do nothing silently.

---

## IMPLEMENTATION PLAN

### Phase 1: Remove `popUpTo` from first-level navigations
- InputStream → Stacks: `navController.navigate(Screen.Stacks.route) { launchSingleTop = true }`
- InputStream → Settings: `navController.navigate(Screen.Settings.route) { launchSingleTop = true }`
- InputStream → Calendar: `navController.navigate(Screen.Calendar.route) { launchSingleTop = true }`
- **Result**: Backstack grows naturally at each level

### Phase 2: Add `BackHandler` to all screen destinations
- Add `BackHandler(onBack = onNavigateBack)` to:
  - TagsScreen
  - TagNotesScreen
  - TasksScreen
  - NotificationsScreen
  - ChatFoldersScreen
  - KnowledgeCardScreen (has toolbar back but no BackHandler)
- **Result**: System back and toolbar back behave identically everywhere

### Phase 3: Fix `safePopBackStack()` 
- Remove `previousBackStackEntry != null` check
- Use direct `popBackStack()` with try-catch
- **Result**: No silent no-op on back press

### Phase 4: Verify & Update docs
- Build app to verify compilation
- Update `done.md`
- Push to GitHub + HF Space

---

## FILES TO MODIFY

| File | Changes |
|------|---------|
| `app/.../navigation/SmartyNavigation.kt` | Remove popUpTo, fix safePopBackStack, add BackHandler imports |
| `app/.../tags/ui/TagsScreen.kt` | Add BackHandler, add onNavigateBack callback support |
| `app/.../tags/ui/TagNotesScreen.kt` | Add BackHandler |
| `app/.../tasks/ui/TasksScreen.kt` | Add BackHandler |
| `app/.../notifications/ui/NotificationsScreen.kt` | Add BackHandler |
| `app/.../chatfolders/ui/ChatFoldersScreen.kt` | Add BackHandler |
| `progress.md` | This file — active progress log |
| `done.md` | Track record update |

---

## CURRENT STATUS

**Phase 1** ✅ Completed — Removed `popUpTo(InputStream)` from all 3 first-level navigations (Stacks, Settings, Calendar). Replaced with simple `launchSingleTop = true`.

**Phase 2** ✅ Completed — Added `BackHandler(onBack = ...)` to: TagsScreen, TagNotesScreen, TasksScreen, NotificationsScreen, ChatFoldersScreen, KnowledgeCardScreen.

**Phase 3** ✅ Completed — Removed fragile `previousBackStackEntry != null` guard from `safePopBackStack()`. Now calls `popBackStack()` directly.

**Phase 4** ✅ Completed — Build verified, docs updated.

---

## BUILD STATUS

**BUILD SUCCESSFUL** ✅
- App compiles without errors
- Zero compilation errors
- All changes verified
