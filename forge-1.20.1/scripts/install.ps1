# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
#
# Installs the packaged Forge 1.20.1 renderer into a Society: Sunlit Valley
# instance, or removes it again. The renderer replaces the scene, so shader
# mods cannot run beside it: Oculus and the two Colorwheel mods, which require
# Oculus, are disabled by renaming them to .jar.disabled, and -Uninstall puts
# them back. Close the instance before running this.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$GameDirectory,
    [string]$Package,
    [switch]$Uninstall
)
$ErrorActionPreference = 'Stop'

$game = (Resolve-Path -LiteralPath $GameDirectory).Path
$mods = Join-Path $game 'mods'
if (-not (Test-Path -LiteralPath $mods -PathType Container)) {
    throw "$game has no mods folder. Pass the instance's game directory (the folder that holds mods\)."
}
$installedName = 'rtx-remix-forge-1.20.1.jar'
$installed = Join-Path $mods $installedName
# File-name patterns of the shader mods the renderer cannot run beside.
$incompatible = @('oculus-*.jar', 'colorwheel-forge-*.jar', 'colorwheel_patcher-*.jar')

if ($Uninstall) {
    if (Test-Path -LiteralPath $installed) { Remove-Item -LiteralPath $installed -Force }
    foreach ($pattern in $incompatible) {
        Get-ChildItem -LiteralPath $mods -Filter ($pattern + '.disabled') | ForEach-Object {
            $restored = Join-Path $mods ($_.Name -replace '\.disabled$', '')
            Move-Item -LiteralPath $_.FullName -Destination $restored -Force
            Write-Output "Re-enabled $(Split-Path -Leaf $restored)"
        }
    }
    Write-Output "Removed $installedName. The extracted runtime in $game\remix-sdk is left in place; delete it to reclaim the space."
    return
}

if (-not $Package) { throw 'Pass -Package with the packageForge output JAR, or -Uninstall.' }
$packageFile = Get-Item -LiteralPath $Package
if ($packageFile.Extension -ne '.jar') { throw 'Package must be the packaged Forge JAR.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($packageFile.FullName)
try {
    $hasRuntime = $archive.Entries | Where-Object { $_.FullName -like 'native/remix-sdk-*/d3d9.dll' } | Select-Object -First 1
    if (-not $hasRuntime) {
        throw 'This JAR has no runtime payload. Use build\distributions\ from gradlew.bat package, not build\libs\.'
    }
    if (-not $archive.GetEntry('META-INF/mods.toml')) { throw 'This is not a Forge mod JAR.' }
} finally { $archive.Dispose() }

$embeddium = Get-ChildItem -LiteralPath $mods -Filter 'embeddium-*.jar' | Select-Object -First 1
if (-not $embeddium) {
    Write-Warning 'Embeddium is not installed in this instance. The renderer requires it; Sunlit Valley ships it.'
}

foreach ($pattern in $incompatible) {
    Get-ChildItem -LiteralPath $mods -Filter $pattern | ForEach-Object {
        Move-Item -LiteralPath $_.FullName -Destination ($_.FullName + '.disabled') -Force
        Write-Output "Disabled $($_.Name)"
    }
}
# Older copies under another name would load twice.
Get-ChildItem -LiteralPath $mods -Filter 'rtx-remix-forge-1.20.1*.jar' |
    Where-Object { $_.Name -ne $installedName } |
    ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force; Write-Output "Removed old $($_.Name)" }
Copy-Item -LiteralPath $packageFile.FullName -Destination $installed -Force
Write-Output "Installed $installed. The runtime unpacks into $game on first launch."
