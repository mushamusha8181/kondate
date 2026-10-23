$ErrorActionPreference = "Stop"
if (-not (Get-Command gradle -ErrorAction SilentlyContinue)) {
  Write-Error "Gradle 9.6.0+ が必要です。Android Studioから開くか、Gradleをインストールしてください。"
}
gradle wrapper --gradle-version 9.6.0
./gradlew.bat --version
