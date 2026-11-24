# Nokor APK protection

## Enabled for release variants
- R8 code shrinking/obfuscation and optimization.
- Android resource shrinking.
- Conservative keep rules for the Java AWT/JNI bridge, Android components,
  reflection metadata, and Gson serialized field names.
- CI verifies the release APK signature with `apksigner verify`.

## Important limitations
- APK signing detects unauthorized changes to an installed package when Android
  enforces update-signature continuity. It does not prevent repackaging with a
  different certificate or guarantee that a device is uncompromised.
- No generic string-encryption pass was added: the launcher uses reflection,
  JNI, runtime-loaded classes and external assets. Blindly encrypting strings
  can break authentication, Java runtime loading, graphics and game startup.
  Do not embed private API keys or signing credentials in the APK; keep secrets
  on a backend.
- Runtime anti-tamper / Play Integrity enforcement needs a trusted backend and
  a configured Play Console project. It is not safely enforceable by a local
  APK-only check, so this project does not pretend to provide that guarantee.

## Build
Use the existing CI workflow or run:
`gradle :app_mojo:assembleFullPubRelease`

Test the resulting release APK on supported devices, especially Java AWT,
Minecraft launch, Microsoft/Ely authentication, file dialogs and native
rendering before publishing.
