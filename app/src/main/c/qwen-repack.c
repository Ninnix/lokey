#include "qwen-repack.h"
#include "gguf.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

static bool u32_is(struct gguf_context *ctx,const char *key,uint32_t value) {
    int64_t id=gguf_find_key(ctx,key);
    return id>=0 && gguf_get_kv_type(ctx,id)==GGUF_TYPE_UINT32 && gguf_get_val_u32(ctx,id)==value;
}

static bool decoder_metadata(struct gguf_context *dst,struct gguf_context *src) {
    gguf_set_kv(dst,src);
    for(int64_t i=gguf_get_n_kv(dst)-1;i>=0;i--) {
        const char *key=gguf_get_key(dst,i);
        if(!strncmp(key,"qwen3asr.",9)) gguf_remove_key(dst,key);
    }
    gguf_set_val_str(dst,"general.architecture","qwen3vl");
    gguf_set_val_u32(dst,"qwen3vl.block_count",28);
    gguf_set_val_u32(dst,"qwen3vl.context_length",65536);
    gguf_set_val_u32(dst,"qwen3vl.embedding_length",1024);
    gguf_set_val_u32(dst,"qwen3vl.feed_forward_length",3072);
    gguf_set_val_u32(dst,"qwen3vl.attention.head_count",16);
    gguf_set_val_u32(dst,"qwen3vl.attention.head_count_kv",8);
    gguf_set_val_u32(dst,"qwen3vl.attention.key_length",128);
    gguf_set_val_u32(dst,"qwen3vl.attention.value_length",128);
    gguf_set_val_f32(dst,"qwen3vl.attention.layer_norm_rms_epsilon",1e-6f);
    gguf_set_val_f32(dst,"qwen3vl.rope.freq_base",1e6f);
    int32_t sections[]={24,20,20,0};
    gguf_set_arr_data(dst,"qwen3vl.rope.dimension_sections",GGUF_TYPE_INT32,sections,4);
    gguf_set_val_u32(dst,"qwen3vl.n_deepstack_layers",0);
    gguf_set_val_str(dst,"tokenizer.ggml.pre","qwen2");
    gguf_set_val_bool(dst,"tokenizer.ggml.add_bos_token",false);
    gguf_set_val_u32(dst,"tokenizer.ggml.bos_token_id",151645);
    gguf_set_val_u32(dst,"tokenizer.ggml.eos_token_id",151645);
    int64_t tokens=gguf_find_key(src,"tokenizer.ggml.tokens");
    int32_t *types=malloc(151936*sizeof(*types));
    if(!types) return false;
    for(size_t i=0;i<151936;i++) {
        const char *token=gguf_get_arr_str(src,tokens,i);
        types[i]=!strncmp(token,"[PAD",4)?5:i>=151643?3:1;
        // User-defined markers, especially <asr_text>, must survive text decoding.
        if(i==151657 || i==151658 || (i>=151665 && i<=151668) || i==151675 || i==151704) types[i]=4;
    }
    gguf_set_arr_data(dst,"tokenizer.ggml.token_type",GGUF_TYPE_INT32,types,151936);
    free(types);
    return true;
}

static void encoder_metadata(struct gguf_context *dst) {
    gguf_set_val_str(dst,"general.architecture","clip");
    gguf_set_val_str(dst,"general.type","mmproj");
    gguf_set_val_u32(dst,"general.quantization_version",2);
    gguf_set_val_u32(dst,"general.file_type",7);
    gguf_set_val_bool(dst,"clip.has_audio_encoder",true);
    gguf_set_val_str(dst,"clip.audio.projector_type","qwen3a");
    gguf_set_val_u32(dst,"clip.audio.projection_dim",1024);
    gguf_set_val_u32(dst,"clip.audio.embedding_length",896);
    gguf_set_val_u32(dst,"clip.audio.feed_forward_length",3584);
    gguf_set_val_u32(dst,"clip.audio.block_count",18);
    gguf_set_val_u32(dst,"clip.audio.attention.head_count",14);
    gguf_set_val_u32(dst,"clip.audio.num_mel_bins",128);
    gguf_set_val_f32(dst,"clip.audio.attention.layer_norm_epsilon",1e-5f);
}

// Only the container changes. Quantized tensor payloads are copied verbatim.
bool qwen_repack(const char *source,const char *decoder,const char *encoder) {
    bool ok=false;
    struct ggml_context *tensors=NULL,*extra=NULL;
    struct gguf_context *src=NULL,*dec=NULL,*enc=NULL;
    void *data=MAP_FAILED;
    float *positions=NULL;
    int fd=open(source,O_RDONLY);
    struct stat st;
    if(fd<0) return false;
    if(fstat(fd,&st) || st.st_size<24) goto done;
    src=gguf_init_from_file(source,(struct gguf_init_params){.no_alloc=true,.ctx=&tensors});
    if(!src) goto done;
    int64_t arch=gguf_find_key(src,"general.architecture"),tokens=gguf_find_key(src,"tokenizer.ggml.tokens"),merges=gguf_find_key(src,"tokenizer.ggml.merges");
    if(arch<0 || gguf_get_kv_type(src,arch)!=GGUF_TYPE_STRING || strcmp(gguf_get_val_str(src,arch),"qwen3asr") ||
       gguf_get_n_tensors(src)!=614 || !u32_is(src,"qwen3asr.llm.n_layers",28) ||
       !u32_is(src,"qwen3asr.llm.d_model",1024) || !u32_is(src,"qwen3asr.audio.n_layers",18) ||
       !u32_is(src,"qwen3asr.audio.d_model",896) || tokens<0 || merges<0 ||
       gguf_get_kv_type(src,tokens)!=GGUF_TYPE_ARRAY || gguf_get_arr_type(src,tokens)!=GGUF_TYPE_STRING ||
       gguf_get_arr_n(src,tokens)!=151936 || gguf_get_kv_type(src,merges)!=GGUF_TYPE_ARRAY ||
       gguf_get_arr_type(src,merges)!=GGUF_TYPE_STRING) goto done;
    data=mmap(NULL,st.st_size,PROT_READ,MAP_PRIVATE,fd,0);
    if(data==MAP_FAILED) goto done;
    dec=gguf_init_empty();enc=gguf_init_empty();
    if(!decoder_metadata(dec,src)) goto done;
    encoder_metadata(enc);
    gguf_set_val_str(dec,"general.license","apache-2.0");
    gguf_set_val_str(enc,"general.license","apache-2.0");
    for(int64_t i=0;i<gguf_get_n_tensors(src);i++) {
        const char *name=gguf_get_tensor_name(src,i);
        bool audio=!strncmp(name,"audio.",6);
        if(!strcmp(name,"audio.mel_filters") || !strcmp(name,"audio.mel_window")) continue;
        size_t offset=gguf_get_data_offset(src)+gguf_get_tensor_offset(src,i),size=gguf_get_tensor_size(src,i);
        if(offset>(size_t)st.st_size || size>(size_t)st.st_size-offset) goto done;
        struct ggml_tensor *tensor=ggml_get_tensor(tensors,name);
        char mapped[GGML_MAX_NAME],suffix[16];int layer;
        if(!audio) snprintf(mapped,sizeof(mapped),"%s",name);
        else if(!strncmp(name,"audio.conv.",11)) snprintf(mapped,sizeof(mapped),"a.conv2d.%s",name+11);
        else if(!strncmp(name,"audio.ln_post.",14)) snprintf(mapped,sizeof(mapped),"a.post_ln.%s",name+14);
        else if(!strncmp(name,"audio.proj1.",12)) snprintf(mapped,sizeof(mapped),"mm.a.mlp.1.%s",name+12);
        else if(!strncmp(name,"audio.proj2.",12)) snprintf(mapped,sizeof(mapped),"mm.a.mlp.2.%s",name+12);
        else if(sscanf(name,"audio.blk.%d.attn_norm.%15s",&layer,suffix)==2) snprintf(mapped,sizeof(mapped),"a.blk.%d.ln1.%s",layer,suffix);
        else if(sscanf(name,"audio.blk.%d.ffn_norm.%15s",&layer,suffix)==2) snprintf(mapped,sizeof(mapped),"a.blk.%d.ln2.%s",layer,suffix);
        else snprintf(mapped,sizeof(mapped),"a.%s",name+6);
        tensor->data=(char *)data+offset;
        if(audio && !strncmp(name,"audio.conv.",11) && strstr(name,".bias")) {
            int64_t channels=tensor->ne[0];
            tensor->ne[0]=1;tensor->ne[1]=1;tensor->ne[2]=channels;
            for(int d=1;d<GGML_MAX_DIMS;d++) tensor->nb[d]=tensor->nb[d-1]*tensor->ne[d-1];
        }
        ggml_set_name(tensor,mapped);
        gguf_add_tensor(audio?enc:dec,tensor);
    }
    // llama.cpp expects the deterministic sinusoidal table in its audio GGUF.
    extra=ggml_init((struct ggml_init_params){.mem_size=ggml_tensor_overhead(),.no_alloc=true});
    positions=malloc(1500*896*sizeof(float));
    if(!extra || !positions) goto done;
    float step=logf(10000.f)/447.f;
    for(int p=0;p<1500;p++) for(int i=0;i<448;i++) {
        float angle=p*expf(-step*i);
        positions[p*896+i]=sinf(angle);positions[p*896+448+i]=cosf(angle);
    }
    struct ggml_tensor *pos=ggml_new_tensor_2d(extra,GGML_TYPE_F32,896,1500);
    pos->data=positions;ggml_set_name(pos,"a.position_embd.weight");gguf_add_tensor(enc,pos);
    if(gguf_get_n_tensors(dec)!=311 || gguf_get_n_tensors(enc)!=302) goto done;
    ok=gguf_write_to_file(enc,encoder,false) && gguf_write_to_file(dec,decoder,false);
done:
    if(enc) gguf_free(enc);
    if(dec) gguf_free(dec);
    if(src) gguf_free(src);
    if(extra) ggml_free(extra);
    if(tensors) ggml_free(tensors);
    free(positions);
    if(data!=MAP_FAILED) munmap(data,st.st_size);
    close(fd);
    if(!ok) { unlink(decoder);unlink(encoder); }
    return ok;
}
