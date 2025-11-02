param(
  [string]$Root = "src/main/resources/templates",
  [switch]$WhatIf
)

Write-Host "Scanning: $Root"
if (!(Test-Path $Root)) { throw "Not found: $Root" }

$utf8Strict = New-Object System.Text.UTF8Encoding($false, $true)
$sjis = [System.Text.Encoding]::GetEncoding(932)

Get-ChildItem -Path $Root -Recurse -Include *.html | ForEach-Object {
  $file = $_.FullName
  $bytes = [System.IO.File]::ReadAllBytes($file)
  $isUtf8 = $true
  try { [void]$utf8Strict.GetString($bytes) } catch { $isUtf8 = $false }

  if ($isUtf8) {
    Write-Host "OK (utf8):" $file
    return
  }

  Write-Host "Convert (cp932->utf8):" $file -ForegroundColor Yellow
  if ($WhatIf) { return }

  $text = $sjis.GetString($bytes)
  Copy-Item $file "$file.bak" -Force
  [System.IO.File]::WriteAllText($file, $text, New-Object System.Text.UTF8Encoding($false))
}

Write-Host "Done. Created .bak for converted files."

