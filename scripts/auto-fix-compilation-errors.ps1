# Auto-fix compilation errors using Claude Code
# Usage: .\auto-fix-compilation-errors.ps1

Write-Host "Starting automated compilation error fixing..." -ForegroundColor Green

# Build the project and capture errors
Write-Host "Building project to capture compilation errors..." -ForegroundColor Yellow
$buildOutput = & .\gradlew.bat assembleDebug 2>&1

# Check if build failed
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed. Analyzing errors with Claude Code..." -ForegroundColor Red
    
    # Save build output to file for Claude Code analysis
    $buildOutput | Out-File -FilePath "build-errors.log" -Encoding UTF8
    
    # Use Claude Code to analyze and fix errors
    Write-Host "Running Claude Code analysis..." -ForegroundColor Cyan
    cd E:\Android\OpenClaw-Android
    claude --permission-mode bypassPermissions --print "Fix these Android Kotlin compilation errors from the build log:" (Get-Content "build-errors.log" -Raw)
    
    Write-Host "Claude Code analysis complete. Please review the suggested fixes." -ForegroundColor Green
} else {
    Write-Host "Build successful! No compilation errors found." -ForegroundColor Green
}

Write-Host "Automated error fixing process completed." -ForegroundColor Green