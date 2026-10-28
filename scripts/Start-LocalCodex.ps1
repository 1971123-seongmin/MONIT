param(
    [string]$Model = 'monit-local:latest'
)

$ErrorActionPreference = 'Stop'
$ollamaCommand = Get-Command ollama -ErrorAction SilentlyContinue
$ollamaPath = if ($ollamaCommand) { $ollamaCommand.Source } else {
    Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe'
}
if (-not (Test-Path -LiteralPath $ollamaPath)) {
    throw 'Ollama를 설치하고 서버를 실행해 주세요.'
}
if (-not (Get-Command codex -ErrorAction SilentlyContinue)) {
    throw 'Codex CLI를 PATH에 추가해 주세요.'
}
& $ollamaPath show $Model *> $null
if ($LASTEXITCODE -ne 0) {
    throw '먼저 ollama create monit-local -f tools/ollama/Modelfile 명령을 실행해 주세요.'
}
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & codex --oss --local-provider ollama --model $Model
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
