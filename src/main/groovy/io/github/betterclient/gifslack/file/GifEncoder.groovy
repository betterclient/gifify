package io.github.betterclient.gifslack.file

import com.madgag.gif.fmsware.AnimatedGifEncoder

class GifEncoder {
    static Map<String, AnimatedGifEncoder> encoderMap = new HashMap<>();
    static Map<String, ByteArrayOutputStream> outputMap = new HashMap<>();

    static AnimatedGifEncoder getEncoderFor(String key) {
        return encoderMap.computeIfAbsent(key, k -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream()
            outputMap.put(k, out)

            AnimatedGifEncoder encoder = new AnimatedGifEncoder()
            encoder.start(out)
            encoder.setRepeat(0)
            return encoder
        })
    }

    static ByteArrayOutputStream getOutputStreamFor(String key) {
        return outputMap.get(key)
    }

    static void reset() {
        encoderMap.clear()
        outputMap.clear()
    }
}
