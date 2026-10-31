// Synthetic H.264 -> RTP loop transport -> PJMEDIA decoder. No SIP, camera, microphone or network.
#include <pjlib.h>
#include <pjlib-util.h>
#include <pjmedia.h>
#include <pjmedia/vid_stream.h>
#include <pjmedia/transport_loop.h>
#include <pjmedia-codec/openh264.h>
#include <wels/codec_api.h>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <vector>

static void check(pj_status_t status, const char* operation) {
    if (status != PJ_SUCCESS) { char error[PJ_ERR_MSG_SIZE]; pj_strerror(status,error,sizeof(error)); throw std::runtime_error(std::string(operation)+": "+error); }
}

static int run(int argc, char** argv) {
    if (argc != 5) return 2;
    const char* mode=argv[1]; int renderEvery=std::atoi(argv[2]); bool reorder=std::atoi(argv[3])!=0;
    int fps=std::atoi(argv[4]);
    if (renderEvery<1 || (fps!=15 && fps!=25)) return 2;
#ifdef _WIN32
    _putenv_s("SIP_VIDEO_LATENCY_MODE",mode);
#else
    setenv("SIP_VIDEO_LATENCY_MODE",mode,1);
#endif
    try {
        check(pj_init(),"pj_init"); check(pjlib_util_init(),"pjlib_util_init"); pj_log_set_level(std::getenv("SIP_PROBE_TRACE")?5:1);
        pj_caching_pool cache; pj_caching_pool_init(&cache,nullptr,0);
        pj_pool_t* pool=pj_pool_create(&cache.factory,"latency-probe",8192,8192,nullptr);
        pjmedia_event_mgr* events=nullptr; check(pjmedia_event_mgr_create(pool,0,&events),"event manager");
        pjmedia_endpt* endpoint=nullptr; check(pjmedia_endpt_create2(&cache.factory,nullptr,0,&endpoint),"endpoint");
        pjmedia_video_format_mgr* formats=nullptr; check(pjmedia_video_format_mgr_create(pool,64,0,&formats),"formats");
        pjmedia_vid_codec_mgr* codecs=nullptr; check(pjmedia_vid_codec_mgr_create(pool,&codecs),"codecs");
        check(pjmedia_codec_openh264_vid_init(codecs,&cache.factory),"openh264");
        unsigned count=1; const pjmedia_vid_codec_info* info=nullptr; pj_str_t id=pj_str(const_cast<char*>("H264"));
        check(pjmedia_vid_codec_mgr_find_codecs_by_id(codecs,&id,&count,&info,nullptr),"find h264");
        pjmedia_vid_codec_param params; check(pjmedia_vid_codec_mgr_get_default_param(codecs,info,&params),"codec params");
        constexpr int width=320,height=180;
        pjmedia_format_init_video(&params.enc_fmt,PJMEDIA_FORMAT_H264,width,height,fps,1);
        pjmedia_format_init_video(&params.dec_fmt,PJMEDIA_FORMAT_I420,width,height,fps,1);
        params.enc_fmt.det.vid.avg_bps=500000; params.enc_fmt.det.vid.max_bps=1000000;
        pjmedia_vid_stream_info settings{}; settings.type=PJMEDIA_TYPE_VIDEO; settings.proto=PJMEDIA_TP_PROTO_RTP_AVP;
        settings.dir=PJMEDIA_DIR_DECODING; settings.codec_info=*info; settings.codec_param=&params;
        settings.tx_pt=settings.rx_pt=97; settings.ssrc=0x87654321;
        settings.jb_init=0; settings.jb_min_pre=0; settings.jb_max_pre=80; settings.jb_max=200;
        settings.rtcp_sdes_bye_disabled=PJ_TRUE;
        pj_str_t localhost=pj_str(const_cast<char*>("127.0.0.1"));
        check(pj_sockaddr_init(pj_AF_INET(),&settings.rem_addr,&localhost,40000),"address");
        check(pj_sockaddr_init(pj_AF_INET(),&settings.rem_rtcp,&localhost,40001),"rtcp address");
        pjmedia_transport* loop=nullptr; check(pjmedia_transport_loop_create(endpoint,&loop),"loop transport");
        pjmedia_vid_stream* stream=nullptr; check(pjmedia_vid_stream_create(endpoint,pool,&settings,loop,nullptr,&stream),"video stream");
        check(pjmedia_vid_stream_start(stream),"video start");
        pjmedia_port* renderer=nullptr; check(pjmedia_vid_stream_get_port(stream,PJMEDIA_DIR_DECODING,&renderer),"renderer");
        ISVCEncoder* encoder=nullptr; if(WelsCreateSVCEncoder(&encoder)!=0) throw std::runtime_error("encoder");
        SEncParamExt encoding{}; encoder->GetDefaultParams(&encoding);
        encoding.iUsageType=CAMERA_VIDEO_REAL_TIME; encoding.iPicWidth=width; encoding.iPicHeight=height;
        encoding.fMaxFrameRate=fps; encoding.iTargetBitrate=500000; encoding.iRCMode=RC_OFF_MODE;
        encoding.iTemporalLayerNum=1; encoding.iSpatialLayerNum=1; encoding.iMultipleThreadIdc=1;
        encoding.bEnableFrameSkip=false; encoding.uiIntraPeriod=0; encoding.uiMaxNalSize=0;
        auto& spatial=encoding.sSpatialLayers[0]; spatial.iVideoWidth=width; spatial.iVideoHeight=height;
        spatial.fFrameRate=fps; spatial.iSpatialBitrate=500000; spatial.iMaxSpatialBitrate=1000000;
        spatial.uiProfileIdc=PRO_BASELINE; spatial.uiLevelIdc=LEVEL_3_0;
        spatial.sSliceArgument.uiSliceMode=SM_FIXEDSLCNUM_SLICE; spatial.sSliceArgument.uiSliceNum=4;
        if(encoder->InitializeExt(&encoding)!=0) throw std::runtime_error("encoder initialize");
        int trace=WELS_LOG_ERROR; encoder->SetOption(ENCODER_OPTION_TRACE_LEVEL,&trace);
        std::vector<unsigned char> pixels(width*height*3/2,128), decoded(width*height*4);
        SSourcePicture picture{}; picture.iColorFormat=videoFormatI420; picture.iPicWidth=width; picture.iPicHeight=height;
        picture.iStride[0]=width; picture.iStride[1]=picture.iStride[2]=width/2;
        picture.pData[0]=pixels.data(); picture.pData[1]=pixels.data()+width*height; picture.pData[2]=pixels.data()+width*height*5/4;
        int sequence=0,outputs=0,nonmonotonic=0,reorderedFrames=0,duplicates=0; long long previous=-1; double totalLag=0,maxLag=0;
        for(int frame=0;frame<1000;frame++) {
            for(int y=0;y<height;y++) for(int x=0;x<width;x++) pixels[y*width+x]=static_cast<unsigned char>(16+((x+frame*2+y)%220));
            picture.uiTimeStamp=static_cast<long long>(frame)*1000/fps; SFrameBSInfo encoded{};
            if(encoder->EncodeFrame(&picture,&encoded)!=0) throw std::runtime_error("encode frame");
            std::vector<std::vector<unsigned char>> packets;
            for(int layer=0;layer<encoded.iLayerNum;layer++) {
                auto& nalLayer=encoded.sLayerInfo[layer]; int offset=0;
                for(int nal=0;nal<nalLayer.iNalCount;nal++) {
                    unsigned char* data=nalLayer.pBsBuf+offset; int length=nalLayer.pNalLengthInByte[nal]; offset+=length;
                    int prefix=data[2]==1?3:4; length-=prefix;
                    if(length>1200) throw std::runtime_error("NAL exceeds single-packet probe limit");
                    std::vector<unsigned char> packet(12+length); packet[0]=0x80; packet[1]=97;
                    packet[2]=static_cast<unsigned char>(sequence>>8); packet[3]=static_cast<unsigned char>(sequence++);
                    unsigned ts=90000+frame*(90000/fps),ssrc=0x12345678;
                    for(int byte=0;byte<4;byte++) {packet[4+byte]=static_cast<unsigned char>(ts>>(24-byte*8));packet[8+byte]=static_cast<unsigned char>(ssrc>>(24-byte*8));}
                    std::memcpy(packet.data()+12,data+prefix,length); packets.push_back(std::move(packet));
                }
            }
            if(!packets.empty()) packets.back()[1]|=0x80;
            if(reorder && frame>10 && packets.size()>1) {std::swap(packets[0],packets[1]);reorderedFrames++;}
            for(auto& packet:packets) check(pjmedia_transport_send_rtp(loop,packet.data(),packet.size()),"inject RTP");
            if(frame%renderEvery==0) {
                pjmedia_frame output{}; output.buf=decoded.data(); output.size=decoded.size();
                check(pjmedia_port_get_frame(renderer,&output),"get video frame");
                if(output.type==PJMEDIA_FRAME_TYPE_VIDEO && output.size>0 && frame>10) {
                    long long timestamp=output.timestamp.u64;
                    double lag=(90000.0+frame*(90000/fps)-timestamp)/90.0;
                    if(previous>timestamp) nonmonotonic++;
                    if(previous==timestamp) duplicates++;
                    previous=timestamp; outputs++;totalLag+=lag;maxLag=std::max(maxLag,lag);
                }
            }
        }
        if(outputs==0 || nonmonotonic || maxLag<0) {
            std::cerr<<"invalid output: mode="<<mode<<" fps="<<fps<<" render="<<renderEvery
                     <<" outputs="<<outputs<<" timestamp_backwards="<<nonmonotonic<<" duplicates="<<duplicates<<" max_lag_ms="<<maxLag<<'\n';
            throw std::runtime_error("no valid monotonic decoded output");
        }
        if(reorder && reorderedFrames<500) throw std::runtime_error("insufficient multi-packet reordering coverage");
        std::cout<<mode<<','<<fps<<','<<renderEvery<<','<<reorder<<','<<outputs<<','<<std::fixed<<std::setprecision(3)<<totalLag/outputs<<','<<maxLag<<','<<nonmonotonic<<','<<reorderedFrames<<','<<duplicates<<'\n';
        encoder->Uninitialize(); WelsDestroySVCEncoder(encoder);
        pj_status_t destroyed=pjmedia_vid_stream_destroy(stream);
        if(destroyed!=PJ_SUCCESS && destroyed!=PJ_EGONE) check(destroyed,"destroy stream");
        check(pjmedia_transport_close(loop),"close transport");
        pjmedia_codec_openh264_vid_deinit(); pjmedia_vid_codec_mgr_destroy(codecs); pjmedia_video_format_mgr_destroy(formats);
        pjmedia_endpt_destroy(endpoint); pjmedia_event_mgr_destroy(events); pj_pool_release(pool); pj_caching_pool_destroy(&cache); pj_shutdown(); return 0;
    } catch(const std::exception& error) {std::cerr<<error.what()<<'\n';return 1;}
}

#ifdef main
#undef main
#endif
int main(int argc,char**argv){return run(argc,argv);}
extern "C" int SDL_main(int argc,char**argv){return run(argc,argv);}
