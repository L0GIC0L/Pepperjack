# Pepperjack

A SPICE circuit simulator for Android in the spirit of Multisim and LTspice IV.
Draw a schematic or paste a netlist, run it through a full
[ngspice](https://ngspice.sourceforge.io/) engine on the phone, and read the
waveforms, Bode plots and operating points. Built with Material 3 and designed
for foldables.

<p>
  <img src="docs/screenshots/home-phone.png" width="200" alt="Circuit list on a phone">
  <img src="docs/screenshots/editor-phone-ce.png" width="200" alt="Schematic editor on a phone">
  <img src="docs/screenshots/editor-phone-clip.png" width="200" alt="Schematic editor in dark theme">
</p>
<img src="docs/screenshots/editor-unfolded-mosfet.png" width="620" alt="Editor and results side by side on an unfolded foldable">

## Features

**Simulation**
- ngspice 47 runs natively on the device (arm64-v8a and x86_64), including the
  XSPICE code models that Multisim netlists rely on, KLU, CIDER and OSDI.
- Operating point, transient, AC and DC sweep from the schematic; anything
  ngspice supports from a netlist (`.noise`, `.tf`, `.four`, `.meas`, `.control`
  scripts, …).
- Simulations run in the background with progress, and can be stopped.

**SPICE dialects** — chosen app-wide in Settings or per circuit:

| Dialect | ngspice mode | Use it for |
| --- | --- | --- |
| ngspice / SPICE3 / Multisim | native | SPICE3f5, XSPICE and Multisim exports |
| LTspice | `lta` | LTspice netlists and libraries |
| PSpice / OrCAD | `psa` | Vendor PSpice models (`VALUE`, `TABLE`, `IF`) |
| LTspice + PSpice (default) | `ltpsa` | Mixed decks; the most forgiving |
| HSPICE | `hsa` | Foundry model cards |
| Berkeley SPICE3 | `s3` | Strict SPICE3 behaviour |

Pepperjack also fills two LTspice gaps that ngspice has:
- `Rser=`, `Rpar=`, `Cpar=` and `Lser=` on inductors, capacitors and voltage
  sources are rewritten into explicit parts.
- `.step` (`list`, linear, `oct`, `dec`; parameters and sources) runs one
  simulation per value and overlays the traces.

**Schematic editor**
- 22 parts: R, L, C, voltage and current sources (DC, AC, sine, pulse, any
  SPICE source), diode, zener, LED, NPN, PNP, N/P MOSFETs, ideal and
  rail-limited op-amps, E/G/H/F controlled sources, behavioral source, switch,
  ground and net labels — with generic models such as 2N3904, 2N2222, 1N4148,
  1N4007, 2N7000 and IRFZ44N.
- Drag or tap-tap wiring, rubber-band moves, rotate, mirror, duplicate,
  undo/redo, junction dots and open-pin markers.
- Probe tool: tap a wire for its voltage or a part for its current; probes take
  their trace's color, as in LTspice.
- SPICE directives on the sheet (`.param`, `.model`, `.include`, `.meas`, …) and
  the generated netlist always one tab away.

**Netlists and models**
- Syntax-highlighted netlist editor; import `.cir`, `.net`, `.sp` and LTspice
  netlist exports (View → SPICE Netlist), or open them from a file manager.
- Model library: add vendor `.lib`, `.mod`, `.sub` files and `.include` them.
- Export any netlist, and any plot as CSV.

**Results**
- Waveforms with pinch zoom on each axis, pan, and two cursors with Δ and 1/Δ.
- Bode plots (magnitude and phase) for AC, an operating-point table, and the
  full ngspice log.

**Material You and foldables**
- Material 3 with dynamic color on Android 12+, a warm fallback palette, light
  and dark themes, and a themed launcher icon.
- Navigation bar on phones, navigation rail on larger windows.
- Unfolded foldables and tablets show the editor and results side by side,
  split exactly at the hinge when it separates the screen. In tabletop posture
  the plots sit above the fold and the editor below it, where it is easy to
  touch. Mouse-wheel zoom works on DeX, Chromebooks and tablets with a
  trackpad.

## Install

Every push builds APKs in GitHub Actions: open the latest run under
**Actions → Android**, then download the `pepperjack-apks` artifact. Tagging a
commit `v*` publishes the release APK on the **Releases** page.

Requires Android 8.0 (API 26) or newer.

## Build it yourself

You need JDK 17 or newer, the Android SDK, and NDK r28 or newer (CI uses
29.0.14206865).

```sh
# 1. Cross-compile ngspice into app/src/main/jniLibs (a few minutes; needs cc and make)
ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865 scripts/build-ngspice-android.sh

# 2. Build, test and install
./gradlew testDebugUnitTest assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Skipping step 1 still gives a working app; it just reports that the
simulation engine is not bundled.

`scripts/check-netlists.sh` runs exported netlists through desktop ngspice; CI
uses it to make sure every bundled example simulates:

```sh
PEPPERJACK_EXPORT_DIR=/tmp/netlists ./gradlew testDebugUnitTest --tests '*ExampleNetlistExport*'
scripts/check-netlists.sh /tmp/netlists
```

### Signed releases

Without signing secrets the release APK is signed with the debug key, so it
installs but cannot update a Play Store build. To sign with your own key, add
these repository secrets:

| Secret | Value |
| --- | --- |
| `PEPPERJACK_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `PEPPERJACK_KEYSTORE_PASSWORD` | keystore password |
| `PEPPERJACK_KEY_ALIAS` | key alias |
| `PEPPERJACK_KEY_PASSWORD` | key password |

## How it fits together

| Path | What it does |
| --- | --- |
| `app/src/main/java/.../model` | Circuit data, part symbols and pins |
| `app/src/main/java/.../spice` | Netlister, built-in models, LTspice translation and `.step` |
| `app/src/main/java/.../sim` | ngspice engine wrapper and result types |
| `app/src/main/cpp` | JNI bridge that `dlopen`s `libngspice.so` |
| `app/src/main/java/.../ui` | Compose screens, schematic canvas, charts, adaptive layout |
| `scripts/build-ngspice-android.sh` | Cross-compiles ngspice and its code models with the NDK |

## Not supported yet

- Opening LTspice `.asc` schematics directly (export the netlist from LTspice).
- Nested `.step` sweeps (only the first `.step` is swept).
- LTspice-only devices with no ngspice equivalent, and `.wave`.
- The XSPICE `table2D`/`table3D` models (their source is GPLv2, so they are
  not bundled).

## License

Pepperjack is MIT licensed (see [LICENSE](LICENSE)).

The bundled simulator is [ngspice 47](https://sourceforge.net/projects/ngspice/files/ng-spice-rework/47/),
© the ngspice team, under the Modified BSD license, except: KLU and numparam
(LGPLv2), OSDI (MPL 2.0), and XSPICE (public domain). The exact source used is
the `ngspice-47.tar.gz` release, downloaded and verified by
`scripts/build-ngspice-android.sh`.
