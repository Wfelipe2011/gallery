# Build & Deploy Instructions (Android)

## Build e instalar via Gradle (Windows)

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" ;
cd Android\src\ && .\gradlew.bat installDebug
```

## Instalar APK manualmente via ADB

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install app\build\outputs\apk\debug\app-debug.apk
```
