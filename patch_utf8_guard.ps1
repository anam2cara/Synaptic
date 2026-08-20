$ErrorActionPreference = "Stop"
$file = "D:\Documents\projek_build_apk_saya\Synaptic\llm\src\main\cpp\llama_jni.cpp"

if (!(Test-Path $file)) { throw "File tidak ditemukan: $file" }

$backup = "$file.bak_$(Get-Date -Format 'yyyyMMdd_HHmmss')"
Copy-Item $file $backup
Write-Host "Backup dibuat: $backup"

$raw = Get-Content -Raw $file
# Normalisasi ke LF untuk matching (file aslinya CRLF), dikembalikan ke CRLF saat ditulis
$content = $raw -replace "`r`n", "`n"

# 1. Sisipkan helper utf8SafePrefixLen setelah freeStateLocked()
$anchor1 = @'
static void freeStateLocked() {
    if (g_state.sampler) { llama_sampler_free(g_state.sampler); g_state.sampler = nullptr; }
    if (g_state.ctx)     { llama_free(g_state.ctx);             g_state.ctx     = nullptr; }
    if (g_state.model)   { llama_model_free(g_state.model);     g_state.model   = nullptr; }
    g_state.loaded = false;
    g_state.n_past = 0;
}
'@

if (-not $content.Contains($anchor1)) { throw "Anchor 1 tidak ditemukan - file sudah berubah dari yang tercek, hentikan." }

$helper = $anchor1 + @'


// Panjang prefix `s` yang berisi codepoint UTF-8 lengkap. Byte multi-byte yang
// belum lengkap di ujung TIDAK ikut, supaya tidak ada token boundary yang
// memotong satu karakter jadi dua NewStringUTF (penyebab SIGABRT saat
// emoji/karakter non-ASCII terpotong di batas token).
static size_t utf8SafePrefixLen(const std::string& s) {
    if (s.empty()) return 0;
    size_t i = s.size();
    size_t back = 0;
    while (i > 0 && back < 4) {
        --i; ++back;
        unsigned char c = (unsigned char)s[i];
        if ((c & 0xC0) != 0x80) {
            int expectedLen;
            if      ((c & 0x80) == 0x00) expectedLen = 1;
            else if ((c & 0xE0) == 0xC0) expectedLen = 2;
            else if ((c & 0xF0) == 0xE0) expectedLen = 3;
            else if ((c & 0xF8) == 0xF0) expectedLen = 4;
            else return s.size();
            size_t have = s.size() - i;
            if (have < (size_t)expectedLen) return i;
            return s.size();
        }
    }
    return s.size();
}
'@

$content = $content.Replace($anchor1, $helper)

# 2. Deklarasikan buffer `pending` sebelum loop token
$anchor2 = @'
    // Loop
    g_abort.store(false);
    llama_batch s_batch = llama_batch_init(1, 0, 1);
'@

if (-not $content.Contains($anchor2)) { throw "Anchor 2 tidak ditemukan - file sudah berubah dari yang tercek, hentikan." }

$replacement2 = @'
    // Loop
    g_abort.store(false);
    std::string pending;
    llama_batch s_batch = llama_batch_init(1, 0, 1);
'@

$content = $content.Replace($anchor2, $replacement2)

# 3. Ganti emit token mentah dengan versi ter-buffer UTF-8-safe
$anchor3 = @'
        char buf[256];
        int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
        if (n > 0) {
            jstring jpiece = env->NewStringUTF(std::string(buf, n).c_str());
            if (jpiece) { env->CallVoidMethod(callback, onToken, jpiece); env->DeleteLocalRef(jpiece); }
        }
'@

if (-not $content.Contains($anchor3)) { throw "Anchor 3 tidak ditemukan - file sudah berubah dari yang tercek, hentikan." }

$replacement3 = @'
        char buf[256];
        int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
        if (n > 0) {
            pending.append(buf, n);
            size_t safeLen = utf8SafePrefixLen(pending);
            if (safeLen > 0) {
                jstring jpiece = env->NewStringUTF(pending.substr(0, safeLen).c_str());
                if (jpiece) { env->CallVoidMethod(callback, onToken, jpiece); env->DeleteLocalRef(jpiece); }
                pending.erase(0, safeLen);
            }
        }
'@

$content = $content.Replace($anchor3, $replacement3)

# 4. Log sisa byte tidak lengkap saat generation selesai (jangan dibuang diam-diam)
$anchor4 = @'
    llama_batch_free(s_batch);
    LOGI("generateStream: Completed.");
'@

if (-not $content.Contains($anchor4)) { throw "Anchor 4 tidak ditemukan - file sudah berubah dari yang tercek, hentikan." }

$replacement4 = @'
    llama_batch_free(s_batch);
    if (!pending.empty()) {
        LOGI("generateStream: %zu byte UTF-8 tidak lengkap di akhir, dibuang", pending.size());
    }
    LOGI("generateStream: Completed.");
'@

$content = $content.Replace($anchor4, $replacement4)

# Kembalikan ke CRLF sesuai konvensi file asli
$final = $content -replace "`n", "`r`n"

Set-Content -Path $file -Value $final -NoNewline
Write-Host "Patch selesai: $file"
Write-Host "Lanjutkan dengan: ./gradlew.bat clean, lalu ./gradlew.bat :app:assembleDebug"
