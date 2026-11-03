# MOSAIC Workspace

MOSAIC is a desktop-inspired Android floating workspace. A slim edge handle opens a tile shelf; each tool opens as its own movable, resizable window with macOS-style red / yellow / green controls.

## Project

- App name: **MOSAIC Workspace**
- Application ID: `app.mosaic.workspace`
- Android: minimum 10 (API 29), target 35, compile 36
- Kotlin + Android Views
- Shizuku API `13.1.5`
- Reused FFT spectrum engine from the previous LumenWave visualizer project

## Included modules (16)

1. Calculator — arithmetic, decimals, brackets, remainder (modulo) and negative values.
2. Translate — Gemini-backed translation.
3. AI Helper — Gemini 3.7 Flash via a private backend; voice input, read-aloud responses, and image, PDF, text/code, ZIP/Office attachments.
4. Google Search — launches a Google search or common web destination.
5. Visualizer — MediaProjection playback capture + FFT spectrum engine.
6. Voice Meter — live microphone RMS test with a fixed green/yellow/orange/red gradient.
7. Speech Subtitles — live speech recognition using the device recognition provider.
8. FPS Monitor — measures MOSAIC's own Choreographer render callbacks and shows active display refresh rate.
9. Notes — local persistent notes.
10. Stopwatch — start, pause and reset.
11. Timer — configurable countdown.
12. Unit Converter — distance, mass, temperature and length.
13. QR Maker — generates a QR bitmap.
14. Color Studio — HEX preview and RGB conversion.
15. Battery Info — battery level and charging status.
16. System Shortcuts — opens Android settings pages.

## Important platform limits

- Android requires the user to grant `SYSTEM_ALERT_WINDOW` before other apps can be covered.
- Shizuku must be installed, started by the user, and approved for MOSAIC in Shizuku. The setup screen detects the binder, requests Shizuku permission and displays the shell/root UID. It is not a way to silently grant Android runtime permissions.
- Background operation is requested through the system battery-optimization screen. Some OEM power managers have additional controls.
- Playback capture requires the user's MediaProjection consent each session. Android only shares playback from apps that permit capture; Shizuku cannot override an app's capture policy.
- Voice Meter and Speech Subtitles request microphone permission only when first used.
- The FPS panel reports MOSAIC's own overlay frame callback rate plus the display refresh rate. Android does not expose a universal, reliable per-game FPS counter to ordinary apps; do not interpret it as the game's FPS.
- Some games intentionally block overlays or restrict touch input. MOSAIC cannot force those games to allow overlays.

## Build the APK

Open the `MOSAIC` directory in Android Studio and let Gradle sync. Use JDK 17 and install Android SDK platform 36 / Build Tools 36.0.0. The included Gradle bootstrap downloads Gradle 8.13 on first use. Then run:

```bash
./gradlew assembleDebug
```

On Windows, use `gradlew.bat assembleDebug`. Android Studio can also build via **Build → Make Project**.

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Or push the project to GitHub: `.github/workflows/build-apk.yml` builds the debug APK and uploads it as the `MOSAIC-debug-apk` artifact.

## Gemini 3.7 Flash backend with GitHub Secrets

The AI Helper includes speech-to-text input (MIC) and text-to-speech output (SAY), subject to the installed Android speech services. The Android client never contains the Gemini API key. It sends requests to the Node backend in `backend/`. The backend reads its credentials from environment variables and talks to the Gemini REST API using `gemini-3.7-flash`. Chat turns in the current AI window are sent as a compact transcript so follow-up questions retain context.

### GitHub repository secrets

Add these in **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `GEMINI_API_KEY` | Your Google AI Studio Gemini API key |
| `MOSAIC_CLIENT_TOKEN` | A long random token used by the app to authenticate to your backend |
| `GCP_PROJECT_ID` | Google Cloud project ID |
| `GCP_REGION` | Optional; use `asia-southeast2` for Jakarta region |
| `GCP_SA_KEY` | JSON key for a Google Cloud service account with Cloud Run deploy, Cloud Build, Artifact Registry and Secret Manager permissions |

Enable the Cloud Run, Cloud Build, Artifact Registry and Secret Manager APIs in that Google Cloud project. The service account also needs permission to grant the Cloud Run runtime identity access to the two secrets. Run the `Deploy MOSAIC Gemini backend` workflow manually once, or push a change under `backend/` on `main`.

The workflow stores the Gemini key and app token in Google Secret Manager, then deploys `backend/` to Cloud Run. The backend uses only Node.js built-in HTTP and ZIP/DEFLATE support, plus an application bearer token and basic per-IP rate limiting. Do not publish your token in a screenshot or commit it to the repository.

After deployment, copy the Cloud Run service URL. In MOSAIC open **Settings → AI Connection** and enter:

- Backend URL: `https://YOUR-CLOUD-RUN-URL`
- Backend access token: the same value as `MOSAIC_CLIENT_TOKEN`

Tap **Save AI Connection**. The app automatically calls `/api/generate`.

### Local backend test

```bash
cd backend
npm install
GEMINI_API_KEY="..." MOSAIC_CLIENT_TOKEN="..." npm start
```

The app must be able to reach that backend from the phone. Debug builds permit cleartext HTTP for local LAN testing; use your computer's reachable IP address and a trusted network. Release builds block cleartext HTTP and should use HTTPS.

## Multi-window behavior

- The edge handle is draggable vertically only.
- Every tool window can be dragged by its title bar, resized from the lower-right corner, minimized (yellow), maximized/restored (green), or closed (red).
- At four or more open tool windows the app displays the warning once. **Ignore** dismisses it and leaves the windows open. **Accept** keeps one random window and closes the others.
- The one-time warning flag is stored locally. Clear MOSAIC app data to show it again.

## Privacy

- Notes are stored in local app preferences.
- Audio samples for visualizer and voice meter are processed in memory and are not written to disk.
- AI prompts and selected attachments are sent to the backend configured by the user. The backend forwards relevant text/image/PDF content to Gemini. ZIP and Office archives are unpacked server-side and supported text entries are extracted.
- Do not upload sensitive documents unless you trust the backend and Google AI service handling your request.
