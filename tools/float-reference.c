#include "llama.h"
#include <stdio.h>

int main(int argc,char **argv) {
    if(argc!=3) { fprintf(stderr,"Usage: %s input.gguf output.gguf\n",argv[0]);return 2; }
    struct llama_model_quantize_params params=llama_model_quantize_default_params();
    params.ftype=LLAMA_FTYPE_ALL_F32;params.allow_requantize=true;params.nthread=4;
    return llama_model_quantize(argv[1],argv[2],&params);
}
