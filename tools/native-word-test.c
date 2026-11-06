#include "../app/src/main/c/lokey.c"
#include <assert.h>

int main(void) {
    char word[128];bool done;
    assert(word_part(" Привет,",word,&done) && done && !strcmp(word,"Привет"));
    assert(word_part(" مرحبًا.",word,&done) && done && !strcmp(word,"مرحبًا"));
    assert(word_part(" 你好。",word,&done) && done && !strcmp(word,"你好"));
    const char *scripts[]={"γεια","नमस्ते","안녕하세요","こんにちは","tiếng","สวัสดี","می‌روم"};
    for(size_t i=0;i<sizeof(scripts)/sizeof(*scripts);i++) {
        char raw[160];snprintf(raw,sizeof(raw)," %s!",scripts[i]);
        assert(word_part(raw,word,&done) && done && !strcmp(word,scripts[i]));
    }
    assert(!word_part(" 'm",word,&done));
    assert(!word_part(" ’s",word,&done));
    assert(word_part(" I'm ready",word,&done) && done && !strcmp(word,"I'm"));
    assert(word_part(" I'",word,&done) && !done);
    assert(word_part(" c’è un",word,&done) && done && !strcmp(word,"c’è"));
    assert(word_part(" café,",word,&done) && done && !strcmp(word,"café"));
    assert(word_part(" azzurra",word,&done) && !done && !strcmp(word,"azzurra"));
    assert(!word_part(" 😀",word,&done));
    assert(!word_part(" I' ",word,&done));
    assert(!word_part(" I’ ",word,&done));
    uint32_t prefix[128],capital[128];
    int size=word_key("ПРИВ",prefix);
    assert(word_key("прив",capital)==size && !memcmp(prefix,capital,(size_t)size*sizeof(*prefix)));
    size=word_key("ΓΕΙΆ",prefix);
    assert(word_key("γειά",capital)==size && !memcmp(prefix,capital,(size_t)size*sizeof(*prefix)));
    size=word_key("TIẾNG",prefix);
    assert(word_key("tiếng",capital)==size && !memcmp(prefix,capital,(size_t)size*sizeof(*prefix)));
    size=word_key("im",prefix);
    assert(word_key("I'm",capital)==size && !memcmp(prefix,capital,(size_t)size*sizeof(*prefix)));
    assert(word_key("AZZU",prefix)==word_key("azzu",capital) && !memcmp(prefix,capital,5*sizeof(*prefix)));
    char *pieces[]={" 'm"," I'm"," azzurra"," azzurro"," café","?"," happy"};
    float logits[]={20,19,18,17,16,15,14};
    token_pieces=pieces;vocab_size=sizeof(pieces)/sizeof(*pieces);
    struct word_step steps[6];
    size=word_key("Im",prefix);
    int n=word_steps("",prefix,size,logits,steps,6,0,false);
    assert(n==1 && !strcmp(steps[0].word,"I'm"));
    assert(word_steps(" I'm",prefix,size,logits,steps,1,0,false)==0);
    size=word_key("azzu",prefix);
    n=word_steps("",prefix,size,logits,steps,6,0,false);
    assert(n==2 && !strcmp(steps[0].word,"azzurra") && !strcmp(steps[1].word,"azzurro"));
    size=word_key("domnai",prefix);int length=word_key("domani",capital);
    assert(prefix_distance(capital,length,prefix,size,true)==1);
    size=word_key("triphosphatr",prefix);length=word_key("triphosphat",capital);
    assert(prefix_distance(capital,length,prefix,size,true)==1);
    assert(prefix_distance(capital,length,prefix,size,false)==0);
    struct completed_word ranked[COMPLETION_WORDS];int found=0;
    struct word_step short_word={.word="tom",.score=-1};
    struct word_step full_word={.word="tomorrow",.score=-2};
    keep_word(ranked,&found,&short_word,-1+logf(.01f));
    keep_word(ranked,&found,&full_word,-2+logf(.9f));
    assert(found==2 && !strcmp(ranked[0].word,"tomorrow"));
    full_word=(struct word_step){.word="Tomorrow",.score=-3};
    keep_word(ranked,&found,&full_word,-3);
    assert(found==2 && !strcmp(ranked[0].word,"tomorrow"));
    full_word=(struct word_step){.word="Tomorrow",.score=-1};
    keep_word(ranked,&found,&full_word,-1);
    assert(found==2 && !strcmp(ranked[0].word,"Tomorrow") && ranked[0].score==-1);
    char *boundary_pieces[]={"tomorrow"," tomorrow"," "," to","morrow"};
    float boundary_logits[]={100,9,8,7,6};
    token_pieces=boundary_pieces;vocab_size=5;
    size=word_key("tom",prefix);
    n=word_steps("",prefix,size,boundary_logits,steps,6,0,true);
    assert(n==3 && !strcmp(steps[0].raw," tomorrow") && !strcmp(steps[1].raw," "));
    n=word_steps(" to",prefix,size,boundary_logits,steps,6,0,false);
    assert(n==1 && !strcmp(steps[0].word,"tomorrow"));
    char *byte_pieces[]={" \xc3","\xa9","lan"};float byte_logits[]={9,8,7};
    token_pieces=byte_pieces;vocab_size=3;
    size=word_key("él",prefix);
    assert(word_steps("",prefix,size,byte_logits,steps,6,0,true)==1 && !*steps[0].word);
    assert(word_steps(" \xc3",prefix,size,byte_logits,steps,6,0,false)==1 && !strcmp(steps[0].word,"é"));
    assert(word_steps(" é",prefix,size,byte_logits,steps,6,0,false)==1 && !strcmp(steps[0].word,"élan"));
    assert(!word_pending(" \xe0\x80") && !word_pending(" \xed\xa0") && !word_pending(" \xf4\x90"));
    for(unsigned a=1;a<256;a++) for(unsigned b=1;b<256;b++) {
        char raw[]={' ',(char)a,(char)b,0};
        word_part(raw,word,&done);word_key(raw,prefix);
    }
    puts("Native word boundary and UTF-8 checks passed");
    return 0;
}
