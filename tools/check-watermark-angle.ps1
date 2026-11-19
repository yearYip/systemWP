param(
    [string]$GradleCacheRoot = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1",
    [string]$JavaHome = "$env:ProgramFiles\Android\Android Studio\jbr"
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$compilerJars = @(rg --files $GradleCacheRoot `
    -g 'kotlin-compiler-embeddable-2.2.10.jar' `
    -g 'kotlin-stdlib-2.2.10.jar' `
    -g 'kotlin-script-runtime-2.2.10.jar' `
    -g 'kotlinx-coroutines-core-jvm-1.9.0.jar' `
    -g 'annotations-13.0.jar')
if ($compilerJars.Count -lt 5) { throw 'Required Kotlin compiler jars are missing from the local Gradle cache.' }
$compilerClasspath = $compilerJars -join ';'
$outputDirectory = Join-Path $projectRoot 'build\angle-check'
New-Item -ItemType Directory -Force $outputDirectory | Out-Null
$outputJar = Join-Path $outputDirectory 'angle.jar'
$javaExecutable = Join-Path $JavaHome 'bin\java.exe'
$sources = @(
    (Join-Path $projectRoot 'app\src\main\java\com\demo\systemwp\WatermarkLocator.kt'),
    (Join-Path $projectRoot 'app\src\main\java\com\demo\systemwp\WatermarkSpectralTemplate.kt'),
    (Join-Path $projectRoot 'app\src\main\java\com\demo\systemwp\WatermarkAngleEstimator.kt'),
    (Join-Path $PSScriptRoot 'WatermarkAngleCheck.kt')
)
& $javaExecutable -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -classpath $compilerClasspath -d $outputJar @sources
if ($LASTEXITCODE -ne 0) { throw 'Kotlin compilation failed.' }
& $javaExecutable -cp "$outputJar;$compilerClasspath" com.demo.systemwp.WatermarkAngleCheckKt
if ($LASTEXITCODE -ne 0) { throw 'Angle checks failed.' }
