#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-alloc.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(void) {
    ggml_backend_load_all();
    ggml_backend_dev_t device=ggml_backend_dev_by_name("HTP0");
    ggml_backend_t backend=device?ggml_backend_dev_init(device,NULL):NULL;
    if(!backend) return 1;
    for(int half=0;half<2;half++) for(int shifted=0;shifted<2;shifted++) {
        const enum ggml_type type=half?GGML_TYPE_F16:GGML_TYPE_F32;
        const size_t element=ggml_type_size(type),offset=shifted?128:0;
        struct ggml_init_params init={.mem_size=ggml_tensor_overhead()*16+ggml_graph_overhead(),.no_alloc=true};
        struct ggml_context *weights=ggml_init(init),*compute=ggml_init(init);
        struct ggml_tensor *source=ggml_new_tensor_2d(weights,type,896,1500);
        struct ggml_tensor *view=ggml_view_2d(compute,source,896,13,896*element,offset*element);
        struct ggml_tensor *shape=ggml_new_tensor_2d(compute,type,896,65);
        struct ggml_tensor *output=ggml_repeat(compute,view,shape);
        ggml_backend_buffer_t input_buffer=ggml_backend_alloc_ctx_tensors(weights,backend);
        ggml_backend_buffer_t output_buffer=ggml_backend_alloc_ctx_tensors(compute,backend);
        if(!input_buffer || !output_buffer) return 1;
        ggml_backend_buffer_set_usage(input_buffer,GGML_BACKEND_BUFFER_USAGE_WEIGHTS);
        unsigned char *input=calloc(896*1500,element),*actual=calloc(896*65,element);
        for(int i=0;i<896*1500;i++) {
            float value=(i%251-125)/128.f;
            if(half) ((ggml_fp16_t *)input)[i]=ggml_fp32_to_fp16(value);
            else ((float *)input)[i]=value;
        }
        ggml_backend_tensor_set(source,input,0,ggml_nbytes(source));
        struct ggml_cgraph *graph=ggml_new_graph(compute);ggml_build_forward_expand(graph,output);
        if(ggml_backend_graph_compute(backend,graph)!=GGML_STATUS_SUCCESS) return 1;
        ggml_backend_tensor_get(output,actual,0,ggml_nbytes(output));
        for(int row=0;row<65;row++) if(memcmp(actual+row*896*element,input+(offset+(row%13)*896)*element,896*element)) {
            fprintf(stderr,"REPEAT mismatch: f%d shifted=%d row=%d\n",half?16:32,shifted,row);return 1;
        }
        printf("REPEAT f%d, extended source, offset %zu: exact match\n",half?16:32,offset);
        free(input);free(actual);ggml_backend_buffer_free(input_buffer);ggml_backend_buffer_free(output_buffer);ggml_free(weights);ggml_free(compute);
    }
    ggml_backend_free(backend);return 0;
}
