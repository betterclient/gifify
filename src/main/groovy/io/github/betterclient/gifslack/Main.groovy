package io.github.betterclient.gifslack

import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp

class Main {
    static def environment = new EnvironmentFile()

    static void main(args) {
        println 'Launching bot!'

        def app = new App(new AppConfig().with {
            it.signingSecret = environment["SLACK_SIGNING_SECRET"]
            it.singleTeamBotToken = environment["SLACK_BOT_TOKEN"]
            return it
        })

        MessageReceiver.register(app)
        new SocketModeApp(environment["SLACK_APP_TOKEN"], app).startAsync()
        while (true) {
            Thread.sleep(500)
            if (GifManager.gifQueue.size() > 0) {
                GifManager.gifQueue.remove(0).run()
            }
        }
    }
}