cmake_minimum_required(VERSION 3.22.1)
project(persona_mnn)

include(FetchContent)
set(FETCHCONTENT_QUIET OFF)

set(MNN_BUILD_LLM ON CACHE BOOL "" FORCE)
set(MNN_BUILD_LLM_OMNI OFF CACHE BOOL "" FORCE)
set(MNN_LLM_BUILD_DEMO OFF CACHE BOOL "" FORCE)
set(MNN_LLM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE BOOL "" FORCE)
set(MNN_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_CONVERTER OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DEMO OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_OPENCV OFF CACHE BOOL "" FORCE)
set(MNN_OPENCL OFF CACHE BOOL "" FORCE)
set(MNN_VULKAN OFF CACHE BOOL "" FORCE)
set(MNN_USE_LOGCAT ON CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)

set(PERSONA_MNN_SOURCE_DIR "" CACHE PATH "Optional local MNN source checkout")
set(PERSONA_DEFAULT_MNN_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/MNN")

if(NOT PERSONA_MNN_SOURCE_DIR AND EXISTS "${PERSONA_DEFAULT_MNN_SOURCE_DIR}/CMakeLists.txt")
    set(PERSONA_MNN_SOURCE_DIR "${PERSONA_DEFAULT_MNN_SOURCE_DIR}")
endif()

if(PERSONA_MNN_SOURCE_DIR)
    if(NOT EXISTS "${PERSONA_MNN_SOURCE_DIR}/CMakeLists.txt")
        message(FATAL_ERROR "PERSONA_MNN_SOURCE_DIR does not point to an MNN source tree: ${PERSONA_MNN_SOURCE_DIR}")
    endif()
    set(mnn_SOURCE_DIR "${PERSONA_MNN_SOURCE_DIR}")
    add_subdirectory("${mnn_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/_deps/mnn-build")
else()
    FetchContent_Declare(
        mnn
        URL https://github.com/alibaba/MNN/archive/refs/tags/3.6.1.zip
    )
    FetchContent_MakeAvailable(mnn)
endif()

add_library(persona_mnn SHARED mnn_jni.cpp)
target_compile_features(persona_mnn PRIVATE cxx_std_17)
target_include_directories(persona_mnn PRIVATE
    "${mnn_SOURCE_DIR}/include"
    "${mnn_SOURCE_DIR}/transformers/llm/engine/include"
)
target_link_options(persona_mnn PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(persona_mnn PRIVATE MNN android log)
