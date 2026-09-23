# GNU GENERAL PUBLIC LICENSE

Version 3, 29 June 2007

Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>
Everyone is permitted to copy and distribute verbatim copies of this license document, but changing it is not allowed.

## Fishbowl License Overview

Fishbowl is free software: you can redistribute it and/or modify it under the terms of the **GNU General Public License** as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but **WITHOUT ANY WARRANTY**; without even the implied warranty of **MERCHANTABILITY** or **FITNESS FOR A PARTICULAR PURPOSE**. See the full GNU General Public License at <https://www.gnu.org/licenses/gpl-3.0.html> for more details.

---

## Third-Party Components & License Exceptions

Fishbowl bundles and interfaces with several open-source components, each subject to its own licensing terms:

### 1. Terminal Emulator for Android
- **Component:** `terminal-emulator` and `terminal-view`
- **Origin:** Jack Palevich / Terminal Emulator for Android (<https://github.com/jackpal/Android-Terminal-Emulator>)
- **License:** Apache License, Version 2.0 (<https://www.apache.org/licenses/LICENSE-2.0>)

### 2. Termux Container Foundation
- **Component:** `termux-shared` and terminal subsystem integration
- **Origin:** Termux Project (<https://github.com/termux/termux-app>)
- **License:** GNU General Public License v3.0 (GPLv3). Refer to `termux-shared/LICENSE.md` for specific library terms.

### 3. Jellyfin Media Server Core
- **Component:** `jellyfin` runtime binaries and `Jellyfin.Server.dll`
- **Origin:** Jellyfin Project (<https://jellyfin.org>)
- **License:** GNU General Public License v3.0 (GPLv3)
- **Notice:** Fishbowl is an independent unofficial host and is not maintained by or affiliated with the Jellyfin core project.

### 4. Microsoft .NET Runtime
- **Component:** Native Bionic ARM64 .NET 10 host (`dotnet`) and runtime assemblies
- **Origin:** .NET Foundation / Microsoft Corporation (<https://github.com/dotnet/runtime>)
- **License:** MIT License (<https://opensource.org/licenses/MIT>)

### 5. Jellyfin-FFmpeg
- **Component:** Native ARM64 media transcoding engine (`ffmpeg`, `ffprobe`)
- **Origin:** FFmpeg Project / Jellyfin Project (<https://github.com/jellyfin/jellyfin-ffmpeg>)
- **License:** GNU General Public License v3.0 (GPLv3) / LGPLv3

### 6. SQLite
- **Component:** Local database engine (`libe_sqlite3.so`)
- **Origin:** SQLite Development Team (<https://www.sqlite.org>)
- **License:** Public Domain

---

## Disclaimer of Warranty

THERE IS NO WARRANTY FOR THE PROGRAM, TO THE EXTENT PERMITTED BY APPLICABLE LAW. EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR OTHER PARTIES PROVIDE THE PROGRAM "AS IS" WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE PROGRAM IS WITH YOU. SHOULD THE PROGRAM PROVE DEFECTIVE, YOU ASSUME THE COST OF ALL NECESSARY SERVICING, REPAIR OR CORRECTION.

## Limitation of Liability

IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN WRITING WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MODIFIES AND/OR CONVEYS THE PROGRAM AS PERMITTED ABOVE, BE LIABLE TO YOU FOR DAMAGES, INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR CONSEQUENTIAL DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE PROGRAM (INCLUDING BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES OR A FAILURE OF THE PROGRAM TO OPERATE WITH ANY OTHER PROGRAMS), EVEN IF SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
