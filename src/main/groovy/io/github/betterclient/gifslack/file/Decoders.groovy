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
        if (frameCount0 > EmojiProcessor.MAX_FRAMES_ALLOWED) {
            grabbery.stop()
            grabbery.release()
            throw new RuntimeException("Found $frameCount0 frames, only ${EmojiProcessor.MAX_FRAMES_ALLOWED} frames (${EmojiProcessor.MAX_FRAMES_ALLOWED_SEC} seconds of 60fps) are allowed.")
        }

        List<Long> timestamps = []
        List<Integer> selectedIndexes = []
        int actualTarget = Math.min(50, frameCount0)
        double step = (double) frameCount0 / actualTarget
        for (int i = 0; i < actualTarget; i++) {
            selectedIndexes << (int)(i * step)
        }

        int currentIndex = 0
        int wantedIndex = selectedIndexes[currentIndex]
        int actualIndex = 0
        Frame frame
        while ((frame = grabbery.grabImage()) != null && currentIndex < actualTarget) {
            if (actualIndex == wantedIndex) {
                BufferedImage img = new Java2DFrameConverter().convert(frame)
                if (img != null) {
                    frames << img
                    timestamps << frame.timestamp
                    currentIndex++
                    if (currentIndex < selectedIndexes.size()) {
                        wantedIndex = selectedIndexes[currentIndex]
                    }
                }
            }
            actualIndex++
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