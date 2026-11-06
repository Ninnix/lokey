#define main keyboard_probe_main
#include "hexagon-keyboard-bench.c"
#undef main

struct trace { FILE *file;int phase;bool active; };
static bool trace_tensor(struct ggml_tensor *tensor,bool ask,void *data) {
    struct trace *trace=data;
    if(!trace->active || tensor->type!=GGML_TYPE_F32 || tensor->op==GGML_OP_NONE || !ggml_is_contiguous(tensor)) return false;
    if(ask) return true;
    uint32_t name=(uint32_t)strlen(tensor->name),count=(uint32_t)ggml_nelements(tensor);
    float *values=malloc(ggml_nbytes(tensor));if(!values) return false;
    ggml_backend_tensor_get(tensor,values,0,ggml_nbytes(tensor));
    fwrite(&trace->phase,sizeof(trace->phase),1,trace->file);fwrite(&name,sizeof(name),1,trace->file);
    fwrite(tensor->name,1,name,trace->file);fwrite(&count,sizeof(count),1,trace->file);
    fwrite(values,sizeof(*values),count,trace->file);free(values);
    return true;
}
int main(int argc,char **argv) {
    if(argc!=4) { fprintf(stderr,"Usage: %s model cpu|htp logits.bin\n",argv[0]);return 2; }
    const struct JNINativeInterface table={.GetStringChars=path_chars,.GetStringLength=path_length,.ReleaseStringChars=path_release};
    JNIEnv env=&table;
    if(!Java_org_lokey_app_Native_loadText(&env,NULL,(jstring)argv[1],!strcmp(argv[2],"cpu")?1:2)) return 1;
    const char *cpu_weights=getenv("LOKEY_CPU_WEIGHTS");
    if(cpu_weights && *cpu_weights && text_accelerated) {
        llama_free(text_ctx);llama_model_free(model);
        struct llama_model_tensor_buft_override overrides[]={{cpu_weights,ggml_backend_cpu_buffer_type()},{NULL,NULL}};
        struct llama_model_params params=cpu_model_params();
        params.devices=text_devices;params.n_gpu_layers=99;params.load_mode=LLAMA_LOAD_MODE_NONE;params.tensor_buft_overrides=overrides;
        model=llama_model_load_from_file(argv[1],params);if(!model) return 1;
        struct llama_context_params cp=llama_context_default_params();
        cp.n_ctx=256;cp.n_batch=256;cp.n_ubatch=128;cp.n_threads=4;cp.n_threads_batch=4;
        text_ctx=llama_init_from_model(model,cp);if(!text_ctx) return 1;
    }
    const char *trace_path=getenv("LOKEY_TENSOR_DUMP");
    struct trace trace={.file=trace_path?fopen(trace_path,"wb"):NULL,.active=true};
    if(trace.file) {
        llama_free(text_ctx);
        struct llama_context_params cp=llama_context_default_params();
        cp.n_ctx=256;cp.n_batch=256;cp.n_ubatch=128;cp.n_threads=4;cp.n_threads_batch=4;
        cp.cb_eval=trace_tensor;cp.cb_eval_user_data=&trace;
        text_ctx=llama_init_from_model(model,cp);if(!text_ctx) return 1;
    }
    FILE *file=fopen(argv[3],"wb");if(!file) return 1;
    const char *prompts[]={"I am on my way ","Can you send me the ","Thank you for your ","See you ","Good mor","I would like to ","Let me know when you ","Ciao come stai ","Bonjour comment allez vous ","Hola como estas "};
    const struct llama_vocab *vocab=llama_model_get_vocab(model);
    llama_token next[16];int n=llama_tokenize(vocab,"hello",5,next,16,false,false);if(n<1) return 1;
    fwrite(&vocab_size,sizeof(vocab_size),1,file);
    printf("{\"backend\":");json_string(text_accelerated?"HTP0":"CPU");
    printf(",\"model\":");json_string(argv[1]);
    printf(",\"cpu_weights\":");json_string(text_accelerated && cpu_weights?cpu_weights:"");
    printf(",\"cases\":[");
    for(size_t i=0;i<sizeof(prompts)/sizeof(*prompts);i++) {
        trace.active=i==0;trace.phase=0;
        char *prompt=prompt_for(prompts[i],i==7?"Italian":i==8?"French":i==9?"Spanish":"English");
        if(!rank_context(prompt)) return 1;free(prompt);
        fwrite(rank_logits,sizeof(float),(size_t)vocab_size,file);
        size_t size=llama_state_get_size(text_ctx);uint8_t *state=malloc(size);
        if(llama_state_get_data(text_ctx,state,size)!=size) return 1;
        trace.phase=1;
        if(llama_decode(text_ctx,llama_batch_get_one(next,1))) return 1;
        float *expected=malloc((size_t)vocab_size*sizeof(float));memcpy(expected,llama_get_logits_ith(text_ctx,-1),(size_t)vocab_size*sizeof(float));
        trace.phase=2;
        if(llama_state_set_data(text_ctx,state,size)!=size || llama_decode(text_ctx,llama_batch_get_one(next,1))) return 1;
        float error=0;const float *actual=llama_get_logits_ith(text_ctx,-1);
        for(int j=0;j<vocab_size;j++) { float delta=fabsf(actual[j]-expected[j]);if(delta>error) error=delta; }
        fwrite(actual,sizeof(float),(size_t)vocab_size,file);
        if(i) putchar(',');printf("{\"prompt\":");json_string(prompts[i]);printf(",\"restore_max_error\":%.9g}",error);
        free(expected);free(state);
    }
    puts("]}");fclose(file);if(trace.file) fclose(trace.file);return 0;
}
