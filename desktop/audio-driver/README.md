# Spatial Launcher Audio — Windows virtual speaker package
#
# This milestone ships a SignPath-signed Virtual Audio Driver (MikeTheTech /
# Microsoft Simple Audio Sample lineage) and renames the playback endpoint to
# **Spatial Launcher Audio** so it appears under Windows Sound with our product
# name. A future WDK rebuild can bake the name into the INF/.sys permanently.
#
# IMPORTANT (Windows 10/11 + Secure Boot): SignPath Authenticode alone is NOT
# Microsoft kernel attestation. On most PCs the device installs then shows
# Device Manager Code 52 and never appears under Sound. Until we ship an
# attestation-signed package, use Steam Streaming Speakers / Virtual Desktop
# Audio as the Headset sink (Desktop already falls back to those).
#
# Dist package (signed — do not edit INF or the catalog signature breaks):
#   dist/x64/VirtualAudioDriver.sys
#   dist/x64/VirtualAudioDriver.inf
#   dist/x64/virtualaudiodriver.cat
#
# Install (elevated):
#   powershell -ExecutionPolicy Bypass -File tools\install_spatial_audio_driver.ps1
#
# Uninstall:
#   powershell -ExecutionPolicy Bypass -File tools\uninstall_spatial_audio_driver.ps1

See THIRD_PARTY_NOTICES.md for licenses.
