#define main keyboard_probe_main
#include "hexagon-keyboard-bench.c"
#undef main

struct audio { jsize count;float *samples; };
static jsize audio_length(JNIEnv *env,jarray value) { (void)env;return ((struct audio *)value)->count; }
static void audio_region(JNIEnv *env,jfloatArray value,jsize start,jsize count,jfloat *out) {
    (void)env;memcpy(out,((struct audio *)value)->samples+start,(size_t)count*sizeof(float));
}
static jstring new_string(JNIEnv *env,const jchar *chars,jsize length) {
    (void)env;
    char *text=calloc((size_t)length*4+1,1);size_t at=0;
    for(int i=0;i<length;i++) {
        unsigned c=chars[i];
        if(c>=0xd800 && c<=0xdbff && i+1<length) { c=0x10000+((c-0xd800)<<10)+(chars[++i]-0xdc00); }
        if(c<128) text[at++]=(char)c;
        else if(c<2048) { text[at++]=(char)(0xc0|(c>>6));text[at++]=(char)(0x80|(c&63)); }
        else if(c<65536) { text[at++]=(char)(0xe0|(c>>12));text[at++]=(char)(0x80|((c>>6)&63));text[at++]=(char)(0x80|(c&63)); }
        else { text[at++]=(char)(0xf0|(c>>18));text[at++]=(char)(0x80|((c>>12)&63));text[at++]=(char)(0x80|((c>>6)&63));text[at++]=(char)(0x80|(c&63)); }
    }
    return (jstring)text;
}
static jclass find_class(JNIEnv *env,const char *name) { (void)env;return (jclass)name; }
static jint throw_new(JNIEnv *env,jclass type,const char *message) {
    (void)env;(void)type;fprintf(stderr,"%s\n",message);return 0;
}
static void delete_ref(JNIEnv *env,jobject value) { (void)env;(void)value; }
static bool capture_encoder(struct ggml_tensor *tensor,bool ask,void *data) {
    if(strcmp(tensor->name,"projected") || tensor->type!=GGML_TYPE_F32) return false;
    if(ask) return true;
    FILE *file=data;uint32_t count=(uint32_t)ggml_nelements(tensor);
    float *values=malloc(ggml_nbytes(tensor));
    ggml_backend_tensor_get(tensor,values,0,ggml_nbytes(tensor));
    fwrite(&count,sizeof(count),1,file);fwrite(values,sizeof(*values),count,file);fflush(file);free(values);
    return true;
}
static uint32_t le32(const unsigned char *data) { return data[0]|data[1]<<8|data[2]<<16|(uint32_t)data[3]<<24; }
static struct audio read_audio(const char *path) {
    struct audio audio={0};FILE *file=fopen(path,"rb");
    if(!file) return audio;
    unsigned char header[12];
    if(fread(header,1,12,file)!=12 || memcmp(header,"RIFF",4) || memcmp(header+8,"WAVE",4)) goto done;
    bool valid=false;
    while(fread(header,1,8,file)==8) {
        uint32_t size=le32(header+4);
        if(!memcmp(header,"fmt ",4)) {
            unsigned char format[16];if(size<16 || fread(format,1,16,file)!=16) break;
            valid=format[0]==1 && format[2]==1 && le32(format+4)==16000 && format[14]==16;
            fseek(file,size-16+(size&1),SEEK_CUR);
        } else if(!memcmp(header,"data",4)) {
            if(!valid || size>10000000) break;
            audio.count=(jsize)(size/2);audio.samples=malloc((size_t)audio.count*sizeof(float));
            for(int i=0;i<audio.count;i++) { unsigned char sample[2];if(fread(sample,1,2,file)!=2) { free(audio.samples);audio=(struct audio){0};break; } audio.samples[i]=(int16_t)(sample[0]|sample[1]<<8)/32768.f; }
            break;
        } else fseek(file,size+(size&1),SEEK_CUR);
    }
done:
    fclose(file);return audio;
}
int main(int argc,char **argv) {
    if(argc<5) { fprintf(stderr,"Usage: %s model encoder-or-empty auto|cpu|decoder|hexagon wav...\n",argv[0]);return 2; }
    int accelerator=!strcmp(argv[3],"auto")?0:!strcmp(argv[3],"cpu")?1:!strcmp(argv[3],"decoder")?2:3;
    const struct JNINativeInterface table={.GetStringChars=path_chars,.GetStringLength=path_length,.ReleaseStringChars=path_release,
        .NewString=new_string,.FindClass=find_class,.ThrowNew=throw_new,.DeleteLocalRef=delete_ref,.GetArrayLength=audio_length,.GetFloatArrayRegion=audio_region};
    JNIEnv env=&table;double start=now_ms();
    if(!Java_org_lokey_app_Native_loadSpeech(&env,NULL,(jstring)argv[1],(jstring)argv[2],accelerator)) return 1;
    const char *dump=getenv("LOKEY_ENCODER_DUMP");FILE *capture=dump?fopen(dump,"wb"):NULL;
    if(capture && asr_audio) {
        mtmd_free(asr_audio);
        struct mtmd_context_params params=mtmd_context_params_default();
        params.use_gpu=accelerator==3;params.device=params.use_gpu?speech_devices[0]:NULL;params.n_threads=4;params.warmup=false;
        params.cb_eval=capture_encoder;params.cb_eval_user_data=capture;
        asr_audio=mtmd_init_from_file(argv[2],asr_model,params);if(!asr_audio) return 1;
    }
    printf("{\"load_ms\":%.3f,\"backend\":",now_ms()-start);
    char *backend=(char *)Java_org_lokey_app_Native_speechBackend(&env,NULL);json_string(backend);free(backend);
    printf(",\"trials\":[");fflush(stdout);
    int trial=0;
    for(int pass=0;pass<2;pass++) for(int i=4;i<argc;i++) {
        struct audio audio=read_audio(argv[i]);if(!audio.samples) return 2;
        start=now_ms();char *text=(char *)Java_org_lokey_app_Native_transcribe(&env,NULL,(jfloatArray)&audio);
        if(!text) return 1;
        if(trial++) putchar(',');printf("{\"pass\":%d,\"ms\":%.3f,\"fixture\":",pass,now_ms()-start);json_string(argv[i]);
        printf(",\"text\":");json_string(text);putchar('}');fflush(stdout);
        free(text);free(audio.samples);
    }
    puts("]}");free_asr();if(speech_ctx) parakeet_free(speech_ctx);if(capture) fclose(capture);
    return 0;
}
