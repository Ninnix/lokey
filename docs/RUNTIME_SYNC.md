# Runtime update: 17 November 2026

The forks include upstream llama.cpp `d92ea9bb`, whisper.cpp `aba02def`
and stable-diffusion.cpp `532d2c15`. Compatible full revisions and the Loina
validation record are in [loina-core/RUNTIMES.md](https://github.com/Loina-AI/loina-core/blob/main/RUNTIMES.md).
Lokey pins its dependencies in [runtimes.json](../runtimes.json).

Checks on a OnePlus 15 include both text models, translation, prediction/cache
state, memory release/reload, Qwen ASR Q4_K and Parakeet on CPU and Hexagon.
All eight regression groups pass. The four NPU groups also pass after the final
precision correction. Normal Android assembly and lint pass.

The merges retain the CPU FFT audio frontend to avoid a CPU speech slowdown,
keep floating-weight Hexagon repacking opt-in to preserve LFM3 logits, and fix
OpenCL buffer queries before backend initialization. Loina's CPU/OpenCL/Vulkan/
Hexagon logits match their respective old-runtime baselines exactly; chat,
vision, speech, Sana, and embedded server/RPC checks pass.

Qwen's repacked decoder now declares `LLAMA_FTYPE_MOSTLY_Q4_K_S`. The source
file used a tensor-type number as its file-type metadata; payload tensors stay
Q4_K. The model installation test checks the corrected quantization.
