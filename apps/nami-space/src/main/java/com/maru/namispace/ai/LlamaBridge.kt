package com.maru.namispace.ai

import android.content.Context
import android.util.Log
import com.sun.jna.*
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Direct In-Process LLaMA/Qwen Inference Engine powered by native libllama.so.
 * Calls the clean C API directly in JVM process memory without subprocesses or sockets.
 */
object LlamaBridge {

    private const val TAG = "LlamaBridge"

    interface LlamaLib : Library {
        fun llama_backend_init()
        fun llama_backend_free()
        fun llama_version(): String
        fun llama_print_system_info(): String

        fun llama_model_default_params(): Pointer
        fun llama_context_default_params(): Pointer
        fun llama_sampler_chain_default_params(): Pointer

        fun llama_model_load_from_file(path: String, params: Pointer): Pointer?
        fun llama_model_free(model: Pointer)

        fun llama_init_from_model(model: Pointer, params: Pointer): Pointer?
        fun llama_free(ctx: Pointer)

        fun llama_model_get_vocab(model: Pointer): Pointer?
        fun llama_tokenize(vocab: Pointer, text: String, text_len: Int, tokens: IntArray, n_tokens_max: Int, add_special: Boolean, parse_special: Boolean): Int
        fun llama_token_to_piece(vocab: Pointer, token: Int, buf: ByteArray, length: Int, lstrip: Int, special: Boolean): Int
        fun llama_vocab_is_eog(vocab: Pointer, token: Int): Boolean
    }

    private var lib: LlamaLib? = null
    var isReady = false
        private set

    fun initialize(context: Context): Boolean {
        return try {
            if (lib == null) {
                lib = Native.load("llama", LlamaLib::class.java)
                lib?.llama_backend_init()
                isReady = true
                Log.i(TAG, "Native libllama loaded successfully in-process! Version: ${lib?.llama_version()}")
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Error initializing native llama in-process", e)
            isReady = false
            false
        }
    }

    fun findModelFile(context: Context): File? {
        val modelDir = File(context.filesDir, "models")
        val candidates = listOf(
            File(modelDir, "qwen2.5-3b-instruct-q4_k_m.gguf"),
            File(modelDir, "qwen2.5-1.5b-instruct-q4_k_m.gguf"),
            File("/sdcard/Download/qwen2.5-3b-instruct-q4_k_m.gguf"),
            File("/sdcard/Download/qwen2.5-1.5b-instruct-q4_k_m.gguf"),
            File("/data/local/tmp/llama/qwen2.5-3b-instruct-q4_k_m.gguf"),
            File("/data/local/tmp/llama/qwen2.5-1.5b-instruct-q4_k_m.gguf")
        )
        return candidates.firstOrNull { it.exists() && it.length() > 50_000_000 }
    }
}
