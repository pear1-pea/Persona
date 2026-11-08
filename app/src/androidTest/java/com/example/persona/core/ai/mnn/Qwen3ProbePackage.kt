package com.example.persona.core.ai.mnn

import java.io.File
import java.security.MessageDigest

internal object Qwen3ProbePackage {
    fun existingRoot(filesDir: File, runId: String): File {
        require(Qwen3ProbeProtocol.runId.matches(runId))
        val base = filesDir.canonicalFile
        val parent = child(base, "qwen3-probe", directory = true)
        return child(parent, runId, directory = true)
    }

    fun child(root: File, path: String, directory: Boolean = false): File {
        require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path)
        val parts = path.split('/')
        require(parts.all { Regex("[A-Za-z0-9_.-]+").matches(it) && it != "." && it != ".." })
        var current = root
        for (part in parts) {
            current = File(current, part)
            require(current.absoluteFile == current.canonicalFile)
        }
        require(if (directory) current.isDirectory else current.isFile)
        return current
    }

    fun verify(root: File, receiptSha256: String): File {
        require(receiptSha256 == Qwen3ProbeProtocol.RECEIPT_SHA256)
        val receiptBytes = Qwen3ProbeJson.readFile(root, "receipt.json")
        require(Qwen3ProbeJson.sha(receiptBytes) == receiptSha256)
        val receipt = Qwen3ProbeJson.parse(receiptBytes).fields("model_revision", "mnn_revision", "quant_bits", "files")
        require(receipt["model_revision"].string() == "c1899de289a04d12100db370d81485cdf75e47ca")
        require(receipt["mnn_revision"].string() == "d407447ed56c4121a11ccbd266dc184ca1ead0c2")
        require(receipt["quant_bits"].array().map { it.integer() } == listOf(8, 8, 16))
        val model = child(root, "model", directory = true)
        val entries = receipt["files"].array()
        require(entries.size == 11)
        val paths = mutableSetOf<String>()
        entries.forEach { element ->
            val entry = element.fields("path", "size", "sha256")
            val path = entry["path"].string()
            require(paths.add(path))
            val file = child(model, path)
            val size = entry["size"]
            require(size.isJsonPrimitive && size.asJsonPrimitive.isNumber)
            require(Regex("0|[1-9][0-9]*").matches(size.asString))
            require(file.length() == size.asString.toLong())
            val hash = entry["sha256"].string()
            require(Qwen3ProbeProtocol.hex.matches(hash) && hash(file) == hash)
        }
        val actual = mutableSetOf<String>()
        fun visit(directory: File) {
            require(directory.absoluteFile == directory.canonicalFile)
            requireNotNull(directory.listFiles()).forEach { file ->
                require(file.absoluteFile == file.canonicalFile)
                val relative = file.relativeTo(model).invariantSeparatorsPath
                if (file.isDirectory) {
                    require(paths.any { it.startsWith("$relative/") })
                    visit(file)
                } else {
                    require(file.isFile)
                    actual.add(relative)
                }
            }
        }
        visit(model)
        require(actual == paths)
        require(paths == setOf("llm.mnn", "llm.mnn.weight", "llm.mnn.json", "config.json", "llm_config.json",
            "tokenizer.mtok", "context.json", "embeddings_bf16.bin", "export_args.json", "LICENSE", "README.md"))
        val config = Qwen3ProbeJson.parse(Qwen3ProbeJson.readFile(model, "config.json")).asJsonObject
        require(config["llm_model"].string() == "llm.mnn" && config["llm_weight"].string() == "llm.mnn.weight")
        require(config["backend_type"].string() == "cpu")
        require(config["tokenizer_file"].string() == "tokenizer.mtok")
        require(config["context_file"].string() == "context.json")
        val context = Qwen3ProbeJson.parse(Qwen3ProbeJson.readFile(model, "context.json")).asJsonObject
        val thinking = context["enable_thinking"]
        require(thinking.isJsonPrimitive && thinking.asJsonPrimitive.isBoolean && !thinking.asBoolean)
        val llm = Qwen3ProbeJson.parse(Qwen3ProbeJson.readFile(model, "llm_config.json")).asJsonObject
        require(llm["jinja"].asJsonObject["chat_template"].string().isNotBlank())
        return child(model, "config.json")
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
