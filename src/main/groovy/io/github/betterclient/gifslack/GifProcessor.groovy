package io.github.betterclient.gifslack

import com.madgag.gif.fmsware.AnimatedGifEncoder
import com.madgag.gif.fmsware.GifDecoder
import com.slack.api.bolt.App
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.model.block.SectionBlock
import com.slack.api.model.block.composition.MarkdownTextObject
import com.slack.api.model.block.composition.PlainTextObject

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.IndexColorModel

static void process(App app, File inputGif, String threadTS) throws IOException {
    def cols = 12 //define the width of the final image
    def maxImageSize = 1024

    GifDecoder decoder = new GifDecoder()
    decoder.read(inputGif.absolutePath)
    int frameCount = decoder.getFrameCount()

    def img = scaleDown(decoder.getFrame(0), maxImageSize)
    int origW = img.getWidth()
    int origH = img.getHeight()
    int targetW = (int) (((origW + cols - 1) / cols) * cols)
    double scale = (double) targetW / origW
    int targetH = (int) Math.round(origH * scale)

    int rows = (int) Math.ceil((double) targetH / (targetH / cols))

    int chunkW = (int) (targetW / cols)
    int chunkH = (int) (targetH / rows)
    long time = System.currentTimeMillis()

    def myClosures = new ArrayList<Closure>()
    for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
        BufferedImage srcFrame = scaleDown(decoder.getFrame(frameIndex), maxImageSize)
        int delay = decoder.getDelay(frameIndex)

        BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB)
        Graphics2D g = resized.createGraphics()
        g.drawImage(srcFrame, 0, 0, targetW, targetH, null)
        g.dispose()

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                BufferedImage chunk = resized.getSubimage(x * chunkW, y * chunkH, chunkW, Math.min(chunkH, resized.getHeight() - y * chunkH))

                String key = x + "_" + y
                AnimatedGifEncoder encoder = GifManager.getEncoderFor(key)
                encoder.setDelay(delay)
                encoder.addFrame(makeSquare(chunk))

                if (frameIndex == frameCount - 1) {
                    encoder.finish()
                    myClosures.add({
                        GifManager.addGif(
                            "work_gif_${key}_${time}",
                            GifManager.getOutputStreamFor(key).with { it.close(); it }.toByteArray()
                        )
                    })
                }
            }
        }
    }

    String finalOutputAsString = ""

    for (int y = 0; y < rows; y++) {
        for (int x = 0; x < cols; x++) {
            finalOutputAsString += ":work_gif_${x}_${y}_$time:"
        }
        finalOutputAsString += "\n"
    }

    app.client.chatPostMessage(
            ChatPostMessageRequest.builder()
                    .blocks([
                            SectionBlock.builder().text(new PlainTextObject("Creating your gif! It should be ready soon!\nWidth: $cols Height: $rows", false)).build()
                    ])
                    .channel(MessageReceiver.BOT_CHANNEL)
                    .threadTs(threadTS)
                    .build()
    )

    myClosures.each { Closure closure ->
        GifManager.gifQueue.add(closure)
    }
    GifManager.gifQueue.add({
        println(finalOutputAsString)
        app.client.chatPostMessage(
                ChatPostMessageRequest.builder()
                        .blocks([
                                SectionBlock.builder().text(new PlainTextObject("Your gif is ready!", false)).build(),
                                SectionBlock.builder().text(new MarkdownTextObject("```${finalOutputAsString}```", false)).build()
                        ])
                        .channel(MessageReceiver.BOT_CHANNEL)
                        .threadTs(threadTS)
                        .build()
        )
    })

    new Thread({
        def lastReported = -1
        while (true) {
            def remaining = 0
            myClosures.each { Closure closure ->
                if (GifManager.gifQueue.contains(closure)) {
                    remaining++
                }
            }
            if (remaining <= 9) break
            if (remaining % 10 == 0 && remaining != lastReported) {
                def doneSoFar = rows * cols - remaining
                def percent = (doneSoFar * 100) / (rows * cols)

                app.client.chatPostMessage(
                        ChatPostMessageRequest.builder()
                                .blocks([
                                        SectionBlock.builder().text(new PlainTextObject("Still working on your gif. (${percent.toInteger()})% done!", false)).build()
                                ])
                                .channel(MessageReceiver.BOT_CHANNEL)
                                .threadTs(threadTS)
                                .build()
                )
                lastReported = remaining
            }
        }
    }).start()
}

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

    return to8Bit(scaled)
}

static BufferedImage makeSquare(BufferedImage img) {
    int size = 64
    BufferedImage stretched = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_INDEXED)
    Graphics2D g = stretched.createGraphics()

    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    g.drawImage(img, 0, 0, size, size, null)
    g.dispose()

    return stretched
}


static BufferedImage to8Bit(BufferedImage src) {
    def steps = [0x00, 0x33, 0x66, 0x99, 0xCC, 0xFF]
    def colors = new int[256]
    def r = new byte[256]
    def g = new byte[256]
    def b = new byte[256]
    int index = 0

    for (rr in steps)
        for (gg in steps)
            for (bb in steps)
                if (index < 256) {
                    colors[index] = (0xFF << 24) | (rr << 16) | (gg << 8) | bb
                    r[index] = (byte) rr
                    g[index] = (byte) gg
                    b[index] = (byte) bb
                    index++
                }

    while (index < 256) {
        colors[index] = (int) 0xFF000000
        r[index] = 0
        g[index] = 0
        b[index] = 0
        index++
    }

    def icm = new IndexColorModel(8, 256, r, g, b)
    def dest = new BufferedImage(src.width, src.height, BufferedImage.TYPE_BYTE_INDEXED, icm)
    def raster = dest.raster
    def srcPixels = ((DataBufferInt) src.raster.dataBuffer).data

    int w = src.width
    int h = src.height

    for (int y = 0, i = 0; y < h; y++) {
        for (int x = 0; x < w; x++, i++) {
            int rgb = srcPixels[i] & 0xFFFFFF
            int r1 = (rgb >> 16) & 0xFF
            int g1 = (rgb >> 8) & 0xFF
            int b1 = rgb & 0xFF

            int minDist = Integer.MAX_VALUE
            int bestIndex = 0

            for (int j = 0; j < 216; j++) {
                int dr = r1 - (r[j] & 0xFF)
                int dg = g1 - (g[j] & 0xFF)
                int db = b1 - (b[j] & 0xFF)
                int dist = dr * dr + dg * dg + db * db

                if (dist < minDist) {
                    minDist = dist
                    bestIndex = j
                    if (dist == 0) break
                }
            }

            raster.setSample(x, y, 0, bestIndex)
        }
    }

    return dest
}