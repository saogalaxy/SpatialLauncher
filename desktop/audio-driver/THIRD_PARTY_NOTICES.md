# Third-party notices — Spatial Launcher Audio

## Virtual Audio Driver (MikeTheTech)

- Source: https://github.com/VirtualDrivers/Virtual-Audio-Driver
- Release used: 25.7.14 (SignPath-signed)
- License: MIT (project code); Microsoft sample portions under MS-PL
- Purpose: Provides the kernel virtual speaker/mic used as **Spatial Launcher Audio**
  after endpoint rename by the Spatial Launcher installer.

Spatial Launcher does not claim authorship of the `.sys` binary. Endpoint
friendly names are set to **Spatial Launcher Audio** at install time so Windows
Sound and Desktop Headset mode can target a clearly named product sink.

## Microsoft Windows Driver Samples (Simple Audio Sample / SYSVAD lineage)

- https://github.com/microsoft/Windows-driver-samples/tree/main/audio
- License: Microsoft Public License (MS-PL)
