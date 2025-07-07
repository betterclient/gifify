package io.github.betterclient.gifslack.file

import groovy.transform.CompileStatic
import io.github.betterclient.gifslack.emoji.EmojiProcessor
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

class GifDecoder implements Decoder {
    public int frameCount
    public List<BufferedImage> frames = []
    public List<Integer> delays = []

    GifDecoder(File f) {
        def internalDecoder = new com.madgag.gif.fmsware.GifDecoder()
        internalDecoder.read(f.absolutePath)

        frameCount = internalDecoder.frameCount
        if (frameCount > EmojiProcessor.MAX_FRAMES_ALLOWED) {
            throw new RuntimeException("Found $frameCount frames, only ${EmojiProcessor.MAX_FRAMES_ALLOWED} frames (${EmojiProcessor.MAX_FRAMES_ALLOWED_SEC} seconds of 60fps) are allowed.")
        }

        for (def index in 0..<frameCount) {
            frames.add(internalDecoder.getFrame(index))
            delays.add(internalDecoder.getDelay(index))
        }
    }

    GifDecoder(List<Integer> delays, List<BufferedImage> frames) {
        this.frames.addAll(frames)
        this.delays.addAll(delays)
        this.frameCount = delays.size()
    }

    @Override
    int getFrameCount() {
        return frameCount
    }

    @Override
    BufferedImage getFrame(int frameIndex) {
        return frames[frameIndex]
    }

    @Override
    int getDelay(int frameIndex) {
        return delays[frameIndex]
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

@CompileStatic
class VideoDecoder implements Decoder {
    public List<BufferedImage> frames = []
    public List<Integer> delays = []
    public FFmpegFrameGrabber grabbery
    public String reducedMessage = null

    VideoDecoder(File file) {
        grabbery = new FFmpegFrameGrabber(file)
        grabbery.start()

        def frameCount0 = grabbery.lengthInFrames
        long videoDurationMicros = grabbery.lengthInTime
        if (frameCount0 > EmojiProcessor.MAX_FRAMES_ALLOWED) {
            grabbery.stop()
            grabbery.release()
            throw new RuntimeException("Found $frameCount0 frames, only ${EmojiProcessor.MAX_FRAMES_ALLOWED} frames (${EmojiProcessor.MAX_FRAMES_ALLOWED_SEC} seconds of 60fps) are allowed.")
        }

        long stepMicros = (long)(videoDurationMicros / 50)
        int delayMillis = (int)(stepMicros / 1000)

        for (int i = 0; i < 50; i++) {
            long timestamp = i * stepMicros
            grabbery.setTimestamp(timestamp)

            Frame frame = grabbery.grabImage()
            if (frame != null) {
                BufferedImage img = new Java2DFrameConverter().convert(frame)
                if (img != null) {
                    frames << img
                    delays << (delayMillis > 0 ? delayMillis : 40)
                }
            }
        }

        grabbery.stop()
        grabbery.release()

        if (frameCount0 > 50) {
            reducedMessage = "Your file was reduced from $frameCount0 to 50 frames."
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