#include <generation_config.h>
#include <llmconfig.hpp>
#include <sampler.hpp>

#include <MNN/MNNDefine.h>

#include <array>
#include <iostream>
#include <stdexcept>

static_assert(MNN_VERSION_MAJOR == 3 && MNN_VERSION_MINOR == 6 && MNN_VERSION_PATCH == 1,
    "These tests require the pinned MNN 3.6.1 APIs");

namespace {

void check(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

std::array<int, 4> sample(MNN::Transformer::Sampler& sampler) {
    const float values[] = {3.0f, 2.0f, 1.0f, 0.0f};
    const auto logits = MNN::Express::_Const(values, {4}, MNN::Express::NHWC, halide_type_of<float>());
    std::array<int, 4> counts{};
    for (int index = 0; index < 4096; ++index) {
        const auto selected = sampler.sample(logits);
        check(selected >= 0 && selected < 4, "Sampler returned an invalid synthetic bin");
        ++counts[selected];
    }
    return counts;
}

std::shared_ptr<MNN::Transformer::LlmConfig> config(const persona::GenerationConfig& params) {
    auto result = std::make_shared<MNN::Transformer::LlmConfig>();
    // Model packages may carry their own sampler and legacy camelCase values.
    result->config_ = ujson::json::parse(R"({"sampler_type":"mixed","topP":0.01,"mixed_samplers":["greedy"]})");
    result->config_.merge(ujson::json::parse(params.json()));
    return result;
}

std::array<int, 4> sample(const persona::GenerationConfig& params) {
    auto context = std::make_shared<MNN::Transformer::LlmContext>();
    std::unique_ptr<MNN::Transformer::Sampler> sampler(
        MNN::Transformer::Sampler::createSampler(context, config(params))
    );
    return sample(*sampler);
}

void validationAndSerialization() {
    check(persona::GenerationConfig{}.valid(), "Default parameters rejected");
    check(persona::GenerationConfig{0.0f, 1.0f, 1}.valid(), "M1 greedy smoke rejected");
    const auto nan = std::numeric_limits<float>::quiet_NaN();
    const auto infinity = std::numeric_limits<float>::infinity();
    for (const auto temperature : {-1.0f, 2.00001f, nan, infinity, -infinity}) {
        check(!persona::GenerationConfig{temperature, 1.0f, 1}.valid(), "Invalid temperature accepted");
    }
    for (const auto topP : {-1.0f, 0.0f, 1.01f, nan, infinity}) {
        check(!persona::GenerationConfig{0.6f, topP, 1}.valid(), "Invalid topP accepted");
    }
    check(!persona::GenerationConfig{0.6f, 0.8f, 0}.valid(), "Zero token cap accepted");
    check(!persona::GenerationConfig{0.6f, 0.8f, -1}.valid(), "Negative token cap accepted");
    const persona::GenerationConfig precise{0.0000001f, 0.0000002f, 7};
    auto parsed = config(precise);
    check(parsed->temperature() == precise.temperature, "Temperature lost float precision");
    check(parsed->topP() == precise.topP, "top_p was rounded or shadowed by topP");
    check(parsed->max_new_tokens() == 7, "Token cap not forwarded");
    check(parsed->sampler_type() == "topP", "Model sampler was not overridden");
    check(config({0.0f, 1.0f, 1})->sampler_type() == "greedy", "Zero temperature must bypass softmax");
    check(persona::GenerationConfig{2.0f, 1.0f, 1}.valid(), "Maximum temperature rejected");
    const persona::GenerationConfig tiny{std::numeric_limits<float>::denorm_min(), 1.0f, 1};
    check(tiny.valid() && config(tiny)->sampler_type() == "greedy", "Tiny temperature must safely use argmax");
    check(precise.sameSampler({precise.temperature, precise.topP, 100}), "Token cap forced sampler reload");
    std::cout << "PASS validation/precision/aliases/minimal sampler/token cap\n";
}

void effectiveSamplerBehavior() {
    check(sample({0.0f, 1.0f, 1})[0] == 4096, "Zero temperature is not greedy");
    check(sample({std::numeric_limits<float>::denorm_min(), 1.0f, 1})[0] == 4096,
        "Tiny temperature overflowed the active sampler");
    check(sample({0.01f, 1.0f, 1})[0] == 4096, "Low temperature did not concentrate mass");
    const auto hot = sample({2.0f, 1.0f, 1});
    check(hot[0] > 1300 && hot[0] < 2400 && hot[3] > 200, "Temperature did not affect actual sampling");
    check(sample({2.0f, 0.01f, 1})[0] == 4096, "topP did not truncate actual support");

    auto context = std::make_shared<MNN::Transformer::LlmContext>();
    auto settings = config({2.0f, 0.01f, 1});
    std::unique_ptr<MNN::Transformer::Sampler> sampler(
        MNN::Transformer::Sampler::createSampler(context, settings)
    );
    settings->config_.merge(ujson::json::parse(persona::GenerationConfig{2.0f, 1.0f, 1}.json()));
    check(sample(*sampler)[0] == 4096, "Pinned MNN sampler unexpectedly stopped snapshotting config");
    sampler.reset(MNN::Transformer::Sampler::createSampler(context, settings));
    check(sample(*sampler)[0] < 2400, "Reconstruction did not apply changed topP");
    settings->config_.merge(ujson::json::parse(persona::GenerationConfig{2.0f, 0.01f, 1}.json()));
    sampler.reset(MNN::Transformer::Sampler::createSampler(context, settings));
    check(sample(*sampler)[0] == 4096, "A-B-A sampler change retained stale state");
    std::cout << "PASS real MNN 3.6.1 greedy/temperature/topP/stale-config/A-B-A behavior\n";
}

}  // namespace

int main() {
    try {
        validationAndSerialization();
        effectiveSamplerBehavior();
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}
