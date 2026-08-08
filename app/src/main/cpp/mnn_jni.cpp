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

constexpr const char* DEFAULT_STOP_WORD = "<eop>";

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

struct StopMatch {
    bool found = false;
    size_t position = std::string::npos;
    std::string value;
};

std::string toString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<std::string> toStringVector(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> result;
    if (values == nullptr) return result;

    const auto size = env->GetArrayLength(values);
    result.reserve(static_cast<size_t>(size));
    for (jsize index = 0; index < size; ++index) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        result.push_back(toString(env, value));
        env->DeleteLocalRef(value);
    }
    return result;
}

std::vector<std::string> toStopWords(JNIEnv* env, jobjectArray values) {
    auto stopWords = toStringVector(env, values);
    stopWords.erase(
        std::remove_if(stopWords.begin(), stopWords.end(), [](const std::string& value) {
            return value.empty();
        }),
        stopWords.end()
    );
    if (stopWords.empty()) {
        stopWords.emplace_back(DEFAULT_STOP_WORD);
    }
    return stopWords;
}

std::string normalizeRole(const std::string& role) {
    if (role == "system" || role == "assistant" || role == "tool" || role == "json") {
        return role;
    }
    return "user";
}

MNN::Transformer::ChatMessages toChatMessages(
    JNIEnv* env,
    jobjectArray roles,
    jobjectArray contents
) {
    MNN::Transformer::ChatMessages messages;
    if (roles == nullptr || contents == nullptr) return messages;

    const auto size = std::min(env->GetArrayLength(roles), env->GetArrayLength(contents));
    messages.reserve(static_cast<size_t>(size));
    for (jsize index = 0; index < size; ++index) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, index));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, index));
        const auto roleText = normalizeRole(toString(env, role));
        const auto contentText = toString(env, content);
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);

        if (!contentText.empty()) {
            messages.emplace_back(roleText, contentText);
        }
    }
    return messages;
}

StopMatch findStopMatch(const std::string& token, const std::vector<std::string>& stopWords) {
    StopMatch match;
    for (const auto& stopWord : stopWords) {
        const auto position = token.find(stopWord);
        if (position != std::string::npos &&
            (!match.found || position < match.position)) {
            match.found = true;
            match.position = position;
            match.value = stopWord;
        }
    }
    return match;
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

void runGeneration(
    JNIEnv* env,
    MnnSession* session,
    jobject callback,
    jfloat temperature,
    jfloat topP,
    jint maxTokens,
    const std::vector<std::string>& stopWords,
    const std::function<void(std::ostream*, const char*)>& prefill,
    const std::function<void(const std::string&)>& syncPromptCache
) {
    if (session == nullptr || session->llm == nullptr || callback == nullptr) return;

    std::lock_guard<std::mutex> lock(session->mutex);
    session->stopRequested = false;
    restoreAndroidSteppingStatusIfNeeded(session->llm);

    const auto callbackClass = env->GetObjectClass(callback);
    const auto onToken = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(callbackClass);
    if (onToken == nullptr) return;

    const auto config = "{\"temperature\":" + std::to_string(temperature) +
        ",\"top_p\":" + std::to_string(topP) + "}";
    session->llm->set_config(config);

    const auto maxNewTokens = maxTokens > 0 ? maxTokens : 512;
    const auto primaryStopWord = stopWords.empty() ? std::string(DEFAULT_STOP_WORD) : stopWords.front();
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
            currentSize < maxNewTokens &&
            primaryStopWord == DEFAULT_STOP_WORD) {
            // The Android stepping path can emit <eop> after each generate(1)
            // boundary. Treat it as intermediate until a real stop condition
            // or the token cap is reached.
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

        const auto stopMatch = findStopMatch(token, stopWords);
        const auto tokenBeforeStop = stopMatch.found
            ? token.substr(0, stopMatch.position)
            : token;

        if (!tokenBeforeStop.empty()) {
            const auto shouldContinue = utf8Accumulator.appendAndEmit(
                tokenBeforeStop,
                [&](const std::string& completeToken) {
                    return emitToken(completeToken);
                }
            );
            if (!shouldContinue) {
                session->stopRequested = true;
                markUserCancelled(session->llm);
            }
        }

        if (stopMatch.found && stopMatch.value == DEFAULT_STOP_WORD) {
            pendingEop = true;
        } else if (stopMatch.found) {
            generationTextEnd = true;
        }
    });
    std::ostream output(&streamBuffer);

    prefill(&output, primaryStopWord.c_str());
    const auto kvBeforeDecode = session->llm->getCurrentHistory();
    resolveAndroidSteppingEop();
    while (!session->stopRequested && !generationTextEnd && currentSize < maxNewTokens) {
        session->llm->generate(1);
        currentSize++;
        resolveAndroidSteppingEop();
    }
    finalizePendingEop();

    const auto responseText = responseBuffer.str();
    if (!session->stopRequested && !responseText.empty() && syncPromptCache) {
        syncPromptCache(responseText);
    } else if (session->stopRequested && currentSize > 0) {
        session->llm->eraseHistory(kvBeforeDecode, 0);
    }
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
Java_com_example_persona_core_ai_mnn_NativeMnnSession_nativeGenerateRawText(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring promptText,
    jobjectArray stopWords,
    jfloat temperature,
    jfloat topP,
    jint maxTokens,
    jobject callback
) {
    auto* session = reinterpret_cast<MnnSession*>(handle);
    const auto prompt = toString(env, promptText);
    const auto nativeStopWords = toStopWords(env, stopWords);

    runGeneration(
        env,
        session,
        callback,
        temperature,
        topP,
        maxTokens,
        nativeStopWords,
        [&](std::ostream* output, const char* primaryStopWord) {
            session->llm->reset();
            const auto inputIds = session->llm->tokenizer_encode(prompt);
            session->llm->response(inputIds, output, primaryStopWord, 0);
        },
        nullptr
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_persona_core_ai_mnn_NativeMnnSession_nativeGenerateChatMessages(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobjectArray roles,
    jobjectArray contents,
    jobjectArray stopWords,
    jfloat temperature,
    jfloat topP,
    jint maxTokens,
    jobject callback
) {
    auto* session = reinterpret_cast<MnnSession*>(handle);
    auto messages = toChatMessages(env, roles, contents);
    const auto nativeStopWords = toStopWords(env, stopWords);

    runGeneration(
        env,
        session,
        callback,
        temperature,
        topP,
        maxTokens,
        nativeStopWords,
        [&](std::ostream* output, const char* primaryStopWord) {
            session->llm->response(messages, output, primaryStopWord, 0);
        },
        [&](const std::string& responseText) {
            auto messagesWithResponse = messages;
            messagesWithResponse.emplace_back("assistant", responseText);
            session->llm->syncPromptCache(messagesWithResponse);
        }
    );
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

    // Stop first, then wait for an in-flight native call to leave the session
    // mutex before destroying the LLM object it is using.
    session->stopRequested = true;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        markUserCancelled(session->llm);
    }
    delete session;
}
