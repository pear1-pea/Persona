#pragma once

#include <llm/llm.hpp>

#include <cmath>
#include <iomanip>
#include <limits>
#include <locale>
#include <memory>
#include <optional>
#include <sstream>
#include <string>

namespace persona {

struct GenerationConfig {
    float temperature = 0.6f;
    float topP = 0.8f;
    int maxTokens = 256;

    bool valid() const {
        return std::isfinite(temperature) && temperature >= 0.0f && temperature <= 2.0f &&
            std::isfinite(topP) && topP > 0.0f && topP <= 1.0f && maxTokens > 0;
    }

    bool greedy() const {
        // Below float reciprocal range, softmax would produce NaNs. Its
        // zero-temperature limit is argmax, so use MNN's greedy pipeline.
        return temperature == 0.0f || !std::isfinite(1.0f / temperature);
    }

    bool sameSampler(const GenerationConfig& other) const {
        return temperature == other.temperature && topP == other.topP;
    }

    std::string json() const {
        std::ostringstream output;
        output.imbue(std::locale::classic());
        output << std::setprecision(std::numeric_limits<float>::max_digits10)
            << "{\"sampler_type\":\"" << (greedy() ? "greedy" : "topP")
            << "\",\"temperature\":" << temperature
            << ",\"top_p\":" << topP << ",\"max_new_tokens\":" << maxTokens << "}";
        return output.str();
    }
};

// MNN d407447 (3.6.1) snapshots sampler config in load(), not set_config().
// Recreate through public APIs on sampling changes, releasing the old model
// first to avoid keeping two weight/KV allocations alive on a mobile device.
inline bool configureGeneration(
    MNN::Transformer::Llm*& llm,
    const std::string& configPath,
    std::optional<GenerationConfig>& applied,
    int& samplerRevision,
    const GenerationConfig& requested
) {
    if (!requested.valid()) return false;
    if (llm != nullptr && applied && applied->sameSampler(requested)) {
        if (!llm->set_config(requested.json())) return false;
        applied = requested;
        return true;
    }

    if (llm != nullptr) MNN::Transformer::Llm::destroy(llm);
    llm = nullptr;
    applied.reset();
    const auto destroy = [](MNN::Transformer::Llm* value) {
        if (value != nullptr) MNN::Transformer::Llm::destroy(value);
    };
    std::unique_ptr<MNN::Transformer::Llm, decltype(destroy)> candidate(
        MNN::Transformer::Llm::createLLM(configPath), destroy
    );
    if (!candidate || !candidate->set_config(requested.json()) || !candidate->load()) return false;
    llm = candidate.release();
    applied = requested;
    ++samplerRevision;
    return true;
}

}  // namespace persona
