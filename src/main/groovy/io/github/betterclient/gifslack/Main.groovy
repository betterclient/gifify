package io.github.betterclient.gifslack

import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import io.github.betterclient.gifslack.emoji.EmojiUploader
import io.github.betterclient.gifslack.slack.EnvironmentFile
import io.github.betterclient.gifslack.slack.MessageReceiver

class Main {
    static EnvironmentFile environment = new EnvironmentFile()

    static void main(args) {
        println 'Launching bot!'

        def app = new App(new AppConfig().with {
            it.signingSecret = environment["SLACK_SIGNING_SECRET"]
            it.singleTeamBotToken = environment["SLACK_BOT_TOKEN"]
            return it
        })

        MessageReceiver.register app
        new SocketModeApp(environment["SLACK_APP_TOKEN"], app).startAsync()
        while (true) {
            Thread.sleep(500)
            if (EmojiUploader.emojiQueue.size() > 0) {
                EmojiUploader.emojiQueue.remove(0).run()
            }
        }
    }
}