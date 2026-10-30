// Synthetic, camera-free encoder cost probe. This does not measure call FPS or visual quality.
#include <wels/codec_api.h>
#include <algorithm>
#include <chrono>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <vector>

void benchmark(const char* name, int width, int height, int fps, int average, int maximum) {
    ISVCEncoder* encoder = nullptr;
    if (WelsCreateSVCEncoder(&encoder) != 0) throw std::runtime_error("create encoder failed");
    try {
        SEncParamExt params{}; encoder->GetDefaultParams(&params);
        params.iUsageType = CAMERA_VIDEO_REAL_TIME;
        params.iComplexityMode = MEDIUM_COMPLEXITY;
        params.iPicWidth = width; params.iPicHeight = height; params.fMaxFrameRate = float(fps);
        params.iTargetBitrate = average; params.iMaxBitrate = maximum; params.iRCMode = RC_BITRATE_MODE;
        params.iTemporalLayerNum = 1; params.iSpatialLayerNum = 1;
        params.iMultipleThreadIdc = 1; params.uiIntraPeriod = 0; params.bEnableFrameSkip = true;
        params.bEnableDenoise = false; params.bEnableSceneChangeDetect = true;
        params.bEnableBackgroundDetection = true; params.bEnableAdaptiveQuant = true;
        params.uiMaxNalSize = 1200;
        auto& layer = params.sSpatialLayers[0];
        layer.iVideoWidth = width; layer.iVideoHeight = height; layer.fFrameRate = float(fps);
        layer.iSpatialBitrate = average; layer.iMaxSpatialBitrate = maximum;
        layer.uiProfileIdc = PRO_BASELINE; layer.uiLevelIdc = LEVEL_3_0;
        layer.sSliceArgument.uiSliceMode = SM_SIZELIMITED_SLICE;
        layer.sSliceArgument.uiSliceSizeConstraint = 1150; layer.sSliceArgument.uiSliceNum = 1;
        if (encoder->InitializeExt(&params) != 0) throw std::runtime_error("initialize encoder failed");
        int trace = WELS_LOG_ERROR; encoder->SetOption(ENCODER_OPTION_TRACE_LEVEL, &trace);
        int format = videoFormatI420; encoder->SetOption(ENCODER_OPTION_DATAFORMAT, &format);
        std::vector<unsigned char> pixels(width * height * 3 / 2, 128);
        SSourcePicture picture{}; picture.iColorFormat = videoFormatI420;
        picture.iPicWidth = width; picture.iPicHeight = height;
        picture.iStride[0] = width; picture.iStride[1] = picture.iStride[2] = width / 2;
        picture.pData[0] = pixels.data(); picture.pData[1] = pixels.data() + width * height;
        picture.pData[2] = pixels.data() + width * height * 5 / 4;
        std::vector<double> timings; long long bytes = 0; int encoded = 0, skipped = 0;
        constexpr int warmup = 30, samples = 180;
        for (int frame = 0; frame < warmup + samples; frame++) {
            // Moving luma texture with constant chroma; generation is outside the timed section.
            for (int y=0; y<height; y++) for (int x=0; x<width; x++)
                pixels[y*width+x] = static_cast<unsigned char>(16 + (((x+frame*3) ^ (y+frame)) % 220));
            picture.uiTimeStamp = static_cast<long long>(frame) * 1000 / fps;
            SFrameBSInfo result{}; auto start = std::chrono::steady_clock::now();
            if (encoder->EncodeFrame(&picture, &result) != 0) throw std::runtime_error("encode failed");
            double elapsed = std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
            if (frame >= warmup) {
                timings.push_back(elapsed); bytes += result.iFrameSizeInBytes;
                if (result.eFrameType == videoFrameTypeSkip) skipped++; else encoded++;
            }
        }
        double total = 0; for (double timing : timings) total += timing;
        std::sort(timings.begin(), timings.end());
        std::cout << name << ',' << width << ',' << height << ',' << fps << ",1," << encoded << ',' << skipped
                  << ',' << std::fixed << std::setprecision(3) << total/samples << ',' << timings[170]
                  << ',' << bytes*8.0*fps/samples/1000 << '\n';
        encoder->Uninitialize();
    } catch (...) { WelsDestroySVCEncoder(encoder); throw; }
    WelsDestroySVCEncoder(encoder);
}

int main() {
    try {
        std::cout << "profile,width,height,target_fps,threads,encoded,skipped,mean_encode_ms,p95_encode_ms,output_kbps\n";
        benchmark("legacy",640,360,15,500000,800000);
        benchmark("balanced",848,480,25,1200000,1800000);
        benchmark("motion",640,360,30,900000,1400000);
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
