# Gradle wrapper bootstrap

`gradle-wrapper.jar` is a binary; I (Claude) can't write it as text. You need
to materialize it once. **You only do this if Android Studio doesn't already
create it on first sync (which it usually does).**

## Option 1 — Android Studio does it for you (recommended)

1. Open `D:\Work\project-pramana\android` in Android Studio.
2. Studio will detect the missing wrapper jar on first sync and offer to
   download Gradle 8.9. Accept.
3. Studio writes `gradle/wrapper/gradle-wrapper.jar` and creates `gradlew`
   + `gradlew.bat`. Commit those.

## Option 2 — From a terminal

If you have a system Gradle installed (`gradle --version` works), run once:

```powershell
cd D:\Work\project-pramana\android
gradle wrapper --gradle-version 8.9
git add gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties
```

That's it — from then on, use `.\gradlew` instead of `gradle`.

## Why this matters

GitHub Actions in `.github/workflows/android.yml` already bootstraps a wrapper
on its end, so CI works even before you commit the jar. But for **local
device installs** (`./gradlew installDebug`), you need the wrapper checked in.
