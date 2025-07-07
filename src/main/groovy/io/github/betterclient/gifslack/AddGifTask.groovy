package io.github.betterclient.gifslack

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