#include "../app/src/main/c/lokey.c"

/* Use the app's model setup and completion code without needing an Android activity. */
static const jchar *path_chars(JNIEnv *env,jstring value,jboolean *copy) {
    (void)env;(void)copy;
    const char *path=(const char *)value;size_t length=strlen(path);
    jchar *chars=malloc(length*sizeof(*chars));
    if(chars) for(size_t i=0;i<length;i++) chars[i]=(unsigned char)path[i];
    return chars;
}
static jsize path_length(JNIEnv *env,jstring value) { (void)env;return (jsize)strlen((const char *)value); }
static void path_release(JNIEnv *env,jstring value,const jchar *chars) { (void)env;(void)value;free((void *)chars); }
static void json_string(const char *value) {
    putchar('"');
    for(const unsigned char *p=(const unsigned char *)value;*p;p++) {
        if(*p=='"' || *p=='\\') { putchar('\\');putchar(*p); }
        else if(*p<32) printf("\\u%04x",*p);
        else putchar(*p);
    }
    putchar('"');
}
static double now_ms(void) {
    struct timespec ts;clock_gettime(CLOCK_MONOTONIC,&ts);
    return ts.tv_sec*1000.0+ts.tv_nsec/1000000.0;
}
int main(int argc,char **argv) {
    if(argc!=3 || (strcmp(argv[2],"cpu") && strcmp(argv[2],"htp") && strcmp(argv[2],"auto"))) {
        fprintf(stderr,"Usage: %s model.gguf cpu|htp|auto\n",argv[0]);return 2;
    }
    int accelerator=!strcmp(argv[2],"htp")?2:!strcmp(argv[2],"cpu")?1:0;
    const struct JNINativeInterface table={.GetStringChars=path_chars,.GetStringLength=path_length,.ReleaseStringChars=path_release};
    JNIEnv env=&table;
    double start=now_ms();
    if(!Java_org_lokey_app_Native_loadText(&env,NULL,(jstring)argv[1],accelerator)) {
        fprintf(stderr,"Cannot load model on %s\n",argv[2]);return 1;
    }
    printf("{\"device\":");json_string(text_accelerated?"HTP0":"CPU");printf(",\"load_ms\":%.3f,\"trials\":[",now_ms()-start);
    const char *prompts[]={"I am on my way ","Can you send me the ","Thank you for your ","See you ","Good mor","I would like to ","Let me know when you ","Ciao come stai ","C'é un amico mio che ha una casa in costa azzu"};
    int count=0;
    for(int pass=0;pass<3;pass++) for(size_t i=0;i<sizeof(prompts)/sizeof(*prompts);i++) {
        char *text=strdup(prompts[i]),output[COMPLETION_BYTES]="";
        size_t size=strlen(text),at=size;
        while(at && (isalpha((unsigned char)text[at-1]) || text[at-1]=='\'' || (unsigned char)text[at-1]>=128)) at--;
        char *prefix=strdup(text+at);text[at]=0;
        start=now_ms();complete(text,i>=7?"Italian":"English",prefix,0,output);
        double elapsed=now_ms()-start;
        if(count++) putchar(',');
        printf("{\"pass\":%d,\"prompt\":",pass);json_string(prompts[i]);
        printf(",\"ms\":%.3f,\"suggestions\":",elapsed);json_string(output);putchar('}');fflush(stdout);
        free(prefix);free(text);
    }
    puts("]}");
    clear_text_cache();llama_free(text_ctx);llama_model_free(model);
    return 0;
}
