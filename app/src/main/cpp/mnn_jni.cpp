#include <jni.h>
#include <llm/llm.hpp>

#include <algorithm>
#include <atomic>
#include <functional>
#include <mutex>
#include <ostream>
#include <sstream>
#include <streambuf>
#include <string>
#include <utility>
#include <vector>

namespace {

class CallbackStreamBuffer final : public std::streambuf {
public:
    explicit CallbackStreamBuffer(std::function<void(const std::string&)> callback)
        : callback_(std::move(callback)) {}

protected:
    std::streamsize xsputn(const char* source, std::streamsize size) override {
        if (!callback_ || size <= 0) return size;
        callback_(std::string(source, static_cast<size_t>(size)));
        return size;
    }

private:
    std::function<void(const std::string&)> callback_;
};

class Utf8Accumulator final {
public:
    bool appendAndEmit(const std::string& chunk, const std::function<bool(const std::string&)>& emit) {
        pending_ += chunk;
        size_t completeBytes = 0;
        while (completeBytes < pending_.size()) {
            const auto length = utf8CodePointLength(static_cast<unsigned char>(pending_[completeBytes]));
            if (length == 0) {
                pending_.erase(completeBytes, 1);
                continue;
            }
            if (completeBytes + length > pending_.size()) break;
            completeBytes += length;
        }

        if (completeBytes > 0) {
            const auto completeChunk = pending_.substr(0, completeBytes);
            pending_.erase(0, completeBytes);
            return emit(completeChunk);
        }
        return true;
    }

private:
    static size_t utf8CodePointLength(unsigned char lead) {
        if ((lead & 0x80) == 0x00) return 1;
        if ((lead & 0xE0) == 0xC0) return 2;
        if ((lead & 0xF0) == 0xE0) return 3;
        if ((lead & 0xF8) == 0xF0) return 4;
        return 0;
    }

    std::string pending_;
};

struct MnnSession {
    MNN::Transformer::Llm* llm = nullptr;
    std::atomic_bool stopRequested = false;
    std::mutex mutex;

    ~MnnSession() {
        if (llm != nullptr) {
            MNN::Transformer::Llm::destroy(llm);
        }
    }
};

std::string toString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

// The Qwen MNN package has no Jinja chat template. Render its ChatML prompt
// explicitly before the tokenizer's fallback concatenates message contents.
std::string formatChatMessage(const std::string& role, const std::string& content) {
    return "<|im_start|>" + role + "\n" + content + "<|im_end|>\n";
}

std::string formatUserPrompt(const std::string& content) {
    return formatChatMessage("user", content) + "<|im_start|>assistant\n";
}

MNN::Transformer::ChatMessages formatChatMessages(
    JNIEnv* env,
    jobjectArray historyRoles,
    jobjectArray historyContents,
    const std::string& prompt
) {
    MNN::Transformer::ChatMessages messages;
    const auto historySize = std::min(
        env->GetArrayLength(historyRoles),
        env->GetArrayLength(historyContents)
    );
    for (jsize index = 0; index < historySize; ++index) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(historyRoles, index));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(historyContents, index));
        const auto roleText = toString(env, role);
        const auto contentText = toString(env, content);
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);

        if (contentText.empty()) continue;
        const auto normalizedRole = roleText == "system" || roleText == "assistant"
            ? roleText
            : "user";
        messages.emplace_back(normalizedRole, formatChatMessage(normalizedRole, contentText));
    }
    messages.emplace_back("user", formatUserPrompt(prompt));
    return messages;
}

bool callTokenCallback(JNIEnv* env, jobject callback, jmethodID onToken, const std::string& token) {
    jstring javaToken = env->NewStringUTF(token.c_str());
    const auto keepGenerating = env->CallBooleanMethod(callback, onToken, javaToken);
    env->DeleteLocalRef(javaToken);
    return keepGenerating == JNI_TRUE && !env->ExceptionCheck();
}

void restoreAndroidSteppingStatusIfNeeded(MNN::Transformer::Llm* llm) {
    const auto* context = llm == nullptr ? nullptr : llm->getContext();
    if (context == nullptr) return;

    if (context->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED ||
        context->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED) {
        auto* mutableContext = const_cast<MNN::Transformer::LlmContext*>(context);
        mutableContext->status = MNN::Transformer::LlmStatus::RUNNING;
    }
}

void markUserCancelled(MNN::Transformer::Llm* llm) {
    const auto* context = llm == nullptr ? nullptr : llm->getContext();
    if (context == nullptr) return;

    auto* mutableContext = const_cast<MNN::Transformer::LlmContext*>(context);
    mutableContext->status = MNN::Transformer::LlmStatus::USER_CANCEL;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_persona_core_ai_mnn_NativeMnnSession_nativeCreate(
    JNIEnv* env,
    jobject,
    jstring configPath
) {
    const auto path = toString(env, configPath);
    if (path.empty()) return 0;

    auto* session = new MnnSession();
    session->llm = MNN::Transformer::Llm::createLLM(path);
    if (session->llm == nullptr || !session->llm->load()) {
        delete session;
        return 0;
    }
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_persona_core_ai_mnn_NativeMnnSession_nativeGenerate(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring prompt,
    jobjectArray historyRoles,
    jobjectArray historyContents,
    jfloat temperature,
    jfloat topP,
    jint maxTokens,
    jobject callback
) {
    auto* session = reinterpret_cast<MnnSession*>(handle);
    if (session == nullptr || session->llm == nullptr || callback == nullptr) return;

    std::lock_guard<std::mutex> lock(session->mutex);
    session->stopRequested = false;
    restoreAndroidSteppingStatusIfNeeded(session->llm);

    const auto callbackClass = env->GetObjectClass(callback);
    const auto onToken = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(callbackClass);
    if (onToken == nullptr) return;

    const auto promptText = toString(env, prompt);
    auto messages = formatChatMessages(env, historyRoles, historyContents, promptText);

    const auto config = "{\"temperature\":" + std::to_string(temperature) +
        ",\"top_p\":" + std::to_string(topP) + "}";
    session->llm->set_config(config);

    const auto maxNewTokens = maxTokens > 0 ? maxTokens : 512;
    int currentSize = 0;
    bool generationTextEnd = false;
    bool pendingEop = false;
    std::stringstream responseBuffer;
    Utf8Accumulator utf8Accumulator;

    auto emitToken = [&](const std::string& completeToken) {
        const auto shouldContinue = callTokenCallback(env, callback, onToken, completeToken);
        if (shouldContinue) {
            responseBuffer << completeToken;
        } else {
            session->stopRequested = true;
            markUserCancelled(session->llm);
        }
        return shouldContinue;
    };

    auto finalizePendingEop = [&]() {
        if (!pendingEop) return;
        generationTextEnd = true;
        pendingEop = false;
    };

    auto resolveAndroidSteppingEop = [&]() {
        const auto* context = session->llm->getContext();
        if (context != nullptr &&
            context->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED &&
            !session->stopRequested &&
            currentSize < maxNewTokens) {
            // The Android stepping path can emit <eop> after each generate(1)
            // boundary. Match the official demo by treating that as an
            // intermediate step until the real stop condition or token cap.
            restoreAndroidSteppingStatusIfNeeded(session->llm);
            pendingEop = false;
            return;
        }

        if (context != nullptr &&
            context->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED &&
            !pendingEop &&
            !session->stopRequested &&
            currentSize < maxNewTokens) {
            restoreAndroidSteppingStatusIfNeeded(session->llm);
            return;
        }

        finalizePendingEop();
    };

    CallbackStreamBuffer streamBuffer([&](const std::string& token) {
        if (session->stopRequested) {
            markUserCancelled(session->llm);
            return;
        }
        const auto eopPosition = token.find("<eop>");
        if (eopPosition != std::string::npos) {
            const auto beforeEop = token.substr(0, eopPosition);
            if (!beforeEop.empty()) {
                const auto shouldContinue = utf8Accumulator.appendAndEmit(beforeEop, [&](const std::string& completeToken) {
                    return emitToken(completeToken);
                });
                if (!shouldContinue) {
                    session->stopRequested = true;
                    markUserCancelled(session->llm);
                }
            }
            pendingEop = true;
            return;
        }

        const auto shouldContinue = utf8Accumulator.appendAndEmit(token, [&](const std::string& completeToken) {
            return emitToken(completeToken);
        });
        if (!shouldContinue) {
            session->stopRequested = true;
            markUserCancelled(session->llm);
        }
    });
    std::ostream output(&streamBuffer);

    session->llm->response(messages, &output, "<eop>", 0);
    const auto kvBeforeDecode = session->llm->getCurrentHistory();
    resolveAndroidSteppingEop();
    while (!session->stopRequested && !generationTextEnd && currentSize < maxNewTokens) {
        session->llm->generate(1);
        currentSize++;
        resolveAndroidSteppingEop();
    }
    finalizePendingEop();

    const auto responseText = responseBuffer.str();
    if (!session->stopRequested && !responseText.empty()) {
        auto messagesWithResponse = messages;
        messagesWithResponse.emplace_back("assistant", responseText);
        session->llm->syncPromptCache(messagesWithResponse);
    } else if (session->stopRequested && currentSize > 0) {
        session->llm->eraseHistory(kvBeforeDecode, 0);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_persona_core_ai_mnn_NativeMnnSession_nativeStop(
    JNIEnv*,
    jobject,
    jlong handle
) {
    auto* session = reinterpret_cast<MnnSession*>(handle);
    if (session != nullptr) {
        session->stopRequested = true;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_persona_core_ai_mnn_NativeMnnSession_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong handle
) {
    auto* session = reinterpret_cast<MnnSession*>(handle);
    if (session == nullptr) return;

    // Stop first, then wait for an in-flight nativeGenerate call to leave the
    // session mutex before destroying the LLM object it is using.
    session->stopRequested = true;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        markUserCancelled(session->llm);
    }
    delete session;
}
