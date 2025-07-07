package io.github.betterclient.gifslack

import groovy.transform.CompileStatic
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter

import javax.imageio.ImageIO
import java.awt.image.BufferedImage

interface Decoder {
    int getFrameCount()
    BufferedImage getFrame(int frameIndex)
    int getDelay(int frameIndex)
}

class Decoders {
    static ArrayList<String> supportedFileTypes = ["gif", "png", "jpg", "jpeg", "mp4", "webm"]

    static Decoder getDecoderForFile(File f) {
        def abs = f.absolutePath
        if(abs.endsWith(".gif")) {
            return new GifDecoder(f)
        } else if (abs.endsWith(".png") || abs.endsWith(".jpg") || abs.endsWith(".jpeg")) {
            return new ImageDecoder(f)
        } else {
            return new VideoDecoder(f)
        }
    }
}

class ImageDecoder implements Decoder {
    public BufferedImage src

    ImageDecoder(File f) {
        try {
            this.src = ImageIO.read(f)
        } catch (Exception e) {
            throw new RuntimeException(e)
        }
    }

    @Override
    int getFrameCount() {
        return 1
    }

    @Override
    BufferedImage getFrame(int frameIndex) {
        return src
    }

    @Override
    int getDelay(int frameIndex) {
        return 1000
    }
}

class GifDecoder implements Decoder {
    public com.madgag.gif.fmsware.GifDecoder internalDecoder

    GifDecoder(File f) {
        internalDecoder = new com.madgag.gif.fmsware.GifDecoder()
        internalDecoder.read(f.absolutePath)
    }

    @Override
    int getFrameCount() {
        return internalDecoder.getFrameCount()
    }

    @Override
    BufferedImage getFrame(int frameIndex) {
        return internalDecoder.getFrame(frameIndex)
    }

    @Override
    int getDelay(int frameIndex) {
        return internalDecoder.getDelay(frameIndex)
    }
}

@CompileStatic
class VideoDecoder implements Decoder {
    public List<BufferedImage> frames = []
    public List<Integer> delays = []
    public FFmpegFrameGrabber grabbery

    VideoDecoder(File file) {
        grabbery = new FFmpegFrameGrabber(file)
        grabbery.start()

        List<Long> timestamps = []

        Frame frame
        while ((frame = grabbery.grabImage()) != null) {
            if (frames.size() >= 50) {
                grabbery.stop()
                grabbery.release()
                throw new RuntimeException("Sorry! Slack only allows 50 frames.")
            }

            BufferedImage img = new Java2DFrameConverter().convert(frame)
            if (img != null) {
                frames << img
                timestamps << frame.timestamp
            }
        }
        grabbery.stop()
        grabbery.release()

        for (int i = 0; i < timestamps.size() - 1; i++) {
            long deltaMicros = timestamps[i + 1] - timestamps[i]
            int delayMillis = (int) (deltaMicros / 1000)
            delays << (delayMillis > 0 ? delayMillis : 40)
        }
        if (timestamps.size() > 0) {
            delays << (delays.size() > 0 ? delays.last() : 40)
        }
    }

    @Override
    int getFrameCount() {
        return frames.size()
    }

    @Override
    BufferedImage getFrame(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= frames.size()) return null
        return frames[frameIndex]
    }

    @Override
    int getDelay(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= delays.size()) return 0
        return delays[frameIndex]
    }
}