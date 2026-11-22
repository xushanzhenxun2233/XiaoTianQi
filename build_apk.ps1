$ErrorActionPreference = "Stop"

$projectDir = "C:\Users\wangz\.gemini\antigravity\scratch\XiaoTianQi"
$sdkDir = "C:\Users\wangz\AppData\Local\Android\Sdk"
$buildTools = "$sdkDir\build-tools\34.0.0"
$androidJar = "$sdkDir\platforms\android-34\android.jar"

$aapt2 = "$buildTools\aapt2.exe"
$d8 = "$buildTools\d8.bat"
$zipalign = "$buildTools\zipalign.exe"
$apksigner = "$buildTools\apksigner.bat"

$buildDir = "$projectDir\build_out"
$resCompiled = "$buildDir\res_compiled"
$genDir = "$buildDir\gen"
$classesDir = "$buildDir\classes"
$dexDir = "$buildDir\dex"
$distDir = "$projectDir\dist"

if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Force -Path $resCompiled, $genDir, $classesDir, $dexDir, $distDir | Out-Null

Write-Host "[1/6] Compiling resources with aapt2..."
& $aapt2 compile --dir "$projectDir\app\src\main\res" -o "$resCompiled\resources.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/6] Linking resources and generating R.java..."
$unalignedApk = "$buildDir\app-unaligned.apk"
& $aapt2 link `
    -I $androidJar `
    --manifest "$projectDir\app\src\main\AndroidManifest.xml" `
    --java $genDir `
    -o $unalignedApk `
    "$resCompiled\resources.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/6] Compiling Java sources..."
$javaFiles = @(Get-ChildItem -Recurse -Filter *.java -Path "$projectDir\app\src\main\java", $genDir | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 --release 8 -classpath $androidJar -d $classesDir $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/6] Converting bytecode to DEX with d8..."
$classFiles = @(Get-ChildItem -Recurse -Filter *.class -Path $classesDir | ForEach-Object { $_.FullName })
& $d8 --min-api 24 --lib $androidJar --output $dexDir $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/6] Packaging classes.dex into APK and zipaligning..."
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unalignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$dexDir\classes.dex", "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
$zip.Dispose()

$alignedApk = "$buildDir\app-aligned.apk"
& $zipalign -f -p 4 $unalignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/6] Signing APK..."
$keystore = "$projectDir\debug.keystore"
if (-not (Test-Path $keystore)) {
    & keytool -genkeypair -v -keystore $keystore -storepass android -alias xiao_debug -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=XiaoTianQi, O=XiaoTianQi, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$finalApk = "$distDir\XiaoTianQi-v1.0.0.apk"
& $apksigner sign --ks $keystore --ks-pass pass:android --ks-key-alias xiao_debug --key-pass pass:android --out $finalApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

& $apksigner verify --verbose $finalApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

$sizeKB = [math]::Round((Get-Item $finalApk).Length / 1KB, 1)
Write-Host "BUILD SUCCESSFUL! APK: $finalApk ($sizeKB KB)"
