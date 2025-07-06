package io.github.betterclient.gifslack

import com.madgag.gif.fmsware.AnimatedGifEncoder
import com.madgag.gif.fmsware.GifDecoder
import com.slack.api.bolt.App
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.model.block.ContextBlock
import com.slack.api.model.block.DividerBlock
import com.slack.api.model.block.SectionBlock
import com.slack.api.model.block.composition.PlainTextObject

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.IndexColorModel

class GifProcessor {
    public App app
    public File inputGif
    public String threadTS
    public int cols
    public long time

    //CONSTANTS
    static def MAX_IMAGE_SIZE = 1024
    static def MAX_CHUNK_SIZE = 64
    //RANDOM
    private static def RANDOM = new Random()

    GifProcessor(App app, File inputGif, String threadTS, int cols) {
        this.app = app
        this.inputGif = inputGif
        this.threadTS = threadTS
        this.cols = cols
        this.time = RANDOM.nextLong().abs()
    }

    static void process(App app, File inputGif, String threadTS, String inputText) throws IOException {
        int textCols = 6
        if (inputText.containsIgnoreCase("small")) {
            textCols = 6
        } else if (inputText.containsIgnoreCase("medium")) {
            textCols = 8
        } else if (inputText.containsIgnoreCase("large")) {
            textCols = 10
        } else if (inputText.containsIgnoreCase("massive")) {
            textCols = 12
        }

        new GifProcessor(app, inputGif, threadTS, textCols).execute()
    }

    def execute() {
        println "Parsing gif..."
        def (rows, cols, tasks) = createTasks(this.time)
        println "Finished parsing gif, uploading."

        def finalOutputAsString = generateFinalOutputAsString(rows, cols)

        app.client.chatPostMessage(
                ChatPostMessageRequest.builder()
                        .blocks([
                                SectionBlock.builder().text(new PlainTextObject("Creating your gif! It should be ready soon!\nWidth: $cols Height: $rows", false)).build(),
                                DividerBlock.builder().build(),
                                ContextBlock.builder().elements([new PlainTextObject("Debug: $time", false)]).build()
                        ])
                        .channel(MessageReceiver.BOT_CHANNEL)
                        .threadTs(threadTS)
                        .build()
        )

        addTasks(rows, cols, finalOutputAsString, tasks)
    }

    private def createTasks(long time) {
        GifManager.reset()
        GifDecoder decoder = new GifDecoder()
        decoder.read(inputGif.absolutePath)
        int frameCount = decoder.getFrameCount()

        def img = scaleDown(decoder.getFrame(0), MAX_IMAGE_SIZE)
        def (int targetW, int targetH, int chunkH, int chunkW, int rows, int cols) = sizeChunks(img.width, img.height, this.cols)

        def tasks = new ArrayList<AddGifTask>()
        println "Gif contains $frameCount frames"
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            BufferedImage srcFrame = scaleDown(decoder.getFrame(frameIndex), MAX_IMAGE_SIZE)
            int delay = decoder.getDelay(frameIndex)

            BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB)
            Graphics2D g = resized.createGraphics()
            g.drawImage(srcFrame, 0, 0, targetW, targetH, null)
            g.dispose()

            extractFrame(time, chunkH, delay, chunkW, rows, cols, frameCount, frameIndex, tasks, resized)
        }

        return [rows, cols, tasks]
    }

    static def sizeChunks(int origW, int origH, int cols) {
        int chunkW = (int) Math.ceil((double) origW / cols)
        int rows = (int) Math.ceil((double) origH / chunkW)
        int targetW = chunkW * cols
        int targetH = chunkW * rows
        int chunkH = chunkW

        return [targetW, targetH, chunkH, chunkW, Math.min(rows, cols * 2), cols]
    }

    private def generateFinalOutputAsString(rows, cols) {
        String finalOutputAsString = ""

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                finalOutputAsString += ":work_gif_${x}_${y}_${this.time}:"
            }
            finalOutputAsString += "\n"
        }

        return finalOutputAsString
    }

    private def addTasks(rows, cols, String finalOutput, List<AddGifTask> tasks) {
        var index = 0
        tasks.each {
            GifManager.gifQueue.add(new Closure(it) {
                @Override
                void run() {
                    it.execute()
                }
            })
            if (index % (((rows * cols) - 1) / 10) == 0 && index != 0) {
                def total = rows * cols
                def percent = (index * 100) / total
                final def immutableIndex = index

                GifManager.gifQueue.add(new Closure(it) {
                    @Override
                    void run() {
                        def owner0 = (owner as AddGifTask)
                        owner0.owner.app.client.chatPostMessage(
                                ChatPostMessageRequest.builder()
                                        .blocks([
                                                SectionBlock.builder().text(new PlainTextObject("Still working on your gif. ${percent.toInteger()}% done! ($immutableIndex/$total)", false)).build()
                                        ])
                                        .channel(MessageReceiver.BOT_CHANNEL)
                                        .threadTs(owner0.owner.threadTS)
                                        .build()
                        )
                    }
                })
            }
            index++
        }

        GifManager.gifQueue.add(new Closure(this) {
            @Override
            void run() {
                println(finalOutput)
                def task = owner as GifProcessor
                task.app.client.chatPostMessage(
                        ChatPostMessageRequest.builder()
                                .text("Your gif is ready!")
                                .channel(MessageReceiver.BOT_CHANNEL)
                                .threadTs(task.threadTS)
                                .build()
                )

                task.app.client.chatPostMessage(
                        ChatPostMessageRequest.builder()
                                .text(finalOutput)
                                .channel(MessageReceiver.BOT_CHANNEL)
                                .threadTs(task.threadTS)
                                .build()
                )
            }
        })
    }

    private def extractFrame(long time, int chunkH, int delay, int chunkW, int rows, int cols, int frameCount, int frameIndex, ArrayList<AddGifTask> tasks, BufferedImage resized) {
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                BufferedImage chunk = resized.getSubimage(x * chunkW, y * chunkH, chunkW, Math.min(chunkH, resized.getHeight() - y * chunkH))

                String key = x + "_" + y
                AnimatedGifEncoder encoder = GifManager.getEncoderFor(key)
                encoder.setDelay(delay)
                encoder.addFrame(makeSquare(chunk))

                if (frameIndex == frameCount - 1) {
                    encoder.finish()
                    String name = "work_gif_${key}_${time}"
                    tasks.add(new AddGifTask(this, new Runnable() {
                        @Override
                        void run() {
                            try {
                                GifManager.addGif(
                                        name,
                                        GifManager.getOutputStreamFor(key).with { it.close(); it }.toByteArray()
                                )
                            } catch (ignored) {

                            }
                        }
                    }))
                }
            }
        }
    }

    //HELPER FUNCTIONS
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
        //return to8Bit(scaled)
    }

    static BufferedImage makeSquare(BufferedImage img) {
        int size = MAX_CHUNK_SIZE
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
}

class AddGifTask {
    public GifProcessor owner
    public Runnable task

    AddGifTask(GifProcessor owner, Runnable task) {
        this.owner = owner
        this.task = task
    }

    void execute() {
        this.task.run()
    }
}