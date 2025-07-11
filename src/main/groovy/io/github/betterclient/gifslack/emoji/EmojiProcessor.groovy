package io.github.betterclient.gifslack.emoji

import com.madgag.gif.fmsware.AnimatedGifEncoder
import com.slack.api.bolt.App
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.model.block.ContextBlock
import com.slack.api.model.block.DividerBlock
import com.slack.api.model.block.SectionBlock
import com.slack.api.model.block.composition.PlainTextObject
import io.github.betterclient.gifslack.file.Decoder
import io.github.betterclient.gifslack.file.Decoders
import io.github.betterclient.gifslack.file.GifEncoder
import io.github.betterclient.gifslack.file.Utilities
import io.github.betterclient.gifslack.file.VideoDecoder
import io.github.betterclient.gifslack.slack.MessageReceiver

import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EmojiProcessor {
    public App app
    public File input
    public String threadTS
    public int cols
    public String identifier

    //CONSTANTS
    static int MAX_IMAGE_SIZE = 1024
    static int MAX_CHUNK_SIZE = 64
    static int MAX_FRAMES_ALLOWED = 20000
    static int MAX_FRAMES_ALLOWED_SEC = 332

    //RANDOM
    private static def RANDOM = new Random()

    EmojiProcessor(App app, File input, String threadTS, int cols, String name) {
        this.app = app
        this.input = input
        this.threadTS = threadTS
        this.cols = cols
        this.identifier = "${name}_${UUID.randomUUID().toString().substring(0, 4)}"
    }

    static void process(App app, File input, String threadTS, String inputText) throws IOException {
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

        String name = inputText.replace("small", "")
                .replace("medium", "")
                .replace("large", "")
                .replace("massive", "")
                .replaceAll("<@U[A-Z0-9]+>", "") //Thanks for the regex, Copilot! (mentions)
                .replaceAll("<#C[A-Z0-9]+>", "") //channels
                .replaceAll("[^a-zA-Z0-9_]", "") //no illegal emoji names
                .trim()
                .replace(" ", "_")
                .toLowerCase()

        if (name.isEmpty()) {
            name = "${RANDOM.nextInt()}"
        } else if (name.length() > 16) {
            name = name.substring(0, 16)
        }

        new EmojiProcessor(app, input, threadTS, textCols, name).execute()
    }

    def execute() {
        println "Parsing file..."
        def (rows, cols, tasks) = createTasks()
        println "Finished parsing file, uploading."

        println "Generating output text"
        def finalOutputAsString = generateFinalOutputAsString(rows, cols)

        sendInitialMessage(cols, rows)

        addTasks(finalOutputAsString, tasks)
    }

    def sendInitialMessage(int cols, int rows) {
        def blocks = [
                SectionBlock.builder().text(new PlainTextObject("Creating your emojis! It should be ready soon!", false)).build(),
                DividerBlock.builder().build(),
                ContextBlock.builder().elements([new PlainTextObject("Identifier ${this.identifier}, Width: $cols, Height: $rows", false)]).build()
        ]

        if (EmojiUploader.emojiQueue.size() > 0) {
            blocks.add(1, SectionBlock.builder().text(new PlainTextObject("There are other emojis in line! This may take a bit.", false)).build())
        }

        app.client.chatPostMessage(
                ChatPostMessageRequest.builder()
                        .blocks(blocks)
                        .channel(MessageReceiver.BOT_CHANNEL)
                        .threadTs(threadTS)
                        .build()
        )
    }

    private def createTasks() {
        GifEncoder.reset()
        Decoder decoder
        try {
            decoder = Decoders.getDecoderForFile(input)
        } catch (RuntimeException e) {
            app.client.chatPostMessage(
                    ChatPostMessageRequest.builder()
                            .text(e.message)
                            .channel(MessageReceiver.BOT_CHANNEL)
                            .threadTs(threadTS)
                            .build()
            )
            throw e
        }

        if (decoder instanceof VideoDecoder) {
            def message = ((VideoDecoder) decoder).reducedMessage
            if (message != null) {
                app.client.chatPostMessage(
                        ChatPostMessageRequest.builder()
                                .text(message)
                                .channel(MessageReceiver.BOT_CHANNEL)
                                .threadTs(threadTS)
                                .build()
                )
            }
        }

        int frameCount = decoder.frameCount
        if (decoder.frameCount > 50) {
            println "File contains $frameCount frames"
            decoder = Utilities.make50Frames(decoder, app, threadTS)
            frameCount = decoder.frameCount
        }

        def img = Utilities.scaleDown(decoder.getFrame(0), MAX_IMAGE_SIZE)
        int thisCols = this.cols
        def (int targetW, int targetH, int chunkH, int chunkW, int rows, int cols) = sizeChunks(img.width, img.height, thisCols)
        while ((rows * cols) > 200) {
            //too many chunks, will take >20 minutes to upload
            thisCols -= 2
            app.client.chatPostMessage(
                    ChatPostMessageRequest.builder()
                            .text("Output too big, reducing size to $thisCols.")
                            .channel(MessageReceiver.BOT_CHANNEL)
                            .threadTs(threadTS)
                            .build()
            )

            (targetW, targetH, chunkH, chunkW, rows, cols) = sizeChunks(img.width, img.height, thisCols)
        }

        def tasks = new ArrayList<AddEmojiTask>()

        println "File contains $frameCount frames"
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            BufferedImage srcFrame = Utilities.scaleDown(decoder.getFrame(frameIndex), MAX_IMAGE_SIZE)
            int delay = decoder.getDelay(frameIndex)

            BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB)
            Graphics2D g = resized.createGraphics()
            g.drawImage(srcFrame, 0, 0, targetW, targetH, null)
            g.dispose()

            extractFrame(chunkH, delay, chunkW, rows, cols, frameCount, frameIndex, tasks, resized)
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
                finalOutputAsString += ":zzwork_gifify_${x}_${y}_${this.identifier}:"
            }
            finalOutputAsString += "\n"
        }

        return finalOutputAsString
    }

    private def addTasks(String finalOutput, List<AddEmojiTask> tasks) {
        println "Adding tasks to emojiQueue..."
        def executionTimes = Collections.synchronizedList(new ArrayList<Long>())

        final int totalTasks = tasks.size()
        final int numMessages = 10
        if (totalTasks == 0) { return } //guaranteed to never happen, but you can never be too sure

        def messageIndices = new HashSet<Integer>()
        for (int i = 1; i <= numMessages; i++) {
            int triggerIndex = Math.round((i * totalTasks) / (float)numMessages) - 1
            if (triggerIndex >= 0) {
                messageIndices.add(triggerIndex)
            }
        }
        messageIndices.add(totalTasks - 1)

        def percent = new AtomicInteger(1)
        tasks.eachWithIndex { task, index ->
            EmojiUploader.emojiQueue.add(new Closure(task) {
                @Override
                void run() {
                    long startTime = System.nanoTime()
                    task.execute()
                    long endTime = System.nanoTime()
                    executionTimes.add(endTime - startTime)
                }
            })

            if (index in messageIndices) {
                final def immutableIndexForMessage = index + 1

                EmojiUploader.emojiQueue.add(new Closure(task) {
                    @Override
                    void run() {
                        if (percent.get() == 10) return

                        String timeEstimate = ""
                        if (!executionTimes.isEmpty()) {
                            long averageNanos = executionTimes.sum() / executionTimes.size()
                            int tasksInQueue = EmojiUploader.emojiQueue.size()
                            long estimatedNanos = tasksInQueue * averageNanos
                            long remainingSeconds = TimeUnit.NANOSECONDS.toSeconds(estimatedNanos)
                            if (remainingSeconds > 1) {
                                long minutes = (remainingSeconds / 60).toLong()
                                long seconds = remainingSeconds % 60
                                if (minutes > 0) {
                                    timeEstimate = " (est. ${minutes}m ${seconds}s remaining)"
                                } else {
                                    timeEstimate = " (est. ${seconds}s remaining)"
                                }
                            }
                        }

                        def owner0 = (owner as AddEmojiTask)
                        owner0.owner.app.client.chatPostMessage(
                                ChatPostMessageRequest.builder()
                                        .blocks([
                                                // Append the time estimate to the message text.
                                                SectionBlock.builder().text(new PlainTextObject("Still working on your gif. ${percent.getAndAdd(1) * 10}% done! ($immutableIndexForMessage/$totalTasks)${timeEstimate}", false)).build()
                                        ])
                                        .channel(MessageReceiver.BOT_CHANNEL)
                                        .threadTs(owner0.owner.threadTS)
                                        .build()
                        )
                    }
                })
            }
        }

        EmojiUploader.emojiQueue.add(new Closure(this) {
            @Override
            void run() {
                println(finalOutput)
                def task = owner as EmojiProcessor
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
                                .replyBroadcast(true)
                                .build()
                )
            }
        })
    }

    private def extractFrame(int chunkH, int delay, int chunkW, int rows, int cols, int frameCount, int frameIndex, ArrayList<AddEmojiTask> tasks, BufferedImage resized) {
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                BufferedImage chunk = resized.getSubimage(x * chunkW, y * chunkH, chunkW, Math.min(chunkH, resized.getHeight() - y * chunkH))

                String key = x + "_" + y
                AnimatedGifEncoder encoder = GifEncoder.getEncoderFor(key)
                encoder.setDelay(delay)
                encoder.addFrame(Utilities.makeSquare(chunk))

                if (frameIndex == frameCount - 1) {
                    encoder.finish()
                    String name = "zzwork_gifify_${key}_${this.identifier}"
                    byte[] bites = GifEncoder.getOutputStreamFor(key).with { it.close(); it }.toByteArray()
                    tasks.add(new AddEmojiTask(this, new Runnable() {
                        @Override
                        void run() {
                            EmojiUploader.uploadEmoji(
                                    name,
                                    bites
                            )
                        }
                    }))
                }
            }
        }
    }
}

