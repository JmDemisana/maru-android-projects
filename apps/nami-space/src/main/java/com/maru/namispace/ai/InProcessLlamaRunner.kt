package com.maru.namispace.ai

import android.content.Context
import android.system.Os
import android.util.Log
import com.sun.jna.FunctionMapper
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.lang.reflect.Method

object InProcessLlamaRunner {

    private const val TAG = "InProcessLlamaRunner"

    interface LlamaCompletionLib : Library {
        fun llama_completion(argc: Int, argv: Array<String>): Int
    }

    private var lib: LlamaCompletionLib? = null

    init {
        try {
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml")
            val options = mapOf<String, Any>(
                Library.OPTION_FUNCTION_MAPPER to object : FunctionMapper {
                    override fun getFunctionName(lib: NativeLibrary?, method: Method?): String {
                        return if (method?.name == "llama_completion") {
                            "_Z16llama_completioniPPc"
                        } else {
                            method?.name ?: ""
                        }
                    }
                }
            )
            lib = Native.load("llama-completion-impl", LlamaCompletionLib::class.java, options)
            Log.i(TAG, "libllama-completion-impl loaded successfully!")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load libllama-completion-impl", e)
        }
    }

    suspend fun generate(
        context: Context,
        modelFile: File,
        prompt: String,
        maxTokens: Int = 90,
        temperature: Float = 0.7f
    ): String = withContext(Dispatchers.IO) {
        val nativeLib = lib ?: return@withContext ""

        // Optimized for Galaxy A55: 4 Big Cortex-A78 cores + full unconstrained token generation (-n -1)
        val args = arrayOf(
            "llama-completion",
            "-m", modelFile.absolutePath,
            "-p", prompt,
            "-n", "-1",
            "-t", "4",
            "-b", "512",
            "-ub", "512",
            "--temp", temperature.toString(),
            "-c", "2048",
            "-r", "<|im_start|>,<|im_end|>",
            "-ngl", "0",
            "-no-cnv",
            "--simple-io"
        )

        val outputBuffer = ByteArrayOutputStream()
        var savedStdout: FileDescriptor? = null
        var pipeFds: Array<FileDescriptor>? = null

        var finalExitCode = 0
        try {
            savedStdout = Os.dup(FileDescriptor.out)
            pipeFds = Os.pipe()
            
            Os.dup2(pipeFds[1], 1)
            Os.close(pipeFds[1])

            val readThread = Thread {
                try {
                    val stream = FileInputStream(pipeFds[0])
                    val buf = ByteArray(1024)
                    var len: Int
                    while (stream.read(buf).also { len = it } != -1) {
                        outputBuffer.write(buf, 0, len)
                        Log.d(TAG, "NATIVE STDOUT: " + String(buf, 0, len).trimEnd())
                    }
                } catch (ignored: Exception) {}
            }
            readThread.start()

            finalExitCode = nativeLib.llama_completion(args.size, args)
            Log.i(TAG, "llama_completion exited with code: $finalExitCode")

            savedStdout?.let { Os.dup2(it, 1) }
            pipeFds?.get(0)?.let { try { Os.close(it) } catch (ignored: Exception) {} }
            
            readThread.join()
        } catch (e: Throwable) {
            Log.e(TAG, "Error executing native completion", e)
        } finally {
            savedStdout?.let { try { Os.dup2(it, 1); Os.close(it) } catch (ignored: Exception) {} }
        }

        val res = outputBuffer.toString("UTF-8").trim()
        if (finalExitCode != 0) {
            return@withContext "ERROR_EXIT_CODE_$finalExitCode\n$res"
        }
        return@withContext res
    }
}
