name: Build OmniScan APK

on:
  push:
    branches: [ main, master ]
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: '8.5'

      # Works two ways: project files at repo root, OR a single project .zip uploaded to the repo
      - name: Locate project
        id: loc
        run: |
          if [ -f settings.gradle.kts ]; then
            echo "dir=." >> "$GITHUB_OUTPUT"
          else
            ZIP="$(ls *.zip | head -n1)"
            echo "Unzipping $ZIP"
            unzip -q -o "$ZIP" -d _project
            echo "dir=_project" >> "$GITHUB_OUTPUT"
          fi

      - name: Build debug APK
        working-directory: ${{ steps.loc.outputs.dir }}
        run: gradle assembleDebug --no-daemon

      - uses: actions/upload-artifact@v4
        with:
          name: OmniScan-debug-apk
          path: ${{ steps.loc.outputs.dir }}/app/build/outputs/apk/debug/app-debug.apk
