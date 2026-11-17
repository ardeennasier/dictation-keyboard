# Dictation Keyboard

An Android keyboard for dictating into any text field. Speech recognition runs entirely on the
phone, and the app has **no internet permission**, so Android itself blocks it from going online.

Phase 1: the keyboard, offline transcription, punctuation, paragraph breaks and setup screens.

## Install

1. **Models (once):** go to the repo's **Actions** tab, open **Package speech models** and tap
   **Run workflow**. When it's done, a **Speech models** release appears under **Releases**.
2. **APK:** every push to `main` builds the app and puts `dictation-keyboard.apk` on the
   **Latest APK** release. Open it on your phone and install it (allow your browser to install
   apps if Android asks).
3. **Open the app** and follow the four setup steps: allow the mic, download a model zip in your
   browser and import it, turn on the keyboard, then switch to it.

## Checking it's offline

- Settings → Apps → Dictation Keyboard → Permissions: there's no network permission to grant.
- Turn on airplane mode and dictate; it works the same.
- The manifest (`app/src/main/AndroidManifest.xml`) strips `INTERNET` even if a library asks for it.

## How it works

```
mic (16 kHz) ─► Silero VAD (splits at pauses) ─► phrase queue ─► Parakeet / Moonshine (ASR thread)
                                                                    │
                                    TranscriptAssembler (paragraph breaks, joins) ─► commitText
```

- Each phrase is transcribed while you're still talking, so when you tap stop only the last
  phrase is left to process.
- The speech model does its own punctuation and capitals. Pauses are used only for paragraph
  breaks (2 s by default, adjustable) and to join phrases split by a mid-sentence pause.
- All recognition runs on one background thread; the mic has its own thread.

## Measurements

The app's **Measurements** section records, per dictation: time from stop to text, peak memory
(PSS), real-time factor, and battery charge used (whole phone, while unplugged). **Copy report**
copies it all as text. **Test with a recording** runs an audio file through the same pipeline for
repeatable tests.

## Code map

| Path | What |
| --- | --- |
| `ime/DictationImeService.kt` | The InputMethodService, with the lifecycle plumbing Compose needs |
| `ime/KeyboardController.kt` | Keyboard logic: record, stop, insert, backspace, enter, switch |
| `ime/KeyboardScreen.kt` | Keyboard UI (mic, waveform, live text) |
| `asr/DictationSession.kt` | VAD → ASR pipeline for one dictation |
| `asr/EngineHolder.kt` | Loads/keeps the model; owns the ASR thread |
| `text/TranscriptAssembler.kt` | Joins phrases, paragraph breaks (unit tested) |
| `text/InsertionFormatter.kt` | Spacing/capitals to fit the cursor position (unit tested) |
| `model/ModelStore.kt` | Imports and lists model packages |
| `ui/MainActivity.kt` | Setup, model choice, settings, file test, measurements |

## Licences

`app/src/main/java/com/k2fsa/sherpa/onnx/` is copied unchanged from
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) v2.0.1 (Apache-2.0). It must match the
native library version set in `.github/workflows/build.yml`. Models: Parakeet TDT 0.6B v2
(CC-BY-4.0, NVIDIA), Moonshine Base (MIT), Silero VAD (MIT).
