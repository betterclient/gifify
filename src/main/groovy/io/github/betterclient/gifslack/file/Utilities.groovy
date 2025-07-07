package io.github.betterclient.gifslack.file

import com.madgag.gif.fmsware.AnimatedGifEncoder
import com.slack.api.bolt.App
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import io.github.betterclient.gifslack.emoji.EmojiProcessor
import io.github.betterclient.gifslack.slack.MessageReceiver

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage

static BufferedImage scaleDown(BufferedImage original, int maxSize) {
    int width = original.getWidth()
    int height = original.getHeight()

    if (width <= maxSize && height <= maxSize) {
        return original
    }

    double scale = Math.min((double) maxSize / width, (double) maxSize / height);
    int newW = (int) Math.round(width * scale)
    int newH = (int) Math.round(height * scale)

    BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB)
    Graphics2D g2d = scaled.createGraphics()

    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g2d.drawImage(original, 0, 0, newW, newH, null)
    g2d.dispose()

    return scaled
}

static BufferedImage makeSquare(BufferedImage img) {
    int size = EmojiProcessor.MAX_CHUNK_SIZE
    BufferedImage stretched = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_INDEXED)
    Graphics2D g = stretched.createGraphics()

    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    g.drawImage(img, 0, 0, size, size, null)
    g.dispose()

    return stretched
}

static Decoder make50Frames(Decoder inputDecoder, App app, String threadTS) {
    double step = inputDecoder.frameCount / 50.0
    List<Integer> delays = []
    List<BufferedImage> frames = []
    for (int i = 0; i < 50; i++) {
        int start = (int) Math.floor(i * step)
        int end = (int) Math.floor((i + 1) * step)
        end = Math.min(end, inputDecoder.frameCount)
        int delaySum = 0
        for (int j = start; j < end; j++) {
            delaySum += inputDecoder.getDelay(j)
        }
        delays.add(delaySum)
        frames.add(inputDecoder.getFrame(start))
    }

    app.client.chatPostMessage(
            ChatPostMessageRequest.builder()
                    .text("Your file was reduced from ${inputDecoder.frameCount} to 50 frames.")
                    .channel(MessageReceiver.BOT_CHANNEL)
                    .threadTs(threadTS)
                    .build()
    )
    return new GifDecoder(delays, frames)
}