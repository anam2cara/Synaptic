Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function To-CMakePath([string]$p) { ($p -replace '\\','/') }

$NDK        = "C:\AndroidSDK\ndk\28.2.13676358"
$CMAKE_EXE  = "C:\AndroidSDK\cmake\3.22.1\bin\cmake.exe"
$NINJA_EXE  = "C:\AndroidSDK\cmake\3.22.1\bin\ninja.exe"
$WORK       = "D:\Documents\projek_build_apk_saya"
$LLAMA_SRC  = Join-Path $WORK "llama.cpp-b10502"
$LLAMA_BLD  = Join-Path $WORK "llama-b9592-build"
$JNILIBS    = Join-Path $WORK "Synaptic\app\src\main\jniLibs\arm64-v8a"
$LIBS_DIR   = Join-Path $WORK "Synaptic\app\libs\arm64-v8a"
$OPENCL_DIR     = Join-Path $WORK "opencl-android-stub"
$OPENCL_STUB_SO = Join-Path $OPENCL_DIR "libOpenCL.so"

if (-not $env:VULKAN_SDK -or -not (Test-Path $env:VULKAN_SDK)) {
    throw "VULKAN_SDK tidak valid. Set dulu ke folder Vulkan SDK."
}

$VULKAN_SDK      = $env:VULKAN_SDK
$VULKAN_INCLUDE  = Join-Path $VULKAN_SDK "Include"
$SPIRV_HEADERS   = Join-Path $VULKAN_SDK "Lib\cmake\SPIRV-Headers"
$VULKAN_GLSLC    = Join-Path $VULKAN_SDK "Bin\glslc.exe"
$VULKAN_LIBRARY  = Join-Path $NDK "toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\29\libvulkan.so"
$ANDROID_TOOLCHAIN = Join-Path $NDK "build\cmake\android.toolchain.cmake"

$checks = @(
    @{ Name = "cmake.exe"; Path = $CMAKE_EXE },
    @{ Name = "ninja.exe"; Path = $NINJA_EXE },
    @{ Name = "NDK toolchain.cmake"; Path = $ANDROID_TOOLCHAIN },
    @{ Name = "glslc.exe"; Path = $VULKAN_GLSLC },
    @{ Name = "SPIRV-HeadersConfig.cmake"; Path = (Join-Path $SPIRV_HEADERS "SPIRV-HeadersConfig.cmake") }
)

Write-Host "`n[1/5] Validasi tools..." -ForegroundColor Cyan
$fail = $false
foreach ($c in $checks) {
    if (Test-Path $c.Path) {
        Write-Host "  OK: $($c.Name)" -ForegroundColor Green
    } else {
        Write-Host "  MISSING: $($c.Name) => $($c.Path)" -ForegroundColor Red
        $fail = $true
    }
}
if ($fail) { throw "Ada tool yang tidak ditemukan." }

function Patch-GgmlVulkanCMake {
    param(
        [Parameter(Mandatory=$true)][string]$FilePath,
        [Parameter(Mandatory=$true)][string]$NinjaExe
    )

    if (!(Test-Path $FilePath)) { throw "File tidak ditemukan: $FilePath" }

    $backup = "$FilePath.bak"
    if (!(Test-Path $backup)) {
        Copy-Item $FilePath $backup -Force
    }

    $text = Get-Content $FilePath -Raw
    $ninjaCMake = To-CMakePath $NinjaExe

    if ($text -notmatch [regex]::Escape($ninjaCMake)) {
        $pattern = '(?m)^[ \t]*list\(APPEND VULKAN_SHADER_GEN_CMAKE_ARGS -DCMAKE_TOOLCHAIN_FILE=\$\{HOST_CMAKE_TOOLCHAIN_FILE\}\)[ \t]*\r?$'
        if ($text -match $pattern) {
            $replacement = @"
        list(APPEND VULKAN_SHADER_GEN_CMAKE_ARGS
            -DCMAKE_TOOLCHAIN_FILE=`${HOST_CMAKE_TOOLCHAIN_FILE}
            -G
            Ninja
            -DCMAKE_MAKE_PROGRAM=$ninjaCMake)
"@
            $text = [regex]::Replace($text, $pattern, $replacement, 1)
            Set-Content $FilePath $text -Encoding UTF8
        } else {
            throw "Gagal mem-patch ggml-vulkan/CMakeLists.txt"
        }
    }
}

Write-Host "`n[2/5] Mengecek source llama.cpp b9592..." -ForegroundColor Cyan
if (-not (Test-Path (Join-Path $LLAMA_SRC "CMakeLists.txt"))) {
    throw "Source llama.cpp tidak ditemukan di $LLAMA_SRC"
}
Write-Host "  Source sudah ada, skip download." -ForegroundColor Green

$vkCmake = Join-Path $LLAMA_SRC "ggml\src\ggml-vulkan\CMakeLists.txt"
Patch-GgmlVulkanCMake -FilePath $vkCmake -NinjaExe $NINJA_EXE

Write-Host "`n[3/5] CMake configure (arm64-v8a, Release, 16KB page-size)..." -ForegroundColor Cyan
if (Test-Path $LLAMA_BLD) {
    Write-Host "  Menghapus build lama..."
    Remove-Item $LLAMA_BLD -Recurse -Force
}

$cmakeArgs = @(
    "-S", (To-CMakePath $LLAMA_SRC),
    "-B", (To-CMakePath $LLAMA_BLD),
    "-G", "Ninja",
    "-DCMAKE_MAKE_PROGRAM=$(To-CMakePath $NINJA_EXE)",
    "-DCMAKE_TOOLCHAIN_FILE=$(To-CMakePath $ANDROID_TOOLCHAIN)",
    "-DANDROID_ABI=arm64-v8a",
    "-DANDROID_PLATFORM=android-26",
    "-DANDROID_STL=c++_static",
    "-DCMAKE_BUILD_TYPE=Release",
    "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
    "-DCMAKE_PREFIX_PATH=$(To-CMakePath $VULKAN_SDK)",
    "-DVulkan_INCLUDE_DIR=$(To-CMakePath $VULKAN_INCLUDE)",
    "-DVulkan_LIBRARY=$(To-CMakePath $VULKAN_LIBRARY)",
    "-DVulkan_GLSLC_EXECUTABLE=$(To-CMakePath $VULKAN_GLSLC)",
    "-DSPIRV-Headers_DIR=$(To-CMakePath $SPIRV_HEADERS)",
    "-DBUILD_SHARED_LIBS=ON",
    "-DLLAMA_BUILD_TESTS=OFF",
    "-DLLAMA_BUILD_EXAMPLES=OFF",
    "-DLLAMA_BUILD_SERVER=OFF",
  "-DLLAMA_BUILD_TOOLS=OFF",
  "-DLLAMA_BUILD_APP=OFF",
    "-DGGML_NATIVE=OFF",
    "-DGGML_VULKAN=ON",
    "-DGGML_VULKAN_SHADERS_GEN_TOOLCHAIN=D:/Documents/projek_build_apk_saya/Synaptic/host-toolchain-mingw.cmake",
    "-DGGML_VULKAN_DEBUG=OFF",
  "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
  "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
    "-DGGML_CUDA=OFF",
    "-DGGML_METAL=OFF",
    "-DGGML_OPENMP=OFF",
    "-DGGML_OPENCL=OFF",
    "-DGGML_OPENCL_USE_ADRENO_KERNELS=ON",
    "-DGGML_OPENCL_EMBED_KERNELS=ON",
    "-DOpenCL_INCLUDE_DIR=$(To-CMakePath (Join-Path $OPENCL_DIR "OpenCL-Headers"))",
    "-DOpenCL_LIBRARY=$(To-CMakePath $OPENCL_STUB_SO)"
)

& $CMAKE_EXE @cmakeArgs
if ($LASTEXITCODE -ne 0) { throw "CMake configure GAGAL! (exit code $LASTEXITCODE)" }
Write-Host "  Configure OK." -ForegroundColor Green

Write-Host "`n[4/5] Building..." -ForegroundColor Cyan
$start = Get-Date
& $CMAKE_EXE --build $LLAMA_BLD --config Release --parallel 3
if ($LASTEXITCODE -ne 0) { throw "Build GAGAL! (exit code $LASTEXITCODE)" }
$elapsed = ((Get-Date) - $start).ToString("mm\:ss")
Write-Host "  Build selesai dalam $elapsed." -ForegroundColor Green

Write-Host "`n[5/5] Mencari output .so dan copy ke project..." -ForegroundColor Cyan
$targets = @("libllama.so", "libllama-common.so", "libggml.so", "libggml-base.so", "libggml-cpu.so", "libggml-vulkan.so", "libggml-opencl.so")

$found = @{}
Get-ChildItem -Path $LLAMA_BLD -Recurse -Filter "*.so" | ForEach-Object {
    if ($_.Name -in $targets) {
        if (-not $found.ContainsKey($_.Name) -or $_.LastWriteTime -gt $found[$_.Name].LastWriteTime) {
            $found[$_.Name] = $_
        }
    }
}

if ($found.Count -eq 0) {
    Write-Error "Tidak ada .so target yang ditemukan di $LLAMA_BLD"
    Get-ChildItem -Path $LLAMA_BLD -Recurse -Filter "*.so" | ForEach-Object { Write-Host "  $($_.FullName)" }
    exit 1
}

foreach ($d in @($JNILIBS, $LIBS_DIR)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d | Out-Null }
}

$copied = 0
foreach ($name in $targets) {
    if ($found.ContainsKey($name)) {
        $src = $found[$name].FullName
        Copy-Item $src (Join-Path $JNILIBS $name) -Force
        Copy-Item $src (Join-Path $LIBS_DIR $name) -Force
        Write-Host "  OK: $name" -ForegroundColor Yellow
        $copied++
    } else {
        Write-Host "  TIDAK DITEMUKAN: $name" -ForegroundColor Red
    }
}

Write-Host "`n==========================================" -ForegroundColor Cyan
Write-Host "SELESAI: $copied/$($targets.Count) library berhasil di-copy" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "`nStep selanjutnya:" -ForegroundColor Cyan
Write-Host "  cd $WORK\Synaptic"
Write-Host "  .\gradlew assembleDebug"









