package com.example.persona.candidate

import com.example.persona.core.ai.mnn.Qwen3ProbeProtocol
import java.io.File

fun main(args: Array<String>) {
    require(args.size == 3)
    val bundle = Qwen3ProbeProtocol.parse(File(args[0]).readBytes(), args[1], args[2])
    require(bundle.cases.size == 9)
    println("Actual Kotlin protocol accepted nine HF reference cases; native execution not run")
}
