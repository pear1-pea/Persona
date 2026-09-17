#include <jni.h>
#include <llm/llm.hpp>

#include <algorithm>
#include <atomic>
#include <cstdint>
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
constexpr uint32_t REPLACEMENT_CODE_POINT = 0xFFFD;

void appendUtf8(std::string& output, uint32_t codePoint) {
    if (codePoint <= 0x7F) {
        output.push_back(static_cast<char>(codePoint));
    } else if (codePoint <= 0x7FF) {
        output.push_back(static_cast<char>(0xC0 | (codePoint >> 6)));
        output.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
    } else if (codePoint <= 0xFFFF) {
        output.push_back(static_cast<char>(0xE0 | (codePoint >> 12)));
        output.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
        output.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
    } else {
        output.push_back(static_cast<char>(0xF0 | (codePoint >> 18)));
        output.push_back(static_cast<char>(0x80 | ((codePoint >> 12) & 0x3F)));
        output.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
        output.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
    }
}

std::string utf16ToUtf8(const jchar* source, jsize length) {
    std::string output;
    if (source == nullptr || length <= 0) return output;
    output.reserve(static_cast<size_t>(length) * 3);

    for (jsize index = 0; index < length; ++index) {
        uint32_t codePoint = source[index];
        if (codePoint >= 0xD800 && codePoint <= 0xDBFF) {
            if (index + 1 < length) {
                const uint32_t low = source[index + 1];
                if (low >= 0xDC00 && low <= 0xDFFF) {
                    codePoint = 0x10000 + ((codePoint - 0xD800) << 10) + (low - 0xDC00);
                    ++index;
                } else {
                    codePoint = REPLACEMENT_CODE_POINT;
                }
            } else {
                codePoint = REPLACEMENT_CODE_POINT;
            }
        } else if (codePoint >= 0xDC00 && codePoint <= 0xDFFF) {
            codePoint = REPLACEMENT_CODE_POINT;
        }
        appendUtf8(output, codePoint);
    }
    return output;
}

std::u16string utf8ToUtf16(const std::string& source) {
    std::u16string output;
    output.reserve(source.size());

    size_t index = 0;
    while (index < source.size()) {
        const auto lead = static_cast<unsigned char>(source[index]);
        uint32_t codePoint = REPLACEMENT_CODE_POINT;
        size_t length = 1;

        if ((lead & 0x80) == 0x00) {
            codePoint = lead;
        } else if ((lead & 0xE0) == 0xC0 && index + 1 < source.size()) {
            const auto b1 = static_cast<unsigned char>(source[index + 1]);
            if (lead >= 0xC2 && (b1 & 0xC0) == 0x80) {
                codePoint = ((lead & 0x1F) << 6) | (b1 & 0x3F);
                length = 2;
            }
        } else if ((lead & 0xF0) == 0xE0 && index + 2 < source.size()) {
            const auto b1 = static_cast<unsigned char>(source[index + 1]);
            const auto b2 = static_cast<unsigned char>(source[index + 2]);
            if ((b1 & 0xC0) == 0x80 && (b2 & 0xC0) == 0x80 &&
                !(lead == 0xE0 && b1 < 0xA0) &&
                !(lead == 0xED && b1 >= 0xA0)) {
                codePoint = ((lead & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
                length = 3;
            }
        } else if ((lead & 0xF8) == 0xF0 && index + 3 < source.size()) {
            const auto b1 = static_cast<unsigned char>(source[index + 1]);
            const auto b2 = static_cast<unsigned char>(source[index + 2]);
            const auto b3 = static_cast<unsigned char>(source[index + 3]);
            if ((b1 & 0xC0) == 0x80 && (b2 & 0xC0) == 0x80 && (b3 & 0xC0) == 0x80 &&
                !(lead == 0xF0 && b1 < 0x90) &&
                !(lead == 0xF4 && b1 >= 0x90) &&
                lead <= 0xF4) {
                codePoint = ((lead & 0x07) << 18) | ((b1 & 0x3F) << 12) |
                    ((b2 & 0x3F) << 6) | (b3 & 0x3F);
                length = 4;
            }
        }

        if (codePoint <= 0xFFFF) {
            output.push_back(static_cast<char16_t>(codePoint));
        } else {
            const uint32_t shifted = codePoint - 0x10000;
            output.push_back(static_cast<char16_t>(0xD800 | (shifted >> 10)));
            output.push_back(static_cast<char16_t>(0xDC00 | (shifted & 0x3FF)));
        }
        index += length;
    }
    return output;
}

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

    // Single-character writes bypass xsputn, so they are routed here as well.
    int_type overflow(int_type character) override {
        if (character == traits_type::eof()) return traits_type::not_eof(character);
        const auto byte = static_cast<char>(traits_type::to_char_type(character));
        if (callback_) callback_(std::string(1, byte));
        return traits_type::not_eof(character);
    }

private:
    std::function<void(const std::string&)> callback_;
};

class Utf8Accumulator final {
public:
    using Emit = std::function<bool(const std::string&)>;

    bool appendAndEmit(const std::string& chunk, const Emit& emit) {
        pending_ += chunk;
        std::string ready;
        size_t index = 0;
        while (index < pending_.size()) {
            const auto length = utf8CodePointLength(static_cast<unsigned char>(pending_[index]));
            if (length == 0) {
                ready += REPLACEMENT_CHARACTER;
                ++index;
                continue;
            }
            if (index + length > pending_.size()) break;
            if (!isValidCodePoint(pending_, index, length)) {
                ready += REPLACEMENT_CHARACTER;
                ++index;
                continue;
            }
            ready.append(pending_, index, length);
            index += length;
        }
        pending_.erase(0, index);

        if (ready.empty()) return true;
        return emit(ready);
    }

    // A truncated sequence at end-of-generation can never be completed, so it is
    // surfaced as U+FFFD instead of being dropped or passed on as invalid UTF-8.
    bool flush(const Emit& emit) {
        if (pending_.empty()) return true;
        std::string ready;
        size_t index = 0;
        while (index < pending_.size()) {
            const auto length = utf8CodePointLength(static_cast<unsigned char>(pending_[index]));
            if (length == 0 || index + length > pending_.size() ||
                !isValidCodePoint(pending_, index, length)) {
                ready += REPLACEMENT_CHARACTER;
                ++index;
                continue;
            }
            ready.append(pending_, index, length);
            index += length;
        }
        pending_.clear();
        return ready.empty() || emit(ready);
    }

private:
    static constexpr const char* REPLACEMENT_CHARACTER = "\xEF\xBF\xBD";

    static size_t utf8CodePointLength(unsigned char lead) {
        if ((lead & 0x80) == 0x00) return 1;
        if ((lead & 0xE0) == 0xC0) return 2;
        if ((lead & 0xF0) == 0xE0) return 3;
        if ((lead & 0xF8) == 0xF0) return 4;
        return 0;
    }

    static bool isValidCodePoint(const std::string& text, size_t start, size_t length) {
        const auto lead = static_cast<unsigned char>(text[start]);
        if ((length == 2 && lead < 0xC2) ||
            (length == 3 && lead == 0xE0 && static_cast<unsigned char>(text[start + 1]) < 0xA0) ||
            (length == 3 && lead == 0xED && static_cast<unsigned char>(text[start + 1]) >= 0xA0) ||
            (length == 4 && lead == 0xF0 && static_cast<unsigned char>(text[start + 1]) < 0x90) ||
            (length == 4 && lead == 0xF4 && static_cast<unsigned char>(text[start + 1]) >= 0x90) ||
            (length == 4 && lead > 0xF4)) {
            return false;
        }
        for (size_t offset = 1; offset < length; ++offset) {
            if ((static_cast<unsigned char>(text[start + offset]) & 0xC0) != 0x80) {
                return false;
            }
        }
        return true;
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

// A stop word can straddle two decoder chunks, so text whose tail is still a
// possible stop-word prefix is withheld until it can be resolved either way.
class StopWordMatcher final {
public:
    struct Result {
        std::string text;
        bool matched = false;
        std::string value;
    };

    explicit StopWordMatcher(std::vector<std::string> stopWords)
        : stopWords_(std::move(stopWords)) {
        for (const auto& stopWord : stopWords_) {
            maxStopWordLength_ = std::max(maxStopWordLength_, stopWord.size());
        }
    }

    Result append(const std::string& chunk) {
        buffer_ += chunk;
        Result result;

        size_t matchPosition = std::string::npos;
        for (const auto& stopWord : stopWords_) {
            const auto position = buffer_.find(stopWord);
            if (position == std::string::npos) continue;
            if (matchPosition == std::string::npos ||
                position < matchPosition ||
                (position == matchPosition && stopWord.size() > result.value.size())) {
                matchPosition = position;
                result.value = stopWord;
            }
        }

        if (matchPosition != std::string::npos) {
            result.matched = true;
            result.text = buffer_.substr(0, matchPosition);
            buffer_.clear();
            return result;
        }

        const auto withheld = pendingPrefixLength();
        const auto emittable = buffer_.size() - withheld;
        result.text = buffer_.substr(0, emittable);
        buffer_.erase(0, emittable);
        return result;
    }

    std::string flush() {
        std::string remaining;
        remaining.swap(buffer_);
        return remaining;
    }

private:
    size_t pendingPrefixLength() const {
        if (maxStopWordLength_ == 0) return 0;
        const auto limit = std::min(buffer_.size(), maxStopWordLength_ - 1);
        for (size_t length = limit; length > 0; --length) {
            const auto start = buffer_.size() - length;
            for (const auto& stopWord : stopWords_) {
                if (stopWord.size() > length &&
                    stopWord.compare(0, length, buffer_, start, length) == 0) {
                    return length;
                }
            }
        }
        return 0;
    }

    std::vector<std::string> stopWords_;
    size_t maxStopWordLength_ = 0;
    std::string buffer_;
};

std::string toString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const auto length = env->GetStringLength(value);
    const auto* chars = env->GetStringChars(value, nullptr);
    std::string result = utf16ToUtf8(chars, length);
    if (chars != nullptr) env->ReleaseStringChars(value, chars);
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

bool callTokenCallback(JNIEnv* env, jobject callback, jmethodID onToken, const std::string& token) {
    const auto utf16Token = utf8ToUtf16(token);
    jstring javaToken = env->NewString(
        reinterpret_cast<const jchar*>(utf16Token.data()),
        static_cast<jsize>(utf16Token.size())
    );
    if (javaToken == nullptr) return false;
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
    StopWordMatcher stopMatcher(stopWords);

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

    auto emitThroughUtf8 = [&](const std::string& text) {
        if (text.empty()) return true;
        const auto shouldContinue = utf8Accumulator.appendAndEmit(text, emitToken);
        if (!shouldContinue) {
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

        const auto stopMatch = stopMatcher.append(token);
        if (!emitThroughUtf8(stopMatch.text)) return;

        if (!stopMatch.matched) return;
        if (stopMatch.value == DEFAULT_STOP_WORD) {
            pendingEop = true;
        } else {
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

    if (!session->stopRequested) {
        // Text withheld as a potential stop-word prefix turned out to be real
        // output, and any truncated code point must still be surfaced.
        if (emitThroughUtf8(stopMatcher.flush())) {
            utf8Accumulator.flush(emitToken);
        }
    }

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

extern "C" JNIEXPORT jint JNICALL
Java_com_example_persona_core_ai_mnn_NativeMnnSession_nativeCountTokens(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring text
) {
    auto* session = reinterpret_cast<MnnSession*>(handle);
    if (session == nullptr || session->llm == nullptr) return -1;

    const auto value = toString(env, text);
    std::lock_guard<std::mutex> lock(session->mutex);
    const auto inputIds = session->llm->tokenizer_encode(value);
    return static_cast<jint>(inputIds.size());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_persona_core_ai_mnn_NativeMnnSession_nativeReset(
    JNIEnv*,
    jobject,
    jlong handle
) {
    auto* session = reinterpret_cast<MnnSession*>(handle);
    if (session == nullptr || session->llm == nullptr) return;

    std::lock_guard<std::mutex> lock(session->mutex);
    session->llm->reset();
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
