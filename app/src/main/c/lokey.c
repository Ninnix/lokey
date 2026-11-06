#include <jni.h>
#include <ctype.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <dlfcn.h>
#include <unistd.h>
#include "llama.h"
#include "gguf.h"
#include "parakeet.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "loina.h"

extern struct parakeet_context *loina_parakeet_load(const char *, unsigned, loina_job *);

static struct llama_model *model;
static struct llama_context *text_ctx;
static struct parakeet_context *speech_ctx;
static struct llama_model *asr_model;
static struct llama_context *asr_ctx;
static mtmd_context *asr_audio;
static pthread_mutex_t text_mutex=PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t speech_mutex=PTHREAD_MUTEX_INITIALIZER;
static atomic_bool cancelled, speech_cancelled;
static struct llama_model *translation_model;
static struct llama_context *translation_ctx;
static pthread_mutex_t translation_mutex=PTHREAD_MUTEX_INITIALIZER;
static atomic_uint_least64_t translation_epoch;
static uint64_t translation_active;
static float letter_probs[26];
static char *rank_prompt;
static float *rank_logits;
static char **token_pieces;
static int vocab_size;
static bool *token_ends;
#define TEXT_CHECKPOINTS 3
struct text_checkpoint { uint8_t *state;float *logits;size_t size;llama_token tokens[240];int count; };
static struct text_checkpoint checkpoints[TEXT_CHECKPOINTS];
static llama_token rank_tokens[240];
static int rank_count,checkpoint_next;
static ggml_backend_dev_t cpu_devices[1],text_devices[2];
static ggml_backend_dev_t speech_devices[2];
static bool text_accelerated;
static int speech_accelerator;
static pthread_once_t backend_once=PTHREAD_ONCE_INIT;
#ifdef LOKEY_HEXAGON
extern int htpdrv_get_arch(int domain,int *arch);
#endif

static void init_backends(void) {
#ifdef LOKEY_HEXAGON
    Dl_info info;
    if(dladdr(&backend_once,&info) && info.dli_fname) {
        char *path=realpath(info.dli_fname,NULL);
        char *slash=path?strrchr(path,'/'):NULL;
        if(slash) {
            *slash=0;
            setenv("ADSP_LIBRARY_PATH",path,1);setenv("CDSP_LIBRARY_PATH",path,1);
        }
        free(path);
    }
    setenv("GGML_HEXAGON_POWER_MODE","balanced",0);
    // These models fit direct mappings; some speech ops still dereference DSP pointers.
    setenv("GGML_HEXAGON_DMA64","0",0);
#endif
    llama_backend_init();
}
JNIEXPORT jboolean JNICALL Java_org_lokey_app_Native_npuAvailable(JNIEnv *env,jobject self) {
    (void)env;(void)self;
#ifdef LOKEY_HEXAGON
    pthread_once(&backend_once,init_backends);
    int arch=0;
    // Query the actual CDSP architecture, before ggml caps older DSPs to v73.
    if(htpdrv_get_arch(3,&arch) || arch<73 || !ggml_backend_dev_by_name("HTP0")) return JNI_FALSE;
    Dl_info info;
    if(!dladdr(&backend_once,&info) || !info.dli_fname) return JNI_FALSE;
    char *path=realpath(info.dli_fname,NULL);
    char *slash=path?strrchr(path,'/'):NULL;
    bool ready=false;
    if(slash) {
        *slash=0;char kernel[4096];
        int n=snprintf(kernel,sizeof(kernel),"%s/libggml-htp-v%d.so",path,arch);
        ready=n>0 && (size_t)n<sizeof(kernel) && access(kernel,R_OK)==0;
    }
    free(path);return ready?JNI_TRUE:JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}
static struct llama_model_params cpu_model_params(void) {
    struct llama_model_params params=llama_model_default_params();
    params.devices=cpu_devices;params.n_gpu_layers=0;
    return params;
}
#ifdef LOKEY_HEXAGON
static bool accelerated_model(const char *path) {
    struct gguf_context *metadata=gguf_init_from_file(path,(struct gguf_init_params){.no_alloc=true});
    if(!metadata) return false;
    int64_t key=gguf_find_key(metadata,"general.architecture");
    const char *arch=key>=0 && gguf_get_kv_type(metadata,key)==GGUF_TYPE_STRING?gguf_get_val_str(metadata,key):"";
    bool supported=!strcmp(arch,"qwen35") || !strcmp(arch,"lfm2");
    gguf_free(metadata);return supported;
}
#endif

#define WORD_CACHE 16384
struct word_token { char *word;llama_token first;int count; };
static struct word_token word_cache[WORD_CACHE];
static unsigned cached_words;

static bool abort_run(void *unused) { (void)unused;return atomic_load(&cancelled); }
static bool abort_speech(void *unused) { (void)unused;return atomic_load(&speech_cancelled); }
static bool abort_translation(void *unused) { (void)unused;return atomic_load(&translation_epoch)!=translation_active; }

static unsigned codepoint(const char *s,size_t size,size_t *at) {
    unsigned c=(unsigned char)s[(*at)++];
    if(c<128) return c;
    int n=c>=0xf0?3:c>=0xe0?2:c>=0xc0?1:4;
    if(n==4 || *at+n>size) { *at=size;return 0; }
    c&=(1u<<(6-n))-1;
    while(n--) {
        unsigned next=(unsigned char)s[(*at)++];
        if((next&0xc0)!=0x80) return 0;
        c=(c<<6)|(next&63);
    }
    return c;
}
static char *utf(JNIEnv *env,jstring value) {
    const jchar *chars=(*env)->GetStringChars(env,value,NULL);
    if(!chars) return NULL;
    int size=(*env)->GetStringLength(env,value);
    char *out=malloc((size_t)size*3+1),*p=out;
    if(out) for(int i=0;i<size;i++) {
        unsigned c=chars[i];
        if(c>=0xd800 && c<=0xdbff && i+1<size && chars[i+1]>=0xdc00 && chars[i+1]<=0xdfff)
            c=0x10000+((c-0xd800)<<10)+(chars[++i]-0xdc00);
        else if(c>=0xd800 && c<=0xdfff) c=0xfffd;
        if(c<0x80) *p++=(char)c;
        else if(c<0x800) { *p++=(char)(0xc0|(c>>6));*p++=(char)(0x80|(c&63)); }
        else if(c<0x10000) { *p++=(char)(0xe0|(c>>12));*p++=(char)(0x80|((c>>6)&63));*p++=(char)(0x80|(c&63)); }
        else { *p++=(char)(0xf0|(c>>18));*p++=(char)(0x80|((c>>12)&63));*p++=(char)(0x80|((c>>6)&63));*p++=(char)(0x80|(c&63)); }
    }
    if(out) *p=0;
    (*env)->ReleaseStringChars(env,value,chars);
    return out;
}
static jstring java_string(JNIEnv *env,const char *text) {
    size_t size=strlen(text),count=0;
    jchar *chars=malloc((size+1)*sizeof(*chars));
    if(!chars) return NULL;
    for(size_t i=0;i<size;) {
        unsigned c=codepoint(text,size,&i);
        if(!c || c>0x10ffff || (c>=0xd800 && c<=0xdfff)) c=0xfffd;
        if(c>=0x10000) { c-=0x10000;chars[count++]=0xd800+(c>>10);chars[count++]=0xdc00+(c&1023); }
        else chars[count++]=(jchar)c;
    }
    jstring result=(*env)->NewString(env,chars,(jsize)count);
    free(chars);
    return result;
}
static void fail(JNIEnv *env,const char *message) {
    jclass type=(*env)->FindClass(env,"java/lang/IllegalStateException");
    if(type) { (*env)->ThrowNew(env,type,message);(*env)->DeleteLocalRef(env,type); }
}
static char *piece(const struct llama_vocab *vocab,llama_token token) {
    int n=llama_token_to_piece(vocab,token,NULL,0,0,false);
    if(n<0) n=-n;
    char *out=calloc((size_t)n+1,1);
    if(out && n>0 && llama_token_to_piece(vocab,token,out,n,0,false)<0) { free(out);return NULL; }
    return out;
}
static void clear_word_cache(void) {
    for(int i=0;i<WORD_CACHE;i++) { free(word_cache[i].word);word_cache[i]=(struct word_token){0}; }
    cached_words=0;
}
static void reset_text(void) {
    free(rank_prompt);rank_prompt=NULL;rank_count=0;
    free(rank_logits);rank_logits=NULL;
    for(int i=0;i<TEXT_CHECKPOINTS;i++) {
        free(checkpoints[i].state);free(checkpoints[i].logits);checkpoints[i]=(struct text_checkpoint){0};
    }
    checkpoint_next=0;
    if(text_ctx) llama_memory_clear(llama_get_memory(text_ctx),true);
}
static void clear_text_cache(void) {
    reset_text();
    free(token_ends);token_ends=NULL;
    if(token_pieces) for(int i=0;i<vocab_size;i++) free(token_pieces[i]);
    free(token_pieces);token_pieces=NULL;vocab_size=0;
    clear_word_cache();
}
static struct word_token *cached_token(const struct llama_vocab *vocab,const char *word) {
    if(cached_words>WORD_CACHE*3/4) clear_word_cache();
    uint32_t hash=2166136261u;
    for(const unsigned char *p=(const unsigned char *)word;*p;p++) hash=(hash^*p)*16777619u;
    unsigned at=hash&(WORD_CACHE-1);
    while(word_cache[at].word && strcmp(word_cache[at].word,word)) at=(at+1)&(WORD_CACHE-1);
    struct word_token *entry=&word_cache[at];
    if(entry->word) return entry;
    int n=-llama_tokenize(vocab,word,(int)strlen(word),NULL,0,false,false);
    if(n<=0) return NULL;
    llama_token *tokens=malloc((size_t)n*sizeof(*tokens));
    if(!tokens) return NULL;
    int count=llama_tokenize(vocab,word,(int)strlen(word),tokens,n,false,false);
    char *copy=count>0?strdup(word):NULL;
    if(copy) { *entry=(struct word_token){copy,tokens[0],count};cached_words++; }
    free(tokens);
    return copy?entry:NULL;
}
static char *prompt_for(const char *context,const char *language) {
    size_t size=strlen(context)+strlen(language)+9;
    char *prompt=malloc(size);
    if(!prompt) return NULL;
    snprintf(prompt,size,"%s text:\n%s",language,context);
    size_t n=strlen(prompt);
    while(n && isspace((unsigned char)prompt[n-1])) prompt[--n]=0;
    return prompt;
}
static bool rank_context(const char *prompt) {
    if(!text_ctx) return false;
    if(rank_prompt && rank_logits && !strcmp(prompt,rank_prompt)) return true;
    const struct llama_vocab *vocab=llama_model_get_vocab(model);
    int n=-llama_tokenize(vocab,prompt,(int)strlen(prompt),NULL,0,true,false);
    if(n<=0 || n>240) return false;
    llama_token tokens[240];
    if(llama_tokenize(vocab,prompt,(int)strlen(prompt),tokens,n,true,false)!=n) return false;
    free(rank_prompt);rank_prompt=NULL;
    int reused=0;
    if(rank_count>0 && rank_count<=n && !memcmp(rank_tokens,tokens,(size_t)rank_count*sizeof(*tokens))) reused=rank_count;
    struct text_checkpoint *checkpoint=NULL;
    for(int i=0;i<TEXT_CHECKPOINTS;i++) {
        struct text_checkpoint *saved=&checkpoints[i];
        if(saved->state && saved->count>reused && saved->count<=n &&
            !memcmp(saved->tokens,tokens,(size_t)saved->count*sizeof(*tokens))) { reused=saved->count;checkpoint=saved; }
    }
    // Full snapshots retain attention and recurrent state. Never partially erase
    // a recurrent sequence or assume matching text implies matching token IDs.
    if(checkpoint && llama_state_set_data(text_ctx,checkpoint->state,checkpoint->size)!=checkpoint->size) reused=0;
    rank_count=0;
    if(!reused) llama_memory_clear(llama_get_memory(text_ctx),true);
    if(reused==n) {
        // Runtime snapshots contain model memory, not the last output logits.
        if(checkpoint) memcpy(rank_logits,checkpoint->logits,(size_t)vocab_size*sizeof(*rank_logits));
        memcpy(rank_tokens,tokens,(size_t)n*sizeof(*tokens));rank_count=n;
        rank_prompt=strdup(prompt);return rank_prompt!=NULL;
    }
    if(reused<n && llama_decode(text_ctx,llama_batch_get_one(tokens+reused,n-reused))) return false;
    const float *logits=llama_get_logits_ith(text_ctx,-1);
    if(!logits) return false;
    float high=-INFINITY;
    for(int i=0;i<vocab_size;i++) if(logits[i]>high) high=logits[i];
    double sum=0;
    for(int i=0;i<vocab_size;i++) sum+=exp(logits[i]-high);
    float normalizer=high+(float)log(sum);
    if(!rank_logits) rank_logits=malloc((size_t)vocab_size*sizeof(*rank_logits));
    if(!rank_logits) return false;
    for(int i=0;i<vocab_size;i++) rank_logits[i]=logits[i]-normalizer;
    rank_prompt=strdup(prompt);
    if(!rank_prompt) return false;
    memcpy(rank_tokens,tokens,(size_t)n*sizeof(*tokens));rank_count=n;
    if(reused<n) {
        size_t size=llama_state_get_size(text_ctx);
        uint8_t *state=malloc(size);
        float *saved_logits=malloc((size_t)vocab_size*sizeof(*saved_logits));
        if(state && saved_logits && llama_state_get_data(text_ctx,state,size)==size) {
            struct text_checkpoint *saved=&checkpoints[checkpoint_next];
            free(saved->state);free(saved->logits);
            *saved=(struct text_checkpoint){.state=state,.logits=saved_logits,.size=size,.count=n};
            memcpy(saved_logits,rank_logits,(size_t)vocab_size*sizeof(*saved_logits));
            memcpy(saved->tokens,tokens,(size_t)n*sizeof(*tokens));
            checkpoint_next=(checkpoint_next+1)%TEXT_CHECKPOINTS;
        } else { free(state);free(saved_logits); }
    }
    return rank_prompt!=NULL;
}
JNIEXPORT void JNICALL Java_org_lokey_app_Native_resetText(JNIEnv *env,jobject self) {
    (void)env;(void)self;
    pthread_mutex_lock(&text_mutex);reset_text();pthread_mutex_unlock(&text_mutex);
}
static bool word_part(const char *raw,char word[128],bool *done);
JNIEXPORT jboolean JNICALL Java_org_lokey_app_Native_loadText(JNIEnv *env,jobject self,jstring path,jint accelerator) {
    (void)self;
    jboolean loaded;
    pthread_mutex_lock(&text_mutex);
    atomic_store(&cancelled,false);
    clear_text_cache();
    if(text_ctx) llama_free(text_ctx);
    if(model) llama_model_free(model);
    text_ctx=NULL;model=NULL;text_accelerated=false;
    char *filename=utf(env,path);
    if(!filename) goto done;
    pthread_once(&backend_once,init_backends);
    text_devices[0]=NULL;
#ifdef LOKEY_HEXAGON
    if(accelerator!=1 && (accelerator==2 || accelerated_model(filename))) text_devices[0]=ggml_backend_dev_by_name("HTP0");
#endif
    if(accelerator==2 && !text_devices[0]) { free(filename);goto done; }
    struct llama_context_params cp=llama_context_default_params();
    cp.n_ctx=256;cp.n_batch=256;cp.n_ubatch=128;cp.n_threads=4;cp.n_threads_batch=4;cp.abort_callback=abort_run;
    for(int attempt=0;attempt<2;attempt++) {
        struct llama_model_params mp=cpu_model_params();
        if(text_devices[0]) { mp.devices=text_devices;mp.n_gpu_layers=99;mp.load_mode=LLAMA_LOAD_MODE_NONE; }
        model=llama_model_load_from_file(filename,mp);
        if(model) text_ctx=llama_init_from_model(model,cp);
        if(text_ctx) { text_accelerated=text_devices[0]!=NULL;break; }
        if(model) llama_model_free(model);
        model=NULL;
        if(!text_devices[0] || accelerator==2) break;
        text_devices[0]=NULL;
    }
    free(filename);
    if(!text_ctx) goto done;
    const struct llama_vocab *vocab=llama_model_get_vocab(model);
    vocab_size=llama_vocab_n_tokens(vocab);
    token_pieces=calloc((size_t)vocab_size,sizeof(*token_pieces));
    token_ends=calloc((size_t)vocab_size,sizeof(*token_ends));
    if(!token_pieces || !token_ends) { clear_text_cache();llama_free(text_ctx);text_ctx=NULL;goto done; }
    for(int i=0;i<vocab_size;i++) if(!llama_vocab_is_control(vocab,i) && !llama_vocab_is_eog(vocab,i)) token_pieces[i]=piece(vocab,i);
    for(int i=0;i<vocab_size;i++) {
        token_ends[i]=llama_vocab_is_eog(vocab,i);
        const char *part=token_pieces[i];
        if(!part || !*part) continue;
        size_t at=0;unsigned c=codepoint(part,strlen(part),&at);
        if(!c || at>4) continue;
        char probe[8]="a",word[128];bool done=false;
        memcpy(probe+1,part,at);probe[at+1]=0;
        token_ends[i]=word_part(probe,word,&done) && done && !strcmp(word,"a");
    }
done:
    loaded=text_ctx!=NULL;
    pthread_mutex_unlock(&text_mutex);
    return loaded;
}
JNIEXPORT jstring JNICALL Java_org_lokey_app_Native_textBackend(JNIEnv *env,jobject self) {
    (void)self;
    pthread_mutex_lock(&text_mutex);
    jstring result=java_string(env,text_accelerated?"HTP0":"CPU");
    pthread_mutex_unlock(&text_mutex);return result;
}
JNIEXPORT jfloatArray JNICALL Java_org_lokey_app_Native_rank(JNIEnv *env,jobject self,jstring context,jstring language,jobjectArray words) {
    (void)self;
    pthread_mutex_lock(&text_mutex);
    atomic_store(&cancelled,false);
    int n=(*env)->GetArrayLength(env,words);
    float *scores=malloc((size_t)(n+1)*sizeof(*scores));
    char *text=utf(env,context),*lang=utf(env,language);
    char *prompt=text && lang?prompt_for(text,lang):NULL;
    jfloatArray result=NULL;
    if(!scores || !prompt || !rank_context(prompt)) { result=(*env)->NewFloatArray(env,0);goto done; }
    const struct llama_vocab *vocab=llama_model_get_vocab(model);
    for(int i=0;i<n;i++) {
        scores[i]=-30;
        jstring value=(jstring)(*env)->GetObjectArrayElement(env,words,i);
        char *word=utf(env,value);
        (*env)->DeleteLocalRef(env,value);
        if(!word) continue;
        size_t size=strlen(word);
        bool punctuation=size && strspn(word,"?!.,:;")==size;
        char *input=malloc(size+2);
        if(input) {
            if(punctuation) memcpy(input,word,size+1);
            else { input[0]=' ';memcpy(input+1,word,size+1); }
            struct word_token *cached=cached_token(vocab,input);
            if(cached) scores[i]=rank_logits[cached->first]-.18f*(cached->count-1);
        }
        free(input);free(word);
    }
    result=(*env)->NewFloatArray(env,n);
    if(result) (*env)->SetFloatArrayRegion(env,result,0,n,scores);
done:
    free(scores);free(prompt);free(text);free(lang);
    pthread_mutex_unlock(&text_mutex);
    return result;
}
static int word_key(const char *word,uint32_t out[128]) {
    size_t size=strlen(word);int n=0;
    for(size_t i=0;i<size && n<127;) {
        unsigned c=codepoint(word,size,&i);
        if(!c) break;
        if(c=='\'' || c==0x2019) continue;
        if(c>='A' && c<='Z') c+=32;
        else if(c>=0xc0 && c<=0xde && c!=0xd7) c+=32;
        else if(c==0x178) c=0xff;
        else if(c>=0x391 && c<=0x3ab && c!=0x3a2) c+=0x20;
        else if(c==0x3c2) c=0x3c3;
        else if(c==0x386) c=0x3ac;
        else if(c>=0x388 && c<=0x38a) c+=0x25;
        else if(c==0x38c) c=0x3cc;
        else if(c>=0x38e && c<=0x38f) c+=0x3f;
        else if(c>=0x1e00 && c<=0x1e95 && !(c&1)) c++;
        else if(c==0x1e9e) c=0xdf;
        else if(c>=0x1ea0 && c<=0x1eff && !(c&1)) c++;
        else if(c>=0x410 && c<=0x42f) c+=0x20;
        else if(c>=0x400 && c<=0x40f) c+=0x50;
        else if(c>=0x460 && c<=0x481 && !(c&1)) c++;
        else if(c>=0x48a && c<=0x4bf && !(c&1)) c++;
        else if(c==0x4c0) c=0x4cf;
        else if(c>=0x4c1 && c<=0x4ce && (c&1)) c++;
        else if(c>=0x4d0 && c<=0x52f && !(c&1)) c++;
        else if(c==0x130) c='i';
        else if(c>=0x100 && c<=0x137 && !(c&1)) c++;
        else if(c>=0x139 && c<=0x148 && (c&1)) c++;
        else if(c>=0x14a && c<=0x177 && !(c&1)) c++;
        else if(c>=0x179 && c<=0x17e && (c&1)) c++;
        else if(c>=0x1f8 && c<=0x21f && !(c&1)) c++;
        out[n++]=c;
    }
    out[n]=0;
    return n;
}
static bool word_part(const char *raw,char word[128],bool *done) {
    size_t start=strspn(raw," "),size=strlen(raw);
    if(start==size) return false;
    *done=false;
    size_t end=size;
    for(size_t i=start;i<size;) {
        size_t at=i;
        unsigned c=codepoint(raw,size,&i);
        if(!c) { end=at;break; }
        bool letter=(c>='a' && c<='z') || (c>='A' && c<='Z') ||
            (c>=0xc0 && c<=0x24f && c!=0xd7 && c!=0xf7) || (c>=0x300 && c<=0x36f) ||
            (c>=0x370 && c<=0x3ff && c!=0x37e && c!=0x387) ||
            (c>=0x1e00 && c<=0x1eff) ||
            (c>=0x400 && c<=0x52f && c!=0x482) || (c>=0x620 && c<=0x65f) ||
            (c>=0x66e && c<=0x6d3) || c==0x6d5 || (c>=0x6d6 && c<=0x6ed) ||
            (c>=0x6ee && c<=0x6ef) || (c>=0x6fa && c<=0x6fc) || c==0x6ff ||
            (c>=0x750 && c<=0x77f) || (c>=0x8a0 && c<=0x8c9) ||
            (c>=0x900 && c<=0x963) || (c>=0xe01 && c<=0xe3a) || (c>=0xe40 && c<=0xe4e) ||
            (c>=0x1100 && c<=0x11ff) || (c>=0x3131 && c<=0x318e) || (c>=0xac00 && c<=0xd7a3) ||
            (c>=0x3041 && c<=0x3096) || (c>=0x3099 && c<=0x309f) || (c>=0x30a1 && c<=0x30fa) || c==0x30fc ||
            c==0x200c || c==0x200d ||
            (c>=0x3400 && c<=0x4dbf) || (c>=0x4e00 && c<=0x9fff) ||
            (c>=0xf900 && c<=0xfaff) || (c>=0x20000 && c<=0x2fa1f);
        bool apostrophe=c=='\'' || c==0x2019;
        if(!letter && !apostrophe) {
            if(at==start && (c=='?' || c=='.' || c=='!')) { word[0]=(char)c;word[1]=0;*done=true;return true; }
            if(at==start) return false;
            end=at;*done=true;break;
        }
        if(at==start && apostrophe) return false;
    }
    if(end<=start || end-start>=128) return false;
    if(*done && (raw[end-1]=='\'' || (end-start>=3 && !memcmp(raw+end-3,"\xe2\x80\x99",3)))) return false;
    memcpy(word,raw+start,end-start);word[end-start]=0;
    return true;
}
struct word_step { llama_token token;char raw[256],word[128];bool done;float score; };
static int prefix_distance(const uint32_t *word,int n,const uint32_t *prefix,int m,bool finished) {
    int rows[3][129],best=129;
    for(int j=0;j<=m;j++) rows[0][j]=j;
    for(int i=1;i<=n;i++) {
        int *row=rows[i%3],*prev=rows[(i-1)%3];row[0]=i;
        for(int j=1;j<=m;j++) {
            int a=prev[j]+1,b=row[j-1]+1,c=prev[j-1]+(word[i-1]!=prefix[j-1]);
            row[j]=a<b?a:b;if(c<row[j]) row[j]=c;
            if(i>1 && j>1 && word[i-1]==prefix[j-2] && word[i-2]==prefix[j-1]) {
                c=rows[(i-2)%3][j-2]+1;if(c<row[j]) row[j]=c;
            }
        }
        if(i>=m-1 && row[m]<best) best=row[m];
    }
    if(finished && n<=m) return rows[n%3][m];
    if(n<m) for(int j=0;j<=m;j++) if(rows[n%3][j]<best) best=rows[n%3][j];
    return best;
}
static bool word_pending(const char *raw) {
    size_t start=strspn(raw," "),size=strlen(raw)-start;
    if(!size) return start<=2;
    const unsigned char *bytes=(const unsigned char *)raw+start;
    int count=bytes[0]>=0xc2 && bytes[0]<=0xdf?2:bytes[0]>=0xe0 && bytes[0]<=0xef?3:bytes[0]>=0xf0 && bytes[0]<=0xf4?4:0;
    if(!count || size>=(size_t)count) return false;
    for(size_t i=1;i<size;i++) if((bytes[i]&0xc0)!=0x80) return false;
    if(size>1 && ((bytes[0]==0xe0 && bytes[1]<0xa0) || (bytes[0]==0xed && bytes[1]>=0xa0) ||
        (bytes[0]==0xf0 && bytes[1]<0x90) || (bytes[0]==0xf4 && bytes[1]>=0x90))) return false;
    return true;
}
static bool word_bytes(const char *raw) {
    size_t size=strlen(raw);
    for(size_t at=0;at<size;) {
        size_t start=at;unsigned first=(unsigned char)raw[at];
        if(first<0x80) { at++;continue; }
        if(first<0xc2 || first>0xf4) return false;
        unsigned c=codepoint(raw,size,&at);
        if(!c) return word_pending(raw+start);
        if((first>=0xc2 && c<0x80) || (first>=0xe0 && c<0x800) || (first>=0xf0 && c<0x10000) ||
            c>0x10ffff || (c>=0xd800 && c<=0xdfff)) return false;
    }
    return true;
}
static int word_steps(const char *raw,const uint32_t *prefix,int prefix_size,const float *logits,struct word_step *best,int limit,int edits,bool boundary) {
    int count=0;
    size_t size=strlen(raw);
    char previous[128];bool finished;
    bool has_word=size && word_part(raw,previous,&finished);
    for(int i=0;i<vocab_size;i++) {
        const char *part=token_pieces[i];
        if(!part || !*part || (count==limit && logits[i]<=best[count-1].score)) continue;
        if(!size && boundary && *part!=' ' && !strchr("?.!",*part)) continue;
        size_t length=strlen(part);
        if(size+length>=sizeof(best->raw)) continue;
        struct word_step step={.token=i,.score=logits[i]};
        memcpy(step.raw,raw,size);memcpy(step.raw+size,part,length+1);
        if(!word_bytes(step.raw)) continue;
        // Byte-level vocabularies can split the first letter across tokens.
        bool pending=word_pending(step.raw);
        if(!pending && !word_part(step.raw,step.word,&step.done)) continue;
        if(has_word && step.done && !strcmp(previous,step.word)) continue;
        uint32_t key[128];int n=word_key(step.word,key);
        int common=n<prefix_size?n:prefix_size;
        if((!n && !pending) || n>32) continue;
        int distance=edits?prefix_distance(key,n,prefix,prefix_size,step.done):memcmp(key,prefix,(size_t)common*sizeof(*key))?1:0;
        if(distance>edits || (step.done && n<prefix_size-edits)) continue;
        if(count==limit && step.score<=best[count-1].score) continue;
        int at=count<limit?count++:limit-1;
        while(at>0 && best[at-1].score<step.score) { best[at]=best[at-1];at--; }
        best[at]=step;
    }
    return count;
}
static double milliseconds(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC,&now);
    return now.tv_sec*1000.0+now.tv_nsec/1000000.0;
}
#define COMPLETION_WORDS 24
#define COMPLETION_BYTES 4096
#define WORD_PATHS 32
struct word_path { struct word_step step;llama_token tokens[8];int count; };
struct completed_word { char word[128];uint32_t key[128];float score; };
static float log_normalizer(const float *logits) {
    float high=-INFINITY;
    for(int i=0;i<vocab_size;i++) if(logits[i]>high) high=logits[i];
    if(!isfinite(high)) return INFINITY;
    double sum=0;
    for(int i=0;i<vocab_size;i++) sum+=exp(logits[i]-high);
    return high+(float)log(sum);
}
static void keep_word(struct completed_word words[COMPLETION_WORDS],int *count,const struct word_step *step,float score) {
    if(!isfinite(score)) return;
    uint32_t key[128];int length=word_key(step->word,key);
    int at=*count;
    for(int i=0;i<*count;i++) if(!memcmp(words[i].key,key,(size_t)(length+1)*sizeof(*key))) { at=i;break; }
    if(at<*count && words[at].score>=score) return;
    if(at==*count) {
        if(*count==COMPLETION_WORDS) {
            at=*count-1;
            if(words[at].score>=score) return;
        } else (*count)++;
    }
    struct completed_word word={.score=score};
    memcpy(word.word,step->word,strlen(step->word)+1);
    memcpy(word.key,key,(size_t)(length+1)*sizeof(*key));
    while(at>0 && words[at-1].score<score) { words[at]=words[at-1];at--; }
    words[at]=word;
}
static float path_priority(const struct word_path *path,int prefix_size) {
    uint32_t key[128];int n=word_key(path->step.word,key);
    // Reach the observed prefix before spending the budget on other tokenizations.
    // Completed words are still ranked by their unadjusted log-probabilities.
    return path->step.score+1.5f*(n<prefix_size?n:prefix_size);
}
static void keep_path(struct word_path paths[WORD_PATHS],int *count,struct word_path path,int prefix_size) {
    if(!isfinite(path.step.score)) return;
    float priority=path_priority(&path,prefix_size);
    if(*count==WORD_PATHS && path_priority(&paths[*count-1],prefix_size)>=priority) return;
    int at=*count<WORD_PATHS?(*count)++:WORD_PATHS-1;
    while(at>0 && path_priority(&paths[at-1],prefix_size)<priority) { paths[at]=paths[at-1];at--; }
    paths[at]=path;
}
static bool restore_text(const uint8_t *state,size_t size) {
    if(llama_state_set_data(text_ctx,state,size)==size) return true;
    free(rank_prompt);rank_prompt=NULL;rank_count=0;
    return false;
}
static void complete(const char *context,const char *language,const char *prefix,int edits,char output[COMPLETION_BYTES]) {
    output[0]=0;
    char *prompt=prompt_for(context,language);
    if(!prompt || !rank_context(prompt)) { free(prompt);return; }
    free(prompt);
    uint32_t key[128];int prefix_size=word_key(prefix,key);
    struct word_step roots[COMPLETION_WORDS];
    bool boundary=*context && strcmp(language,"Chinese") && strcmp(language,"Cantonese") && strcmp(language,"Japanese") && strcmp(language,"Thai");
    int count=word_steps("",key,prefix_size,rank_logits,roots,COMPLETION_WORDS,edits,boundary);
    size_t state_size=llama_state_get_size(text_ctx);
    uint8_t *state=malloc(state_size);
    if(!state || llama_state_get_data(text_ctx,state,state_size)!=state_size) { free(state);return; }
    struct word_path paths[WORD_PATHS];int pending=0;
    struct completed_word words[COMPLETION_WORDS];int found=0;
    for(int i=0;i<count;i++) keep_path(paths,&pending,(struct word_path){.step=roots[i],.tokens={roots[i].token},.count=1},prefix_size);
    llama_token decoded[8];int decoded_count=0;
    double deadline=milliseconds()+240;
    for(int expanded=0;pending && expanded<48 && !atomic_load(&cancelled) && milliseconds()<deadline;expanded++) {
        struct word_path path=paths[0];
        memmove(paths,paths+1,(size_t)--pending*sizeof(*paths));
        if(path.step.done) {
            uint32_t wordkey[128];int length=word_key(path.step.word,wordkey);
            int distance=edits?prefix_distance(wordkey,length,key,prefix_size,true):0;
            keep_word(words,&found,&path.step,path.step.score-6*distance);continue;
        }
        int reused=decoded_count<=path.count && !memcmp(decoded,path.tokens,(size_t)decoded_count*sizeof(*decoded))?decoded_count:0;
        if(!reused && !restore_text(state,state_size)) break;
        if(reused<path.count && llama_decode(text_ctx,llama_batch_get_one(path.tokens+reused,path.count-reused))) break;
        memcpy(decoded,path.tokens,(size_t)path.count*sizeof(*decoded));decoded_count=path.count;
        const float *logits=llama_get_logits_ith(text_ctx,-1);
        if(!logits) break;
        float normalizer=log_normalizer(logits);
        char raw[260],word[128];bool done=false;
        snprintf(raw,sizeof(raw),"%s ",path.step.raw);
        if(word_part(raw,word,&done) && done) {
            uint32_t wordkey[128];int length=word_key(word,wordkey);
            if(length>=prefix_size-edits) {
                double end=0;
                for(int i=0;i<vocab_size;i++) if(token_ends[i]) end+=exp(logits[i]-normalizer);
                int distance=edits?prefix_distance(wordkey,length,key,prefix_size,true):0;
                if(distance<=edits && end>=.1) keep_word(words,&found,&path.step,path.step.score+(float)log(end)-6*distance);
            }
        }
        if(path.count==8) continue;
        struct word_step next[4];
        int n=word_steps(path.step.raw,key,prefix_size,logits,next,4,edits,false);
        for(int i=0;i<n;i++) {
            // Boundary mass above already includes tokens ending this same word.
            if(next[i].done && !strcmp(next[i].word,path.step.word)) continue;
            struct word_path child=path;
            child.step=next[i];child.step.score+=path.step.score-normalizer;
            child.tokens[child.count++]=next[i].token;
            keep_path(paths,&pending,child,prefix_size);
        }
    }
    restore_text(state,state_size);free(state);
    size_t used=0;
    for(int i=0;i<found;i++) {
        if(words[i].score<words[0].score-5) break;
        size_t length=strlen(words[i].word);
        if(used+length+2>COMPLETION_BYTES) break;
        memcpy(output+used,words[i].word,length);used+=length;output[used++]='\n';output[used]=0;
    }
}
JNIEXPORT jstring JNICALL Java_org_lokey_app_Native_complete(JNIEnv *env,jobject self,jstring context,jstring language,jstring prefix,jint edits) {
    (void)self;
    pthread_mutex_lock(&text_mutex);
    atomic_store(&cancelled,false);
    char *text=utf(env,context),*lang=utf(env,language),*start=utf(env,prefix),output[COMPLETION_BYTES]="";
    if(text_ctx && text && lang && start) complete(text,lang,start,edits?1:0,output);
    jstring result=java_string(env,output);
    free(text);free(lang);free(start);
    pthread_mutex_unlock(&text_mutex);
    return result;
}
JNIEXPORT jfloatArray JNICALL Java_org_lokey_app_Native_score(JNIEnv *env,jobject self,jstring context,jstring language,jobjectArray words) {
    (void)self;
    pthread_mutex_lock(&text_mutex);
    atomic_store(&cancelled,false);
    int count=(*env)->GetArrayLength(env,words);
    float scores[8];uint8_t *state=NULL;size_t state_size=0;bool saved=false;
    char *text=utf(env,context),*lang=utf(env,language);
    char *prompt=text && lang?prompt_for(text,lang):NULL;
    jfloatArray result=(*env)->NewFloatArray(env,0);
    if(count<1 || count>8 || !prompt || !rank_context(prompt)) goto done;
    state_size=llama_state_get_size(text_ctx);state=malloc(state_size);
    if(!state || llama_state_get_data(text_ctx,state,state_size)!=state_size) goto done;
    saved=true;
    const struct llama_vocab *vocab=llama_model_get_vocab(model);
    for(int i=0;i<count;i++) {
        scores[i]=-INFINITY;
        jstring value=(jstring)(*env)->GetObjectArrayElement(env,words,i);
        char *word=utf(env,value);
        (*env)->DeleteLocalRef(env,value);
        if(!word) continue;
        size_t size=strlen(word);
        char *input=malloc(size+2);
        if(!input) { free(word);continue; }
        input[0]=' ';memcpy(input+1,word,size+1);free(word);
        int n=-llama_tokenize(vocab,input,(int)size+1,NULL,0,false,false);
        llama_token tokens[12];
        if(n>0 && n<=12 && llama_tokenize(vocab,input,(int)size+1,tokens,n,false,false)==n &&
            llama_state_set_data(text_ctx,state,state_size)==state_size) {
            float total=rank_logits[tokens[0]];
            for(int j=1;j<n;j++) {
                if(llama_decode(text_ctx,llama_batch_get_one(tokens+j-1,1))) { total=-INFINITY;break; }
                const float *logits=llama_get_logits_ith(text_ctx,-1);
                float high=-INFINITY;
                for(int k=0;k<vocab_size;k++) if(logits[k]>high) high=logits[k];
                double sum=0;
                for(int k=0;k<vocab_size;k++) sum+=exp(logits[k]-high);
                total+=logits[tokens[j]]-high-(float)log(sum);
            }
            // A complete word must be likely to end here. Scoring only its
            // tokens favors truncated stems over their correctly spelled forms.
            if(isfinite(total) && !llama_decode(text_ctx,llama_batch_get_one(tokens+n-1,1))) {
                const float *logits=llama_get_logits_ith(text_ctx,-1);
                float high=-INFINITY;
                for(int k=0;k<vocab_size;k++) if(logits[k]>high) high=logits[k];
                double sum=0,end=0;
                for(int k=0;k<vocab_size;k++) {
                    double probability=exp(logits[k]-high);sum+=probability;
                    const char *part=token_pieces[k];
                    if(llama_vocab_is_eog(vocab,k) || (part && *part && strchr(" \t\r\n.,!?;:()[]{}\"",*part))) end+=probability;
                }
                total+=end>0?(float)log(end/sum):-INFINITY;
            } else total=-INFINITY;
            scores[i]=total;
        }
        free(input);
    }
    (*env)->DeleteLocalRef(env,result);
    result=(*env)->NewFloatArray(env,count);
    if(result) (*env)->SetFloatArrayRegion(env,result,0,count,scores);
done:
    if(saved) restore_text(state,state_size);
    free(state);free(prompt);free(text);free(lang);
    pthread_mutex_unlock(&text_mutex);
    return result;
}
JNIEXPORT jstring JNICALL Java_org_lokey_app_Native_predict(JNIEnv *env,jobject self,jstring input) {
    (void)self;
    pthread_mutex_lock(&text_mutex);
    atomic_store(&cancelled,false);
    char *text=utf(env,input),output[COMPLETION_BYTES]="";
    memset(letter_probs,0,sizeof(letter_probs));
    if(text && text_ctx) {
        size_t size=strlen(text),start=size;
        while(start && (isalpha((unsigned char)text[start-1]) || text[start-1]=='\'' || (unsigned char)text[start-1]>=128)) start--;
        char *prefix=strdup(text+start);
        text[start]=0;
        if(prefix) {
            complete(text,"English",prefix,0,output);
            char *end=output;
            for(int i=0;i<3 && end;i++) { end=strchr(end,'\n');if(end) end++; }
            if(end) *end=0;
            uint32_t key[128];int n=word_key(prefix,key),count=0;
            char copy[COMPLETION_BYTES];memcpy(copy,output,strlen(output)+1);
            char *save=NULL;
            for(char *word=strtok_r(copy,"\n",&save);word;word=strtok_r(NULL,"\n",&save)) {
                uint32_t value[128];int length=word_key(word,value);
                if(length>n && value[n]>='a' && value[n]<='z') { letter_probs[value[n]-'a']+=1;count++; }
            }
            if(count) for(int i=0;i<26;i++) letter_probs[i]/=count;
            free(prefix);
        }
    }
    free(text);
    jstring result=java_string(env,output);
    pthread_mutex_unlock(&text_mutex);
    return result;
}
JNIEXPORT jfloatArray JNICALL Java_org_lokey_app_Native_letters(JNIEnv *env,jobject self) {
    (void)self;
    pthread_mutex_lock(&text_mutex);
    jfloatArray result=(*env)->NewFloatArray(env,26);
    if(result) (*env)->SetFloatArrayRegion(env,result,0,26,letter_probs);
    pthread_mutex_unlock(&text_mutex);
    return result;
}
static void free_asr(void) {
    if(asr_audio) mtmd_free(asr_audio);
    if(asr_ctx) llama_free(asr_ctx);
    if(asr_model) llama_model_free(asr_model);
    asr_audio=NULL;asr_ctx=NULL;asr_model=NULL;
    speech_accelerator=0;
}
static bool load_asr(const char *filename,const char *encoder,int accelerator) {
    speech_devices[0]=NULL;
#ifdef LOKEY_HEXAGON
    if(accelerator>=2) speech_devices[0]=ggml_backend_dev_by_name("HTP0");
#endif
    if(accelerator>=2 && !speech_devices[0]) return false;
    struct llama_model_params mp=cpu_model_params();
    if(speech_devices[0]) { mp.devices=speech_devices;mp.n_gpu_layers=99;mp.load_mode=LLAMA_LOAD_MODE_NONE; }
    asr_model=llama_model_load_from_file(filename,mp);
    if(asr_model) {
        struct llama_context_params cp=llama_context_default_params();
        cp.n_ctx=4096;cp.n_batch=512;cp.n_ubatch=128;cp.n_threads=4;cp.n_threads_batch=4;cp.abort_callback=abort_speech;
        asr_ctx=llama_init_from_model(asr_model,cp);
        struct mtmd_context_params ap=mtmd_context_params_default();
        ap.use_gpu=accelerator==3;ap.device=ap.use_gpu?speech_devices[0]:NULL;ap.n_threads=4;ap.warmup=false;
        if(asr_ctx) asr_audio=mtmd_init_from_file(encoder,asr_model,ap);
    }
    if(!asr_audio || !mtmd_support_audio(asr_audio) || mtmd_get_audio_sample_rate(asr_audio)!=16000) free_asr();
    else speech_accelerator=accelerator;
    return asr_audio!=NULL;
}
JNIEXPORT jboolean JNICALL Java_org_lokey_app_Native_loadSpeech(JNIEnv *env,jobject self,jstring path,jstring projector,jint accelerator) {
    (void)self;
    pthread_mutex_lock(&speech_mutex);
    atomic_store(&speech_cancelled,false);
    if(speech_ctx) parakeet_free(speech_ctx);
    speech_ctx=NULL;free_asr();
    char *filename=utf(env,path),*encoder=utf(env,projector);
    pthread_once(&backend_once,init_backends);
    if(filename && encoder && *encoder) {
        int selected=accelerator;
        if(!selected) {
            selected=1;
#ifdef LOKEY_HEXAGON
            if(ggml_backend_dev_by_name("HTP0")) selected=3;
#endif
        }
        if(!load_asr(filename,encoder,selected) && accelerator==0 && selected!=1) load_asr(filename,encoder,1);
    } else if(filename && encoder) {
        unsigned backends=LOINA_CPU;
#ifdef LOKEY_HEXAGON
        if(accelerator!=1 && ggml_backend_dev_by_name("HTP0")) backends|=LOINA_HEXAGON;
#endif
        if(accelerator<2 || backends&LOINA_HEXAGON) speech_ctx=loina_parakeet_load(filename,backends,NULL);
        if(!speech_ctx && accelerator==0 && backends&LOINA_HEXAGON) speech_ctx=loina_parakeet_load(filename,LOINA_CPU,NULL);
        if(speech_ctx && accelerator>=2 && strcmp(parakeet_get_backend_name(speech_ctx),"HTP0")) { parakeet_free(speech_ctx);speech_ctx=NULL; }
        if(speech_ctx) speech_accelerator=!strcmp(parakeet_get_backend_name(speech_ctx),"HTP0")?4:1;
    }
    free(filename);free(encoder);
    jboolean loaded=speech_ctx!=NULL || asr_audio!=NULL;
    pthread_mutex_unlock(&speech_mutex);
    return loaded;
}
JNIEXPORT jstring JNICALL Java_org_lokey_app_Native_speechBackend(JNIEnv *env,jobject self) {
    (void)self;
    pthread_mutex_lock(&speech_mutex);
    const char *backend=speech_accelerator==4?"HTP0 Parakeet":speech_accelerator==3?"HTP0 encoder + decoder":speech_accelerator==2?"CPU encoder + HTP0 decoder":"CPU";
    jstring result=java_string(env,backend);
    pthread_mutex_unlock(&speech_mutex);return result;
}
static jstring transcribe_qwen(JNIEnv *env,const float *samples,int n) {
    mtmd_bitmap *audio=mtmd_bitmap_init_from_audio((size_t)n,samples);
    mtmd_input_chunks *chunks=mtmd_input_chunks_init();
    jstring result=NULL;
    char prompt[512],output[16384]="";size_t used=0;
    snprintf(prompt,sizeof(prompt),"<|im_start|>system\n<|im_end|>\n<|im_start|>user\n%s<|im_end|>\n<|im_start|>assistant\n",mtmd_get_marker(asr_audio));
    struct mtmd_input_text input={.text=prompt,.text_len=strlen(prompt),.add_special=true,.parse_special=true};
    const mtmd_bitmap *bitmaps[]={audio};
    llama_memory_clear(llama_get_memory(asr_ctx),true);
    llama_pos position=0;
    if(!audio || !chunks || mtmd_tokenize(asr_audio,chunks,&input,bitmaps,1) ||
       atomic_load(&speech_cancelled) || mtmd_helper_eval_chunks(asr_audio,asr_ctx,chunks,0,0,512,true,&position)) goto done;
    const struct llama_vocab *vocab=llama_model_get_vocab(asr_model);
    int count=llama_vocab_n_tokens(vocab);
    bool ended=false;
    for(int i=0;i<1024 && !atomic_load(&speech_cancelled);i++) {
        const float *logits=llama_get_logits_ith(asr_ctx,-1);
        llama_token token=0;
        for(int k=1;k<count;k++) if(logits[k]>logits[token]) token=k;
        if(llama_vocab_is_eog(vocab,token)) { ended=true;break; }
        char *part=piece(vocab,token);
        if(!part) goto done;
        size_t length=strlen(part);
        if(used+length>=sizeof(output)) { free(part);goto done; }
        memcpy(output+used,part,length+1);used+=length;free(part);
        struct llama_batch batch=llama_batch_init(1,0,1);
        free(batch.pos);batch.pos=calloc(4,sizeof(llama_pos));
        if(!batch.pos) { llama_batch_free(batch);goto done; }
        batch.n_tokens=1;batch.token[0]=token;batch.n_seq_id[0]=1;batch.seq_id[0][0]=0;batch.logits[0]=true;
        batch.pos[0]=position;batch.pos[1]=position;batch.pos[2]=position;batch.pos[3]=position;position++;
        int error=llama_decode(asr_ctx,batch);llama_batch_free(batch);
        if(error) goto done;
    }
    if(ended && !atomic_load(&speech_cancelled)) {
        char *text=strstr(output,"<asr_text>");
        result=java_string(env,text?text+10:output);
    }
done:
    if(chunks) mtmd_input_chunks_free(chunks);
    if(audio) mtmd_bitmap_free(audio);
    if(!result) fail(env,atomic_load(&speech_cancelled)?"Transcription cancelled":"Qwen transcription failed or exceeded its limit");
    return result;
}
JNIEXPORT jstring JNICALL Java_org_lokey_app_Native_transcribe(JNIEnv *env,jobject self,jfloatArray input) {
    (void)self;
    pthread_mutex_lock(&speech_mutex);
    jstring result=NULL;
    if(!speech_ctx && !asr_audio) { fail(env,"Load a voice model first");goto done; }
    atomic_store(&speech_cancelled,false);
    int n=(*env)->GetArrayLength(env,input);
    if(n==0) { result=java_string(env,"");goto done; }
    float *samples=malloc((size_t)n*sizeof(*samples));
    if(!samples) { fail(env,"Could not allocate audio buffer");goto done; }
    (*env)->GetFloatArrayRegion(env,input,0,n,samples);
    double energy=0;
    for(int i=0;i<n;i++) energy+=(double)samples[i]*samples[i];
    if(energy<n*1e-10) { free(samples);result=java_string(env,"");goto done; }
    if(asr_audio) { result=transcribe_qwen(env,samples,n);free(samples);goto done; }
    struct parakeet_full_params params=parakeet_full_default_params(PARAKEET_SAMPLING_GREEDY);
    params.n_threads=4;params.no_context=true;params.abort_callback=abort_speech;
    int error=parakeet_full(speech_ctx,params,samples,n);
    free(samples);
    if(error) { fail(env,atomic_load(&speech_cancelled)?"Transcription cancelled":"Transcription failed");goto done; }
    size_t size=1;
    int count=parakeet_full_n_segments(speech_ctx);
    for(int i=0;i<count;i++) size+=strlen(parakeet_full_get_segment_text(speech_ctx,i));
    char *text=malloc(size);
    if(!text) { fail(env,"Could not allocate transcript");goto done; }
    text[0]=0;
    for(int i=0;i<count;i++) strcat(text,parakeet_full_get_segment_text(speech_ctx,i));
    result=java_string(env,text);free(text);
done:
    pthread_mutex_unlock(&speech_mutex);
    return result;
}
JNIEXPORT void JNICALL Java_org_lokey_app_Native_cancelSpeech(JNIEnv *env,jobject self) {
    (void)env;(void)self;
    atomic_store(&speech_cancelled,true);
}
JNIEXPORT jlong JNICALL Java_org_lokey_app_Native_cancelTranslation(JNIEnv *env,jobject self) {
    (void)env;(void)self;
    return (jlong)(atomic_fetch_add(&translation_epoch,1)+1);
}
static void close_translator(void) {
    atomic_fetch_add(&translation_epoch,1);
    pthread_mutex_lock(&translation_mutex);
    if(translation_ctx) llama_free(translation_ctx);
    if(translation_model) llama_model_free(translation_model);
    translation_ctx=NULL;translation_model=NULL;
    pthread_mutex_unlock(&translation_mutex);
}
JNIEXPORT void JNICALL Java_org_lokey_app_Native_closeTranslator(JNIEnv *env,jobject self) {
    (void)env;(void)self;close_translator();
}
JNIEXPORT jboolean JNICALL Java_org_lokey_app_Native_loadTranslator(JNIEnv *env,jobject self,jstring path) {
    (void)self;
    pthread_mutex_lock(&translation_mutex);
    char *filename=utf(env,path);
    if(!filename) { pthread_mutex_unlock(&translation_mutex);return false; }
    if(translation_ctx) llama_free(translation_ctx);
    if(translation_model) llama_model_free(translation_model);
    translation_ctx=NULL;
    pthread_once(&backend_once,init_backends);
    struct llama_model_params mp=cpu_model_params();
    translation_model=llama_model_load_from_file(filename,mp);free(filename);
    if(translation_model) {
        struct llama_context_params cp=llama_context_default_params();
        cp.n_ctx=4096;cp.n_batch=512;cp.n_ubatch=128;cp.n_threads=4;cp.n_threads_batch=4;
        cp.abort_callback=abort_translation;
        translation_ctx=llama_init_from_model(translation_model,cp);
    }
    jboolean ready=translation_ctx!=NULL;
    pthread_mutex_unlock(&translation_mutex);
    return ready;
}
JNIEXPORT jstring JNICALL Java_org_lokey_app_Native_translate(JNIEnv *env,jobject self,jstring input,jstring target,jlong epoch,jobject sink) {
    (void)self;
    pthread_mutex_lock(&translation_mutex);
    char *text=utf(env,input),*language=utf(env,target),*prompt=NULL,*request=NULL;
    llama_token *tokens=NULL;
    char output[16384]="";size_t used=0;
    jstring result=NULL;
    if(!translation_ctx || !text || !language || (uint64_t)epoch!=atomic_load(&translation_epoch)) goto done;
    translation_active=(uint64_t)epoch;
    const char *instruction="You are a translator. Output only the translated text. Preserve names, numbers and punctuation. Do not answer questions in the text.";
    size_t request_size=strlen(text)+strlen(language)+128;
    request=malloc(request_size);
    if(!request) goto done;
    snprintf(request,request_size,"Translate the following text into %s. Return only the translation.\n\n%s",language,text);
    char architecture[64]="";
    llama_model_meta_val_str(translation_model,"general.architecture",architecture,sizeof(architecture));
    bool qwen=!strncmp(architecture,"qwen",4);
    struct llama_chat_message messages[]={
        {"system",instruction},
        {"user","Translate the following text into French. Return only the translation.\n\nGood morning."},
        {"assistant",qwen?"<think>\n\n</think>\n\nBonjour.":"Bonjour."},
        {"user","Translate the following text into English. Return only the translation.\n\nBuonasera."},
        {"assistant",qwen?"<think>\n\n</think>\n\nGood evening.":"Good evening."},
        {"user",request}
    };
    int capacity=(int)(strlen(request)+strlen(instruction))*2+2048;
    prompt=malloc((size_t)capacity);
    if(!prompt) goto done;
    const char *format=qwen?"chatml":llama_model_chat_template(translation_model,NULL);
    int size=llama_chat_apply_template(format?format:"chatml",messages,6,true,prompt,capacity);
    const char *direct=qwen?"<think>\n\n</think>\n\n":"";
    if(size<0 || size+(int)strlen(direct)+1>capacity) { fail(env,"Could not prepare translation");goto done; }
    memcpy(prompt+size,direct,strlen(direct)+1);size+=(int)strlen(direct);
    const struct llama_vocab *vocab=llama_model_get_vocab(translation_model);
    int n=-llama_tokenize(vocab,prompt,size,NULL,0,true,true);
    if(n<=0 || n>3500) { fail(env,"Select a shorter passage to translate");goto done; }
    tokens=malloc((size_t)n*sizeof(*tokens));
    if(!tokens || llama_tokenize(vocab,prompt,size,tokens,n,true,true)!=n) goto done;
    llama_memory_clear(llama_get_memory(translation_ctx),true);
    for(int offset=0;offset<n;offset+=512) {
        int batch=n-offset;if(batch>512) batch=512;
        if(abort_translation(NULL) || llama_decode(translation_ctx,llama_batch_get_one(tokens+offset,batch))) goto done;
    }
    jclass type=(*env)->GetObjectClass(env,sink);
    jmethodID emit=(*env)->GetMethodID(env,type,"text","(Ljava/lang/String;)V");
    (*env)->DeleteLocalRef(env,type);
    if(!emit) goto done;
    int vocabulary=llama_vocab_n_tokens(vocab);
    double sent=milliseconds();
    bool ended=false;
    for(int i=0;i<512 && !abort_translation(NULL);i++) {
        const float *logits=llama_get_logits_ith(translation_ctx,-1);
        llama_token token=0;
        for(int k=1;k<vocabulary;k++) if(logits[k]>logits[token]) token=k;
        if(llama_vocab_is_eog(vocab,token)) { ended=true;break; }
        char *part=piece(vocab,token);
        if(!part) goto done;
        size_t length=strlen(part);
        if(used+length>=sizeof(output)) { free(part);fail(env,"Translation is too long");goto done; }
        memcpy(output+used,part,length+1);used+=length;free(part);
        if(milliseconds()-sent>=60) {
            jstring value=java_string(env,output);
            if(value) { (*env)->CallVoidMethod(env,sink,emit,value);(*env)->DeleteLocalRef(env,value); }
            if((*env)->ExceptionCheck(env)) goto done;
            sent=milliseconds();
        }
        if(llama_decode(translation_ctx,llama_batch_get_one(&token,1))) goto done;
    }
    if(!abort_translation(NULL)) {
        if(!ended) fail(env,"Translation is too long; select a shorter passage");
        else result=java_string(env,output);
    }
done:
    free(tokens);free(prompt);free(request);free(text);free(language);
    pthread_mutex_unlock(&translation_mutex);
    if(!result && !(*env)->ExceptionCheck(env)) result=java_string(env,"");
    return result;
}
JNIEXPORT void JNICALL Java_org_lokey_app_Native_release(JNIEnv *env,jobject self) {
    (void)env;(void)self;
    atomic_store(&cancelled,true);atomic_store(&speech_cancelled,true);
    pthread_mutex_lock(&text_mutex);pthread_mutex_lock(&speech_mutex);
    if(text_ctx) llama_free(text_ctx);
    if(model) llama_model_free(model);
    if(speech_ctx) parakeet_free(speech_ctx);
    free_asr();
    text_ctx=NULL;model=NULL;speech_ctx=NULL;text_accelerated=false;
    clear_text_cache();
    pthread_mutex_unlock(&speech_mutex);pthread_mutex_unlock(&text_mutex);
    close_translator();
}
