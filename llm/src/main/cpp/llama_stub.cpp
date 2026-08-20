// Stub file untuk llama.cpp yang belum tersedia
// File ini akan diganti dengan library sesungguhnya ketika NDK setup lengkap

#include <stddef.h>

// Forward declarations untuk tipe data yang digunakan di llama.h
struct llama_model;
struct llama_context;

// Placeholder untuk mencegah linker error
extern "C" {
    void llama_backend_init() {}
    void llama_backend_free() {}
    void llama_free(struct llama_context * ctx) {}
    void llama_free_model(struct llama_model * model) {}

    // Tambahan jika dibutuhkan simbol lain oleh JNI
    void llama_print_system_info() {}
}
