#Requires -Version 7.0
param()

$ErrorActionPreference = 'Stop'
$monitRoot = Split-Path $PSScriptRoot -Parent
$monitRoles = @('pm', 'planning', 'design', 'backend', 'android', 'qa-devops')
$monitRequired = @('AGENTS.md', 'docs/ai/definition-of-done.md') + @(
    $monitRoles | ForEach-Object { "docs/ai/roles/$_.md" }
)
$monitErrors = [System.Collections.Generic.List[string]]::new()
foreach ($monitRelative in $monitRequired) {
    if (-not (Test-Path -LiteralPath (Join-Path $monitRoot $monitRelative) -PathType Leaf)) {
        $monitErrors.Add("Missing required document: $monitRelative")
    }
}
if ($monitErrors.Count) { throw ($monitErrors -join [Environment]::NewLine) }

$monitFiles = @((Get-Item -LiteralPath (Join-Path $monitRoot 'AGENTS.md'))) + @(
    Get-ChildItem -LiteralPath (Join-Path $monitRoot 'docs/ai') -Recurse -File -Filter '*.md'
    Get-ChildItem -LiteralPath (Join-Path $monitRoot '.agents/skills') -Recurse -File -Filter '*.md'
)
$monitExpectedHeadings = @(
    '## 1. 역할과 책임', '## 2. 작업 범위', '## 3. 산출물',
    '## 4. 완료 조건', '## 5. 협업 및 인수인계 기준', '## 6. 관련 문서'
)
foreach ($monitFile in $monitFiles) {
    $monitContent = Get-Content -LiteralPath $monitFile.FullName -Raw -Encoding utf8
    if ($monitContent -match '(?m)^(<<<<<<< |=======$|>>>>>>> )') {
        $monitErrors.Add("Conflict marker: $($monitFile.FullName)")
    }
    if ($monitContent -match '(?m)[\t ]+\r?$') {
        $monitErrors.Add("Trailing whitespace: $($monitFile.FullName)")
    }
    # This small checker supports inline links only. Reject reference definitions
    # and full/collapsed reference uses rather than silently skipping their targets.
    if ($monitContent -match '(?m)^ {0,3}\[[^\]\r\n]+\]:|\[[^\]\r\n]+\]\[[^\]\r\n]*\]') {
        $monitErrors.Add("Unsupported reference link in $($monitFile.Name): use an inline Markdown link.")
    }
    foreach ($monitLink in [regex]::Matches($monitContent, '\]\(([^)]+)\)')) {
        $monitTarget = $monitLink.Groups[1].Value.Trim('<', '>')
        if ($monitTarget -match '^[a-zA-Z][a-zA-Z0-9+.-]*:|^#') { continue }
        $monitLocalTarget = [Uri]::UnescapeDataString(($monitTarget -split '#', 2)[0])
        if (-not (Test-Path -LiteralPath (Join-Path $monitFile.DirectoryName $monitLocalTarget))) {
            $monitErrors.Add("Broken link in $($monitFile.Name): $monitTarget")
        }
    }
    if ($monitFile.Directory.Name -eq 'roles') {
        foreach ($monitHeading in $monitExpectedHeadings) {
            if ($monitContent -notmatch ('(?m)^' + [regex]::Escape($monitHeading) + '\r?$')) {
                $monitErrors.Add("Missing section in $($monitFile.Name): $monitHeading")
            }
        }
    }
}
if ($monitErrors.Count) { throw ($monitErrors -join [Environment]::NewLine) }
Write-Output "PASS: $($monitFiles.Count) documents; required files, role sections, relative links and whitespace checked."
