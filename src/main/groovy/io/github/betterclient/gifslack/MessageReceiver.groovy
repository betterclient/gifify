package io.github.betterclient.gifslack

import com.slack.api.bolt.App
import com.slack.api.bolt.context.builtin.EventContext
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.model.block.ImageBlock
import com.slack.api.model.event.AppMentionEvent

class MessageReceiver {
    static final String BOT_CHANNEL = "C0943TNEFLP"

    static void register(App app) {
        app.event(AppMentionEvent.class, { payload, context ->
            def event = payload.event
            if (event.channel != BOT_CHANNEL) return context.ack()
            if (context.retryReason == "timeout") return context.ack()

            new Thread(generate(app, event, context)).start()

            return context.ack()
        })
    }

    static Closure<ChatPostMessageResponse> generate(App app, AppMentionEvent event, EventContext context) {
        return {
            sendToThis(app, event.ts, "Downloading your gif...")
            println "Downloading gif"

            File file = null
            event.attachments?.each { itt ->
                def block = itt.blocks[0]
                if (block instanceof ImageBlock) {
                    file = downloadFile(block.imageUrl, "work", null)
                }
            }
            if (file == null) {
                if (event.files.size() > 0) {
                    def file0 = event.files[0]

                    if (file0.filetype == "gif") {
                        def fileInfoResponse = app.client.filesInfo({ req ->
                            req.file(file0.id)
                        })

                        file = downloadFile(fileInfoResponse.file.urlPrivate, "work", context.getBotToken())
                    }
                }
            }

            //downloaded!!
            if (file == null) {
                sendToThis(app, event.ts, "Not a gif or internal error.")
            } else {
                println "Download success"
                GifProcessor.process(app, file, event.ts, event.text)
            }
        }
    }

    static void sendToThis(App app, String threadTS, String text) {
        app.client.chatPostMessage(ChatPostMessageRequest.builder().threadTs(threadTS).text(text).channel(BOT_CHANNEL).build())
    }

    static File downloadFile(String urlStr, String fileName, String token) {
        def url = new URL(urlStr)
        HttpURLConnection conn = (HttpURLConnection) url.openConnection()
        if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token)
        conn.requestMethod = "GET"

        if (conn.responseCode == 200) {
            var f = File.createTempFile(fileName, ".gif")
            f.withOutputStream { os ->
                os << conn.inputStream
            }
            return f
        } else {
            return null
        }
    }
}