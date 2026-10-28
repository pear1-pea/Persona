cmake_minimum_required(VERSION 3.22.1)
project(persona_sampling_tests LANGUAGES C CXX)

set(PERSONA_MNN_SOURCE_DIR "" CACHE PATH "Pinned MNN 3.6.1 source checkout")
if(NOT EXISTS "${PERSONA_MNN_SOURCE_DIR}/transformers/llm/engine/src/sampler.hpp")
    message(FATAL_ERROR "Supply -DPERSONA_MNN_SOURCE_DIR=<pinned MNN 3.6.1 source>")
endif()

set(MNN_BUILD_LLM ON CACHE BOOL "" FORCE)
set(MNN_BUILD_LLM_OMNI OFF CACHE BOOL "" FORCE)
set(MNN_LLM_BUILD_DEMO OFF CACHE BOOL "" FORCE)
set(MNN_LLM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_CONVERTER OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DEMO OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_OPENCV OFF CACHE BOOL "" FORCE)
set(MNN_METAL OFF CACHE BOOL "" FORCE)
set(MNN_OPENCL OFF CACHE BOOL "" FORCE)
set(MNN_VULKAN OFF CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)
add_subdirectory("${PERSONA_MNN_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/mnn")

enable_testing()
add_executable(persona_sampling_test generation_config_test.cpp)
target_compile_features(persona_sampling_test PRIVATE cxx_std_17)
target_include_directories(persona_sampling_test PRIVATE
    ".."
    "${PERSONA_MNN_SOURCE_DIR}/include"
    "${PERSONA_MNN_SOURCE_DIR}/transformers/llm/engine/include"
    "${PERSONA_MNN_SOURCE_DIR}/transformers/llm/engine/src"
    "${PERSONA_MNN_SOURCE_DIR}/3rd_party"
)
target_link_libraries(persona_sampling_test PRIVATE MNN)
add_test(NAME persona_sampling_test COMMAND persona_sampling_test)

# Compile and link the production JNI translation unit against the same MNN.
if(NOT ANDROID)
    find_package(JNI REQUIRED)
    add_library(persona_jni_compile_check SHARED ../mnn_jni.cpp)
    target_compile_features(persona_jni_compile_check PRIVATE cxx_std_17)
    target_include_directories(persona_jni_compile_check PRIVATE ${JNI_INCLUDE_DIRS}
        ".."
        "${PERSONA_MNN_SOURCE_DIR}/include"
        "${PERSONA_MNN_SOURCE_DIR}/transformers/llm/engine/include"
    )
    target_link_libraries(persona_jni_compile_check PRIVATE MNN)
endif()
