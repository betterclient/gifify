package io.github.betterclient.gifslack.emoji

class AddEmojiTask {
    public EmojiProcessor owner
    public Runnable task

    AddEmojiTask(EmojiProcessor owner, Runnable task) {
        this.owner = owner
        this.task = task
    }

    void execute() {
        this.task.run()
    }
}