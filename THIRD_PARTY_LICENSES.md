# Third-Party Licenses & Open Source Attributions

Fishbowl is free software licensed under the **GNU General Public License v3.0 (GPLv3)**. See [`LICENSE`](LICENSE) or [`LICENSE.md`](LICENSE.md) for the complete license terms.

This document provides full attribution, licensing details, and source code disclosures for all open-source libraries, runtimes, and bundled binary components included in or interfaced with Fishbowl.

---

## 1. GPLv3 Section 6 — Corresponding Source Code Disclosure

In accordance with Section 6 of the GNU General Public License v3.0 (*Conveying Non-Source Forms*), Fishbowl provides open access to the Corresponding Source for all bundled pre-compiled object code and binary assets:

| Bundled Binary Component | Version / Build | Upstream Source Repository | Upstream License |
| :--- | :--- | :--- | :--- |
| **Jellyfin Media Server Core** | `12.1.0` (ARM64) | [jellyfin/jellyfin](https://github.com/jellyfin/jellyfin) | GNU GPLv3 |
| **Microsoft .NET Runtime Engine** | `10.0.12` (ARM64 Bionic) | [dotnet/runtime](https://github.com/dotnet/runtime) | MIT License |
| **Jellyfin FFmpeg Engine** | `7.1.4-Jellyfin` (ARM64) | [jellyfin/jellyfin-ffmpeg](https://github.com/jellyfin/jellyfin-ffmpeg) | GNU GPLv3 / LGPLv3 |
| **SQLite3 Database Engine** | `3.46.1` (`libe_sqlite3.so`) | [sqlite/sqlite](https://sqlite.org) | Public Domain |
| **Fontconfig Engine** | `2.15.0` (`libfontconfig.so`) | [fontconfig/fontconfig](https://gitlab.freedesktop.org/fontconfig/fontconfig) | MIT / Permissive |
| **FreeType Font Engine** | `2.13.2` (`libfreetype.so`) | [freetype/freetype](https://freetype.org) | FreeType License (FTL) |
| **OpenSSL Security Engine** | `3.x` (`libcrypto.so` / `libssl.so`) | [openssl/openssl](https://www.openssl.org) | Apache License 2.0 |
| **ICU Globalization Engine** | `74.x` | [unicode-org/icu](https://icu.unicode.org) | Unicode-DFS-2016 |
| **Termux Bootstrap Archive** | `2026.02.12-r1` (Android 7+) | [termux/termux-packages](https://github.com/termux/termux-packages) | GNU GPLv3 |

> **FFmpeg Compilation Notice:** The bundled mobile FFmpeg transcoding binary is compiled exclusively using free and open-source codecs and libraries. It is built **without** `--enable-nonfree` (such as Fraunhofer FDK AAC) to ensure full legal redistributability under GNU GPLv3 / LGPLv3.

---

## 2. Component Licensing & Copyright Notices

### Jellyfin Media Server Core
- **Copyright:** (c) 2018–2026 Jellyfin Contributors
- **License:** GNU General Public License v3.0 (GPLv3)
- **Website:** <https://jellyfin.org>
- **Notice:** Fishbowl is an independent, unofficial community project. It is not affiliated with, endorsed by, maintained by, or sponsored by the official Jellyfin Project.

### Termux Subsystems & Shared Libraries
- **Copyright:** (c) Fredrik Fornwall and Termux Contributors
- **License:** GNU General Public License v3.0 (GPLv3), with modules under MIT and Apache 2.0
- **Source:** <https://github.com/termux/termux-app>
- **Notice:** Fishbowl incorporates modified components from the Termux project for POSIX process management and terminal session handling. Termux is a trademark of its respective authors.

### Terminal Emulator for Android
- **Copyright:** (c) 2011–2016 Jack Palevich
- **License:** Apache License, Version 2.0
- **Source:** <https://github.com/jackpal/Android-Terminal-Emulator>

```text
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

### Microsoft .NET Runtime
- **Copyright:** (c) .NET Foundation and Contributors
- **License:** MIT License
- **Source:** <https://github.com/dotnet/runtime>

```text
The MIT License (MIT)

Copyright (c) .NET Foundation and Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### SQLite
- **Author:** D. Richard Hipp and the SQLite Development Team
- **License:** Public Domain
- **Source:** <https://www.sqlite.org>
- All code and documentation in SQLite have been dedicated to the public domain by the authors.

### OpenSSL Toolkit
- **Copyright:** (c) 1998–2026 The OpenSSL Project Authors
- **License:** Apache License, Version 2.0
- **Source:** <https://www.openssl.org>

### FreeType Project
- **Copyright:** (c) 1996–2002, 2006 David Turner, Robert Wilhelm, Werner Lemberg
- **License:** The FreeType Project LICENSE (FTL) / GNU GPLv2
- **Source:** <https://freetype.org>

### Fontconfig
- **Copyright:** (c) 2000, 2001, 2002, 2003, 2004 Keith Packard, (c) 2005 Patrick Lam, (c) 2009 Roozbeh Pournader
- **License:** MIT / Permissive License
- **Source:** <https://gitlab.freedesktop.org/fontconfig/fontconfig>

### AndroidX & Android Open Source Project (AOSP)
- **Copyright:** (c) The Android Open Source Project
- **License:** Apache License, Version 2.0
- **Source:** <https://android.googlesource.com>

---

## 3. Trademark Disclaimers

- **Jellyfin:** "Jellyfin" is a trademark of the Jellyfin Project. Any use of the Jellyfin name within Fishbowl is solely for descriptive purposes to indicate software compatibility and interoperability under nominative fair use.
- **Termux:** "Termux" is a trademark of the Termux Project. Fishbowl is an independent derivative work not affiliated with or endorsed by Termux.
- **Android, Google Play:** Android is a trademark of Google LLC.
- All other trademarks and registered trademarks are the property of their respective owners.

---

## 4. Disclaimer of Warranty & Limitation of Liability

THERE IS NO WARRANTY FOR THE PROGRAM, TO THE EXTENT PERMITTED BY APPLICABLE LAW. EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR OTHER PARTIES PROVIDE THE PROGRAM "AS IS" WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE PROGRAM IS WITH YOU. SHOULD THE PROGRAM PROVE DEFECTIVE, YOU ASSUME THE COST OF ALL NECESSARY SERVICING, REPAIR OR CORRECTION.

IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN WRITING WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MODIFIES AND/OR CONVEYS THE PROGRAM AS PERMITTED ABOVE, BE LIABLE TO YOU FOR DAMAGES, INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR CONSEQUENTIAL DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE PROGRAM (INCLUDING BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES OR A FAILURE OF THE PROGRAM TO OPERATE WITH ANY OTHER PROGRAMS), EVEN IF SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
